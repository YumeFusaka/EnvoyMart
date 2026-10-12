import {readFile} from 'node:fs/promises'
import {resolve,relative,isAbsolute,dirname,basename} from 'node:path'
import {fileURLToPath} from 'node:url'
import {createHash} from 'node:crypto'
import net from 'node:net'
import {digest} from './write-operation-receipts.mjs'
import {projectionRecoverySnapshot,readOnlyEvidenceSelect} from './write-receipt-seata-local.mjs'
import {PROJECTION_PROXY} from './product-projection-fault-proxy.mjs'

/** 仅显式进程配置；密码不进入命令行、文件或错误输出。 */
export function configuredReadOnlySql(query) {
  if(!/^SELECT\s/i.test(query)||query.includes(';'))throw new Error('验收SQL仅允许单条SELECT')
  if(!process.env.VERIFY_MYSQL_USER)throw new Error('高级验收缺少VERIFY_MYSQL_USER，请通过进程环境显式配置')
  try{return readOnlyEvidenceSelect(query)}
  catch{throw Object.assign(new Error('单SELECT只读取证失败，未输出凭据或SQL身份'),{blocked:true})}
}
export const recoveryDatabase=options=>projectionRecoverySnapshot({...options,execute:configuredReadOnlySql})
/** 固定本机版本键的一次RESP2 GET；总期限含建连，不依赖Docker进程调度。 */
export function readProjectionRedisVersion({signal,connect=address=>net.createConnection(address),schedule=setTimeout,cancelTimer=clearTimeout,now=()=>performance.now()}={}) {
  return new Promise((resolve,reject)=>{
    const deadline=now()+4000
    let socket,timer,settled=false,pending=Buffer.alloc(0)
    const finish=(error,value)=>{
      if(settled)return;settled=true
      if(timer!==undefined)cancelTimer(timer)
      signal?.removeEventListener('abort',cancel)
      if(socket){socket.removeListener('data',receive);socket.removeListener('connect',send);socket.removeListener('end',truncated);socket.removeListener('close',truncated);socket.removeListener('error',network);socket.on('error',()=>{});socket.destroy()}
      if(error)reject(Object.assign(new Error(error),{blocked:true}));else resolve(value)
    }
    const cancel=()=>finish('真实Redis版本只读取证已取消')
    const truncated=()=>finish('真实Redis版本只读取证连接提前关闭')
    const network=()=>finish('真实Redis版本只读取证网络不可用')
    const send=()=>{if(now()>=deadline){finish('真实Redis版本单GET超过4秒总期限');return}try{socket.write('*2\r\n$3\r\nGET\r\n$29\r\nproduct:projection:version:20\r\n')}catch{network()}}
    const receive=chunk=>{
      if(now()>=deadline){finish('真实Redis版本单GET超过4秒总期限');return}
      if(pending.length+chunk.length>256){finish('真实Redis版本RESP响应超过上限');return}
      pending=Buffer.concat([pending,chunk])
      const headerEnd=pending.indexOf('\r\n')
      if(headerEnd<0)return
      const header=pending.subarray(0,headerEnd).toString('ascii')
      if(!/^\$[0-9]+$/.test(header)){finish('真实Redis版本键不存在或RESP返回类型不合法');return}
      const size=Number(header.slice(1)),frameEnd=headerEnd+2+size+2
      if(!Number.isSafeInteger(size)||size<1||size>128){finish('真实Redis版本RESP长度不合法');return}
      if(pending.length<frameEnd)return
      const value=pending.subarray(headerEnd+2,frameEnd-2).toString('utf8')
      if(pending.length!==frameEnd||pending[frameEnd-2]!==13||pending[frameEnd-1]!==10||!/^\d+$/.test(value)){finish('真实Redis永久版本不是完整十进制字符串');return}
      finish(null,value)
    }
    if(signal?.aborted){cancel();return}
    timer=schedule(()=>finish('真实Redis版本单GET超过4秒总期限'),4000)
    signal?.addEventListener('abort',cancel,{once:true})
    try{socket=connect({host:'127.0.0.1',port:6379});socket.on('connect',send);socket.on('data',receive);socket.on('error',network);socket.on('end',truncated);socket.on('close',truncated)}catch{network()}
  })
}
/** 本机已授权SQL回执确认，不伪装为公开HTTP或内部服务身份。 */
export function confirmLocalCheckoutReceipt({fixture,xid,database,logs}) {
  const reject=()=>{throw Object.assign(new Error('原操作只读回执身份、版本或业务结果不匹配，禁止重新执行'),{blocked:true})}
  if(database?.readConsistency!=='SINGLE_SELECT')return reject()
  const rows=database.receiptMetadata
  if(database.receiptCount===0&&rows?.length===0)return null
  if(rows?.length!==1)return reject()
  const row=rows[0]
  if(row.userId!==fixture.userId||row.operationId!==fixture.operationId||row.toolName!=='cart_checkout'||row.parametersHash!==fixture.parametersHash||row.globalXid!==xid||row.hashVersion!==1||row.receiptVersion!==1)return reject()
  if(!row.completed||!row.response)return null
  const data=row.response.data,order=database.orderRows?.[0],tasks=database.projection?.sourceTasks
  if(row.response.code!==200||!Number.isInteger(data?.id)||!data.orderNo||database.orderCount!==1||database.orderRows.length!==1||data.id!==order.id||data.orderNo!==order.orderNo
    ||tasks?.length!==1||tasks[0].xid!==xid)return reject()
  if(database.completeCount!==1||!['order','product'].every(db=>database.undo?.[db]?.length===0)||database.promotionUndo?.length!==0||logs?.tc?.xid!==xid||logs.tc.committed!==true)return null
  if(!['order','product','promotion'].every(service=>logs.services?.[service]?.some(line=>line.includes(xid)&&line.includes('branch register success'))))return null
  return {mode:'LOCAL_READ_ONLY_RECEIPT_AUTHORITY',operationId:row.operationId,parametersHash:row.parametersHash,globalXid:row.globalXid,hashVersion:row.hashVersion,receiptVersion:row.receiptVersion,responseDigest:digest(row.response),data,database,logs}
}
/** 数据库调用已按原user/op/hash限定；错误响应不能代替同XID提交证明。返回副本，不修改待冻结输入。 */
export function bindCommittedCheckoutIdentity(capture,{database,logs}) {
  if(capture.checkoutAttempted!==true||!capture.xid||database?.readConsistency!=='SINGLE_SELECT'||database.orderCount!==1||database.orderRows?.length!==1
    ||database.receiptCount!==1||database.completeCount!==1||!['order','product'].every(db=>database.undo?.[db]?.length===0)||database.promotionUndo?.length!==0
    ||logs?.tc?.xid!==capture.xid||logs.tc.committed!==true||!['order','product','promotion'].every(service=>logs.services?.[service]?.some(line=>line.includes(capture.xid)&&line.includes('branch register success'))))return {...capture}
  const order=database.orderRows[0]
  if(!Number.isInteger(order.id)||!order.orderNo||capture.orderNo&&capture.orderNo!==order.orderNo||capture.orderId&&capture.orderId!==order.id)return {...capture}
  return {...capture,orderNo:order.orderNo,orderId:order.id,checkoutIdentityEvidence:{database,logs,source:'ORIGINAL_OPERATION_SCOPED_SINGLE_SELECT_AND_TC'}}
}
export function validProductControl(control,{runId,phase,previousPid}) {
  const proxies=phase!=='BASELINE_RESTORED'
  return control?.version==='product-projection-control-v1'&&control.runId===runId&&control.phase===phase
    &&control.ready===true&&control.configurationVerified===true&&Number.isInteger(control.pid)&&control.pid>0
    &&Number.isFinite(Date.parse(control.startedAt))&&Number.isFinite(Date.parse(control.observedAt))&&Date.parse(control.observedAt)>=Date.parse(control.startedAt)
    &&control.elasticsearchUris===(proxies?PROJECTION_PROXY.esUri:'http://127.0.0.1:9200')&&control.redisPort===(proxies?PROJECTION_PROXY.redisPort:6379)
    &&(previousPid===undefined||control.previousPid===previousPid&&control.pid!==previousPid)
}
export const authorityFingerprint=db=>digest({stock:db.stock,product:db.product,receiptCount:db.receiptCount,completeCount:db.completeCount,orderCount:db.orderCount,orderRows:db.orderRows,cart:db.cart,coupon:db.coupon,undo:db.undo,promotionUndo:db.promotionUndo,
  outbox:db.outbox?.map(({id,eventType,orderNo,payloadDigest})=>({id,eventType,orderNo,payloadDigest}))})
