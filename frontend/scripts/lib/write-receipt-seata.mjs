/** 只判定已采集事实，不执行配置、数据库或服务操作。 */
export function assessNaturalRollback({ beforeCoupon, afterCoupon, beforeDatabase, afterDatabase, waiting, receipt, seata, checkoutCode }) {
  if (beforeDatabase?.engine !== 'MySQL' || beforeDatabase?.seataEnabled !== true) return { status:'BLOCKED',reason:'没有真实MySQL与Seata运行证据' }
  if (beforeCoupon?.status !== 'USED' || !beforeCoupon.orderNo) return { status:'BLOCKED',reason:'缺独立首笔订单已核销券前提' }
  if (afterCoupon?.status !== 'USED' || afterCoupon.orderNo !== beforeCoupon.orderNo) return { status:'FAIL',reason:'失败路径退还了首笔订单已用券' }
  if (checkoutCode === 200 || receipt?.code === 200 || afterDatabase?.receiptCount !== 0 || afterDatabase?.operationOrderCount !== 0
      || !Number.isInteger(beforeDatabase?.stock) || afterDatabase?.stock !== beforeDatabase.stock) return { status:'FAIL',reason:'失败后业务或库存或回执未复原' }
  if (waiting?.waiting !== true || waiting.table !== 'user_coupon' || !waiting.blockingTransactionId) return { status:'BLOCKED',reason:'未证实核销UPDATE实际等待该行锁' }
  if (!seata?.xid || !seata.atBranchRegistered || !seata.globalRolledBack) return { status:'BLOCKED',reason:'缺同XID的AT分支登记及全局回滚' }
  return { status:'PASS',reason:'自然领域失败的真实全局回滚已结算；phase1已提交回执窗口仍需单独验证' }
}
