import {test} from 'node:test'
import assert from 'node:assert/strict'
import {selectPhase1ServiceEvidence} from './write-receipt-seata-local.mjs'

test('原AT过滤不能丢失同XID实际投影等待与完成日志',()=>{
  const xid='172.18.0.5:8091:6978'
  const waiting='[商品投影] 阶段=等待重试 failureStage=稳定来源消费事务 taskId=109 spuId=20 xid='+xid+' errorType=QueryTimeoutException'
  const complete='[商品投影] 阶段=已完成 taskId=109 spuId=20 xid='+xid+' 本地提交=已返回'
  const branch='branch register success: xid='+xid
  const other=waiting.replace('6978','9999')
  assert.deepEqual(selectPhase1ServiceEvidence([waiting,complete,branch,other],'product',xid),[waiting,complete,branch])
  assert.deepEqual(selectPhase1ServiceEvidence([waiting,branch],'order',xid),[branch])
  assert.deepEqual(selectPhase1ServiceEvidence([waiting],'product',undefined),[])
})