export function committedAuthority(before,after) {
  return after?.readConsistency==='SINGLE_SELECT'&&after.stock===before.stock-1&&after.product?.totalStock===before.product.totalStock-1
    &&after.receiptCount===1&&after.completeCount===1&&after.orderCount===1&&after.orderRows?.length===1&&after.orderRows[0].status==='CREATED'
    &&after.cart?.length===0&&after.coupon?.status==='USED'&&after.coupon.orderNo===after.orderRows[0].orderNo&&after.outbox?.length===1&&after.outboxScope==='DIRECT_ORIGINAL_ORDER_NOS'
    &&['order','product'].every(db=>after.undo?.[db]?.length===0)&&after.promotionUndo?.length===0
}
export function taskFailure(db,{xid,fault}) {
  const tasks=db?.projection?.sourceTasks??[]
  return tasks.length===1&&tasks[0].xid===xid&&tasks[0].completedAt===null&&tasks[0].attempts>0
    &&tasks[0].lastErrorType?.startsWith(fault==='es'?'ES:':'CACHE:')&&!!tasks[0].nextAttemptAt
}
export function publicationComplete(db,index,redisVersion,{taskId}) {
  const task=db?.projection?.sourceTasks?.find(t=>t.id===taskId),guard=db?.projection?.guard
  if(!task?.completedAt||task.mode!=='CACHE_AND_ES'||task.lastErrorType!==null||task.nextAttemptAt!==null||db.projection.pendingTaskCount!==0
    ||!/^\d+$/.test(taskId??'')||!/^\d+$/.test(task.appliedTaskId??'')||!/^\d+$/.test(guard?.versionBase??'')||!/^\d+$/.test(guard?.lastAppliedTaskId??'')||!/^\d+$/.test(index?.version??''))return false
  return index.totalStock===db.product.totalStock&&BigInt(task.appliedTaskId)>=BigInt(taskId)&&BigInt(guard.lastAppliedTaskId)>=BigInt(task.appliedTaskId)
    &&BigInt(index.version)===BigInt(guard.versionBase)+BigInt(guard.lastAppliedTaskId)&&redisVersion===guard.lastAppliedTaskId
}
const boundLog=(proof,pid,taskId,stage)=>proof?.lines?.some(line=>line.includes(' '+pid+' --- [product-service]')&&line.includes('[商品投影]')&&line.includes('阶段='+stage)&&line.includes(' taskId='+taskId+' '))
export function assessProjectionRecovery(e) {
  const fail=reason=>({status:'FAIL',reason}),blocked=reason=>({status:'BLOCKED',reason})
  if(e?.version!=='product-projection-recovery-v1'||!['es','redis'].includes(e.fault)||!e.xid||!/^\d+$/.test(e.taskId??'')||!e.operationId||!/^[a-f0-9]{64}$/.test(e.parametersHash??''))return blocked('缺独立外部故障/原操作和来源任务身份')
  if(!e.before?.database||!e.before?.index||!e.failed?.database||!e.failed?.index||!e.restarted?.database||!e.restarted?.index||!e.recovered?.database||!e.recovered?.index)return blocked('缺完整故障、重启与恢复阶段快照')
  if(e.checkoutFirstResponse?.httpStatus===503){
    const first=e.checkoutFirstResponse,recovered=e.checkoutRecovery
    if(first.body?.code!==503||first.responseDigest!==hash(JSON.stringify(first.body))||!e.userId||recovered?.mode!=='LOCAL_READ_ONLY_RECEIPT_AUTHORITY')return blocked('缺首次503原响应摘要与明确本机只读恢复证据')
    const metadata=first.receiptMetadata
    if(metadata&&(metadata.operationId!==null&&metadata.operationId!==e.operationId||metadata.parametersHash!==null&&metadata.parametersHash!==e.parametersHash||metadata.receiptVersion!==null&&metadata.receiptVersion!=='1'))return blocked('首次503响应已携带相冲突的原操作元数据')
    let proof
    try{proof=confirmLocalCheckoutReceipt({fixture:{userId:e.userId,operationId:e.operationId,parametersHash:e.parametersHash},xid:e.xid,database:recovered.database,logs:recovered.logs})}
    catch{return blocked('原503只读确认身份或回执契约不匹配')}
    if(!proof||proof.responseDigest!==recovered.responseDigest||proof.data.id!==e.orderId||proof.data.orderNo!==e.orderNo||digest(proof.data)!==digest(e.checkout)
      ||e.calls?.filter(call=>call.path==='/orders/checkout'&&call.method==='POST').length!==1)return blocked('首次503没有保持唯一写请求、原响应或原操作只读权威结果')
  }
  if(!validProductControl(e.activation,{runId:e.runId,phase:'PROXY_READY'})||!validProductControl(e.restart,{runId:e.runId,phase:'RESTARTED_FAULT_HELD',previousPid:e.activation.pid})
    ||!validProductControl(e.restoration,{runId:e.runId,phase:'BASELINE_RESTORED',previousPid:e.restart.pid}))return blocked('缺真实商品新PID、配置与最终直连恢复marker')
  if(!(e.transactionLogs?.tc?.xid===e.xid&&e.transactionLogs.tc.committed===true)
    ||!['order','product','promotion'].every(service=>e.transactionLogs?.services?.[service]?.some(line=>line.includes(e.xid)&&line.includes('branch register success'))))return blocked('缺三服务同XID实际AT分支及TC提交终态')
  if(!committedAuthority(e.before.database,e.failed.database)||!taskFailure(e.failed.database,e)||!taskFailure(e.restarted.database,e))return fail('未证明业务真实提交且原任务外部失败持久保留')
  if(!/^\d+$/.test(e.before.database.projection?.maximumTaskId??'')||e.before.database.projection.globalPendingTaskCount!==0)return blocked('缺开始前永久任务基线与全局pending零证据')
  if(BigInt(e.taskId)<=BigInt(e.before.database.projection.maximumTaskId))return fail('故障任务不是本实验后新登记的来源任务')
  if(e.failed.database.projection.sourceTasks[0].id!==e.taskId||e.restarted.database.projection.sourceTasks[0].id!==e.taskId)return fail('跨进程任务身份被替换')
  if(e.restarted.database.projection.sourceTasks[0].attempts<e.failed.database.projection.sourceTasks[0].attempts)return fail('重启使原任务持久尝试次数回退')
  if(!e.events?.some(v=>v.kind===(e.fault==='es'?'ES_WRITE_REJECTED':'REDIS_CONNECTION_REJECTED'))||e.events.some(v=>v.reason==='MAXIMUM_FAULT_WINDOW_EXPIRED'))return blocked('缺自有代理实际拒绝，或故障控制窗口自动过期')
  if(!boundLog(e.failed.logs,e.activation.pid,e.taskId,'外部失败保留待办')||!boundLog(e.restarted.logs,e.restart.pid,e.taskId,'外部失败保留待办')||!boundLog(e.recovered.logs,e.restart.pid,e.taskId,'已完成'))return blocked('缺原任务在两个真实PID的失败与自动完成日志')
  if(e.failed.index.version!==e.before.index.version||e.restarted.index.version!==e.before.index.version||e.failed.index.totalStock!==e.before.index.totalStock)return fail('外部失败时ES可见投影或版本已改变')
  if(authorityFingerprint(e.failed.database)!==authorityFingerprint(e.restarted.database)||authorityFingerprint(e.failed.database)!==authorityFingerprint(e.recovered.database))return fail('自动恢复修改了三库业务权威状态')
  if(e.recovered.database.outbox[0].attempts<1||!e.recovered.database.outbox[0].sentAt)return blocked('最终Outbox事件尚未被实际接受发送')
  if(!publicationComplete(e.recovered.database,e.recovered.index,e.recovered.redisVersion,e))return fail('原任务未自动完成或SQL/Redis/ES持久版本不一致')
  return {status:'PASS',reason:'实际外部失败持久待办跨商品新PID自动完成，三库业务状态保持，直连配置已恢复'}
}
export function assessProjectionCleanup(capture,cleanup,{capturePath,captureDigest}) {
  const blocked=reason=>({status:'BLOCKED',reason}),fail=reason=>({status:'FAIL',reason})
  if(cleanup?.version!=='product-projection-cleanup-v1'||cleanup.runId!==capture.runId||cleanup.fault!==capture.fault||cleanup.capturePath!==capturePath||cleanup.captureDigest!==captureDigest)return blocked('清理报告未绑定原capture路径、runId及SHA256')
  const after=cleanup.after?.database,index=cleanup.after?.index,guard=after?.projection?.guard
  if(!after||after.readConsistency!=='SINGLE_SELECT'||!guard)return blocked('缺清理后的单SELECT三库与版本闸证据')
  const noCheckout=cleanup.cleanupMode==='NO_CHECKOUT'
  if(noCheckout){
    if(capture.checkoutAttempted!==false||!Array.isArray(capture.calls)||capture.calls.some(call=>call.path==='/orders/checkout')||capture.xid||capture.orderNo||capture.taskId||capture.events?.some(event=>event.kind==='FAULT_ARMED'))return fail('未执行业务清理声明与原请求或故障记录矛盾')
    if(after.orderCount!==0||after.receiptCount!==0||after.completeCount!==0||after.orderRows?.length!==0||after.outbox?.length!==0||after.restoreRows?.length!==0||after.projection.sourceTasks?.length!==0
      ||after.coupon?.status!=='UNUSED'||after.coupon.orderNo!==null||after.coupon.usedAtPresent!==false||after.cart?.length!==0||!Number.isInteger(after.stock))return fail('未checkout的独立用户仍有业务副作用或夹具未清理')
  }else if(!capture.before?.database?.coupon)return blocked('已尝试checkout却缺执行前权威基线，不能以当前值补造')
  else if(after.stock!==capture.before.database.stock||after.coupon?.status!=='UNUSED'||after.coupon?.orderNo!==null||after.coupon?.usedAtPresent!==capture.before.database.coupon.usedAtPresent||after.cart?.length!==0
    ||after.orderRows?.length!==1||after.orderRows[0].status!=='CANCELLED'||after.orderRows[0].orderNo!==capture.orderNo||after.receiptCount!==1||after.completeCount!==1
    ||after.restoreRows?.length!==1||after.restoreRows[0].quantity!==1||after.restoreRows[0].orderNo!==capture.orderNo||after.restoreRows[0].beforeStock+1!==after.restoreRows[0].afterStock)return fail('自有订单取消、唯一回补、券和购物车未完整结算')
  if(after.projection.globalPendingTaskCount!==0||index?.totalStock!==after.product.totalStock||!/^\d+$/.test(index.version??'')||!/^\d+$/.test(guard.versionBase??'')||!/^\d+$/.test(guard.lastAppliedTaskId??'')
    ||BigInt(index.version)!==BigInt(guard.versionBase)+BigInt(guard.lastAppliedTaskId)||cleanup.after.redisVersion!==guard.lastAppliedTaskId)return fail('清理后的自动投影或永久版本未结算')
  return {status:'PASS',reason:noCheckout?'原capture未发checkout且SHA不变，零订单回执、原券未使用及自有购物车夹具已清理':'原capture SHA不变，自有订单取消后唯一库存回补、券及自动投影均已结算'}
}
const evidenceRoot=fileURLToPath(new URL('../../.evidence/',import.meta.url))
const hash=bytes=>createHash('sha256').update(bytes).digest('hex')
function publicEvidencePath(path,base=evidenceRoot) {
  const absolute=resolve(base,path),inside=relative(evidenceRoot,absolute)
  if(inside.startsWith('..')||isAbsolute(inside)||!/^product-projection-(?:recovery|cleanup)-[a-f0-9-]+-(?:es|redis|index)\.json$/.test(basename(absolute)))throw new Error('仅接受本仓库公开投影capture、cleanup或两模式索引')
  return absolute
}
/** 默认门禁只读重判；两模式索引须分别强绑定原capture及其独立cleanup SHA。 */
export async function compareProjectionEvidence(path,{read=readFile,requireBoth=false}={}) {
  const checks=[],loadedPath=publicEvidencePath(path,process.cwd()),loaded=JSON.parse(await read(loadedPath))
  const group=loaded.version==='product-projection-recovery-index-v1'
  if(requireBoth&&!group)return {status:'BLOCKED',reason:'总门禁必须提供ES和Redis两模式索引，单份capture不能替代'}
  if(group&&(!['es','redis'].every(mode=>loaded.cases?.[mode])||Object.keys(loaded.cases).length!==2))return {status:'BLOCKED',reason:'两模式索引必须同时包含ES及Redis独立原capture/cleanup'}
  const entries=group?Object.entries(loaded.cases):[[loaded.fault,{capture:loadedPath}]],identities=[]
  for(const [mode,entry] of entries)try{
    if(!['es','redis'].includes(mode))throw new Error('不是受支持的实际故障模式')
    const capturePath=publicEvidencePath(entry.capture,dirname(loadedPath)),raw=await read(capturePath),capture=JSON.parse(raw),captureDigest=hash(raw)
    if(capture.fault!==mode||capturePath!==resolve(evidenceRoot,`product-projection-recovery-${capture.runId}-${mode}.json`))throw new Error('原capture路径与runId/故障模式不匹配')
    const cleanupPath=publicEvidencePath(entry.cleanup??`product-projection-cleanup-${capture.runId}-${mode}.json`),cleanupRaw=await read(cleanupPath),cleanup=JSON.parse(cleanupRaw),cleanupDigest=hash(cleanupRaw)
    if(group&&(entry.captureDigest!==captureDigest||entry.cleanupDigest!==cleanupDigest))throw new Error('索引未严格绑定两份原证据SHA256')
    let assessment=assessProjectionRecovery(capture)
    if(capture.status==='FAIL'&&assessment.status!=='FAIL')assessment={status:'FAIL',reason:'保留原capture失败事实'}
    else if(capture.status!=='PASS'&&assessment.status==='PASS')assessment={status:'BLOCKED',reason:'原capture未通过，不覆盖原运行的阻断事实'}
    const cleanupAssessment=assessProjectionCleanup(capture,cleanup,{capturePath,captureDigest})
    if(cleanup.status!=='PASS'&&cleanupAssessment.status==='PASS')cleanupAssessment.status=cleanup.status==='FAIL'?'FAIL':'BLOCKED'
    const status=[assessment,cleanupAssessment].some(v=>v.status==='FAIL')?'FAIL':[assessment,cleanupAssessment].every(v=>v.status==='PASS')?'PASS':'BLOCKED'
    identities.push({runId:capture.runId,xid:capture.xid,taskId:capture.taskId,orderNo:capture.orderNo,operationId:capture.operationId})
    checks.push({mode,status,capturePath,captureDigest,sourceStatus:capture.status,cleanupPath,cleanupDigest,capture:assessment,cleanup:cleanupAssessment})
  }catch(error){checks.push({mode,status:'BLOCKED',reason:error.message})}
  if(group&&identities.length===2&&['runId','xid','taskId','orderNo','operationId'].some(key=>identities[0][key]===identities[1][key]))checks.push({status:'FAIL',reason:'两故障复用了同一runId、业务XID、订单、原操作或持久任务，缺独立实验'})
  return {status:checks.some(c=>c.status==='FAIL')?'FAIL':checks.length&&checks.every(c=>c.status==='PASS')?'PASS':'BLOCKED',checks}
}
