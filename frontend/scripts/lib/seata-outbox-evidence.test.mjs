import {test} from 'node:test'
import assert from 'node:assert/strict'
import {assessOutboxBarrier} from './seata-outbox-evidence.mjs'
const event={id:516,eventType:'order.created',payloadDigest:'a'.repeat(64),attempts:0,sentAt:null}
const evidence=()=>({mode:'rollback',outboxSamples:[0,1200,2400].map(ms=>({at:new Date(1700000000000+ms).toISOString(),outbox:[{...event}]})),outboxDuringLog:{orderConsumerEvents:[],outboxBarrierAttempts:['等待1','等待2']},after:{outbox:[],outboxScope:'DIRECT_ORIGINAL_ORDER_NOS'},logs:{orderConsumerEvents:[]}})
test('未决三采样跨周期、实际运行且回滚直接查原事件无残留可兑约',()=>assert.equal(assessOutboxBarrier(evidence()).status,'PASS'))
test('订单JOIN掩盖孤儿、无时间/屏障证据不能通过',()=>{
 const joined=evidence();joined.after.outboxScope='CURRENT_ORDER_JOIN';assert.equal(assessOutboxBarrier(joined).status,'BLOCKED')
 const noDate=evidence();noDate.outboxSamples.forEach(v=>v.at='invalid');assert.equal(assessOutboxBarrier(noDate).status,'BLOCKED')
 const noLog=evidence();delete noLog.outboxDuringLog.outboxBarrierAttempts;assert.equal(assessOutboxBarrier(noLog).status,'BLOCKED')
})
test('提前发送或回滚孤儿事件明确失败',()=>{
 const sent=evidence();sent.outboxSamples[1].outbox[0].attempts=1;assert.equal(assessOutboxBarrier(sent).status,'FAIL')
 const orphan=evidence();orphan.after.outbox=[event];assert.equal(assessOutboxBarrier(orphan).status,'FAIL')
})
