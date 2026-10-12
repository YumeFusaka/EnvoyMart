/** 仅验证实际日志时间窗，不用提交后的等待代替未决期间真实执行。 */
export function projectionWaitingInWindow(e,lines) {
  const held=e.held,released=e.proxyEvents?.find(v=>v.kind==='GLOBAL_COMMIT_RELEASED'&&v.xid===e.xid&&v.id===held?.id&&v.type===7)
  const start=Date.parse(held?.at),end=Date.parse(released?.at)
  if(held?.kind!=='GLOBAL_COMMIT_HELD'||held.xid!==e.xid||held.type!==7||!Number.isFinite(start)||!Number.isFinite(end)||!Number.isFinite(Date.parse(e.startedAt))||end<start||Date.parse(e.startedAt)>start)return {status:'BLOCKED',reason:'缺原帧真实持留和释放时间界限'}
  const tasks=e.stockIndex?.during?.[0]?.projection?.tasks?.filter(v=>v.xid===e.xid)??[]
  if(tasks.length!==1)return {status:'BLOCKED',reason:'缺唯一原来源任务'}
  const taskId=tasks[0].id,spuId=e.stockIndex.before.spuId
  const pids=new Set((e.logs?.services?.product??[]).map(line=>line.match(/\s(?:INFO|WARN|ERROR|DEBUG)\s+(\d+)\s+---\s+\[product-service\]/)?.[1]).filter(Boolean))
  if(pids.size!==1)return {status:'BLOCKED',reason:'缺原商品进程唯一PID绑定'}
  const pid=[...pids][0]
  const matched=lines.filter(line=>{
    const at=Date.parse(line.split(' ')[0])
    return at>=start&&at<=end&&line.includes(' '+pid+' --- [product-service]')&&line.includes('[商品投影]')
      &&line.includes('阶段=等待重试')&&line.includes('failureStage=稳定来源消费事务')
      &&line.includes(' taskId='+taskId+' ')&&line.includes(' spuId='+spuId+' ')&&line.includes(' xid='+e.xid+' ')
  })
  return matched.length?{status:'PASS',pid,taskId,spuId,heldAt:held.at,releasedAt:released.at,lines:matched}
    :{status:'BLOCKED',reason:'缺本来源任务在原持留窗内实际经过消费屏障等待的日志',pid,taskId,heldAt:held.at,releasedAt:released.at,lines:[]}
}
