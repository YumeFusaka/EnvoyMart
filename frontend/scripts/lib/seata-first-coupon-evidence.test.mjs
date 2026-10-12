import {test} from 'node:test'
import assert from 'node:assert/strict'
import {assessFirstCoupon} from './seata-first-coupon-evidence.mjs'
const xid='172.18.0.5:8091:12345'
const snapshot={engine:'MySQL',firstOrder:{id:100,orderNo:'M2_FIRST',status:'CREATED',userCouponId:200},coupon:{status:'USED',orderNo:'M2_FIRST'},receiptCount:0,orderCount:0,cart:[['29','1','1']],stock:496,undo:{order:[],product:[]},promotionUndo:[]}
const evidence=()=>({version:'seata-first-coupon-v1',xid,before:structuredClone(snapshot),after:structuredClone(snapshot),checkout:{code:409},logs:{services:{order:[],product:[xid+' branch register success',xid+' branch rollback success']},tc:{xid,rolledBack:true}}})
test('USED不进入UPDATE等待仍可证明独立领域失败保护首券',()=>assert.equal(assessFirstCoupon(evidence()).status,'PASS'))
test('错退首券、首单失效、二次订单或回执残留明确失败',()=>{for(const change of [e=>e.after.coupon.status='UNUSED',e=>e.after.firstOrder.status='CLOSED',e=>e.after.orderCount=1,e=>e.after.receiptCount=1]){const e=evidence();change(e);assert.equal(assessFirstCoupon(e).status,'FAIL')}})
test('没有真实AT参与/终态、undo尚未结算不可通过',()=>{const noAt=evidence();noAt.logs.services.product=[];assert.equal(assessFirstCoupon(noAt).status,'BLOCKED');const pending=evidence();pending.after.undo.order=[{xid}];assert.equal(assessFirstCoupon(pending).status,'BLOCKED')})
