/** 有效券先在真实AT阶段一核销成功，再由原TC决议回滚；仅独立fixture。 */
import {readFile,writeFile,mkdir} from 'node:fs/promises'
import {randomUUID} from 'node:crypto'
import {resolve,isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {createSeataCommitProxy} from './lib/seata-commit-proxy.mjs'
import {lockSku,observeCheckoutXid,lateCouponSnapshot,lateCouponLogEvidence,stockIndexSnapshot} from './lib/write-receipt-seata-local.mjs'
import {hasGlobalRollbackDecision,hasExpectedDatabaseOutcome} from './lib/write-receipt-phase1.mjs'
import {assessOutboxBarrier} from './lib/seata-outbox-evidence.mjs'
import {assessStockIndex} from './lib/seata-index-evidence.mjs'
const args=process.argv.slice(2),option=(prefix)=>args.find((arg)=>arg.startsWith(prefix))?.slice(prefix.length)
const compare=option('--compare=')
if(compare){if(args.length!==1)throw new Error('compare仅接受单一只读证据路径');const {compareSeataEvidence}=await import('./lib/seata-evidence-compare.mjs');const verdict=await compareSeataEvidence(compare,{caseName:'couponrollback'});console.log(JSON.stringify(verdict));process.exit(verdict.status==='PASS'?0:1)}
if(!args.length){console.log(JSON.stringify({status:'BLOCKED',reason:'已核销AT回滚未覆盖，需要显式capture或--compare=保留证据'}));process.exit(2)}
if(args.includes('--help')||args.includes('--dry-run')){console.log('node scripts/verify-write-receipt-seata.mjs --prepare-unused\nSEATA_PHASE1_ENABLE=1 node scripts/verify-write-receipt-seata-coupon-rollback.mjs --capture --fixture=<绝对private路径> --activate-evidence=<绝对主代理19091就绪路径> --restore-evidence=<绝对恢复证据路径>\n只持独立SKU读锁取得XID；原提交帧缓留后必须三库同XID undo、完整回执和该订单USED券；自然TC回滚后恢复，缺事实非0。');process.exit(0)}
if(args.some((arg)=>arg!=='--capture'&&!['--fixture=','--activate-evidence=','--restore-evidence='].some((prefix)=>arg.startsWith(prefix)))||!args.includes('--capture')||process.env.SEATA_PHASE1_ENABLE!=='1')throw new Error('有券AT回滚需显式批准19091capture')
const fixturePath=option('--fixture='),markerPath=option('--activate-evidence='),restorePath=option('--restore-evidence=')
if(!fixturePath||!isAbsolute(fixturePath)||!markerPath||!isAbsolute(markerPath)||!restorePath||!isAbsolute(restorePath))throw new Error('需要绝对独立fixture/主代理就绪及恢复路径')
const fixture=JSON.parse(await readFile(fixturePath,'utf8'))
if(fixture.mode!=='phase1'||!fixture.token||!fixture.userCouponId)throw new Error('仅接受prepare-unused独立有效券fixture')
const runId=fixture.runId,dir=fileURLToPath(new URL('../.evidence/',import.meta.url)),output=resolve(dir,'seata-coupon-phase1-'+runId+'.json')
const evidence={version:'seata-coupon-phase1-v1',projectionContract:'durable-source-v1',runId,status:'BLOCKED',startedAt:new Date().toISOString()},gateway=process.env.VERIFY_GW??'http://127.0.0.1:8080'
let proxy,lock,checkout
const cancelled=new AbortController(),pause=(ms)=>new Promise((done)=>setTimeout(done,ms)),blocked=(message)=>Object.assign(new Error(message),{blocked:true})
for(const name of ['SIGINT','SIGTERM'])process.once(name,()=>cancelled.abort())
const ensureActive=()=>{if(cancelled.signal.aborted)throw blocked('用户取消，释放自有资源后保留证据')}
try {
  evidence.before=await lateCouponSnapshot({fixture})
  evidence.stockIndex={before:await stockIndexSnapshot({fixture}),during:[]}
  if(!evidence.stockIndex.before.projection?.guard?.versionBase||evidence.stockIndex.before.projection.pendingTaskCount!==0)throw blocked('新持久投影表/永久版本闸未就绪或商品已有待办，不开始真实事务')
  if(evidence.before.coupon.status!=='UNUSED'||evidence.before.coupon.orderNo!==null||evidence.before.orderCount!==0||evidence.before.receiptCount!==0)throw blocked('不是独立有效券零原订单/回执前提')
  proxy=await createSeataCommitProxy({holdMs:70000})
  console.log(JSON.stringify({status:'WAITING_ORDER_READY',runId,listen:'127.0.0.1:19091',markerPath}))
  const deadline=Date.now()+900000;let marker
  while(Date.now()<deadline){ensureActive();try{marker=JSON.parse(await readFile(markerPath,'utf8'))}catch{}if(marker?.runId===runId&&marker.ready===true&&marker.pid>0&&marker.seataServer==='127.0.0.1:19091')break;await pause(250)}
  if(marker?.runId!==runId||marker.ready!==true||!(marker.pid>0)||marker.seataServer!=='127.0.0.1:19091')throw blocked('主代理未兑现19091出口就绪')
  evidence.orderActivation=marker
  lock=await lockSku({skuId:fixture.skuId});evidence.lock=lock.evidence
  const requestId=randomUUID();evidence.requestId=requestId
  checkout=fetch(gateway+'/orders/checkout',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+fixture.token,
    'X-Request-Id':requestId,'X-Operation-Id':fixture.operationId},body:JSON.stringify(fixture.checkoutBody),signal:AbortSignal.any([cancelled.signal,AbortSignal.timeout(120000)])})
    .then(async(response)=>({httpStatus:response.status,code:(await response.json()).code}),()=>({transportUnknown:true}))
  const observed=await observeCheckoutXid({requestId,maxWaitMs:15000});evidence.xid=observed.xid;proxy.arm(observed.xid)
  await lock.release();lock=null
  const holdDeadline=Date.now()+12000
  while(!proxy.held()&&Date.now()<holdDeadline){ensureActive();await pause(50)}
  if(!proxy.held())throw blocked('未捕获独立XID阶段一原提交帧')
  evidence.held=proxy.events.find((event)=>event.kind==='GLOBAL_COMMIT_HELD')
  evidence.during=await lateCouponSnapshot({fixture,xid:observed.xid})
  evidence.stockIndex.during.push(await stockIndexSnapshot({fixture}))
  evidence.outboxSamples=[{at:new Date().toISOString(),outbox:evidence.during.outbox}]
  for(let sample=0;sample<2;sample++){await pause(1200);if(!proxy.held())throw blocked('Outbox周期采样时全局窗口已结束');const snapshot=await lateCouponSnapshot({fixture,xid:observed.xid});evidence.outboxSamples.push({at:new Date().toISOString(),outbox:snapshot.outbox});evidence.stockIndex.during.push(await stockIndexSnapshot({fixture}))}
  evidence.outboxDuringLog=await lateCouponLogEvidence({xid:observed.xid,orderNos:evidence.during.orderRows.map((row)=>row.orderNo),outboxIds:evidence.during.outbox.map((row)=>row.id)})
  evidence.projectionDuringLog=(evidence.outboxDuringLog.services?.product??[]).filter(line=>line.includes('[商品投影]'))
  if(evidence.outboxSamples.some((sample)=>sample.outbox.length!==1||sample.outbox[0].attempts!==0||sample.outbox[0].sentAt!==null)
    ||evidence.outboxDuringLog.orderConsumerEvents.length!==0)throw new Error('未决营销阶段一Outbox在跨两周期采样中已经发送/消费')
  const during=evidence.during
  if(during.receiptCount!==1||during.completeCount!==1||during.orderCount!==1||during.coupon.status!=='USED'
    ||during.coupon.orderNo!==during.orderRows[0]?.orderNo||during.orderRows[0]?.userCouponId!==fixture.userCouponId
    ||!['order','product'].every((db)=>during.undo[db].some((row)=>row.xid===observed.xid))
    ||!during.promotionUndo.some((row)=>row.xid===observed.xid))throw blocked('缺三服务真实阶段一undo、完整回执及本订单已核销券证据')
  const rollbackDeadline=Date.now()+85000
  while(Date.now()<rollbackDeadline) {
    ensureActive();evidence.after=await lateCouponSnapshot({fixture,xid:observed.xid,orderNos:evidence.during.orderRows.map(row=>row.orderNo)});evidence.logs=await lateCouponLogEvidence({xid:observed.xid,orderNos:evidence.during.orderRows.map((row)=>row.orderNo),outboxIds:evidence.during.outbox.map((row)=>row.id)})
    if(hasExpectedDatabaseOutcome({mode:'rollback',before:evidence.before,after:evidence.after})
      &&evidence.logs.services.promotion.some((line)=>line.includes('branch rollback success')))break
    await pause(250)
  }
  proxy.release('RELEASE_AFTER_OBSERVED_AT_COUPON_ROLLBACK')
  evidence.checkout=await checkout
  const after=evidence.after
  if(after.coupon.status!=='UNUSED'||after.coupon.orderNo!==null||after.receiptCount!==0||after.orderCount!==0
    ||after.stock!==evidence.before.stock||JSON.stringify(after.cart)!==JSON.stringify(evidence.before.cart))throw new Error('AT回滚后已核销券或订单/回执/库存/购物车未复原')
  if(!hasExpectedDatabaseOutcome({mode:'rollback',before:evidence.before,after}))throw blocked('观察窗口结束后完整三库回滚终态仍未结算')
  if(!(hasGlobalRollbackDecision(evidence.logs.services.order)||evidence.logs.tc?.xid===observed.xid&&evidence.logs.tc.rolledBack===true)
    ||!['product','promotion'].every((service)=>evidence.logs.services[service].some((line)=>line.includes('branch rollback success'))))throw blocked('缺同XID全局与商品/营销AT回滚最终日志')
  evidence.outboxAssessment=assessOutboxBarrier({...evidence,mode:'rollback'})
  evidence.proxyEvents=[...proxy.events]
  evidence.projectionDuringLog=(evidence.logs.services.product??[]).filter(line=>line.includes('[商品投影]'))
  const indexDeadline=Date.now()+15000
  do{evidence.stockIndex.after=await stockIndexSnapshot({fixture});if(assessStockIndex({...evidence,mode:'rollback'}).status==='PASS')break;await pause(250)}while(Date.now()<indexDeadline)
  evidence.indexAssessment=assessStockIndex({...evidence,mode:'rollback'})
  evidence.status=[evidence.outboxAssessment,evidence.indexAssessment].some(v=>v.status==='FAIL')?'FAIL':[evidence.outboxAssessment,evidence.indexAssessment].some(v=>v.status!=='PASS')?'BLOCKED':'PASS'
}catch(error){evidence.status=error.blocked?'BLOCKED':'FAIL';evidence.error=error.message}
finally {
  if(lock)try{await lock.release()}catch(error){evidence.status='FAIL';evidence.lockReleaseError=error.message}
  if(checkout)await checkout
  if(proxy){
    proxy.release('WAITING_ROOT_RESTORE')
    console.log(JSON.stringify({status:'WAITING_ORDER_RESTORE',runId,restorePath,assessment:evidence.status,reason:evidence.error}))
    const deadline=Date.now()+300000;let restored
    while(Date.now()<deadline){try{restored=JSON.parse(await readFile(restorePath,'utf8'))}catch{}if(restored?.runId===runId&&restored.ready===true&&restored.pid>0&&restored.seataServer==='127.0.0.1:8091')break;await pause(250)}
    if(restored?.runId!==runId||restored.ready!==true||!(restored.pid>0)||restored.seataServer!=='127.0.0.1:8091'){evidence.status='FAIL';evidence.restoreError='主代理未在5分钟内兑现order8091恢复就绪'}
    else evidence.orderRestoration=restored
    await proxy.close();evidence.proxyEvents=proxy.events
  }
  evidence.finishedAt=new Date().toISOString();await mkdir(dir,{recursive:true});await writeFile(output,JSON.stringify(evidence,null,2)+'\n')
  if(evidence.status!=='PASS')process.exitCode=1
  console.log(JSON.stringify({status:evidence.status,path:output,reason:evidence.error,runId}))
}
