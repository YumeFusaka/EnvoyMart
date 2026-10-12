/** 真实事务未决时的发件箱屏障：SQL采样、调度运行痕迹和消费者事实共同兑约。 */
export function assessOutboxBarrier(e) {
  const samples=e.outboxSamples
  if(!Array.isArray(samples)||samples.length<3||samples.some((sample,i)=>!Number.isFinite(Date.parse(sample.at))||i>0&&Date.parse(sample.at)<=Date.parse(samples[i-1].at))||Date.parse(samples.at(-1).at)-Date.parse(samples[0].at)<1000)return {status:'BLOCKED',reason:'缺跨至少两个500ms调度周期的三次SQL采样'}
  const first=samples[0].outbox?.[0]
  if(!first||!Number.isInteger(first.id)||first.eventType!=='order.created'||!/^[a-f0-9]{64}$/.test(first.payloadDigest??'')||samples.some((sample)=>sample.outbox?.length!==1||sample.outbox[0].id!==first.id||sample.outbox[0].payloadDigest!==first.payloadDigest))return {status:'BLOCKED',reason:'采样没有绑定同一原事件与载荷'}
  if(samples.some((sample)=>sample.outbox[0].attempts!==0||sample.outbox[0].sentAt!==null)||e.outboxDuringLog?.orderConsumerEvents?.length>0)return {status:'FAIL',reason:'全局未决事件已投递/消费或发送状态改变'}
  if(!Array.isArray(e.outboxDuringLog?.orderConsumerEvents)||!Array.isArray(e.outboxDuringLog.outboxBarrierAttempts)||e.outboxDuringLog.outboxBarrierAttempts.length<2)return {status:'BLOCKED',reason:'缺消费者未出现与至少两次实际调度等待屏障日志'}
  if(e.after?.outboxScope!=='DIRECT_ORIGINAL_ORDER_NOS')return {status:'BLOCKED',reason:'最终Outbox需直接按原订单号查库，不能由已删除订单JOIN遮蔽孤儿事件'}
  if(e.mode==='commit') {
    if(e.after?.outbox?.length!==1||e.after.outbox[0].id!==first.id||e.after.outbox[0].sentAt===null||e.after.outbox[0].attempts!==1
      ||!e.logs?.orderConsumerEvents?.length)return {status:'BLOCKED',reason:'提交后原事件一次成功发送与消费者运行尚未证明'}
  } else if(e.after?.outbox?.length!==0||e.logs?.orderConsumerEvents?.length!==0)return {status:'FAIL',reason:'回滚后发件箱残留或原创建事件已消费'}
  return {status:'PASS',reason:'未决不发送、调度真实运行及决议后发件箱结算已证明'}
}
