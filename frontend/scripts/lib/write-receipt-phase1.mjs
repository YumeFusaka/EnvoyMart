/** 阶段一验收判定只接受采集事实，不代替真实TC运行。 */
export const hasGlobalRollbackDecision=(lines)=>lines?.some((line)=>/(?:rollback|commit) status: (?:Rollbacked|TimeoutRollbacked)\b/.test(line))??false
export const hasGlobalRollbackFailure=(logs,xid)=>logs?.tc?.xid===xid&&logs.tc.lines?.some((line)=>/TimeoutRollbackFailed|RollbackFailed|rollback failed|dirty undo/i.test(line))
  ||logs?.services?.order?.some((line)=>line.includes(xid)&&/TimeoutRollbackFailed|branchRollback failed|dirty undo/.test(line))||false
/** undo消失不是全部业务已恢复；必须在同一数据库读视图内满足完整目标。 */
export function hasExpectedDatabaseOutcome({mode,before,after}) {
  if(!before||!after||!['order','product'].every(db=>Array.isArray(after.undo?.[db])&&after.undo[db].length===0))return false
  if(before.coupon&&(typeof before.coupon.usedAtPresent!=='boolean'||typeof after.coupon?.usedAtPresent!=='boolean'
    ||!Array.isArray(after.promotionUndo)||after.promotionUndo.length!==0))return false
  if(mode==='commit')return after.receiptCount===1&&after.completeCount===1&&after.orderCount===1
    &&after.stock===before.stock-1&&Array.isArray(after.cart)&&after.cart.length===0
  if(mode!=='rollback'||after.receiptCount!==0||after.completeCount!==0||after.orderCount!==0
    ||after.stock!==before.stock||JSON.stringify(after.cart)!==JSON.stringify(before.cart)
    ||!Array.isArray(after.outbox)||after.outbox.length!==0)return false
  return !before.coupon||after.coupon?.status===before.coupon.status&&after.coupon?.orderNo===before.coupon.orderNo
    &&after.coupon?.usedAtPresent===before.coupon.usedAtPresent
}
/** 只纠正已定位的旧事件字段覆盖，不制造缺失的网络/事务事实；原报告始终保留。 */
export function reassessLegacyPhase1(source) {
  if(source?.version!=='seata-phase1-v1'||source.status!=='BLOCKED'||!['commit','rollback'].includes(source.mode))throw new Error('仅接受保留的旧BLOCKED阶段一报告')
  const events=source.proxyEvents??[]
  const held=events.filter((event)=>event.kind==='GLOBAL_COMMIT'&&event.xid===source.xid&&event.type===7&&event.transport===0
    &&event.codec===1&&event.compressor===0&&/^[a-f0-9]{64}$/.test(event.digest??''))
  const released=events.filter((event)=>event.kind==='GLOBAL_COMMIT'&&event.xid===source.xid&&event.type===7&&event.forwarded>0&&typeof event.reason==='string')
  if(held.length!==1||released.length!==1||held[0].id!==released[0].id||!events.some((event)=>event.kind==='ARMED'&&event.xid===source.xid)
    ||source.orderRestoration?.ready!==true||source.orderRestoration?.seataServer!=='127.0.0.1:8091')return {status:'BLOCKED',reason:'旧报告缺可独立还原的原提交帧或恢复证据，禁止补造'}
  const facts={...source,held:{...held[0],kind:'GLOBAL_COMMIT_HELD',messageKind:'GLOBAL_COMMIT'}}
  return {...assessPhase1(facts),correction:'仅纠正旧代理事件kind被frame.kind覆盖；输入事务/回执/副作用证据不变',held:facts.held}
}
export function assessPhase1({mode,before,during,after,held,recoveryDuring,recoveryAfter,logs}) {
  if(!held?.xid||held.kind!=='GLOBAL_COMMIT_HELD')return {status:'BLOCKED',reason:'没有独立XID原GlobalCommit缓留证据'}
  if(hasGlobalRollbackFailure(logs,held.xid))return {status:'FAIL',reason:'真实TC/AT分支回滚失败，不能按未覆盖处理'}
  if(during?.engine!=='MySQL'||during.receiptCount!==1||during.completeCount!==1||during.orderCount!==1
    ||!['order','product'].every((db)=>during.undo?.[db]?.some((row)=>row.xid===held.xid)))return {status:'BLOCKED',reason:'缺同XID订单/商品阶段一undo与完整成功回执行'}
  if(recoveryDuring?.operationState==='COMMITTED')return {status:'FAIL',reason:'全局未决回执提前声明成功'}
  if(recoveryDuring?.operationState!=='UNKNOWN'||recoveryDuring?.modelCalls!==0||recoveryDuring?.original!==true)return {status:'BLOCKED',reason:'缺原token原op只读恢复UNKNOWN及零模型证据'}
  if(!['order','product'].every((db)=>after?.undo?.[db]?.length===0))return {status:'BLOCKED',reason:'AT分支undo尚未结算'}
  if(recoveryAfter?.original!==true||recoveryAfter?.modelCalls!==0)return {status:'FAIL',reason:'最终恢复未保持原身份或调用模型'}
  if(mode==='commit') {
    if(after.receiptCount!==1||after.completeCount!==1||after.orderCount!==1||after.stock!==before.stock-1||after.cart.length!==0
      ||recoveryAfter.operationState!=='COMMITTED'||recoveryAfter.receiptReplayed!==true)return {status:'FAIL',reason:'提交后成功回执或副作用结算不符'}
    if(!(logs?.services?.order?.some((line)=>/commit status: (Committed|AsyncCommitting)/.test(line))
      ||logs?.tc?.xid===held.xid&&logs.tc.committed===true))return {status:'BLOCKED',reason:'缺同XID全局提交状态日志'}
  } else if(mode==='rollback') {
    if(after.receiptCount!==0||after.orderCount!==0||after.stock!==before.stock||JSON.stringify(after.cart)!==JSON.stringify(before.cart)
      ||recoveryAfter.operationState!=='NOT_FOUND')return {status:'FAIL',reason:'回滚后业务或回执未复原'}
    if(!(hasGlobalRollbackDecision(logs?.services?.order)||logs?.tc?.xid===held.xid&&logs.tc.rolledBack===true)
      ||!logs?.services?.product?.some((line)=>line.includes('branch rollback success')))return {status:'BLOCKED',reason:'缺同XID全局及AT分支回滚日志'}
  } else return {status:'FAIL',reason:'模式不受支持'}
  return {status:'PASS',reason:'原操作阶段一未决、TC决议和只读回执恢复均有真实证据'}
}

