/** 本地数据库取证适配器：显式验收配置，不读取.env。 */
import { spawn, spawnSync } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { createHash } from 'node:crypto'

const mysql = process.env.VERIFY_MYSQL ?? 'mysql'
const connection=()=>{
  const user=process.env.VERIFY_MYSQL_USER
  if(!user)throw Object.assign(new Error('真实数据库取证需显式设置VERIFY_MYSQL_USER'),{blocked:true})
  return {args:['-h127.0.0.1','-P3306','-u'+user,'-N','--batch','--raw','--unbuffered','--default-character-set=utf8mb4'],
    env:{...process.env,MYSQL_PWD:process.env.VERIFY_MYSQL_PASSWORD??''}}
}
const logDir = fileURLToPath(new URL('../../../logs/logs-local/',import.meta.url))
const numeric = (value) => { if(!/^\d+$/.test(String(value)))throw new Error('数据主键不是可信数字');return String(value) }
const text = (value) => { if(!/^[A-Za-z0-9_:.-]{1,256}$/.test(String(value)))throw new Error('SQL身份/操作编号不符合本实验字符约束');return "'"+value+"'" }
const sql = (query) => {
  if(!/^SELECT\s/i.test(query))throw new Error('取证SQL只允许SELECT')
  const config=connection()
  const result=spawnSync(mysql,[...config.args,'-e',query],{encoding:'utf8',windowsHide:true,timeout:4000,env:config.env})
  if(result.status!==0)throw Object.assign(new Error('只读SQL取证失败：'+String(result.stderr).replace(/Warning:[^\n]*/g,'').trim()),{blocked:true})
  return result.stdout.trim()
}
const rows = (query) => sql(query).split(/\r?\n/).filter(Boolean).map((line)=>line.split('\t'))
/** 验收调用方可复用同一份显式连接配置，并在边界处脱敏错误。 */
export function readOnlyEvidenceSelect(query) { return sql(query) }
/** 同实例跨库的全部InnoDB输入共用一条SELECT读视图，禁止逐连接拼接不同时刻。 */
export function readConsistentSections(sections,execute=sql) {
  const queries=Object.entries(sections).map(([name,{columns,source}])=>{
    if(!/^[A-Za-z][A-Za-z0-9]*$/.test(name)||!Array.isArray(columns)||!columns.length||!/^(?:FROM\s|$)/i.test(source))throw new Error('快照分组不符合固定查询契约')
    return `SELECT '${name}' AS snapshot_section,JSON_ARRAY(${columns.join(',')}) AS snapshot_row ${source}`
  })
  const result=Object.fromEntries(Object.keys(sections).map(name=>[name,[]]))
  for(const line of execute(queries.join(' UNION ALL ')).split(/\r?\n/).filter(Boolean)) {
    const separator=line.indexOf('\t'),name=line.slice(0,separator)
    if(separator<0||!Object.hasOwn(result,name))throw new Error('快照返回未知分组')
    const value=JSON.parse(line.slice(separator+1))
    if(!Array.isArray(value))throw new Error('快照行不是数组')
    result[name].push(value.map(item=>item==null?'':String(item)))
  }
  return result
}
const sha = (value) => createHash('sha256').update(value).digest('hex')
let request

