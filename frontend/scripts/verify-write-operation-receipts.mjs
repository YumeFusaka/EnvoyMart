/** 八写真实 API 回执验收；不调用模型，不重启服务，不删除测试记录。 */
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { randomUUID, randomBytes, createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { WRITE_TOOLS, digest, assertReceipt, restartVerified } from './lib/write-operation-receipts.mjs'
import { ADDRESS } from './lib/complex-agent-executor.mjs'
import { redact } from './lib/public-api-evidence.mjs'

const args=process.argv.slice(2)
if (args.includes('--help')) {
  console.log('node scripts/verify-write-operation-receipts.mjs --capture [--matrix=eight-writes]\nnode scripts/verify-write-operation-receipts.mjs --compare=<capture.json> [--restart-evidence=<controls.json>]\n创建两个独立账号，仅修改其购物车/订单/工单/券/售后；售后前提通过现有模拟支付与管理员发货API。私有凭据留在.gitignore的.evidence，无自动删除。compare只重放原op，不触发模型；没有两服务真实PID证据时联合重启标为BLOCKED。')
  process.exit(0)
}
if (args.some((arg) => arg!=='--capture' && arg!=='--dry-run' && arg!=='--matrix=eight-writes'
  && !arg.startsWith('--compare=') && !arg.startsWith('--restart-evidence='))) throw new Error('不支持的参数')
if (args.includes('--dry-run')) { console.log('DRY_RUN：'+WRITE_TOOLS.join('、')+'；没有API调用或重启'); process.exit(0) }
const comparePath=args.find((arg)=>arg.startsWith('--compare='))?.slice(10)
if (Boolean(comparePath)===args.includes('--capture')) throw new Error('必须且只能选择capture或compare模式')
const gateway=process.env.VERIFY_GW ?? 'http://127.0.0.1:8080'
const dir=fileURLToPath(new URL('../.evidence/',import.meta.url))
let snapshot=comparePath?JSON.parse(await readFile(resolve(comparePath),'utf8')):{version:'write-operation-receipts-v1',runId:randomUUID(),startedAt:new Date().toISOString(),mode:'CAPTURE',actions:[],checks:[],calls:[]}
const capturePath=comparePath?resolve(comparePath):resolve(dir,'write-operations-'+snapshot.runId+'.json')
const reportPath=comparePath?resolve(dir,'write-operations-'+snapshot.runId+'-comparison-'+randomUUID()+'.json'):capturePath
if(comparePath) snapshot.sourceCapture={path:capturePath,digest:createHash('sha256').update(await readFile(capturePath)).digest('hex')}
const privatePath=resolve(dir,'write-operations-'+snapshot.runId+'.private.json')
let privateState=comparePath?JSON.parse(await readFile(privatePath,'utf8')):{actors:[],actions:[]}
if(comparePath&&(snapshot.version!=='write-operation-receipts-v1'||!['CAPTURED_WAITING_COMPARE','BLOCKED','PASS'].includes(snapshot.status)
  ||privateState.actors.length!==2||!WRITE_TOOLS.every((tool)=>privateState.actions.some((item)=>item.tool===tool))
  ||snapshot.checks.some((item)=>item.pass===false)))throw new Error('capture不完整或已有失败，不允许比较成PASS')
const tokens=new Map()
const check=(name,condition,details={})=>{snapshot.checks.push({name,pass:Boolean(condition),details:redact(details)});if(!condition)throw new Error(name)}
async function api(path,{method='GET',actor=0,body,operationId,allow=[200]}={}) {
  const started=Date.now(), requestId=randomUUID()
  const response=await fetch(gateway+path,{method,headers:{'Content-Type':'application/json','X-Request-Id':requestId,
    ...(tokens.has(actor)?{Authorization:'Bearer '+tokens.get(actor)}:{}),...(operationId?{'X-Operation-Id':operationId}:{})},
    ...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(60000)})
  const result={status:response.status,headers:Object.fromEntries(response.headers),body:await response.json()}
  snapshot.calls.push({path,method,actor,requestId,status:result.status,code:result.body.code,operationId,durationMs:Date.now()-started})
  if(!allow.includes(result.body.code))throw new Error(path+'返回HTTP='+result.status+'业务码='+result.body.code)
  return result
}
const data=async(path,options)=>(await api(path,options)).body.data
async function save() { await mkdir(dir,{recursive:true}); await writeFile(reportPath,JSON.stringify(redact(snapshot),null,2)+'\n'); if(!comparePath)await writeFile(privatePath,JSON.stringify(privateState,null,2)+'\n') }
async function authority() {
  const cart=await data('/cart'),orders=await data('/orders?tab=ALL&page=0&size=100'),tickets=await data('/tickets?page=0&size=100'),coupons=await data('/coupons/mine'),afterSales=await data('/after-sales')
  return {cart:cart.map(({id,skuId,quantity})=>({id,skuId,quantity})),orders:orders.records.map(({id,status})=>({id,status})),tickets:tickets.records.map(({id,status})=>({id,status})),coupons:coupons.map(({id,couponId,status})=>({id,couponId,status})),afterSales:afterSales.map(({id,status})=>({id,status}))}
}
async function action(tool,path,method,body,parameters,{concurrent=1,operationId='write:'+snapshot.runId+':'+tool}={}) {
  const item={tool,path,method,body,parameters,operationId}
  const responses=await Promise.all(Array.from({length:concurrent},()=>api(path,{method,body,operationId})))
  const initial=responses.find((reply)=>reply.headers['x-operation-replayed']==='false')
  check(tool+'恰有一份首次提交',Boolean(initial)&&responses.filter((reply)=>reply.headers['x-operation-replayed']==='false').length===1)
  const firstDigest=assertReceipt(initial,item,privateState.actors[0].userId,false)
  for(const response of responses)check(tool+'并发回执精确一致',assertReceipt(response,item,privateState.actors[0].userId,response===initial?false:true)===firstDigest)
  item.firstDigest=firstDigest;privateState.actions.push(item)
  snapshot.actions.push({tool,path,method,operationId,firstDigest,firstData:redact(initial.body.data),parametersHash:initial.headers['x-operation-parameters-hash']})
  await save();return initial.body.data
}
async function poll(path,predicate) {
  const deadline=Date.now()+30000
  while(Date.now()<deadline){const result=await data(path);if(predicate(result))return result;await new Promise((done)=>setTimeout(done,250))}
  throw new Error('状态未满足：'+path)
}
try {
  if(comparePath) {
    snapshot.mode='COMPARE';snapshot.compareStartedAt=new Date().toISOString()
    for(const [index,actor]of privateState.actors.entries()){const login=await data('/auth/login',{method:'POST',actor:-1,body:{username:actor.username,password:actor.password}});tokens.set(index,login.token)}
    const before=await authority();check('重启前后权威状态无漂移',digest(before)===snapshot.authorityDigest)
    for(const item of privateState.actions){const result=await api(item.path,{method:item.method,body:item.body,operationId:item.operationId});check(item.tool+'原op恢复首次回执',assertReceipt(result,item,privateState.actors[0].userId,true)===item.firstDigest)}
    check('原op重放未新增副作用',digest(await authority())===snapshot.authorityDigest)
    const controlsPath=args.find((arg)=>arg.startsWith('--restart-evidence='))?.slice(19)
    const controls=controlsPath?JSON.parse(await readFile(resolve(controlsPath),'utf8')):null
    snapshot.restartStatus=restartVerified(controls)?'PASS':'BLOCKED'
    snapshot.restartEvidence=controls?redact(controls):{reason:'未提供order/promotion真实重启PID与ready证据'}
    snapshot.status=snapshot.restartStatus==='PASS'?'PASS':'BLOCKED'
  }else{
    for(let index=0;index<2;index++){
      const actor={username:'wr_'+randomBytes(7).toString('hex'),password:'Wr'+randomBytes(12).toString('hex')}
      const registration=await data('/auth/register',{method:'POST',actor:-1,body:{...actor,nickname:'回执独立验收'+index}})
      check('独立账号身份已签发',registration.token&&registration.user?.id)
      actor.userId=String(registration.user.id);privateState.actors.push(actor);tokens.set(index,registration.token)
    }
    await save();snapshot.before=await authority();check('独立用户初始无业务',Object.values(snapshot.before).every((rows)=>rows.length===0))
    const products=await data('/products?page=0&size=50');let sku
    for(const product of products.records){const detail=await data('/products/'+product.id);sku=detail.skus?.find((candidate)=>candidate.stock>=5);if(sku)break}
    if(sku){const skus=await data('/products/skus?ids='+sku.id);check('SKU/SPU两级在售',skus.length===1&&skus[0].status===1&&skus[0].spuStatus===1)}
    check('在售SKU可支撑验收库存',Boolean(sku));snapshot.skuId=sku.id
    const added=await action('cart_add','/cart/items','POST',{skuId:sku.id,quantity:1},{skuId:sku.id,quantity:1},{concurrent:8})
    check('八并发只加一件',(await data('/cart')).find((row)=>row.id===added.id)?.quantity===1)
    const conflict=await api('/cart/items',{method:'POST',operationId:privateState.actions[0].operationId,body:{skuId:sku.id,quantity:2},allow:[409]});check('原op换参数409',conflict.status===409)
    const ownership=await api('/cart/items/'+added.id,{method:'PUT',actor:1,operationId:'foreign:'+snapshot.runId,body:{quantity:2},allow:[400]});check('其他用户不能改原行',ownership.body.code===400&&(await data('/cart',{actor:1})).length===0)
    await action('cart_update','/cart/items/'+added.id,'PUT',{quantity:2},{cartItemId:added.id,quantity:2})
    const apiOnly=await api('/cart/items/'+added.id,{method:'PUT',body:{quantity:3}});check('无操作头原API兼容',!apiOnly.headers['x-operation-id']&&apiOnly.body.data.quantity===3)
    await action('cart_remove','/cart/items/'+added.id,'DELETE',undefined,{cartItemId:added.id})
    const ticketBody={category:'OTHER',title:'回执 '+snapshot.runId,content:'隔离账号并发提交',orderId:null}
    const ticket=await action('ticket_create','/tickets','POST',ticketBody,ticketBody,{concurrent:8});check('八并发仅一工单',(await data('/tickets?page=0&size=100')).records.length===1)
    await api('/tickets/'+ticket.ticket.id+'/close',{method:'POST'})
    const coupons=await data('/coupons/available'),coupon=coupons.find((item)=>!item.received&&item.remainingCount>0);check('存在可领取券',Boolean(coupon))
    await action('coupon_receive','/coupons/'+coupon.id+'/receive','POST',undefined,{couponId:coupon.id},{concurrent:8});check('八并发只领一张券',(await data('/coupons/mine')).length===1)
    const failOp='retry:'+snapshot.runId;await api('/cart/items',{method:'POST',operationId:failOp,body:{skuId:9007199254740000,quantity:1},allow:[400]});check('被拒写未改购物车',(await data('/cart')).length===0)
    await action('cart_add','/cart/items','POST',{skuId:sku.id,quantity:1},{skuId:sku.id,quantity:1},{operationId:failOp})
    const checkoutParams={...ADDRESS,remark:null,userCouponId:null};const order=await action('cart_checkout','/orders/checkout','POST',checkoutParams,checkoutParams)
    await action('order_cancel','/orders/'+order.id+'/cancel','POST',undefined,{orderId:String(order.id)})
    await api('/cart/items',{method:'POST',body:{skuId:sku.id,quantity:1}})
    const saleOrder=await data('/orders/checkout',{method:'POST',body:{...ADDRESS,requestId:randomUUID()}})
    await api('/payments',{method:'POST',body:{orderId:saleOrder.id,channel:'MOCK',payType:'MOCK'}});await api('/payments/'+saleOrder.id+'/mock-pay',{method:'POST'});await poll('/orders/'+saleOrder.id,(value)=>value.status==='PAID')
    const admin=await data('/auth/login',{method:'POST',actor:-1,body:{username:process.env.COMPLEX_EVAL_ADMIN_USERNAME??'admin',password:process.env.COMPLEX_EVAL_ADMIN_PASSWORD??'123456'}});tokens.set(2,admin.token)
    await api('/orders/admin/orders/'+saleOrder.id+'/ship',{method:'POST',actor:2,body:{carrierCode:'SF',carrierName:'顺丰速运',trackingNo:'WR-'+snapshot.runId}});await api('/orders/'+saleOrder.id+'/receive',{method:'POST'})
    const current=await data('/orders/'+saleOrder.id);const afterBody={orderItemId:current.items[0].id,type:'RETURN_REFUND',reason:'回执测试',description:null,qualityIssue:true}
    const after=await action('after_sale_apply','/after-sales','POST',afterBody,afterBody);await api('/after-sales/'+after.id+'/cancel',{method:'POST'})
    check('八写均已捕获',WRITE_TOOLS.every((tool)=>snapshot.actions.some((item)=>item.tool===tool)))
    snapshot.authority=await authority();snapshot.authorityDigest=digest(snapshot.authority);snapshot.status='CAPTURED_WAITING_COMPARE';snapshot.restartStatus='NOT_EXECUTED'
  }
}catch(error){snapshot.status='FAIL';snapshot.error=error.message;process.exitCode=1}
finally{snapshot.finishedAt=new Date().toISOString();await save();console.log(JSON.stringify({runId:snapshot.runId,status:snapshot.status,checks:snapshot.checks.length,path:reportPath,restartStatus:snapshot.restartStatus}))}
