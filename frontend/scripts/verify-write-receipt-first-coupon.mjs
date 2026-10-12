/** 独立首券保护真实领域失败；不调用模型、不持锁、不改服务或事务状态。 */
import {readFile,writeFile} from 'node:fs/promises'
import {randomUUID} from 'node:crypto'
import {resolve,isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {prepareSeataFixture} from './lib/setup-write-receipt-seata.mjs'
import {firstCouponSnapshot,observeCheckoutXid,lateCouponLogEvidence} from './lib/write-receipt-seata-local.mjs'
import {assessFirstCoupon} from './lib/seata-first-coupon-evidence.mjs'
const args=process.argv.slice(2),option=prefix=>args.find(v=>v.startsWith(prefix))?.slice(prefix.length)
const compare=option('--compare=')
if(compare){if(args.length!==1)throw new Error('compare仅接受单一公开证据路径');const {compareFirstCouponEvidence}=await import('./lib/seata-evidence-compare.mjs');const result=await compareFirstCouponEvidence(compare);console.log(JSON.stringify(result));process.exit(result.status==='PASS'?0:1)}
if(!args.length){console.log(JSON.stringify({status:'BLOCKED',reason:'首券保护需独立prepare/capture或--compare=原公开证据'}));process.exit(2)}
if(args.includes('--help')||args.includes('--dry-run')){console.log('node scripts/verify-write-receipt-first-coupon.mjs --prepare\nSEATA_VERIFY_ENABLE=1 node scripts/verify-write-receipt-first-coupon.mjs --capture --fixture=<绝对新private路径>\nnode scripts/verify-write-receipt-first-coupon.mjs --compare=<公开证据路径>\n首单真实核销，第二次同券409，原券/首单不变、第二操作回执/订单0、库存购物车复原、同XIDTC与商品AT回滚和三库undo0。无模型、不要求USED行UPDATE等待或phase1。');process.exit(0)}
if(args.length===1&&args[0]==='--prepare'){console.log(JSON.stringify(await prepareSeataFixture()));process.exit(0)}
if(args.length!==2||!args.includes('--capture')||!option('--fixture=')||process.env.SEATA_VERIFY_ENABLE!=='1')throw new Error('capture需要显式本任务开关与唯一独立fixture')
const fixturePath=option('--fixture=')
if(!isAbsolute(fixturePath))throw new Error('fixture必须绝对路径')
const fixture=JSON.parse(await readFile(fixturePath,'utf8'))
if(fixture.version!=='seata-private-fixture-v1'||fixture.mode!=='used-coupon'||fixture.status!=='READY'||!fixture.firstOrderId||!fixture.firstOrderNo)throw new Error('必须新准备的自有首单已核销前提')
const output=resolve(fileURLToPath(new URL('../.evidence/',import.meta.url)),'seata-first-coupon-'+fixture.runId+'.json')
const e={version:'seata-first-coupon-v1',runId:fixture.runId,status:'BLOCKED',startedAt:new Date().toISOString(),calls:[]}
const cancelled=new AbortController(),pause=ms=>new Promise(done=>setTimeout(done,ms))
for(const signal of ['SIGINT','SIGTERM'])process.once(signal,()=>cancelled.abort())
try{
 e.before=await firstCouponSnapshot({fixture})
 if(e.before.orderCount!==0||e.before.receiptCount!==0||e.before.coupon.status!=='USED'||e.before.coupon.orderNo!==fixture.firstOrderNo||!e.before.firstOrder)throw Object.assign(new Error('自有首单/已用券/第二op零原写入前提不满足'),{blocked:true})
 const requestId=randomUUID();e.requestId=requestId
 const response=await fetch((process.env.VERIFY_GW??'http://127.0.0.1:8080')+'/orders/checkout',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+fixture.token,'X-Operation-Id':fixture.operationId,'X-Request-Id':requestId},body:JSON.stringify(fixture.checkoutBody),signal:AbortSignal.any([cancelled.signal,AbortSignal.timeout(120000)])})
 const result=await response.json();e.checkout={httpStatus:response.status,code:result.code};e.calls.push({path:'/orders/checkout',requestId,...e.checkout})
 e.xid=(await observeCheckoutXid({requestId,maxWaitMs:5000})).xid
 const deadline=Date.now()+20000
 do{
  if(cancelled.signal.aborted)throw Object.assign(new Error('验收已取消，保留真实事实'),{blocked:true})
  e.after=await firstCouponSnapshot({fixture,xid:e.xid});e.logs=await lateCouponLogEvidence({xid:e.xid})
  e.assessment=assessFirstCoupon(e)
  if(e.assessment.status!=='BLOCKED')break
  await pause(250)
 }while(Date.now()<deadline)
 e.status=e.assessment.status
}catch(error){e.status=error.blocked?'BLOCKED':'FAIL';e.error=error.message}
finally{
 e.finishedAt=new Date().toISOString();await writeFile(output,JSON.stringify(e,null,2)+'\n',{flag:'wx'})
 fixture.status=e.status;await writeFile(fixturePath,JSON.stringify(fixture,null,2)+'\n')
 console.log(JSON.stringify({status:e.status,path:output,reason:e.error??e.assessment?.reason,runId:fixture.runId}))
 if(e.status!=='PASS')process.exitCode=1
}
