/** 四项真实Seata报告只读兑约：不触发模型、SQL、网络、代理或服务控制。 */
import {readFile} from 'node:fs/promises'
import {resolve,relative,dirname,isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {createHash} from 'node:crypto'
import {assessPhase1,reassessLegacyPhase1,hasGlobalRollbackDecision,hasGlobalRollbackFailure,hasExpectedDatabaseOutcome} from './write-receipt-phase1.mjs'
import {assessOutboxBarrier} from './seata-outbox-evidence.mjs'
import {assessStockIndex} from './seata-index-evidence.mjs'
import {assessFirstCoupon} from './seata-first-coupon-evidence.mjs'
import {validateProjectionSupplement} from './seata-projection-supplement.mjs'
const root=fileURLToPath(new URL('../../.evidence/',import.meta.url))
const supported=['commit','rollback','late','couponrollback']
const sha=(value)=>createHash('sha256').update(value).digest('hex')
const validXid=(xid)=>/^\d+(?:\.\d+){3}:8091:\d+$/.test(xid??'')
const undoDone=(value)=>['order','product'].every((db)=>Array.isArray(value?.undo?.[db])&&value.undo[db].length===0)&&value?.promotionUndo?.length===0
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b)
const failure=(reason)=>({status:'FAIL',reason}),blocked=(reason)=>({status:'BLOCKED',reason})
function safePath(path,base=root) {
  if(typeof path!=='string')throw new Error('证据路径缺失')
  const absolute=isAbsolute(path)?resolve(path):resolve(base,path),within=relative(root,absolute)
  if(within.startsWith('..')||isAbsolute(within)||!/^seata-[A-Za-z0-9_.-]+\.json$/.test(within)||within.endsWith('.private.json'))throw new Error('只允许本仓库.evidence公开Seata JSON，拒绝private/secret/外部路径')
  return absolute
}
async function read(path,base) {const absolute=safePath(path,base),raw=await readFile(absolute,'utf8');return {path:absolute,raw,value:JSON.parse(raw),digest:sha(raw)}}
function rollbackConfirmed(e) {return hasGlobalRollbackDecision(e.logs?.services?.order)||e.logs?.tc?.xid===e.xid&&e.logs.tc.rolledBack===true}
function incompleteRollback(e) {
  if(typeof e.before?.coupon?.usedAtPresent!=='boolean'||typeof e.after?.coupon?.usedAtPresent!=='boolean')return blocked('缺券使用时间字段的原始前后证据')
  if(e.after.coupon.usedAtPresent!==e.before.coupon.usedAtPresent)return failure('券回滚后使用时间字段未恢复')
  return hasExpectedDatabaseOutcome({mode:'rollback',before:e.before,after:e.after})?null:blocked('完整三库回滚终态尚未结算')
}
export function assessLateCoupon(e) {
  if(e?.version!=='seata-late-coupon-v1'||!validXid(e.xid)||e.before?.engine!=='MySQL')return blocked('不是完整真实迟到核销报告')
  if(hasGlobalRollbackFailure(e.logs,e.xid))return failure('真实TC/AT回滚失败')
  if(e.before.coupon?.status!=='UNUSED'||e.before.coupon.orderNo!==null||e.before.receiptCount!==0||e.before.orderCount!==0
    ||!e.waiting?.waiting||e.waiting.table!=='user_coupon'||!e.waiting.blockingTransactionId)return blocked('缺UNUSED零原回执/订单以及实际UPDATE等待证据')
  if(e.checkout?.code===200||e.after?.coupon?.status!=='UNUSED'||e.after.coupon.orderNo!==null||e.after.receiptCount!==0||e.after.orderCount!==0
    ||e.after.stock!==e.before.stock||!same(e.after.cart,e.before.cart))return failure('迟到核销留下成功订单、孤儿券或业务/回执/库存/购物车未复原')
  const incomplete=incompleteRollback(e);if(incomplete)return incomplete
  const promo=e.logs?.services?.promotion??[]
  const rejected=promo.some((line)=>line.includes(e.xid)&&/BranchRegisterFailed|could not register branch|branch register.*(fail|reject)|global transaction.*not exist/i.test(line))
  const undone=promo.some((line)=>line.includes(e.xid)&&line.includes('branch register success'))&&promo.some((line)=>line.includes(e.xid)&&line.includes('branch rollback success'))
  if(!undoDone(e.after)||!rollbackConfirmed(e)||!e.logs?.services?.product?.some((line)=>line.includes(e.xid)&&line.includes('branch rollback success'))||!(rejected||undone))return blocked('缺同XID终态/商品AT回滚/营销拒迟到或AT回滚及全部undo结算')
  return {status:'PASS',reason:'迟到核销真实AT决议及业务恢复兑约'}
}
export function assessCouponRollback(e) {
  if(e?.version!=='seata-coupon-phase1-v1'||!validXid(e.xid)||e.before?.engine!=='MySQL')return blocked('不是完整真实有券阶段一回滚报告')
  if(hasGlobalRollbackFailure(e.logs,e.xid))return failure('真实TC/AT回滚失败')
  const d=e.during,a=e.after,held=e.held
  if(e.before.coupon?.status!=='UNUSED'||e.before.coupon.orderNo!==null||e.before.receiptCount!==0||e.before.orderCount!==0
    ||held?.kind!=='GLOBAL_COMMIT_HELD'||held.xid!==e.xid||held.type!==7||!/^[a-f0-9]{64}$/.test(held.digest??''))return blocked('缺UNUSED零原回执/订单及真实原提交帧证据')
  if(d?.receiptCount!==1||d.completeCount!==1||d.orderCount!==1||d.coupon?.status!=='USED'||d.coupon.orderNo!==d.orderRows?.[0]?.orderNo
    ||d.orderRows[0].userCouponId==null||!['order','product'].every((db)=>d.undo?.[db]?.some((row)=>row.xid===e.xid))
    ||!d.promotionUndo?.some((row)=>row.xid===e.xid))return blocked('缺本订单核销成功与三服务同XID阶段一undo及完整回执')
  if(a?.coupon?.status!=='UNUSED'||a.coupon.orderNo!==null||a.receiptCount!==0||a.orderCount!==0||a.stock!==e.before.stock||!same(a.cart,e.before.cart))return failure('真实回滚后已核销券或订单/库存/购物车/回执未复原')
  const incomplete=incompleteRollback(e);if(incomplete)return incomplete
  if(!undoDone(a)||!rollbackConfirmed(e)||!['product','promotion'].every((service)=>e.logs?.services?.[service]?.some((line)=>line.includes(e.xid)&&line.includes('branch rollback success'))))return blocked('缺同XIDTC和两RM回滚终态及undo结算')
  if(!restored(e))return blocked('未兑现真实order8091恢复元数据')
  return {status:'PASS',reason:'营销已核销阶段一真实AT回滚及order恢复兑约'}
}
function restored(e) {return e.orderRestoration?.runId===e.runId&&e.orderRestoration.ready===true&&Number.isInteger(e.orderRestoration.pid)&&e.orderRestoration.pid>0&&e.orderRestoration.seataServer==='127.0.0.1:8091'}
export async function compareFirstCouponEvidence(path) {
  const source=await read(path,process.cwd()),e=source.value
  let verdict=assessFirstCoupon(e)
  if(e.status==='FAIL'&&verdict.status!=='FAIL')verdict=failure('原首券保护capture为FAIL，保留原失败事实')
  return {...verdict,sourcePath:source.path,sourceDigest:source.digest,sourceStatus:e.status}
}
export async function compareSeataEvidence(path,{caseName,caseNames}={}) {
  const loaded=await read(path,process.cwd()),index=loaded.value
  let entries
  if(index.version==='seata-evidence-index-v1')entries=Object.entries(index.cases??{}).filter(([key])=>(!caseName||key===caseName)&&(!caseNames||caseNames.includes(key)))
  else if(caseName)entries=[[caseName,{capture:loaded.path}]]
  else if(index.version==='seata-phase1-v1')entries=[[index.mode,{capture:loaded.path}]]
  else if(index.version==='seata-late-coupon-v1')entries=[['late',{capture:loaded.path}]]
  else if(index.version==='seata-coupon-phase1-v1')entries=[['couponrollback',{capture:loaded.path}]]
  else throw new Error('证据版本不受支持')
  if(caseNames&&index.version!=='seata-evidence-index-v1')entries=entries.filter(([key])=>caseNames.includes(key))
  if(!entries.length||entries.some(([key])=>!supported.includes(key)))return blocked('组索引缺固定验收项')
  const required=caseName?[caseName]:caseNames??supported
  if(index.version==='seata-evidence-index-v1'&&!required.every((key)=>Object.hasOwn(index.cases??{},key)))return blocked('组索引缺所需固定验收项：'+required.join('/'))
  const checks=[]
  for(const [key,entry] of entries) {
    try {
      const source=await read(entry.capture,dirname(loaded.path));let e=source.value,supplementalProof
      if(entry.projectionSupplement){
        if(entry.expectedCaptureDigest!==source.digest){checks.push({case:key,sourcePath:source.path,sourceDigest:source.digest,...failure('索引未显式绑定原capture SHA256')});continue}
        const supplement=await read(entry.projectionSupplement,dirname(loaded.path)),snapshot=await read(supplement.value.logSnapshotPath,dirname(supplement.path))
        const binding=validateProjectionSupplement({capture:e,capturePath:source.path,captureDigest:source.digest,supplement:supplement.value,snapshot:snapshot.value,snapshotDigest:snapshot.digest,expectedLogPath:resolve(root,'../../logs/logs-local/product-service.log')})
        if(binding.status!=='PASS'){checks.push({case:key,sourcePath:source.path,sourceDigest:source.digest,...binding});continue}
        e={...e,projectionDuringLog:binding.projectionDuringLog}
        supplementalProof={path:supplement.path,digest:supplement.digest,logSnapshotDigest:snapshot.digest,waitingCount:binding.waitingCount,pid:binding.pid,taskId:binding.taskId,originalStatus:source.value.status}
      }
      let assessment
      if(['commit','rollback'].includes(key)) {
        if(e.version!=='seata-phase1-v1'||e.mode!==key||!validXid(e.xid))assessment=blocked('阶段一模式/版本/真实XID不符')
        else if(entry.reassessment) {
          const reevaluated=await read(entry.reassessment,dirname(loaded.path))
          if(reevaluated.value.version!=='seata-phase1-reassessment-v1'||reevaluated.value.runId!==e.runId||reevaluated.value.sourceDigest!==source.digest
            ||resolve(reevaluated.value.sourcePath)!==source.path)assessment=failure('重判报告未严格绑定保留的原BLOCKED文件SHA256/路径/runId')
          else assessment=reassessLegacyPhase1(e)
        } else {
          if(e.held?.xid!==e.xid||e.held?.type!==7||!/^[a-f0-9]{64}$/.test(e.held?.digest??''))assessment=blocked('缺原提交帧哈希/XID/type7')
          else assessment=assessPhase1(e)
        }
        if(assessment.status==='PASS'&&!restored(e))assessment=blocked('缺真实order8091恢复元数据')
        if(assessment.status==='PASS'&&(!e.logs?.requests?.[e.confirmRequestId]?.modes?.length||e.logs.requests[e.confirmRequestId].modelCalls!==0))assessment=blocked('缺真实confirm模式及零模型运行证据')
        if(assessment.status==='PASS'&&(!(e.signatureModelProof?.totalTokens>0)||!(e.signatureModelProof?.observedModelLogCount>0)))assessment=blocked('缺原唯一checkout签名真实模型运行/用量证据')
      } else assessment=key==='late'?assessLateCoupon(e):assessCouponRollback(e)
      if(assessment.status==='PASS'&&key!=='late'){
        const outbox=assessOutboxBarrier({...e,mode:key==='couponrollback'?'rollback':e.mode})
        if(outbox.status!=='PASS')assessment=outbox
        if(assessment.status==='PASS'){
          const stockIndex=assessStockIndex({...e,mode:key==='couponrollback'?'rollback':e.mode})
          if(stockIndex.status!=='PASS')assessment=stockIndex
        }
      }
      if(e.status==='FAIL'&&assessment.status!=='FAIL')assessment=failure('原真实capture为FAIL，保留失败事实：'+(e.error??e.restoreError??e.assessment?.reason??'未知失败'))
      checks.push({case:key,sourcePath:source.path,sourceDigest:source.digest,sourceStatus:e.status,...(supplementalProof?{supplementalProof}:{}),...assessment})
    }catch(error){checks.push({case:key,status:'BLOCKED',reason:error.message})}
  }
  return {status:checks.some((check)=>check.status==='FAIL')?'FAIL':checks.some((check)=>check.status!=='PASS')?'BLOCKED':'PASS',checks}
}