export function validateCheckoutSignature(response,expected) {
  if(!response?.approvalToken||response.usage?.totalTokens<=0||response.pendingActionDetails?.length!==1
    ||response.pendingActionDetails[0].tool!=='cart_checkout')throw new Error('真实模型未签发唯一checkout审批')
  // 载荷仅作为不可信数据核对，绝不执行其中命令；服务确认时负责验证签名。
  const payload=JSON.parse(Buffer.from(response.approvalToken.split('.')[0],'base64url').toString('utf8'))
  const action=payload.a?.[0]
  if(payload.a?.length!==1||action?.t!=='cart_checkout'||!/^[A-Za-z0-9_:.-]{1,256}$/.test(action.o??''))throw new Error('签名动作/op不符')
  if(Object.keys(action.p??{}).some((key)=>key!=='remark'&&!Object.hasOwn(expected,key)))throw new Error('模型引入未授权checkout参数')
  for(const [key,value] of Object.entries(expected))if(key!=='remark'&&String(action.p?.[key]??'').trim()!==String(value).trim())throw new Error('模型checkout地址参数不符合独立实验输入：'+key)
  if(action.p?.remark!=null&&String(action.p.remark).trim()!=='')throw new Error('模型引入未授权备注')
  return {operationId:action.o,parameters:{...expected,remark:null,userCouponId:null}}
}
