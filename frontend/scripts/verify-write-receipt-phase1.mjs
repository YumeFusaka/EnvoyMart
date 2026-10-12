/** 真实AI原token只读桥 + Seata原提交帧缓留；不读secret、不改配置、不重启服务。 */
import {readFile,writeFile,mkdir} from 'node:fs/promises'
import {randomUUID,randomBytes} from 'node:crypto'
import {resolve,isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {ADDRESS} from './lib/complex-agent-executor.mjs'
import {parameterHash} from './lib/write-operation-receipts.mjs'
import {fingerprint} from './lib/approval-recovery-live.mjs'
import {createSeataCommitProxy} from './lib/seata-commit-proxy.mjs'
import {lockSku,observeCheckoutXid,phase1Snapshot,phase1LogEvidence,stockIndexSnapshot} from './lib/write-receipt-seata-local.mjs'
import {validateCheckoutSignature,assessPhase1,hasExpectedDatabaseOutcome} from './lib/write-receipt-phase1.mjs'
import {assessOutboxBarrier} from './lib/seata-outbox-evidence.mjs'
import {assessStockIndex} from './lib/seata-index-evidence.mjs'

const args=process.argv.slice(2),option=(prefix)=>args.find((arg)=>arg.startsWith(prefix))?.slice(prefix.length)
const compare=option('--compare=')
if(compare){if(args.length!==1)throw new Error('compare仅接受单一只读证据路径');const {compareSeataEvidence}=await import('./lib/seata-evidence-compare.mjs');const verdict=await compareSeataEvidence(compare,{caseNames:['commit','rollback']});console.log(JSON.stringify(verdict));process.exit(verdict.status==='PASS'?0:1)}
if(!args.length){console.log(JSON.stringify({status:'BLOCKED',reason:'阶段一未执行，需要显式prepare/capture或--compare=保留证据'}));process.exit(2)}
if(args.some((arg)=>!['--help','--dry-run','--prepare','--capture'].includes(arg)&&!['--fixture=','--mode=','--activate-evidence=','--restore-evidence='].some((prefix)=>arg.startsWith(prefix))))throw new Error('参数不在验收白名单')
if(args.includes('--help')||args.includes('--dry-run')) {
  console.log('node scripts/verify-write-receipt-phase1.mjs --prepare\nnode scripts/verify-write-receipt-phase1.mjs --capture --mode=commit|rollback --fixture=<绝对private路径> --activate-evidence=<绝对主代理就绪证据路径> --restore-evidence=<绝对恢复证据路径>\nprepare：隔离用户、购物车、一次真实模型唯一checkout签名。capture：需显式SEATA_PHASE1_ENABLE=1，只监听19091并等待主代理将order临时SEATA_SERVER切到19091及ready证据；不自改配置、不重启。SKU只读持锁取得XID，释放后hold原GlobalCommit，原token只读recover；失败/未覆盖退出非0。每种模式单独prepare，不复用已执行token。')
  process.exit(0)
}
if(Number(args.includes('--prepare'))+Number(args.includes('--capture'))!==1)throw new Error('必须唯一选择prepare或capture')
const directory=fileURLToPath(new URL('../.evidence/',import.meta.url)),gateway=process.env.VERIFY_GW??'http://127.0.0.1:8080'
await mkdir(directory,{recursive:true})
const fixturePath=option('--fixture='),mode=option('--mode=')??'commit',markerPath=option('--activate-evidence='),restorePath=option('--restore-evidence=')
if(!['commit','rollback'].includes(mode))throw new Error('模式不在审阅范围')
const preparing=args.includes('--prepare')
if(!preparing&&(!fixturePath||!isAbsolute(fixturePath)||!markerPath||!isAbsolute(markerPath)||!restorePath||!isAbsolute(restorePath)))throw new Error('capture需要绝对fixture和主代理ready/restore证据路径')
if(!preparing&&process.env.SEATA_PHASE1_ENABLE!=='1')throw new Error('未授权启动19091代理，请由主代理批准后设置SEATA_PHASE1_ENABLE=1')
let fixture=preparing?{version:'seata-phase1-private-v1',runId:randomUUID(),status:'PREPARING'}:JSON.parse(await readFile(fixturePath,'utf8'))
if(fixture.version!=='seata-phase1-private-v1'||(!preparing&&fixture.status!=='READY'))throw new Error('独立签名fixture不在可执行状态')
const privatePath=fixturePath??resolve(directory,'seata-phase1-'+fixture.runId+'.private.json'),output=resolve(directory,'seata-phase1-'+fixture.runId+'-'+(preparing?'prepare':mode)+'.json')
const evidence={version:'seata-phase1-v1',projectionContract:'durable-source-v1',runId:fixture.runId,mode:preparing?'PREPARE':mode,status:'BLOCKED',startedAt:new Date().toISOString(),calls:[]}
let proxy,lock,confirmation
const cancellation=new AbortController()
for(const name of ['SIGINT','SIGTERM'])process.once(name,()=>cancellation.abort(new Error('用户中断验收')))
const ensureActive=()=>{if(cancellation.signal.aborted)throw blocked('验收已取消，释放自有资源后保留证据')}
const savePrivate=()=>writeFile(privatePath,JSON.stringify(fixture,null,2)+'\n')
const pause=(ms)=>new Promise((done)=>setTimeout(done,ms))
const blocked=(message)=>Object.assign(new Error(message),{blocked:true})
async function api(path,{body,requestId=randomUUID(),timeoutMs=120000}={}) {
  const response=await fetch(gateway+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json','X-Request-Id':requestId,
    ...(fixture.token?{Authorization:'Bearer '+fixture.token}:{})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.any([cancellation.signal,AbortSignal.timeout(timeoutMs)])})
  const payload=await response.json();evidence.calls.push({path:path.split('?')[0],requestId,httpStatus:response.status,code:payload.code})
  if(payload.code!==200)throw new Error('验收API失败：'+path.split('?')[0]+' code='+payload.code)
  return {data:payload.data,requestId}
}
async function recovery() {
  const result=await api('/ai/sessions/'+fixture.sessionId+'/approvals/recover',{body:{approvalToken:fixture.approvalToken,retry:false},timeoutMs:15000})
  const action=result.data?.toolCalls?.find((item)=>item.operationId===fixture.operationId)
  const log=await phase1LogEvidence({xid:evidence.xid,requestIds:[result.requestId]})
  const proof=log.requests[result.requestId]
  return {requestId:result.requestId,operationState:action?.operationState??null,receiptReplayed:action?.receiptReplayed??false,
    original:result.data?.assistantMessageId===fixture.messageId&&result.data?.turnId===fixture.turnId
      &&fingerprint(result.data?.approvalToken)===fingerprint(fixture.approvalToken)&&result.data?.toolCalls?.length===1&&!!action,
    modelCalls:proof?.modes?.length?proof.modelCalls:null,modes:proof?.modes??[],approvalStatus:result.data?.approvalStatus,outputFingerprint:action?fingerprint(JSON.stringify(action.output)):null}
}
try {
  if(preparing) {
    const credentials={username:'sp_'+randomBytes(7).toString('hex'),password:'Sp'+randomBytes(12).toString('hex'),nickname:'Seata阶段一隔离验收'}
    fixture.credentials=credentials;await savePrivate()
    const login=(await api('/auth/register',{body:credentials})).data
    Object.assign(fixture,{token:login.token,userId:String(login.user.id)});await savePrivate()
    const products=(await api('/products?page=0&size=50')).data;let sku
    for(const product of products.records){const detail=(await api('/products/'+product.id)).data;sku=detail.skus?.find((item)=>item.stock>=5);if(sku)break}
    if(!sku)throw blocked('无可售库存SKU')
    const verified=(await api('/products/skus?ids='+sku.id)).data
    if(verified.length!==1||verified[0].status!==1||verified[0].spuStatus!==1)throw blocked('SKU/SPU非在售')
    fixture.skuId=sku.id;await api('/cart/items',{body:{skuId:sku.id,quantity:1}})
    fixture.sessionId='phase1-'+randomBytes(10).toString('hex');await savePrivate()
    const signing=await api('/ai/chat',{body:{sessionId:fixture.sessionId,message:'请结算购物车，收货信息严格使用：'+JSON.stringify(ADDRESS)+'。不要备注，不使用优惠券，只给出cart_checkout唯一确认卡，不执行其他写操作。'},timeoutMs:600000})
    const signed=signing.data
    const validated=validateCheckoutSignature(signed,ADDRESS)
    const signingLog=await phase1LogEvidence({requestIds:[signing.requestId]})
    evidence.signatureModelProof={requestId:signing.requestId,totalTokens:signed.usage?.totalTokens??0,observedModelLogCount:signingLog.requests[signing.requestId]?.modelCalls??0}
    if(evidence.signatureModelProof.totalTokens<=0||evidence.signatureModelProof.observedModelLogCount<=0)throw blocked('新唯一checkout签名缺同requestId真实模型运行/用量证据')
    Object.assign(fixture,{approvalToken:signed.approvalToken,messageId:signed.assistantMessageId,turnId:signed.turnId,operationId:validated.operationId,
      parametersHash:parameterHash(fixture.userId,'cart_checkout',validated.parameters),signatureModelProof:evidence.signatureModelProof,status:'READY',createdAt:new Date().toISOString()})
    await savePrivate();Object.assign(evidence,{status:'READY',privatePath,operationId:fixture.operationId,tokenFingerprint:fingerprint(fixture.approvalToken),signedModelUsage:signed.usage.totalTokens})
  } else {
    evidence.signatureModelProof=fixture.signatureModelProof
    if(!(evidence.signatureModelProof?.totalTokens>0)||!(evidence.signatureModelProof?.observedModelLogCount>0))throw blocked('capture前提缺本次真实模型签名运行证据，需新prepare')
    fixture.status='CAPTURING';await savePrivate()
    evidence.before=await phase1Snapshot({fixture})
    evidence.stockIndex={before:await stockIndexSnapshot({fixture}),during:[]}
    if(!evidence.stockIndex.before.projection?.guard?.versionBase||evidence.stockIndex.before.projection.pendingTaskCount!==0)throw blocked('新持久投影表/永久版本闸未就绪或实验商品已有未完成任务，不开始真实事务')
    if(evidence.before.receiptCount!==0||evidence.before.orderCount!==0||evidence.before.cart.length!==1)throw blocked('隔离前提必须零订单零原回执且购物车唯一一行')
    proxy=await createSeataCommitProxy({holdMs:mode==='rollback'?70000:20000})
    console.log(JSON.stringify({status:'WAITING_ORDER_READY',runId:fixture.runId,listen:'127.0.0.1:19091',markerPath}))
    const readyDeadline=Date.now()+900000;let ready
    while(Date.now()<readyDeadline){ensureActive();try{ready=JSON.parse(await readFile(markerPath,'utf8'))}catch{}if(ready?.runId===fixture.runId&&ready.ready===true&&ready.pid>0&&ready.seataServer==='127.0.0.1:19091')break;await pause(250)}
    if(ready?.runId!==fixture.runId||ready.ready!==true||!(ready.pid>0)||ready.seataServer!=='127.0.0.1:19091')throw blocked('主代理未兑现order临时TC代理出口就绪证据')
    evidence.orderActivation=ready
    lock=await lockSku({skuId:fixture.skuId});evidence.lock=lock.evidence
    const requestId=randomUUID();evidence.confirmRequestId=requestId
    confirmation=api('/ai/chat',{body:{sessionId:fixture.sessionId,message:'确认执行',approvalToken:fixture.approvalToken},requestId})
      .then((value)=>({value}),()=>({transport:'确认响应失败，仍按同XID及原token查事实'}))
    const observed=await observeCheckoutXid({requestId,maxWaitMs:15000});evidence.xid=observed.xid;evidence.xidObservation=observed
    proxy.arm(observed.xid);await lock.release();lock=null
    const holdDeadline=Date.now()+12000
    while(!proxy.held()&&Date.now()<holdDeadline){ensureActive();await pause(50)}
    if(!proxy.held())throw blocked('未捕获已绑定XID原GlobalCommit，不算阶段一窗口')
    evidence.held=proxy.events.find((item)=>item.kind==='GLOBAL_COMMIT_HELD'&&item.xid===observed.xid)
    evidence.during=await phase1Snapshot({fixture,xid:observed.xid})
    evidence.stockIndex.during.push(await stockIndexSnapshot({fixture}))
    evidence.outboxSamples=[{at:new Date().toISOString(),outbox:evidence.during.outbox}]
    for(let sample=0;sample<2;sample++){await pause(1200);if(!proxy.held())throw blocked('Outbox采样前未决窗口已失效');const snapshot=await phase1Snapshot({fixture,xid:observed.xid});evidence.outboxSamples.push({at:new Date().toISOString(),outbox:snapshot.outbox});evidence.stockIndex.during.push(await stockIndexSnapshot({fixture}))}
    evidence.outboxDuringLog=await phase1LogEvidence({xid:observed.xid,orderNos:evidence.during.orderRows.map((row)=>row.orderNo),outboxIds:evidence.during.outbox.map((row)=>row.id)})
    evidence.projectionDuringLog=(evidence.outboxDuringLog.services?.product??[]).filter(line=>line.includes('[商品投影]'))
    if(evidence.outboxSamples.some((sample)=>sample.outbox.length!==1||sample.outbox[0].attempts!==0||sample.outbox[0].sentAt!==null)
      ||evidence.outboxDuringLog.orderConsumerEvents.length!==0)throw new Error('全局未决Outbox已被消费/修改或缺失，跨两周期屏障失败')
    evidence.recoveryDuring=await recovery()
    if(!proxy.held())throw blocked('只读recover完成前缓留窗口已失效，不能证明UNKNOWN处于未决')
    if(evidence.recoveryDuring.operationState==='COMMITTED')throw new Error('全局未决原回执提前可重放')
    if(mode==='commit')proxy.release('EXPLICIT_COMMIT_AFTER_OBSERVATION')
    const deadline=Date.now()+85000
    while(Date.now()<deadline) {
      ensureActive()
      evidence.after=await phase1Snapshot({fixture,xid:observed.xid,orderNos:evidence.during.orderRows.map(row=>row.orderNo)})
      if(hasExpectedDatabaseOutcome({mode,before:evidence.before,after:evidence.after}))break
      await pause(250)
    }
    if(mode==='rollback')proxy.release('RELEASE_AFTER_TC_TIMEOUT_OBSERVATION')
    evidence.recoveryAfter=await recovery()
    const confirmed=await confirmation
    evidence.confirmation=confirmed.value?{approvalStatus:confirmed.value.data?.approvalStatus,usage:confirmed.value.data?.usage,
      states:confirmed.value.data?.toolCalls?.map((item)=>item.operationState)}:confirmed
    if(mode==='commit'){
      const deliveredDeadline=Date.now()+10000
      while(Date.now()<deliveredDeadline){evidence.after=await phase1Snapshot({fixture,xid:observed.xid,orderNos:evidence.during.orderRows.map(row=>row.orderNo)});if(hasExpectedDatabaseOutcome({mode,before:evidence.before,after:evidence.after})&&evidence.after.outbox.length===1&&evidence.after.outbox[0].attempts>=1&&evidence.after.outbox[0].sentAt!==null)break;await pause(250)}
    }
    evidence.logs=await phase1LogEvidence({xid:observed.xid,requestIds:[requestId,evidence.recoveryDuring.requestId,evidence.recoveryAfter.requestId],includeTC:true,orderNos:evidence.during.orderRows.map((row)=>row.orderNo),outboxIds:evidence.during.outbox.map((row)=>row.id)})
    evidence.proxyEvents=[...proxy.events]
    evidence.projectionDuringLog=(evidence.logs.services.product??[]).filter(line=>line.includes('[商品投影]'))
    const indexDeadline=Date.now()+15000
    do{evidence.stockIndex.after=await stockIndexSnapshot({fixture});if(assessStockIndex(evidence).status==='PASS')break;await pause(250)}while(Date.now()<indexDeadline)
    if(!evidence.logs.requests[requestId]?.modes?.length||evidence.logs.requests[requestId].modelCalls!==0)throw blocked('真实confirm缺审批模式与零模型日志')
    evidence.assessment=assessPhase1({mode,...evidence});evidence.outboxAssessment=assessOutboxBarrier(evidence);evidence.indexAssessment=assessStockIndex(evidence)
    evidence.status=[evidence.assessment,evidence.outboxAssessment,evidence.indexAssessment].some((item)=>item.status==='FAIL')?'FAIL':
      [evidence.assessment,evidence.outboxAssessment,evidence.indexAssessment].some((item)=>item.status!=='PASS')?'BLOCKED':'PASS'
    fixture.status=evidence.status;await savePrivate()
  }
} catch(error) {
  evidence.status=error.blocked?'BLOCKED':'FAIL';evidence.error=error.message
  fixture.status=evidence.status;await savePrivate()
} finally {
  if(lock)try{await lock.release()}catch(error){evidence.status='FAIL';evidence.lockReleaseError=error.message}
  if(confirmation)await confirmation
  if(proxy){
    proxy.release('WAITING_ROOT_RESTORE')
    console.log(JSON.stringify({status:'WAITING_ORDER_RESTORE',runId:fixture.runId,restorePath,assessment:evidence.status,reason:evidence.error??evidence.assessment?.reason}))
    const deadline=Date.now()+300000;let restored
    while(Date.now()<deadline){try{restored=JSON.parse(await readFile(restorePath,'utf8'))}catch{}if(restored?.runId===fixture.runId&&restored.ready===true&&restored.pid>0&&restored.seataServer==='127.0.0.1:8091')break;await pause(250)}
    if(restored?.runId!==fixture.runId||restored.ready!==true||!(restored.pid>0)||restored.seataServer!=='127.0.0.1:8091'){evidence.status='FAIL';evidence.restoreError='主代理未在5分钟内兑现order8091恢复就绪，当前配置风险必须继续收口'}
    else evidence.orderRestoration=restored
    await proxy.close();evidence.proxyEvents=proxy.events
  }
  evidence.finishedAt=new Date().toISOString()
  await writeFile(output,JSON.stringify(evidence,null,2)+'\n')
  if(!['PASS','READY'].includes(evidence.status))process.exitCode=1
  console.log(JSON.stringify({status:evidence.status,path:output,runId:fixture.runId,reason:evidence.error??[evidence.assessment,evidence.outboxAssessment,evidence.indexAssessment].find(item=>item&&item.status===evidence.status)?.reason??evidence.assessment?.reason,privatePath:preparing?privatePath:undefined}))
}
