/** 真实有效UNUSED券持锁直到checkout超时，再观察迟到AT分支禁止孤儿提交。 */
import {readFile,writeFile,mkdir} from 'node:fs/promises'
import {resolve,isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {randomUUID} from 'node:crypto'
import {lockUserCoupon,observeWaiting,observeCheckoutXid,lateCouponSnapshot,lateCouponLogEvidence} from './lib/write-receipt-seata-local.mjs'
import {hasGlobalRollbackDecision,hasExpectedDatabaseOutcome} from './lib/write-receipt-phase1.mjs'
const args=process.argv.slice(2),fixturePath=args.find((arg)=>arg.startsWith('--fixture='))?.slice(10)
const compare=args.find((arg)=>arg.startsWith('--compare='))?.slice(10)
if(compare){if(args.length!==1)throw new Error('compare仅接受单一只读证据路径');const {compareSeataEvidence}=await import('./lib/seata-evidence-compare.mjs');const verdict=await compareSeataEvidence(compare,{caseName:'late'});console.log(JSON.stringify(verdict));process.exit(verdict.status==='PASS'?0:1)}
if(!args.length){console.log(JSON.stringify({status:'BLOCKED',reason:'迟到核销未覆盖，需要显式capture或--compare=保留证据'}));process.exit(2)}
if(args.includes('--help')||args.includes('--dry-run')){console.log('先node scripts/verify-write-receipt-seata.mjs --prepare-unused；营销真实AT启动后：SEATA_VERIFY_ENABLE=1 node scripts/verify-write-receipt-seata-late-coupon.mjs --capture --fixture=<绝对private路径>。只持指定独立券行锁，不改表/配置、不重启；实际UPDATE等待、请求超时和三服务XID证据缺失均非0。');process.exit(0)}
if(args.some((arg)=>arg!=='--capture'&&!arg.startsWith('--fixture='))||!args.includes('--capture')||!fixturePath||!isAbsolute(fixturePath)||process.env.SEATA_VERIFY_ENABLE!=='1')throw new Error('迟到核销capture需要显式授权与绝对独立fixture')
const fixture=JSON.parse(await readFile(fixturePath,'utf8'))
if(fixture.mode!=='phase1'||!fixture.userCouponId||!fixture.token)throw new Error('需要prepare-unused生成的独立UNUSED券')
const dir=fileURLToPath(new URL('../.evidence/',import.meta.url)),runId=randomUUID(),output=resolve(dir,'seata-late-coupon-'+runId+'.json')
const evidence={version:'seata-late-coupon-v1',runId,status:'BLOCKED',startedAt:new Date().toISOString()},gateway=process.env.VERIFY_GW??'http://127.0.0.1:8080'
let lock,checkout
const cancellation=new AbortController()
for(const name of ['SIGINT','SIGTERM'])process.once(name,()=>cancellation.abort(new Error('用户中断验收')))
const pause=(ms)=>new Promise((done)=>setTimeout(done,ms)),blocked=(message)=>Object.assign(new Error(message),{blocked:true})
const promoDecision=(lines)=>({rejected:lines.some((line)=>/BranchRegisterFailed|could not register branch|branch register.*(fail|reject)|global transaction.*not exist/i.test(line)),
  undone:lines.some((line)=>line.includes('branch register success'))&&lines.some((line)=>line.includes('branch rollback success'))})
try {
  evidence.before=await lateCouponSnapshot({fixture})
  if(evidence.before.coupon.status!=='UNUSED'||evidence.before.coupon.orderNo!==null||evidence.before.receiptCount!==0||evidence.before.orderCount!==0)throw blocked('实验前提不是无本次订单/回执的独立UNUSED券')
  lock=await lockUserCoupon(fixture);evidence.lock=lock.evidence
  const requestId=randomUUID();evidence.requestId=requestId
  checkout=fetch(gateway+'/orders/checkout',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+fixture.token,
    'X-Operation-Id':fixture.operationId,'X-Request-Id':requestId},body:JSON.stringify(fixture.checkoutBody),signal:AbortSignal.any([cancellation.signal,AbortSignal.timeout(20000)])})
    .then(async(response)=>({httpStatus:response.status,code:(await response.json()).code}),()=>({transportUnknown:true}))
  const observed=await observeCheckoutXid({requestId,maxWaitMs:5000});evidence.xid=observed.xid
  evidence.waiting=await observeWaiting({userCouponId:fixture.userCouponId,requestId,maxWaitMs:15000})
  if(!evidence.waiting.waiting)throw blocked('没有实际核销UPDATE等待，禁止用仅持锁替代')
  // 保持核销等待到传输层超时/领域失败完成，才能复现“退券先于迟到核销”。
  evidence.checkout=await checkout
  if(evidence.checkout.code===200)throw new Error('券仍持锁但checkout报告成功')
  evidence.beforeUnlock=await lateCouponSnapshot({fixture,xid:observed.xid})
  await lock.release();lock=null
  const deadline=Date.now()+70000
  while(Date.now()<deadline) {
      if(cancellation.signal.aborted)throw blocked('验收已取消，释放自有资源后保留证据')
    evidence.after=await lateCouponSnapshot({fixture,xid:observed.xid})
    evidence.logs=await lateCouponLogEvidence({xid:observed.xid})
    const global=hasGlobalRollbackDecision(evidence.logs.services.order)||evidence.logs.tc?.xid===observed.xid&&evidence.logs.tc.rolledBack===true
    const decision=promoDecision(evidence.logs.services.promotion)
    if(global&&(decision.rejected||decision.undone)&&hasExpectedDatabaseOutcome({mode:'rollback',before:evidence.before,after:evidence.after}))break
    await pause(250)
  }
  const after=evidence.after
  if(after.coupon.status!=='UNUSED'||after.coupon.orderNo!==null||after.receiptCount!==0||after.orderCount!==0
    ||after.stock!==evidence.before.stock||JSON.stringify(after.cart)!==JSON.stringify(evidence.before.cart))throw new Error('全局失败后出现孤儿券或业务/库存/购物车/回执未复原')
  if(!hasExpectedDatabaseOutcome({mode:'rollback',before:evidence.before,after}))throw blocked('观察窗口结束后完整三库回滚终态仍未结算')
  const promo=evidence.logs.services.promotion
  const {rejected,undone}=promoDecision(promo)
  if(!(hasGlobalRollbackDecision(evidence.logs.services.order)||evidence.logs.tc?.xid===observed.xid&&evidence.logs.tc.rolledBack===true)
    ||!evidence.logs.services.product.some((line)=>line.includes('branch rollback success'))||!(rejected||undone))throw blocked('缺同XID全局回滚、商品AT回滚和营销迟到分支拒绝/AT回滚证据')
  evidence.promotionDecision=rejected?'LATE_BRANCH_REJECTED':'AT_BRANCH_ROLLED_BACK';evidence.status='PASS'
}catch(error){evidence.status=error.blocked?'BLOCKED':'FAIL';evidence.error=error.message}
finally {
  if(lock)try{await lock.release()}catch(error){evidence.status='FAIL';evidence.lockReleaseError=error.message}
  if(checkout)evidence.checkout=await checkout
  if(evidence.xid&&!evidence.after)try{evidence.after=await lateCouponSnapshot({fixture,xid:evidence.xid});evidence.logs=await lateCouponLogEvidence({xid:evidence.xid})}catch(error){evidence.finalObservationError=error.message}
  evidence.finishedAt=new Date().toISOString();await mkdir(dir,{recursive:true});await writeFile(output,JSON.stringify(evidence,null,2)+'\n')
  if(evidence.status!=='PASS')process.exitCode=1
  console.log(JSON.stringify({status:evidence.status,path:output,reason:evidence.error}))
}
