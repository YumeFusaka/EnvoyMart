import {test} from 'node:test'
import assert from 'node:assert/strict'
import {resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {createHash} from 'node:crypto'
import {EventEmitter} from 'node:events'
import {projectionRecoverySnapshot} from './write-receipt-seata-local.mjs'
import * as recovery from './product-projection-recovery.mjs'
import {isTargetEsWrite} from './product-projection-fault-proxy.mjs'
import {assessProjectionRecovery,validProductControl,publicationComplete,configuredReadOnlySql,assessProjectionCleanup,compareProjectionEvidence} from './product-projection-recovery.mjs'

const control=(phase,pid,previousPid)=>({version:'product-projection-control-v1',runId:'r1',phase,pid,previousPid,ready:true,configurationVerified:true,
  startedAt:'2026-10-12T00:00:00Z',observedAt:'2026-10-12T00:00:01Z',elasticsearchUris:phase==='BASELINE_RESTORED'?'http://127.0.0.1:9200':'http://127.0.0.1:19200',redisPort:phase==='BASELINE_RESTORED'?6379:16379})
function evidence() {
  const before={readConsistency:'SINGLE_SELECT',stock:495,product:{skuId:29,spuId:20,totalStock:495},coupon:{status:'UNUSED',orderNo:null,usedAtPresent:false},projection:{maximumTaskId:'100',globalPendingTaskCount:0}}
  const task={id:'107',xid:'x1',completedAt:null,attempts:1,lastErrorType:'ES:ResponseException',nextAttemptAt:'2026-10-12 08:00:00',appliedTaskId:null,mode:null}
  const database={readConsistency:'SINGLE_SELECT',stock:494,product:{skuId:29,spuId:20,totalStock:494},receiptCount:1,completeCount:1,orderCount:1,
    orderRows:[{id:1,orderNo:'o1',status:'CREATED'}],cart:[],coupon:{status:'USED',orderNo:'o1',usedAtPresent:false},undo:{order:[],product:[]},promotionUndo:[],
    outbox:[{id:1,eventType:'ORDER_CREATED',orderNo:'o1',payloadDigest:'d1',attempts:1,sentAt:'2026-10-12 08:00:00'}],outboxScope:'DIRECT_ORIGINAL_ORDER_NOS',
    projection:{pendingTaskCount:1,guard:{versionBase:'136',lastAppliedTaskId:'100'},sourceTasks:[task]}}
  const log=(pid,stage)=>({lines:[`2026-10-12T08:00:00+08:00 INFO ${pid} --- [product-service] [] [商品投影] 阶段=${stage} taskId=107 spuId=20 xid=x1 mode=CACHE_AND_ES`]})
  const recovered=structuredClone(database)
  Object.assign(recovered.projection,{pendingTaskCount:0,guard:{versionBase:'136',lastAppliedTaskId:'200'}})
  Object.assign(recovered.projection.sourceTasks[0],{completedAt:'2026-10-12 08:00:03',lastErrorType:null,nextAttemptAt:null,appliedTaskId:'200',mode:'CACHE_AND_ES'})
  return {version:'product-projection-recovery-v1',runId:'r1',fault:'es',xid:'x1',taskId:'107',orderNo:'o1',operationId:'op1',parametersHash:'a'.repeat(64),status:'PASS',activation:control('PROXY_READY',1),restart:control('RESTARTED_FAULT_HELD',2,1),restoration:control('BASELINE_RESTORED',3,2),
    before:{database:before,index:{version:'236',totalStock:495}},failed:{database,index:{version:'236',totalStock:495},logs:log(1,'外部失败保留待办')},
    restarted:{database:structuredClone(database),index:{version:'236',totalStock:495},logs:log(2,'外部失败保留待办')},
    recovered:{database:recovered,index:{version:'336',totalStock:494},redisVersion:'200',logs:log(2,'已完成')},events:[{kind:'ES_WRITE_REJECTED'}],
    transactionLogs:{tc:{xid:'x1',committed:true},services:Object.fromEntries(['order','product','promotion'].map(service=>[service,['branch register success xid:x1']]))}}
}
test('故障仅拒绝目标SPU的ES写，搜索/读/其他SPU/其他索引透明',()=>{
  assert.equal(isTargetEsWrite('PUT','/envoymart_spu_v3/_doc/20?version=100',Buffer.alloc(0),20),true)
  for(const [method,path] of [['GET','/envoymart_spu_v3/_doc/20'],['POST','/envoymart_spu_v3/_search'],['PUT','/envoymart_spu_v3/_doc/21'],['PUT','/other/_doc/20']])assert.equal(isTargetEsWrite(method,path,Buffer.alloc(0),20),false)
  assert.equal(isTargetEsWrite('POST','/_bulk',Buffer.from('{"index":{"_index":"envoymart_spu_v3","_id":"20"}}\n{}\n'),20),true)
  assert.equal(isTargetEsWrite('POST','/_bulk',Buffer.from('{"index":{"_index":"other","_id":"20"}}\n{}\n'),20),false)
})
test('原任务两个PID真实失败后自动完成与永久版本对齐才通过',()=>assert.equal(assessProjectionRecovery(evidence()).status,'PASS'))
test('缺重启/错误配置/相同PID不冒充跨进程恢复',()=>{
  const e=evidence();e.restart.pid=1;assert.equal(assessProjectionRecovery(e).status,'BLOCKED')
  const c=control('PROXY_READY',1);c.redisPort=6379;assert.equal(validProductControl(c,{runId:'r1',phase:'PROXY_READY'}),false)
})
test('故障必须实际发生并写持久错误，不接受只有最终UI或新任务替代',()=>{
  const e=evidence();e.failed.database.projection.sourceTasks[0].lastErrorType=null;assert.equal(assessProjectionRecovery(e).status,'FAIL')
  const f=evidence();f.restarted.database.projection.sourceTasks[0].id='108';assert.equal(assessProjectionRecovery(f).status,'FAIL')
  const g=evidence();g.events=[];assert.equal(assessProjectionRecovery(g).status,'BLOCKED')
})
test('缺新PID原任务完成日志、故障自动过期、回滚库存被改都不能通过',()=>{
  const e=evidence();e.recovered.logs.lines=[];assert.equal(assessProjectionRecovery(e).status,'BLOCKED')
  const f=evidence();f.events.push({kind:'FAULT_CLEARED',reason:'MAXIMUM_FAULT_WINDOW_EXPIRED'});assert.equal(assessProjectionRecovery(f).status,'BLOCKED')
  const g=evidence();g.recovered.database.stock=495;assert.equal(assessProjectionRecovery(g).status,'FAIL')
})
test('Redis故障须CACHE错误分类且真实拒自有连接',()=>{
  const e=evidence();e.fault='redis';e.events=[{kind:'REDIS_CONNECTION_REJECTED'}]
  e.failed.database.projection.sourceTasks[0].lastErrorType='CACHE:RedisConnectionFailureException'
  e.restarted.database.projection.sourceTasks[0].lastErrorType='CACHE:RedisConnectionFailureException'
  assert.equal(assessProjectionRecovery(e).status,'PASS')
})
test('超过2的53次方版本保持字符串精确相加，Redis旧版本拒绿',()=>{
  const e=evidence(),db=e.recovered.database
  db.projection.guard={versionBase:'9007199254740993',lastAppliedTaskId:'200'}
  assert.equal(publicationComplete(db,{totalStock:494,version:'9007199254741193'},'200',e),true)
  assert.equal(publicationComplete(db,{totalStock:494,version:'9007199254741192'},'200',e),false)
  assert.equal(publicationComplete(db,{totalStock:494,version:'9007199254741193'},'199',e),false)
})
test('新高级SQL执行器缺显式用户名时阻断，拒多语句和业务写',()=>{
  assert.throws(()=>configuredReadOnlySql('DELETE FROM product_spu'),/单条SELECT/)
  assert.throws(()=>configuredReadOnlySql('SELECT 1;SELECT 2'),/单条SELECT/)
  const previous=process.env.VERIFY_MYSQL_USER
  delete process.env.VERIFY_MYSQL_USER
  try{assert.throws(()=>configuredReadOnlySql('SELECT 1'),/VERIFY_MYSQL_USER/)}finally{if(previous!==undefined)process.env.VERIFY_MYSQL_USER=previous}
})
test('采样超时缺恢复阶段快照及缺TC终态只阻断，不能靠最终状态标签变绿',()=>{
  const e=evidence();delete e.recovered;assert.equal(assessProjectionRecovery(e).status,'BLOCKED')
  const f=evidence();f.transactionLogs.tc.committed=false;assert.equal(assessProjectionRecovery(f).status,'BLOCKED')
})
function cleanup(capture,capturePath,captureDigest) {
  const database=structuredClone(capture.recovered.database)
  database.stock=495;database.product.totalStock=495;database.orderRows[0].status='CANCELLED';database.coupon={...capture.before.database.coupon};database.projection.globalPendingTaskCount=0
  database.restoreRows=[{id:'8',quantity:1,beforeStock:494,afterStock:495,orderNo:capture.orderNo}]
  return {version:'product-projection-cleanup-v1',runId:capture.runId,fault:capture.fault,capturePath,captureDigest,status:'PASS',after:{database,index:{version:'336',totalStock:495},redisVersion:'200'}}
}
test('清理报告须严格绑定原SHA且唯一库存回补/原券字段恢复齐全',()=>{
  const e=evidence(),c=cleanup(e,'p1','d1')
  assert.equal(assessProjectionCleanup(e,c,{capturePath:'p1',captureDigest:'d1'}).status,'PASS')
  assert.equal(assessProjectionCleanup(e,c,{capturePath:'p1',captureDigest:'changed'}).status,'BLOCKED')
  c.after.database.coupon.usedAtPresent=true
  assert.equal(assessProjectionCleanup(e,c,{capturePath:'p1',captureDigest:'d1'}).status,'FAIL')
})
test('两模式门禁必须两份独立原capture/cleanup及其精确SHA，缺一或误绑阻断',async()=>{
  const root=fileURLToPath(new URL('../../.evidence/',import.meta.url)),indexPath=resolve(root,'product-projection-recovery-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa-index.json'),files=new Map()
  const hash=raw=>createHash('sha256').update(raw).digest('hex'),index={version:'product-projection-recovery-index-v1',cases:{}}
  for(const [mode,runId] of [['es','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'],['redis','bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb']]){
    let e=evidence()
    if(mode==='redis'){e=JSON.parse(JSON.stringify(e).replaceAll('x1','x2').replaceAll('o1','o2').replaceAll('op1','op2').replaceAll('107','108'));e.fault=mode;e.events=[{kind:'REDIS_CONNECTION_REJECTED'}];e.failed.database.projection.sourceTasks[0].lastErrorType='CACHE:RedisConnectionFailureException';e.restarted.database.projection.sourceTasks[0].lastErrorType='CACHE:RedisConnectionFailureException'}
    e.runId=runId;for(const c of [e.activation,e.restart,e.restoration])c.runId=runId
    const capturePath=resolve(root,`product-projection-recovery-${runId}-${mode}.json`),raw=Buffer.from(JSON.stringify(e)),captureDigest=hash(raw)
    const c=cleanup(e,capturePath,captureDigest),cleanupPath=resolve(root,`product-projection-cleanup-${runId}-${mode}.json`),cleanupRaw=Buffer.from(JSON.stringify(c))
    files.set(capturePath,raw);files.set(cleanupPath,cleanupRaw);index.cases[mode]={capture:capturePath,captureDigest,cleanup:cleanupPath,cleanupDigest:hash(cleanupRaw)}
  }
  const read=async path=>{if(path===indexPath)return Buffer.from(JSON.stringify(index));if(!files.has(path))throw new Error('不存在');return files.get(path)}
  assert.equal((await compareProjectionEvidence(indexPath,{read})).status,'PASS')
  assert.equal((await compareProjectionEvidence(index.cases.es.capture,{read})).status,'PASS')
  assert.equal((await compareProjectionEvidence(index.cases.es.capture,{read,requireBoth:true})).status,'BLOCKED')
  index.cases.redis.captureDigest='wrong'
  assert.equal((await compareProjectionEvidence(indexPath,{read})).status,'BLOCKED')
  delete index.cases.redis
  assert.equal((await compareProjectionEvidence(indexPath,{read})).status,'BLOCKED')
})
test('三库权威、完整回执/Outbox/券时间及持久错误元数据来自同条SELECT，bigint不截断',async()=>{
  let calls=0
  const data={stock:[[494]],receipt:[[1,1]],orderCount:[[1]],orderRows:[[1,'o1','CREATED',9]],outbox:[[1,'ORDER_CREATED','o1',1,'2026-10-12 08:00:00','d1']],cart:[],
    orderUndo:[],productUndo:[],coupon:[['USED','o1',1]],promotionUndo:[],product:[[20,494]],
    sourceTasks:[['9007199254740993','127.0.0.1:8091:12','',1,'CACHE:RedisConnectionFailureException','2026-10-12 08:00:02','','']],
    projectionGuard:[['9007199254740994','9007199254740992']],projectionMaximum:[['9007199254740993']],projectionPending:[[1]],globalPending:[[1]],restoreRows:[]}
  const snapshot=await projectionRecoverySnapshot({fixture:{userId:'u1',operationId:'op:1',skuId:29,userCouponId:9},xid:'127.0.0.1:8091:12',orderNos:['o1'],execute:query=>{
    calls++;assert.doesNotMatch(query,/;/);assert.match(query,/CAST\(id AS CHAR\)/)
    for(const db of ['envoymart_order','envoymart_product','envoymart_promotion'])assert.ok(query.includes(db))
    const names=[...query.matchAll(/SELECT '([A-Za-z][A-Za-z0-9]*)' AS snapshot_section/g)].map(match=>match[1])
    assert.deepEqual(new Set(names),new Set(Object.keys(data)))
    return names.flatMap(name=>data[name].map(row=>name+'\t'+JSON.stringify(row))).join('\n')
  }})
  assert.equal(calls,1);assert.equal(snapshot.completeCount,1);assert.equal(snapshot.outboxScope,'DIRECT_ORIGINAL_ORDER_NOS');assert.equal(snapshot.coupon.usedAtPresent,true)
  assert.equal(snapshot.projection.sourceTasks[0].id,'9007199254740993');assert.equal(snapshot.projection.sourceTasks[0].lastErrorType,'CACHE:RedisConnectionFailureException')
  assert.equal(snapshot.projection.guard.versionBase,'9007199254740994');assert.equal(snapshot.readConsistency,'SINGLE_SELECT')
})
test('未发checkout且缺before时清理可独立证明零业务，但不能证明故障恢复通过',()=>{
  const e=evidence(),c=cleanup(e,'p1','d1')
  e.status='BLOCKED';e.checkoutAttempted=false;e.calls=[];e.events=[];delete e.before;delete e.xid;delete e.taskId;delete e.orderNo
  Object.assign(c.after.database,{receiptCount:0,completeCount:0,orderCount:0,orderRows:[],outbox:[],restoreRows:[],coupon:{status:'UNUSED',orderNo:null,usedAtPresent:false}})
  c.after.database.projection.sourceTasks=[]
  c.cleanupMode='NO_CHECKOUT'
  assert.equal(assessProjectionCleanup(e,c,{capturePath:'p1',captureDigest:'d1'}).status,'PASS')
  assert.equal(assessProjectionRecovery(e).status,'BLOCKED')
  e.calls=[{path:'/orders/checkout',method:'POST'}]
  assert.equal(assessProjectionCleanup(e,c,{capturePath:'p1',captureDigest:'d1'}).status,'FAIL')
  e.calls=[];c.after.database.receiptCount=1
  assert.equal(assessProjectionCleanup(e,c,{capturePath:'p1',captureDigest:'d1'}).status,'FAIL')
})
test('bd99错误响应后的订单身份只能在冻结前由原操作单SELECT及TC证据绑定，清理不修改原输入',()=>{
  const e=evidence();e.status='FAIL';e.error='checkout503';e.checkoutAttempted=true;delete e.orderNo;delete e.orderId
  const database=structuredClone(e.failed.database)
  const bound=recovery.bindCommittedCheckoutIdentity(e,{database,logs:e.transactionLogs})
  assert.equal(bound.orderNo,'o1');assert.equal(bound.orderId,1);assert.equal(e.orderNo,undefined)
  const frozen=JSON.parse(JSON.stringify(bound)),c=cleanup(frozen,'p1','d1')
  assert.equal(assessProjectionCleanup(frozen,c,{capturePath:'p1',captureDigest:'d1'}).status,'PASS')
  assert.equal(frozen.status,'FAIL')
  const unproved=recovery.bindCommittedCheckoutIdentity(e,{database,logs:{tc:{xid:'x1',committed:false}}})
  assert.equal(unproved.orderNo,undefined)
  assert.notEqual(assessProjectionCleanup(unproved,c,{capturePath:'p1',captureDigest:'d1'}).status,'PASS')
  const wrong=recovery.bindCommittedCheckoutIdentity(e,{database,logs:{...e.transactionLogs,tc:{xid:'other',committed:true}}})
  assert.equal(wrong.orderNo,undefined)
})
test('503只读确认严格绑定原user/op/tool/hash、回执版本、响应订单与来源任务同XID',()=>{
  const e=evidence(),fixture={userId:'u1',operationId:e.operationId,parametersHash:e.parametersHash}
  const database=structuredClone(e.failed.database)
  database.receiptMetadata=[{userId:'u1',operationId:'op1',toolName:'cart_checkout',parametersHash:e.parametersHash,globalXid:'x1',hashVersion:1,receiptVersion:1,completed:true,response:{code:200,data:{id:1,orderNo:'o1'}}}]
  const input={fixture,xid:'x1',database,logs:e.transactionLogs}
  const proof=recovery.confirmLocalCheckoutReceipt(input)
  assert.equal(proof.mode,'LOCAL_READ_ONLY_RECEIPT_AUTHORITY');assert.equal(proof.data.orderNo,'o1');assert.match(proof.responseDigest,/^[a-f0-9]{64}$/)
  for(const [field,value] of [['userId','other'],['operationId','other'],['toolName','order_cancel'],['parametersHash','b'.repeat(64)],['globalXid','other'],['hashVersion',2],['receiptVersion',2]]){
    const wrong=structuredClone(database);wrong.receiptMetadata[0][field]=value
    assert.throws(()=>recovery.confirmLocalCheckoutReceipt({...input,database:wrong}),error=>error.blocked===true)
  }
  const wrongOrder=structuredClone(database);wrongOrder.receiptMetadata[0].response.data.id=2
  assert.throws(()=>recovery.confirmLocalCheckoutReceipt({...input,database:wrongOrder}),error=>error.blocked===true)
  const wrongTask=structuredClone(database);wrongTask.projection.sourceTasks[0].xid='other'
  assert.throws(()=>recovery.confirmLocalCheckoutReceipt({...input,database:wrongTask}),error=>error.blocked===true)
  assert.equal(recovery.confirmLocalCheckoutReceipt({...input,logs:{...input.logs,tc:{xid:'x1',committed:false}}}),null)
  assert.equal(recovery.confirmLocalCheckoutReceipt({...input,database:{...database,receiptCount:0,receiptMetadata:[]}}),null)
  e.userId='u1';e.orderId=1;e.calls=[{method:'POST',path:'/orders/checkout'}];e.checkout=proof.data;e.checkoutRecovery=proof
  const body={code:503,msg:'结果待确认',data:null}
  e.checkoutFirstResponse={httpStatus:503,body,responseDigest:createHash('sha256').update(JSON.stringify(body)).digest('hex')}
  assert.equal(assessProjectionRecovery(e).status,'PASS')
  e.calls.push({method:'POST',path:'/orders/checkout'})
  assert.equal(assessProjectionRecovery(e).status,'BLOCKED')
  e.calls.pop();e.checkoutFirstResponse.responseDigest='wrong'
  assert.equal(assessProjectionRecovery(e).status,'BLOCKED')
})
function redisSocket() {
  const socket=new EventEmitter();socket.writes=[];socket.destroyed=false
  socket.write=value=>socket.writes.push(value);socket.destroy=()=>{socket.destroyed=true}
  let timeout,cleared=false
  const options={connect:address=>{assert.deepEqual(address,{host:'127.0.0.1',port:6379});return socket},schedule:(callback,ms)=>{assert.equal(ms,4000);timeout=callback;return 1},cancelTimer:()=>{cleared=true}}
  return {socket,options,timeout:()=>timeout(),cleared:()=>cleared}
}
test('固定Redis单GET支持RESP分片，超过2的53次方版本返回精确原字符串',async()=>{
  const fake=redisSocket(),promise=recovery.readProjectionRedisVersion(fake.options)
  fake.socket.emit('connect')
  assert.deepEqual(fake.socket.writes,['*2\r\n$3\r\nGET\r\n$29\r\nproduct:projection:version:20\r\n'])
  fake.socket.emit('data',Buffer.from('$16\r\n900719'))
  fake.socket.emit('data',Buffer.from('9254740993\r\n'))
  assert.equal(await promise,'9007199254740993');assert.equal(fake.socket.destroyed,true);assert.equal(fake.cleared(),true)
})
test('Redis不存在、错误类型、非数字、超长帧和截断都BLOCKED并释放连接',async()=>{
  for(const frame of ['$-1\r\n','-ERR forbidden\r\n',':362\r\n','$3\r\n3x2\r\n','$9999\r\n','$3\r\n362xx']){
    const fake=redisSocket(),promise=recovery.readProjectionRedisVersion(fake.options);fake.socket.emit('data',Buffer.from(frame))
    await assert.rejects(()=>promise,error=>error.blocked===true);assert.equal(fake.socket.destroyed,true);assert.equal(fake.cleared(),true)
  }
  const fake=redisSocket(),promise=recovery.readProjectionRedisVersion(fake.options)
  fake.socket.emit('data',Buffer.from('$3\r\n36'));fake.socket.emit('end')
  await assert.rejects(()=>promise,error=>error.blocked===true);assert.equal(fake.socket.destroyed,true)
})
test('Redis总deadline不因分片延长，取消、网络错误、已取消信号都只结束一次且释放',async()=>{
  for(const event of ['timeout','abort','error','close']){
    const fake=redisSocket(),cancel=new AbortController(),promise=recovery.readProjectionRedisVersion({...fake.options,signal:cancel.signal})
    fake.socket.emit('data',Buffer.from('$3\r\n'))
    if(event==='timeout')fake.timeout();else if(event==='abort')cancel.abort();else fake.socket.emit(event,...(event==='error'?[new Error('network')]:[]))
    await assert.rejects(()=>promise,error=>error.blocked===true);assert.equal(fake.socket.destroyed,true);assert.equal(fake.cleared(),true)
  }
  const cancelled=new AbortController();cancelled.abort();let connected=false
  await assert.rejects(()=>recovery.readProjectionRedisVersion({signal:cancelled.signal,connect:()=>{connected=true}}),error=>error.blocked===true)
  assert.equal(connected,false)
  const fake=redisSocket();let elapsed=0
  const late=recovery.readProjectionRedisVersion({...fake.options,now:()=>elapsed})
  elapsed=4001;fake.socket.emit('data',Buffer.from('$3\r\n362\r\n'))
  await assert.rejects(()=>late,error=>error.blocked===true);assert.equal(fake.socket.destroyed,true)
})
