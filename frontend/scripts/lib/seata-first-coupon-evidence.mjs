import {hasGlobalRollbackDecision,hasGlobalRollbackFailure} from './write-receipt-phase1.mjs'
/** 二次同券领域失败保护首单；不要求USED行进入UPDATE等待或阶段一提交。 */
export function assessFirstCoupon(e) {
  if(e?.version!=='seata-first-coupon-v1'||!/^\d+(?:\.\d+){3}:8091:\d+$/.test(e.xid??''))return {status:'BLOCKED',reason:'缺独立首券保护与真实XID证据'}
  if(hasGlobalRollbackFailure(e.logs,e.xid))return {status:'FAIL',reason:'真实TC回滚失败'}
  const before=e.before,after=e.after,first=before?.firstOrder
  const valid=(snapshot)=>snapshot?.firstOrder&&['CREATED','PAID','SHIPPED','RECEIVED','COMPLETED'].includes(snapshot.firstOrder.status)
    &&snapshot.coupon?.status==='USED'&&snapshot.coupon.orderNo===snapshot.firstOrder.orderNo
  if(!valid(before)||before.receiptCount!==0||before.orderCount!==0||before.cart?.length!==1)return {status:'BLOCKED',reason:'缺自有首单有效且券已绑定、第二操作零订单回执前提'}
  if(e.checkout?.code!==409||!valid(after)||after.firstOrder.id!==first.id||after.firstOrder.orderNo!==first.orderNo||after.firstOrder.userCouponId!==first.userCouponId
    ||after.coupon.orderNo!==before.coupon.orderNo||after.receiptCount!==0||after.orderCount!==0||after.stock!==before.stock||JSON.stringify(after.cart)!==JSON.stringify(before.cart))return {status:'FAIL',reason:'二次失败影响首单券、留下第二单回执或库存购物车未复原'}
  if(!['order','product'].every(db=>Array.isArray(after.undo?.[db])&&after.undo[db].length===0)||after.promotionUndo?.length!==0)return {status:'BLOCKED',reason:'同XID三库undo尚未结算'}
  const product=e.logs?.services?.product??[]
  if(!(hasGlobalRollbackDecision(e.logs?.services?.order)||e.logs?.tc?.xid===e.xid&&e.logs.tc.rolledBack===true)
    ||!product.some(line=>line.includes(e.xid)&&line.includes('branch register success'))||!product.some(line=>line.includes(e.xid)&&line.includes('branch rollback success')))return {status:'BLOCKED',reason:'缺同XID真实全局与商品AT注册/回滚终态'}
  return {status:'PASS',reason:'二次同券失败保首单、业务恢复与真实AT回滚已证明'}
}
