/** 自有代理外部故障→原持久任务→商品新PID自动恢复；服务控制全部由根代理兑现marker。 */
import {readFile,writeFile,mkdir} from 'node:fs/promises'
import {resolve,isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {randomUUID,createHash} from 'node:crypto'
import {ADDRESS} from './lib/complex-agent-executor.mjs'
import {parameterHash} from './lib/write-operation-receipts.mjs'
import {observeCheckoutXid,lateCouponLogEvidence} from './lib/write-receipt-seata-local.mjs'
import {createProjectionFaultProxies,PROJECTION_PROXY} from './lib/product-projection-fault-proxy.mjs'
import {configuredReadOnlySql,recoveryDatabase,validProductControl,committedAuthority,taskFailure,publicationComplete,assessProjectionRecovery,assessProjectionCleanup,compareProjectionEvidence,readProjectionRedisVersion,bindCommittedCheckoutIdentity,confirmLocalCheckoutReceipt} from './lib/product-projection-recovery.mjs'

const args=process.argv.slice(2),option=prefix=>args.find(arg=>arg.startsWith(prefix))?.slice(prefix.length)
const directory=fileURLToPath(new URL('../.evidence/',import.meta.url)),sha=bytes=>createHash('sha256').update(bytes).digest('hex')
const compare=option('--compare=')
if(compare){
  if(!(args.length===1||args.length===2&&args.includes('--require-both')))throw new Error('compare仅允许原证据路径及可选--require-both')
  const verdict=await compareProjectionEvidence(compare,{requireBoth:args.includes('--require-both')})
  console.log(JSON.stringify(verdict));process.exit(verdict.status==='PASS'?0:1)
}
if(!args.length||args.includes('--help')||args.includes('--dry-run')){
  console.log('先独立prepare-unused，ES/Redis各一个新fixture，不复用已接受operationId。\n'+
    'PRODUCT_PROJECTION_FAULT_ENABLE=1 node scripts/verify-product-projection-recovery.mjs --capture --fault=es|redis --fixture=<绝对private> --activate-evidence=<绝对ready.json> --restart-evidence=<绝对restarted.json> --restore-evidence=<绝对restored.json>\n'+
    'node scripts/verify-product-projection-recovery.mjs --compare=<两模式index.json> --require-both：总门禁只读重判，无SQL/网络/代理；index.version=product-projection-recovery-index-v1，cases.es/redis各含capture/captureDigest/cleanup/cleanupDigest，必须两独立runId/XID/订单/原操作/任务及四个SHA齐全。人工单份compare可省--require-both。\n'+
    '高级验收环境：VERIFY_MYSQL默认使用PATH中的mysql；VERIFY_MYSQL_USER必需；VERIFY_MYSQL_PASSWORD可选，仅经子进程MYSQL_PWD传递；不读.env或输出凭据。Redis固定版本键直连127.0.0.1:6379，一次RESP GET，总期限4秒，无AUTH/重试/Docker子进程，失败保留BLOCKED。\n'+
    '仅监听本机19200→9200、16379→6379；ES只拒SPU20写，Redis只断自有代理连接。故障最多5分钟，finally立即透明恢复，等待根兑现product直连恢复后关闭代理。\n'+
    '首次checkout503保留原响应和摘要，只用LOCAL_READ_ONLY_RECEIPT_AUTHORITY确认原user/op/tool/hash及同XID三库终态，最多20秒且不延长故障期限；不再次POST、不访问被网关禁止的internal回执GET、不读取内部token。\n'+
    'marker统一version=product-projection-control-v1、runId、phase、pid、previousPid、ready=true、configurationVerified=true、startedAt、observedAt、elasticsearchUris、redisPort；配置须由根实际核查。\n'+
    'phase=PROXY_READY及RESTARTED_FAULT_HELD必须URI=http://127.0.0.1:19200/port=16379；phase=BASELINE_RESTORED必须URI=http://127.0.0.1:9200/port=6379。第二/第三marker必须新PID并绑定previousPid。\n'+
    '先冻结并哈希原capture，再公开取消自有订单/删除自有购物车行，独立cleanup报告绑定原SHA；保留账户、券及审计fixture，不执行数据库删除或手工同步。')
  process.exit(args.length?0:2)
}
const allowed=['--fixture=','--fault=','--activate-evidence=','--restart-evidence=','--restore-evidence=']
if(args.some(arg=>arg!=='--capture'&&!allowed.some(prefix=>arg.startsWith(prefix)))||!args.includes('--capture')||process.env.PRODUCT_PROJECTION_FAULT_ENABLE!=='1')throw new Error('故障capture需要根明确运行窗口及PRODUCT_PROJECTION_FAULT_ENABLE=1')
const fixturePath=option('--fixture='),fault=option('--fault='),readyPath=option('--activate-evidence='),restartPath=option('--restart-evidence='),restorePath=option('--restore-evidence=')
if(!['es','redis'].includes(fault)||[fixturePath,readyPath,restartPath,restorePath].some(path=>!path||!isAbsolute(path)))throw new Error('需要固定故障模式及四个绝对证据路径')
configuredReadOnlySql('SELECT 1')
const fixture=JSON.parse(await readFile(fixturePath,'utf8'))
if(fixture.version!=='seata-private-fixture-v1'||!/^[a-f0-9-]{36}$/.test(fixture.runId??'')||fixture.mode!=='phase1'||fixture.status!=='READY'||fixture.skuId!==29||!fixture.token||!fixture.userCouponId||parameterHash(fixture.userId,'cart_checkout',fixture.checkoutBody)!==fixture.parametersHash)throw new Error('需要独立READY有效券SKU29 fixture')
for(const [key,value] of Object.entries(ADDRESS))if(fixture.checkoutBody[key]!==value)throw new Error('fixture收货字段不是本实验输入')
if(Object.keys(fixture.checkoutBody).some(key=>key!=='remark'&&key!=='userCouponId'&&!Object.hasOwn(ADDRESS,key)))throw new Error('fixture引入未授权checkout字段')
if(fixture.checkoutBody.userCouponId!==fixture.userCouponId||fixture.checkoutBody.remark!==null)throw new Error('fixture券或备注不匹配')
const runId=fixture.runId,output=resolve(directory,`product-projection-recovery-${runId}-${fault}.json`),cleanupPath=resolve(directory,`product-projection-cleanup-${runId}-${fault}.json`)
await mkdir(directory,{recursive:true})
try{await readFile(output);throw new Error('原capture已存在，禁止覆盖或重复接受操作')}catch(error){if(error.code!=='ENOENT')throw error}
const e={version:'product-projection-recovery-v1',runId,fault,userId:fixture.userId,operationId:fixture.operationId,parametersHash:fixture.parametersHash,checkoutAttempted:false,status:'BLOCKED',startedAt:new Date().toISOString(),calls:[]},cancelled=new AbortController()
for(const signal of ['SIGINT','SIGTERM'])process.once(signal,()=>cancelled.abort())
const blocked=message=>Object.assign(new Error(message),{blocked:true}),pause=ms=>new Promise(done=>setTimeout(done,ms))
let proxy,checkoutAttempted=false,cleanupPhase=false
const active=()=>{if(cancelled.signal.aborted)throw blocked('用户取消，finally恢复自有代理并等待根恢复直连')}
const gateway=process.env.VERIFY_GW??'http://127.0.0.1:8080'
async function api(path,{method='GET',body,operationId,requestId=randomUUID(),allowPending=false}={}) {
  const call={path,method,requestId};e.calls.push(call)
  const response=await fetch(gateway+path,{method,headers:{'Content-Type':'application/json',Authorization:'Bearer '+fixture.token,'X-Request-Id':requestId,...(operationId?{'X-Operation-Id':operationId}:{})},
    ...(body===undefined?{}:{body:JSON.stringify(body)}),signal:cleanupPhase?AbortSignal.timeout(90000):AbortSignal.any([cancelled.signal,AbortSignal.timeout(90000)])})
  const result=await response.json();Object.assign(call,{httpStatus:response.status,code:result.code})
  if(allowPending){
    e.checkoutFirstResponse={requestId,httpStatus:response.status,body:result,responseDigest:sha(JSON.stringify(result)),receiptMetadata:{operationId:response.headers.get('x-operation-id'),parametersHash:response.headers.get('x-operation-parameters-hash'),receiptVersion:response.headers.get('x-operation-receipt-version'),replayed:response.headers.get('x-operation-replayed')}}
    if(response.status===503&&result.code===503){
      const metadata=e.checkoutFirstResponse.receiptMetadata
      if(metadata.operationId!==null&&metadata.operationId!==fixture.operationId||metadata.parametersHash!==null&&metadata.parametersHash!==fixture.parametersHash||metadata.receiptVersion!==null&&metadata.receiptVersion!=='1')throw blocked('首次503响应已携带相冲突的原操作元数据，停止只读确认')
      return null
    }
  }
  if(result.code!==200)throw new Error('自有用户公开API失败：'+path+' code='+result.code)
  if(operationId&&(response.headers.get('x-operation-id')!==operationId||response.headers.get('x-operation-parameters-hash')!==fixture.parametersHash||response.headers.get('x-operation-receipt-version')!=='1'||response.headers.get('x-operation-replayed')!=='false'))throw blocked('原checkout回执元数据不匹配')
  return result.data
}
async function waitControl(path,phase,previousPid,maxMs=180000,{ignoreCancellation=false}={}) {
  const deadline=Date.now()+maxMs
  while(Date.now()<deadline){if(!ignoreCancellation)active();let marker;try{marker=JSON.parse(await readFile(path,'utf8'))}catch{}
    if(validProductControl(marker,{runId,phase,previousPid}))return marker
    await pause(250)
  }
  throw blocked('根未在有界窗口兑现商品'+phase+'实际PID/ready/配置marker')
}
async function indexSnapshot(spuId) {
  if(spuId!==20)throw blocked('实验SKU不是独占SPU20')
  const response=await fetch('http://127.0.0.1:9200/envoymart_spu_v3/_doc/20',{signal:AbortSignal.timeout(4000)})
  if(!response.ok)throw blocked('实际ES直连只读取证不可用')
  const raw=await response.text(),document=JSON.parse(raw),version=raw.match(/"_version"\s*:\s*(\d+)/)?.[1]
  if(!document.found||!Number.isInteger(document._source?.totalStock)||!version)throw blocked('ES原文缺库存或精确版本')
  return {at:new Date().toISOString(),version,totalStock:document._source.totalStock,readOnly:true}
}
function redisVersion() {
  return readProjectionRedisVersion({signal:cleanupPhase?undefined:cancelled.signal})
}
async function productLogs() {
  const path=fileURLToPath(new URL('../../logs/logs-local/product-service.log',import.meta.url)),raw=await readFile(path)
  return {path,sourceDigest:sha(raw),readAt:new Date().toISOString(),lines:raw.toString('utf8').split(/\r?\n/).filter(line=>line.includes('[商品投影]')&&(!e.taskId||line.includes(' taskId='+e.taskId+' ')))}
}
async function snapshot() {
  const database=await recoveryDatabase({fixture,xid:e.xid,orderNos:e.orderNo?[e.orderNo]:[]})
  return {at:new Date().toISOString(),database,index:await indexSnapshot(database.product.spuId),redisVersion:await redisVersion(),logs:await productLogs()}
}
async function poll(capture,predicate,maxMs) {
  const deadline=Date.now()+maxMs;let value
  do{active();value=await capture();if(predicate(value))return value;await pause(400)}while(Date.now()<deadline)
  throw blocked('有界轮询未观察到完整持久任务或业务终态')
}
try {
  fixture.status='PROJECTION_CAPTURING';await writeFile(fixturePath,JSON.stringify(fixture,null,2)+'\n')
  proxy=await createProjectionFaultProxies({spuId:20})
  console.log(JSON.stringify({status:'WAITING_PRODUCT_READY',runId,fault,...PROJECTION_PROXY,readyPath}))
  e.activation=await waitControl(readyPath,'PROXY_READY',undefined,900000)
  e.before=await poll(snapshot,value=>value.database.projection.globalPendingTaskCount===0&&value.database.projection.pendingTaskCount===0&&value.database.projection.guard?.versionBase&&value.database.product.totalStock===value.index.totalStock,45000)
  if(e.before.database.stock<5||e.before.database.receiptCount!==0||e.before.database.orderCount!==0||JSON.stringify(e.before.database.cart)!==JSON.stringify([['29','1','1']])||e.before.database.coupon.status!=='UNUSED'||e.before.database.coupon.orderNo!==null||e.before.database.coupon.usedAtPresent!==false)throw blocked('隔离有效券零订单/回执或SKU29唯一选中一件前提不满足')
  proxy.arm(fault)
  const requestId=randomUUID();e.checkoutRequestId=requestId;checkoutAttempted=true;e.checkoutAttempted=true
  e.checkout=await api('/orders/checkout',{method:'POST',body:fixture.checkoutBody,operationId:fixture.operationId,requestId,allowPending:true})
  const observed=await observeCheckoutXid({requestId,maxWaitMs:5000});e.xid=observed.xid;e.xidObservation=observed
  if(e.checkout===null){
    const armedAt=Date.parse(proxy.events.find(v=>v.kind==='FAULT_ARMED').at),remaining=armedAt+300000-Date.now(),windowMs=Math.min(20000,remaining)
    if(windowMs<=0)throw blocked('原故障窗口已失效，不确认或重发写请求')
    const recoveryDeadline=Date.now()+windowMs
    e.checkoutRecovery=await poll(async()=>{
      if(proxy.active()!==fault)throw blocked('只读确认时原故障窗口已失效')
      const database=await recoveryDatabase({fixture,xid:e.xid,receiptMetadata:true}),logs=await lateCouponLogEvidence({xid:e.xid})
      if(Date.now()>recoveryDeadline||proxy.active()!==fault)throw blocked('只读确认已超过原有界窗口或故障有效期，不延长或重发')
      return confirmLocalCheckoutReceipt({fixture,xid:e.xid,database,logs})
    },value=>value!==null,windowMs)
    e.checkout=e.checkoutRecovery.data
  }
  e.orderNo=e.checkout.orderNo;e.orderId=e.checkout.id
  e.failed=await poll(snapshot,value=>committedAuthority(e.before.database,value.database)&&taskFailure(value.database,e),30000)
  e.taskId=e.failed.database.projection.sourceTasks[0].id;e.failed.logs=await productLogs()
  e.transactionLogs=await poll(()=>lateCouponLogEvidence({xid:e.xid,orderNos:[e.orderNo],outboxIds:e.failed.database.outbox.map(v=>v.id)}),value=>value.tc?.xid===e.xid&&value.tc.committed===true&&['order','product','promotion'].every(service=>value.services[service]?.some(line=>line.includes(e.xid)&&line.includes('branch register success'))),10000)
  const armedAt=proxy.events.find(v=>v.kind==='FAULT_ARMED').at
  console.log(JSON.stringify({status:'FAULT_PENDING_WAITING_PRODUCT_RESTART',runId,fault,taskId:e.taskId,xid:e.xid,previousPid:e.activation.pid,restartPath,faultExpiresAt:new Date(Date.parse(armedAt)+300000).toISOString()}))
  e.restart=await waitControl(restartPath,'RESTARTED_FAULT_HELD',e.activation.pid)
  e.restarted=await poll(snapshot,value=>taskFailure(value.database,e)&&value.logs.lines.some(line=>line.includes(' '+e.restart.pid+' --- [product-service]')&&line.includes('阶段=外部失败保留待办')&&line.includes(' taskId='+e.taskId+' ')),30000)
  if(proxy.active()!==fault)throw blocked('原故障窗口已经结束，不能证明持久失败跨进程')
  proxy.clear('ORIGINAL_PENDING_PROVED_IN_NEW_PRODUCT_PID')
  e.recovered=await poll(snapshot,value=>publicationComplete(value.database,value.index,value.redisVersion,e)&&value.database.projection.globalPendingTaskCount===0,60000)
  e.status='OBSERVED_WAITING_RESTORE'
}catch(error){e.status=error.blocked?'BLOCKED':'FAIL';e.error=error.message}
finally {
  try{await finalizeCapture()}
  catch(error){process.exitCode=1;console.log(JSON.stringify({status:'FAIL',runId,fault,path:output,orderId:e.orderId,orderNo:e.orderNo,reason:'验收收尾执行失败：'+error.message}))}
}
async function finalizeCapture() {
  if(proxy)proxy.clear('FINALLY')
  if(checkoutAttempted&&!e.xid)try{const observed=await observeCheckoutXid({requestId:e.checkoutRequestId,maxWaitMs:5000});e.xid=observed.xid;e.xidObservation=observed}catch{e.xidObservationError='原确认请求XID未定位，不能假定业务未执行'}
  if(proxy){
    proxy.clear('FINALLY')
    console.log(JSON.stringify({status:'WAITING_PRODUCT_BASELINE_RESTORE',runId,fault,previousPid:e.restart?.pid??e.activation?.pid??null,restorePath,assessment:e.status,reason:e.error}))
    try{e.restoration=await waitControl(restorePath,'BASELINE_RESTORED',e.restart?.pid??e.activation?.pid,300000,{ignoreCancellation:true})}
    catch(error){e.status='FAIL';e.restoreError=error.message}
    await proxy.close();e.events=proxy.events
  }
  if(!e.error&&!e.restoreError){e.assessment=assessProjectionRecovery(e);e.status=e.assessment.status}
  if(checkoutAttempted&&e.xid&&!e.orderNo)try{
    const database=await recoveryDatabase({fixture,xid:e.xid}),logs=await lateCouponLogEvidence({xid:e.xid})
    Object.assign(e,bindCommittedCheckoutIdentity(e,{database,logs}))
    if(!e.orderNo)e.checkoutIdentityError='原操作数据库行与同XID提交证明未齐，保持未绑定'
  }catch{e.checkoutIdentityError='冻结前原操作身份只读取证不可用，未补造身份'}
  e.finishedAt=new Date().toISOString();await writeFile(output,JSON.stringify(e,null,2)+'\n',{flag:'wx'})
  const captureRaw=await readFile(output),frozenCapture=JSON.parse(captureRaw),captureDigest=sha(captureRaw),cleanupCallsStart=e.calls.length,cleanup={version:'product-projection-cleanup-v1',runId,fault,capturePath:output,captureDigest,originalStatus:e.status,cleanupMode:checkoutAttempted?'CHECKOUT_ATTEMPTED':'NO_CHECKOUT',status:'BLOCKED',startedAt:new Date().toISOString()}
  let cleanupOrderNo=frozenCapture.orderNo
  cleanupPhase=true
  try{
    if(!e.restoration)throw blocked('未确认服务直连恢复，停止业务清理并保留证据')
    let database=await recoveryDatabase({fixture,xid:frozenCapture.xid,orderNos:cleanupOrderNo?[cleanupOrderNo]:[]})
    const owned=database.orderRows.find(row=>row.status==='CREATED')
    if(owned){cleanup.cancelledOrder={id:owned.id,orderNo:owned.orderNo};await api('/orders/'+owned.id+'/cancel',{method:'POST'});cleanupOrderNo=owned.orderNo}
    const cart=await api('/cart'),items=cart.items??cart
    if(!Array.isArray(items))throw new Error('自有购物车响应结构不明确')
    for(const item of items)if(item.skuId===fixture.skuId)await api('/cart/items/'+item.id,{method:'DELETE'})
    const deadline=Date.now()+60000
    do{database=await recoveryDatabase({fixture,xid:frozenCapture.xid,orderNos:cleanupOrderNo?[cleanupOrderNo]:[]});cleanup.after={database,index:await indexSnapshot(database.product.spuId),redisVersion:await redisVersion()};
      cleanup.assessment=assessProjectionCleanup(frozenCapture,cleanup,{capturePath:output,captureDigest})
      if(cleanup.assessment.status==='PASS')break
      if(checkoutAttempted&&(!frozenCapture.before||!frozenCapture.orderNo))throw blocked('原checkout缺冻结的执行前基线或提交订单身份，不进行无意义等待')
      await pause(400)
    }while(Date.now()<deadline)
    cleanup.status=cleanup.assessment.status
    cleanup.retained='独立账户、UNUSED券及审计fixture保留，未修改其他用户或直接删库'
  }catch(error){cleanup.status=error.blocked?'BLOCKED':'FAIL';cleanup.error=error.message}
  cleanup.calls=e.calls.slice(cleanupCallsStart);cleanup.finishedAt=new Date().toISOString();await writeFile(cleanupPath,JSON.stringify(cleanup,null,2)+'\n',{flag:'wx'})
  fixture.status=e.status;fixture.projectionCapturePath=output;await writeFile(fixturePath,JSON.stringify(fixture,null,2)+'\n')
  console.log(JSON.stringify({status:e.status,path:output,captureDigest,cleanupPath,cleanupStatus:cleanup.status,runId,fault,reason:e.error??e.restoreError??e.assessment?.reason}))
  if(e.status!=='PASS'||cleanup.status!=='PASS')process.exitCode=1
}
