/** 索引不能暴露阶段一库存；最终值需经自动链路对齐，无人工同步。 */
import {projectionWaitingInWindow} from './seata-projection-waiting.mjs'
export function assessStockIndex(e) {
  const proof=e.stockIndex
  if(!proof?.before||!Array.isArray(proof.during)||proof.during.length<3||!proof.after)return {status:'BLOCKED',reason:'缺ES与SKU/SPU汇总三阶段取证'}
  const before=proof.before
  const samples=[before,...proof.during,proof.after]
  if(samples.some(v=>v.readOnly!==true||v.skuId!==before.skuId||v.spuId!==before.spuId||!Number.isInteger(v.indexTotalStock)||!Number.isInteger(v.databaseTotalStock)))return {status:'BLOCKED',reason:'索引取证身份或数值契约不完整'}
  if(before.indexTotalStock!==before.databaseTotalStock)return {status:'FAIL',reason:'实验开始前ES与SPU库存不一致'}
  if(proof.during.some(v=>v.skuStock!==before.skuStock-1||v.databaseTotalStock!==before.databaseTotalStock-1||v.indexTotalStock!==before.indexTotalStock))return {status:'FAIL',reason:'阶段一未决库存已覆盖可见索引或阶段一扣减未发生'}
  const delta=e.mode==='commit'?-1:0
  if(proof.after.skuStock!==before.skuStock+delta||proof.after.databaseTotalStock!==before.databaseTotalStock+delta||proof.after.indexTotalStock!==proof.after.databaseTotalStock)return {status:'FAIL',reason:'决议后SKU/SPU/ES库存未自动对齐'}
  if(e.projectionContract!==undefined){
    if(e.projectionContract!=='durable-source-v1'||!e.xid)return {status:'BLOCKED',reason:'新持久投影契约或来源XID缺失'}
    if(samples.some(v=>v.projection?.readOnly!==true||!Array.isArray(v.projection.tasks)||!/^\d+$/.test(v.indexVersion??'')||!/^\d+$/.test(v.projection.maximumTaskId??'')))return {status:'BLOCKED',reason:'新run缺task/guard/ES外部版本三阶段取证'}
    const sourceTasks=v=>v.projection.tasks.filter(task=>task.xid===e.xid)
    if(sourceTasks(before).length!==0||proof.during.some(v=>sourceTasks(v).length!==1||sourceTasks(v).some(task=>task.completedAt!==null||task.attempts!==0||BigInt(task.id)<=BigInt(before.projection.maximumTaskId))))return {status:'FAIL',reason:'阶段一来源任务缺失或被消费者修改AT后像'}
    if(proof.during.some(v=>v.indexVersion!==before.indexVersion))return {status:'FAIL',reason:'未决期间ES外部版本已推进'}
    const waiting=projectionWaitingInWindow(e,e.projectionDuringLog??[])
    if(waiting.status!=='PASS')return waiting
    const after=proof.after,finalTasks=sourceTasks(after)
    if(e.mode==='rollback'){
      if(finalTasks.length!==0||after.indexVersion!==before.indexVersion)return {status:'FAIL',reason:'回滚未撤销任务或已改旧索引外部版本'}
    }else{
      const guard=after.projection.guard,task=finalTasks[0]
      if(finalTasks.length!==1||!task.completedAt||task.attempts<1||task.mode!=='CACHE_AND_ES'||!/^\d+$/.test(task.appliedTaskId??'')||!/^\d+$/.test(guard?.versionBase??'')||!/^\d+$/.test(guard?.lastAppliedTaskId??''))return {status:'BLOCKED',reason:'提交后缺持久完成任务及永久版本闸'}
      if(BigInt(task.appliedTaskId)<BigInt(task.id)||BigInt(guard.lastAppliedTaskId)<BigInt(task.appliedTaskId)||BigInt(after.indexVersion)!==BigInt(guard.versionBase)+BigInt(guard.lastAppliedTaskId)||BigInt(after.indexVersion)<=BigInt(before.indexVersion))return {status:'FAIL',reason:'持久任务版本与ES实际版本未自动对齐'}
    }
  }
  return {status:'PASS',reason:'阶段一索引隔离与最终库存自动对齐已证明'}
}
