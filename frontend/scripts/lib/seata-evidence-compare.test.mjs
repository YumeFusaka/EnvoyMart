import {test} from 'node:test'
import assert from 'node:assert/strict'
import {assessLateCoupon,assessCouponRollback,compareSeataEvidence} from './seata-evidence-compare.mjs'
const xid='192.0.2.10:8091:123'
const before=()=>({engine:'MySQL',coupon:{status:'UNUSED',orderNo:null,usedAtPresent:false},receiptCount:0,completeCount:0,orderCount:0,stock:10,cart:[['1','1','1']]})
const restored=()=>({...before(),outbox:[],undo:{order:[],product:[]},promotionUndo:[]})
const late=()=>({version:'seata-late-coupon-v1',xid,before:before(),after:restored(),waiting:{waiting:true,table:'user_coupon',blockingTransactionId:'tx'},checkout:{code:500},
  logs:{tc:{xid,rolledBack:true,lines:[]},services:{order:[],product:[xid+' branch rollback success'],promotion:[xid+' branch register failed Could not register branch']}}})
test('完整迟到核销原报告只读兑约通过，不依赖最终status标签',()=>assert.equal(assessLateCoupon(late()).status,'PASS'))
test('只有持锁、营销未参与AT、错XID或孤儿券不可通过',()=>{
  const a=late();a.waiting.waiting=false;assert.equal(assessLateCoupon(a).status,'BLOCKED')
  const b=late();b.logs.services.promotion=[];assert.equal(assessLateCoupon(b).status,'BLOCKED')
  const c=late();c.logs.tc.xid='other';assert.equal(assessLateCoupon(c).status,'BLOCKED')
  const d=late();d.after.coupon.status='USED';assert.equal(assessLateCoupon(d).status,'FAIL')
})
test('已核销阶段一必须三库同XID、真实回滚和恢复PID齐全',()=>{
  const e={...late(),version:'seata-coupon-phase1-v1',runId:'run',held:{kind:'GLOBAL_COMMIT_HELD',xid,type:7,digest:'a'.repeat(64)},
    during:{receiptCount:1,completeCount:1,orderCount:1,coupon:{status:'USED',orderNo:'YS'},orderRows:[{orderNo:'YS',userCouponId:1}],undo:{order:[{xid}],product:[{xid}]},promotionUndo:[{xid}]},
    orderRestoration:{runId:'run',ready:true,pid:42,seataServer:'127.0.0.1:8091'}}
  e.logs.services.promotion=[xid+' branch rollback success'];assert.equal(assessCouponRollback(e).status,'PASS')
  e.during.promotionUndo=[];assert.equal(assessCouponRollback(e).status,'BLOCKED')
})
test('TC实际RollbackFailed在两个专项都必须FAIL',()=>{const e=late();e.logs.tc.lines=['TimeoutRollbackFailed'];assert.equal(assessLateCoupon(e).status,'FAIL')})
test('券回滚的使用时间残留不能通过，缺字段不能视作已恢复',()=>{
  for(const assess of [assessLateCoupon,assessCouponRollback]) {
    const make=()=>{
      const e=late()
      if(assess===assessCouponRollback)Object.assign(e,{version:'seata-coupon-phase1-v1',runId:'run',
        held:{kind:'GLOBAL_COMMIT_HELD',xid,type:7,digest:'a'.repeat(64)},
        during:{receiptCount:1,completeCount:1,orderCount:1,coupon:{status:'USED',orderNo:'YS'},orderRows:[{orderNo:'YS',userCouponId:1}],undo:{order:[{xid}],product:[{xid}]},promotionUndo:[{xid}]},
        orderRestoration:{runId:'run',ready:true,pid:42,seataServer:'127.0.0.1:8091'}})
      if(assess===assessCouponRollback)e.logs.services.promotion=[xid+' branch rollback success']
      return e
    }
    const residual=make();residual.after.coupon.usedAtPresent=true
    assert.equal(assess(residual).status,'FAIL')
    const missing=make();delete missing.after.coupon.usedAtPresent
    assert.equal(assess(missing).status,'BLOCKED')
  }
})
test('compare拒绝私有凭据与越界路径，不读取secret',async()=>{
  await assert.rejects(compareSeataEvidence('C:/Users/example/.env'))
  await assert.rejects(compareSeataEvidence(new URL('../../.evidence/seata-no-read.private.json',import.meta.url).pathname))
})