export async function lockUserCoupon({userId,userCouponId}) {
  const rowId=numeric(userCouponId),user=text(userId)
  return lockRow({query:`SELECT id FROM envoymart_promotion.user_coupon WHERE id=${rowId} AND user_id=${user} FOR UPDATE`,
    database:'envoymart_promotion',table:'user_coupon',rowId:userCouponId})
}
export async function lockSku({skuId}) {
  const rowId=numeric(skuId)
  return lockRow({query:`SELECT id FROM envoymart_product.product_sku WHERE id=${rowId} FOR UPDATE`,
    database:'envoymart_product',table:'product_sku',rowId:skuId})
}
async function lockRow({query,database,table,rowId}) {
  const config=connection()
  const child=spawn(mysql,config.args,{stdio:['pipe','pipe','pipe'],windowsHide:true,env:config.env})
  const closed=new Promise(done=>child.once('close',done))
  let output='',error='',released=false
  const evidence={database,table,rowId,readOnlyLock:true,startedAt:new Date().toISOString()}
  child.stderr.on('data',(chunk)=>{error+=chunk})
  const ready=new Promise((done,reject)=>{
    const timer=setTimeout(()=>reject(new Error('独立券行锁未取得')),3000)
    child.stdout.on('data',(chunk)=>{output+=chunk;const match=output.match(/SEATA_LOCKED\t(\d+)/);if(match){clearTimeout(timer);evidence.connectionId=Number(match[1]);done()}})
    child.once('error',(e)=>{clearTimeout(timer);reject(e)})
    child.once('exit',()=>{clearTimeout(timer);reject(new Error('行锁连接提前退出：'+error))})
  })
  child.stdin.write(`START TRANSACTION; ${query}; SELECT 'SEATA_LOCKED',CONNECTION_ID();\n`)
  try{await ready}catch(error){
    // 查询仍在等锁时，排队ROLLBACK无法释放客户端；终止本任务连接并等资源关闭。
    child.kill()
    await closed
    throw error
  }
  return {evidence,release:async()=>{
    if(released)return;released=true
    if(child.exitCode!==null||child.signalCode!==null){await closed;throw new Error('本轮数据库锁连接已提前关闭')}
    await new Promise((done,reject)=>{
      let timedOut=false
      const timer=setTimeout(()=>{timedOut=true;child.kill()},3000)
      closed.then(()=>{clearTimeout(timer);if(timedOut)reject(new Error('本轮数据库锁连接未正常退出，已断开自有客户端'));else done()})
      child.stdin.end("ROLLBACK; SELECT 'SEATA_RELEASED';\n")
    })
    evidence.releasedAt=new Date().toISOString()
  }}
}
export async function observeCheckoutXid({requestId,maxWaitMs=5000}) {
  if(!/^[A-Za-z0-9_-]{1,100}$/.test(requestId))throw new Error('requestId不符合实验约束')
  const deadline=Date.now()+maxWaitMs
  while(Date.now()<deadline) {
    const log=await readFile(resolve(logDir,'order-service.log'),'utf8')
    const line=log.split(/\r?\n/).find((item)=>item.includes('['+requestId+']')&&item.includes('Begin new global transaction'))
    const xid=line?.match(/Begin new global transaction \[([^\]]+)\]/)?.[1]
    if(xid)return {xid,requestId,line,observedAt:new Date().toISOString()}
    await new Promise((done)=>setTimeout(done,50))
  }
  throw Object.assign(new Error('持锁期间没有同requestId的真实checkout XID'),{blocked:true})
}
function databaseSnapshot({fixture,xid,orderNos=[],coupon=false,firstOrder=false,projection=false,receiptMetadata=false,execute}) {
  const user=text(fixture.userId),op=text(fixture.operationId),sku=numeric(fixture.skuId),key=text(sha(fixture.userId+':'+fixture.operationId))
  const outboxScope=orderNos.length?'DIRECT_ORIGINAL_ORDER_NOS':'CURRENT_ORDER_JOIN'
  const outboxSource=orderNos.length?`FROM envoymart_order.event_outbox e WHERE e.aggregate_id IN (${orderNos.map(text).join(',')})`:`FROM envoymart_order.event_outbox e JOIN envoymart_order.shop_order o ON o.order_no=e.aggregate_id WHERE o.user_id=${user} AND o.request_id=${key}`
  const sections={
    stock:{columns:['stock'],source:`FROM envoymart_product.product_sku WHERE id=${sku}`},
    receipt:{columns:['count(*)','count(CASE WHEN completed_at IS NOT NULL AND response_json IS NOT NULL THEN 1 END)'],source:`FROM envoymart_order.order_operation_receipt WHERE user_id=${user} AND operation_id=${op}`},
    orderCount:{columns:['count(*)'],source:`FROM envoymart_order.shop_order WHERE user_id=${user} AND request_id=${key}`},
    orderRows:{columns:['id','order_no','status',"COALESCE(user_coupon_id,'')"],source:`FROM envoymart_order.shop_order WHERE user_id=${user} AND request_id=${key}`},
    outbox:{columns:['e.id','e.event_type','e.aggregate_id','e.attempts',"COALESCE(date_format(e.sent_at,'%Y-%m-%d %H:%i:%s'),'')",'SHA2(e.payload,256)'],source:outboxSource},
    cart:{columns:['sku_id','quantity','selected'],source:`FROM envoymart_order.cart_item WHERE user_id=${user}`}
  }
  if(receiptMetadata)sections.receiptMetadata={columns:['user_id','operation_id','tool_name','parameters_hash',"COALESCE(global_xid,'')",'hash_version','receipt_version','completed_at IS NOT NULL',"COALESCE(CAST(response_json AS CHAR),'')"],source:`FROM envoymart_order.order_operation_receipt WHERE user_id=${user} AND operation_id=${op}`}
  if(xid)for(const db of ['order','product'])sections[db+'Undo']={columns:['xid',"CAST(branch_id AS CHAR)"],source:`FROM envoymart_${db}.undo_log WHERE xid=${text(xid)}`}
  if(coupon){
    sections.coupon={columns:['status',"COALESCE(order_no,'')",'used_at IS NOT NULL'],source:`FROM envoymart_promotion.user_coupon WHERE id=${numeric(fixture.userCouponId)} AND user_id=${user}`}
    sections.promotionUndo={columns:['xid',"CAST(branch_id AS CHAR)"],source:`FROM envoymart_promotion.undo_log WHERE ${xid?'xid='+text(xid):'1=0'}`}
  }
  if(firstOrder)sections.firstOrder={columns:['id','order_no','status','user_coupon_id'],source:`FROM envoymart_order.shop_order WHERE id=${numeric(fixture.firstOrderId)} AND user_id=${user} AND order_no=${text(fixture.firstOrderNo)}`}
  if(projection){
    const target=`(SELECT spu_id FROM envoymart_product.product_sku WHERE id=${sku})`
    sections.product={columns:['spu_id','(SELECT SUM(stock) FROM envoymart_product.product_sku all_sku WHERE all_sku.spu_id=s.spu_id)'],source:`FROM envoymart_product.product_sku s WHERE id=${sku}`}
    sections.sourceTasks={columns:['CAST(id AS CHAR)','global_xid',"COALESCE(date_format(completed_at,'%Y-%m-%d %H:%i:%s'),'')",'attempts',"COALESCE(last_error_type,'')","COALESCE(date_format(next_attempt_at,'%Y-%m-%d %H:%i:%s'),'')","COALESCE(CAST(applied_task_id AS CHAR),'')","COALESCE(publish_mode,'')"],source:`FROM envoymart_product.product_projection_task WHERE spu_id=${target} AND ${xid?'global_xid='+text(xid):'1=0'}`}
    sections.projectionGuard={columns:["COALESCE(CAST(es_version_base AS CHAR),'')",'CAST(last_applied_task_id AS CHAR)'],source:`FROM envoymart_product.product_projection_spu_guard WHERE spu_id=${target}`}
    sections.projectionMaximum={columns:['CAST(COALESCE(MAX(id),0) AS CHAR)'],source:`FROM envoymart_product.product_projection_task WHERE spu_id=${target}`}
    sections.projectionPending={columns:['COUNT(*)'],source:`FROM envoymart_product.product_projection_task WHERE spu_id=${target} AND completed_at IS NULL`}
    sections.globalPending={columns:['COUNT(*)'],source:'FROM envoymart_product.product_projection_task WHERE completed_at IS NULL'}
    sections.restoreRows={columns:['CAST(id AS CHAR)','quantity','before_stock','after_stock','biz_id'],source:`FROM envoymart_product.stock_log WHERE sku_id=${sku} AND biz_type='ORDER' AND change_type='RESTORE' AND ${orderNos.length?'biz_id IN ('+orderNos.map(text).join(',')+')':'1=0'}`}
  }
  const snapshot=readConsistentSections(sections,execute)
  const [stock]=snapshot.stock.at(0)??[],[receiptCount,completeCount]=snapshot.receipt.at(0)??[],[orderCount]=snapshot.orderCount.at(0)??[]
  const orderRows=snapshot.orderRows.map(([id,orderNo,status,userCouponId])=>({id:Number(id),orderNo,status,userCouponId:userCouponId?Number(userCouponId):null})).sort((a,b)=>a.id-b.id)
  const outbox=snapshot.outbox
    .map(([id,eventType,orderNo,attempts,sentAt,payloadDigest])=>({id:Number(id),eventType,orderNo,attempts:Number(attempts),sentAt:sentAt||null,payloadDigest}))
    .sort((a,b)=>a.id-b.id)
  const cart=snapshot.cart.sort((a,b)=>Number(a[0])-Number(b[0]))
  const undo={}
  if(xid)for(const db of ['order','product'])undo[db]=snapshot[db+'Undo'].map(([value,branchId])=>({xid:value,branchId}))
  const result={engine:'MySQL',readConsistency:'SINGLE_SELECT',stock:Number(stock),receiptCount:Number(receiptCount),completeCount:Number(completeCount),orderCount:Number(orderCount),orderRows,outbox,outboxScope,cart,undo}
  if(receiptMetadata)result.receiptMetadata=snapshot.receiptMetadata.map(([userId,operationId,toolName,parametersHash,globalXid,hashVersion,receiptVersion,completed,responseJson])=>({userId,operationId,toolName,parametersHash,globalXid:globalXid||null,hashVersion:Number(hashVersion),receiptVersion:Number(receiptVersion),completed:completed==='1',response:responseJson?JSON.parse(responseJson):null}))
  if(coupon){
    const value=snapshot.coupon.at(0)
    if(!value)throw new Error('指定独立用户券不存在')
    result.coupon={status:value[0],orderNo:value[1]||null,usedAtPresent:value[2]==='1'}
    result.promotionUndo=snapshot.promotionUndo.map(([value,branchId])=>({xid:value,branchId}))
  }
  if(firstOrder){const value=snapshot.firstOrder.at(0);result.firstOrder=value?{id:Number(value[0]),orderNo:value[1],status:value[2],userCouponId:Number(value[3])}:null}
  if(projection){
    result.product={skuId:Number(fixture.skuId),spuId:Number(snapshot.product[0][0]),totalStock:Number(snapshot.product[0][1])}
    const guard=snapshot.projectionGuard.at(0)
    result.projection={readConsistency:'SINGLE_SELECT',maximumTaskId:snapshot.projectionMaximum[0][0],pendingTaskCount:Number(snapshot.projectionPending[0][0]),globalPendingTaskCount:Number(snapshot.globalPending[0][0]),
      guard:guard?{versionBase:guard[0]||null,lastAppliedTaskId:guard[1]}:null,
      sourceTasks:snapshot.sourceTasks.map(([id,globalXid,completedAt,attempts,lastErrorType,nextAttemptAt,appliedTaskId,mode])=>({id,xid:globalXid,completedAt:completedAt||null,attempts:Number(attempts),lastErrorType:lastErrorType||null,nextAttemptAt:nextAttemptAt||null,appliedTaskId:appliedTaskId||null,mode:mode||null}))}
    result.restoreRows=snapshot.restoreRows.map(([id,quantity,beforeStock,afterStock,orderNo])=>({id,quantity:Number(quantity),beforeStock:Number(beforeStock),afterStock:Number(afterStock),orderNo}))
  }
  return result
}
export async function phase1Snapshot(options) { return databaseSnapshot(options) }
export async function projectionRecoverySnapshot(options) { return databaseSnapshot({...options,coupon:true,projection:true}) }
/** 只读取固定本地ES与该SKU所属SPU的全量SKU汇总，不触发索引同步。 */
export async function stockIndexSnapshot({fixture}) {
  const sku=numeric(fixture.skuId),target=`(SELECT spu_id FROM envoymart_product.product_sku WHERE id=${sku})`
  const projectionTables=Number(sql("SELECT count(*) FROM information_schema.tables WHERE table_schema='envoymart_product' AND table_name IN ('product_projection_task','product_projection_spu_guard')"))
  const sections={stock:{columns:['s.spu_id','s.stock','(SELECT SUM(all_sku.stock) FROM envoymart_product.product_sku all_sku WHERE all_sku.spu_id=s.spu_id)'],source:`FROM envoymart_product.product_sku s WHERE s.id=${sku}`}}
  if(projectionTables===2){
    sections.tasks={columns:['CAST(id AS CHAR)',"COALESCE(global_xid,'')","COALESCE(date_format(completed_at,'%Y-%m-%d %H:%i:%s'),'')",'attempts',"COALESCE(CAST(applied_task_id AS CHAR),'')","COALESCE(publish_mode,'')"],source:`FROM (SELECT * FROM envoymart_product.product_projection_task WHERE spu_id=${target} ORDER BY id DESC LIMIT 64) recent_tasks`}
    sections.guard={columns:["COALESCE(CAST(es_version_base AS CHAR),'')",'CAST(last_applied_task_id AS CHAR)'],source:`FROM envoymart_product.product_projection_spu_guard WHERE spu_id=${target}`}
    sections.pending={columns:['COUNT(*)'],source:`FROM envoymart_product.product_projection_task WHERE spu_id=${target} AND completed_at IS NULL`}
    sections.maximum={columns:['CAST(COALESCE(MAX(id),0) AS CHAR)'],source:`FROM envoymart_product.product_projection_task WHERE spu_id=${target}`}
  }
  const snapshot=readConsistentSections(sections)
  const [spuId,skuStock,totalStock]=snapshot.stock.at(0)??[]
  if(!spuId)throw Object.assign(new Error('ES取证SKU不存在'),{blocked:true})
  const response=await fetch('http://127.0.0.1:9200/envoymart_spu_v3/_doc/'+numeric(spuId),{signal:AbortSignal.timeout(4000)})
  if(!response.ok)throw Object.assign(new Error('ES只读库存取证失败HTTP'+response.status),{blocked:true})
  const documentText=await response.text(),document=JSON.parse(documentText)
  if(!document.found||!Number.isInteger(document._source?.totalStock))throw Object.assign(new Error('ES SPU文档缺totalStock'),{blocked:true})
  let projection=null
  if(projectionTables===2){
    const tasks=snapshot.tasks
      .map(([id,xid,completedAt,attempts,appliedTaskId,mode])=>({id,xid:xid||null,completedAt:completedAt||null,attempts:Number(attempts),appliedTaskId:appliedTaskId||null,mode:mode||null}))
      .sort((a,b)=>BigInt(a.id)>BigInt(b.id)?-1:BigInt(a.id)<BigInt(b.id)?1:0)
    const guardRows=snapshot.guard
    projection={readOnly:true,readConsistency:'SINGLE_SELECT',maximumTaskId:snapshot.maximum[0][0],pendingTaskCount:Number(snapshot.pending[0][0]),tasks,
      guard:guardRows.length?{versionBase:guardRows[0][0]||null,lastAppliedTaskId:guardRows[0][1]}:null}
  }
  return {at:new Date().toISOString(),readConsistency:'SINGLE_SELECT',skuId:Number(fixture.skuId),spuId:Number(spuId),skuStock:Number(skuStock),databaseTotalStock:Number(totalStock),indexTotalStock:document._source.totalStock,index:'envoymart_spu_v3',indexVersion:documentText.match(/"_version"\s*:\s*(\d+)/)?.[1]??null,projection,sequenceNumber:document._seq_no,primaryTerm:document._primary_term,readOnly:true}
}
export async function phase1LogEvidence({xid,requestIds=[],includeTC=false,orderNos=[],outboxIds=[]}) {
  const result={services:{},requests:{}}
  for(const service of ['order','product','ai']) {
    const log=await readFile(resolve(logDir,service+'-service.log'),'utf8'),lines=log.split(/\r?\n/)
    result.services[service]=selectPhase1ServiceEvidence(lines,service,xid)
    if(service==='order'){
      result.orderConsumerEvents=lines.filter((line)=>line.includes('[MQ.Consumer]')&&orderNos.some((orderNo)=>line.includes(orderNo)))
      result.outboxBarrierAttempts=lines.filter((line)=>line.includes('[Outbox]')&&line.includes('等待事务屏障')&&outboxIds.some((id)=>line.includes('id='+id+' ')))
    }
    for(const id of requestIds)if(service==='ai') {
      const matched=lines.filter((line)=>line.includes('['+id+']'))
      result.requests[id]={modes:matched.filter((line)=>line.includes('[Approval]')).map((line)=>line.match(/mode=([^\s]+)/)?.[1]).filter(Boolean),
        modelCalls:matched.filter((line)=>/\[LLM\]|\[QueryExpand\]|\[Rerank\]|本轮用量/.test(line)).length}
    }
  }
  if(includeTC)result.tc=collectTcEvidence(xid)
  return result
}
/** 保留同XID的实际投影屏障日志，不能由旧AT分支正则把它们丢掉。 */
export function selectPhase1ServiceEvidence(lines,service,xid) {
  if(!xid)return []
  return lines.filter(line=>line.includes(xid)&&(/branch register success|rollback status|commit status|branch rollback success|BranchRollbackRequest/.test(line)
    ||service==='product'&&line.includes('[商品投影]')))
}
/** 清理自有实验单后只读核对原订单的唯一回补，不改变任何账本。 */
export function stockRestoreLedger({fixture,orderNo}) {
  return rows(`SELECT id,quantity,before_stock,after_stock FROM envoymart_product.stock_log WHERE sku_id=${numeric(fixture.skuId)} AND biz_type='ORDER' AND biz_id=${text(orderNo)} AND change_type='RESTORE' ORDER BY id`)
    .map(([id,quantity,beforeStock,afterStock])=>({id,quantity:Number(quantity),beforeStock:Number(beforeStock),afterStock:Number(afterStock)}))
}
function collectTcEvidence(xid) {
  text(xid)
  const captured=spawnSync('docker',['logs','--since','15m','--tail','2000','envoymart-seata'],{encoding:'utf8',windowsHide:true,timeout:4000,maxBuffer:4*1024*1024})
  if(captured.status!==0)return {source:'LOCAL_TC_CONTAINER_LOG',observed:false,reason:'限定时间和行数的TC只读取证不可用'}
  const lines=(captured.stdout+'\n'+captured.stderr).split(/\r?\n/).filter((line)=>line.includes(xid))
  const rolledBack=lines.some((line)=>/Rollback global transaction successfully, xid|rollback(?:ing)? global transaction.*successfully done|globalStatus=TimeoutRollbacked/i.test(line))
  const committed=lines.some((line)=>/Committing global transaction is successfully done/i.test(line))
  return {source:'LOCAL_TC_CONTAINER_LOG',observed:lines.length>0,scope:{container:'envoymart-seata',since:'15m',tail:2000},xid,rolledBack,committed,lines}
}
export async function lateCouponSnapshot({fixture,xid,orderNos=[]}) {
  return databaseSnapshot({fixture,xid,orderNos,coupon:true})
}
export async function firstCouponSnapshot({fixture,xid}) {
  return databaseSnapshot({fixture,xid,coupon:true,firstOrder:true})
}
export async function lateCouponLogEvidence({xid,orderNos=[],outboxIds=[]}) {
  const result=await phase1LogEvidence({xid,includeTC:true,orderNos,outboxIds})
  const promotion=await readFile(resolve(logDir,'promotion-service.log'),'utf8')
  result.services.promotion=promotion.split(/\r?\n/).filter((line)=>line.includes(xid)&&/register|rollback|Branch|GlobalTransaction/.test(line))
  return result
}
export async function observeWaiting({userCouponId,requestId,maxWaitMs}) {
  request=requestId
  const deadline=Date.now()+maxWaitMs;let observed=[]
  while(Date.now()<deadline){
    observed=rows("SELECT r.OBJECT_NAME,r.LOCK_DATA,w.BLOCKING_ENGINE_TRANSACTION_ID,w.REQUESTING_ENGINE_TRANSACTION_ID FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE r.OBJECT_SCHEMA='envoymart_promotion' AND r.OBJECT_NAME='user_coupon'")
    const match=observed.find((item)=>String(item[1]).split(',')[0].trim()===String(userCouponId))
    if(match)return {waiting:true,table:'user_coupon',rowId:userCouponId,blockingTransactionId:match[2],requestingTransactionId:match[3],observedAt:new Date().toISOString()}
    await new Promise((done)=>setTimeout(done,100))
  }
  return {waiting:false,table:'user_coupon',rowId:userCouponId,observed,observedAt:new Date().toISOString()}
}
export async function snapshotDatabase({fixture}) {
  const user=text(fixture.userId),op=text(fixture.operationId),key=text(sha(fixture.userId+':'+fixture.operationId)),sku=numeric(fixture.skuId)
  const [stock]=rows(`SELECT stock FROM envoymart_product.product_sku WHERE id=${sku}`).at(0)??[]
  const [receiptCount]=rows(`SELECT count(*) FROM envoymart_order.order_operation_receipt WHERE user_id=${user} AND operation_id=${op}`).at(0)
  const [operationOrderCount]=rows(`SELECT count(*) FROM envoymart_order.shop_order WHERE user_id=${user} AND request_id=${key}`).at(0)
  const productLog=await readFile(resolve(logDir,'product-service.log'),'utf8')
  const seataEnabled=/register RM success[\s\S]*branch register success/.test(productLog)
  const xid=request?productLog.split(/\r?\n/).filter((line)=>line.includes('['+request+']')&&line.includes('branch register success'))
    .map((line)=>line.match(/xid:([^,\s]+)/)?.[1]).find(Boolean):null
  const undo=xid?rows(`SELECT xid,branch_id FROM envoymart_product.undo_log WHERE xid=${text(xid)}`):[]
  return {engine:'MySQL',seataEnabled,stock:Number(stock),receiptCount:Number(receiptCount),operationOrderCount:Number(operationOrderCount),undoLog:undo.map(([xid,branchId])=>({xid,branchId}))}
}
export async function queryReceipt({fixture}) {
  const matches=rows(`SELECT receipt_version,parameters_hash,completed_at IS NOT NULL,response_json IS NOT NULL FROM envoymart_order.order_operation_receipt WHERE user_id=${text(fixture.userId)} AND operation_id=${text(fixture.operationId)}`)
  return {source:'MYSQL_STORED_ROW_ONLY',globalLockHttpVerified:false,code:matches.length?200:404,rows:matches}
}
export async function collectSeataEvidence({requestId}) {
  const productLog=await readFile(resolve(logDir,'product-service.log'),'utf8')
  const entries=productLog.split(/\r?\n/).filter((line)=>line.includes('['+requestId+']')&&line.includes('branch register success'))
  const xid=entries.map((line)=>line.match(/xid:([^,\s]+)/)?.[1]).find(Boolean)
  const rollback=xid?productLog.split(/\r?\n/).filter((line)=>line.includes(xid)&&/BranchRollback|branch rollback|rollback successfully|rollback succeeded/i.test(line)):[]
  let orderLog='';try{orderLog=await readFile(resolve(logDir,'order-service.log'),'utf8')}catch{}
  const global=xid?orderLog.split(/\r?\n/).filter((line)=>line.includes(xid)&&/global.*rollback|rollback.*global/i.test(line)):[]
  return {xid:xid??null,atBranchRegistered:entries.length>0,globalRolledBack:global.length>0,branchRolledBack:rollback.length>0,entries,rollback,global,limits:global.length?[]:['order当前日志缺同XID全局回滚，不能仅凭product分支回滚判globalPASS']}
}
