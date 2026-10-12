import {test} from 'node:test'
import assert from 'node:assert/strict'
import {assessStockIndex} from './seata-index-evidence.mjs'
const before={skuId:29,spuId:9,skuStock:496,databaseTotalStock:900,indexTotalStock:900,readOnly:true}
const held={...before,skuStock:495,databaseTotalStock:899}
const evidence=(mode)=>({mode,stockIndex:{before,during:[held,held,held],after:mode==='commit'?{...held,indexTotalStock:899}:before}})
test('提交/回滚分别自动对齐且未决索引保持原值',()=>{for(const mode of ['commit','rollback'])assert.equal(assessStockIndex(evidence(mode)).status,'PASS')})
test('阶段一泄漏、回滚残留、缺取证明确失败',()=>{
  const leak=evidence('rollback');leak.stockIndex.during=[{...held,indexTotalStock:899},held,held];assert.equal(assessStockIndex(leak).status,'FAIL')
  const dirty=evidence('rollback');dirty.stockIndex.after={...before,indexTotalStock:899};assert.equal(assessStockIndex(dirty).status,'FAIL')
  assert.equal(assessStockIndex({}).status,'BLOCKED')
})

const durable=(mode)=>{
  const e=evidence(mode);e.xid='same-xid';e.projectionContract='durable-source-v1';e.startedAt='2026-10-11T00:00:00Z'
  e.held={kind:'GLOBAL_COMMIT_HELD',type:7,id:16,xid:e.xid,at:'2026-10-11T00:00:01Z'}
  e.proxyEvents=[{kind:'GLOBAL_COMMIT_RELEASED',type:7,id:16,xid:e.xid,at:'2026-10-11T00:00:05Z'}]
  e.logs={services:{product:['2026-10-11T00:00:01Z INFO 100 --- [product-service] branch register success xid=same-xid']}}
  e.projectionDuringLog=['2026-10-11T00:00:02Z INFO 100 --- [product-service] [商品投影] 阶段=等待重试 failureStage=稳定来源消费事务 taskId=6 spuId=9 xid=same-xid errorType=LockConflictException durationMs=20']
  e.stockIndex.before={...before,indexVersion:'100',projection:{readOnly:true,maximumTaskId:'5',tasks:[],guard:{versionBase:'95',lastAppliedTaskId:'5'}}}
  const pending={id:'6',xid:e.xid,completedAt:null,attempts:0,appliedTaskId:null,mode:null}
  e.stockIndex.during=[0,1,2].map(()=>({...held,indexVersion:'100',projection:{readOnly:true,maximumTaskId:'6',tasks:[pending],guard:{versionBase:'95',lastAppliedTaskId:'5'}}}))
  e.stockIndex.after=mode==='rollback'?e.stockIndex.before:{...held,indexTotalStock:899,indexVersion:'101',projection:{readOnly:true,maximumTaskId:'6',tasks:[{...pending,completedAt:'真实提交后',attempts:1,appliedTaskId:'6',mode:'CACHE_AND_ES'}],guard:{versionBase:'95',lastAppliedTaskId:'6'}}}
  return e
}
test('新run强制三阶段任务/永久闸/ES版本兑约，旧证据保持兼容',()=>{
  for(const mode of ['commit','rollback'])assert.equal(assessStockIndex(durable(mode)).status,'PASS')
  const missing=durable('commit');delete missing.stockIndex.before.projection;assert.equal(assessStockIndex(missing).status,'BLOCKED')
  const pending=durable('commit');pending.stockIndex.during[0].projection.tasks[0].attempts=1;assert.equal(assessStockIndex(pending).status,'FAIL')
  const mismatch=durable('commit');mismatch.stockIndex.after.indexVersion='102';assert.equal(assessStockIndex(mismatch).status,'FAIL')
  const inactive=durable('commit');inactive.projectionDuringLog=[];assert.equal(assessStockIndex(inactive).status,'BLOCKED')
  const late=durable('commit');late.projectionDuringLog=late.projectionDuringLog.map(line=>line.replace('00:00:02','00:00:06'));assert.equal(assessStockIndex(late).status,'BLOCKED')
  const residual=durable('rollback');residual.stockIndex.after={...residual.stockIndex.before,projection:residual.stockIndex.during[0].projection};assert.equal(assessStockIndex(residual).status,'FAIL')
})
