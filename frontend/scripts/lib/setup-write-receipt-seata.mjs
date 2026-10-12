import { randomUUID,randomBytes } from 'node:crypto'
import { mkdir,writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { ADDRESS } from './complex-agent-executor.mjs'
import { parameterHash } from './write-operation-receipts.mjs'

/** 只通过公开业务API创建新的实验用户与券；不碰其他用户记录。 */
export async function prepareSeataFixture({mode='used-coupon'}={}) {
  if(!['used-coupon','phase1'].includes(mode))throw new Error('独立前提模式不受支持')
  const gateway=process.env.VERIFY_GW??'http://127.0.0.1:8080',runId=randomUUID()
  const dir=fileURLToPath(new URL('../../.evidence/',import.meta.url));await mkdir(dir,{recursive:true})
  const path=resolve(dir,'seata-fixture-'+runId+'.private.json'),progress={version:'seata-private-fixture-v1',runId,createdAt:new Date().toISOString(),status:'PREPARING'}
  const save=()=>writeFile(path,JSON.stringify(progress,null,2)+'\n')
  let token
  async function api(apiPath,body){progress.stage=apiPath;await save();try{const response=await fetch(gateway+apiPath,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(15000)});const result=await response.json();if(result.code!==200)throw new Error('独立前提API失败：'+apiPath+'，code='+result.code);return result.data}catch(error){progress.status='FAILED';progress.error={stage:apiPath,type:error.name};await save();throw error}}
  const credentials={username:'sg_'+randomBytes(7).toString('hex'),password:'Sg'+randomBytes(12).toString('hex'),nickname:'Seata独立回执验收'}
  const login=await api('/auth/register',credentials);token=login.token
  const userId=String(login.user.id)
  Object.assign(progress,{token,userId,credentials});await save()
  const products=await api('/products?page=0&size=50');let sku
  for(const product of products.records){const detail=await api('/products/'+product.id);sku=detail.skus?.find((item)=>item.stock>=5);if(sku)break}
  if(!sku)throw new Error('没有足够库存SKU，不能准备独立前提')
  const skus=await api('/products/skus?ids='+sku.id)
  if(skus.length!==1||skus[0].status!==1||skus[0].spuStatus!==1)throw new Error('SKU/SPU不在售')
  const coupons=await api('/coupons/available'),coupon=coupons.find((item)=>!item.received&&item.remainingCount>0&&item.scopeType==='ALL'&&item.threshold<=sku.price)
  if(!coupon)throw new Error('没有该商品满足门槛的全场券')
  const received=await api('/coupons/'+coupon.id+'/receive',{})
  await api('/cart/items',{skuId:sku.id,quantity:1})
  let first
  if(mode==='used-coupon') {
  first=await api('/orders/checkout',{...ADDRESS,userCouponId:received.id,requestId:'sg-'+randomUUID()})
  const used=(await api('/coupons/mine')).find((item)=>item.id===received.id)
  if(used?.status!=='USED'||used.orderNo!==first.orderNo)throw new Error('首次订单没有成功核销独立用户券')
  await api('/cart/items',{skuId:sku.id,quantity:1})
  }
  const operationId='sg:'+runId,checkoutBody={...ADDRESS,remark:null,userCouponId:received.id}
  const fixture={version:'seata-private-fixture-v1',mode,runId,token,userId,userCouponId:received.id,...(first?{firstOrderId:first.id,firstOrderNo:first.orderNo}:{}),skuId:sku.id,operationId,checkoutBody,parametersHash:parameterHash(userId,'cart_checkout',checkoutBody),credentials,createdAt:new Date().toISOString()}
  await writeFile(path,JSON.stringify({...fixture,status:'READY'},null,2)+'\n')
  return {path,mode,runId,userId,userCouponId:received.id,...(first?{firstOrderId:first.id}:{})}
}
