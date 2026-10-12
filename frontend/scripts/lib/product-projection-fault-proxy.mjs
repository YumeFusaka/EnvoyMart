/** 固定本机自有代理；不停止真实ES/Redis，不记录请求载荷或凭据。 */
import http from 'node:http'
import net from 'node:net'

export const PROJECTION_PROXY={esUri:'http://127.0.0.1:19200',redisPort:16379}
export function isTargetEsWrite(method,path,body,spuId) {
  if(!['PUT','POST','DELETE'].includes(method))return false
  const pathname=new URL(path,'http://127.0.0.1').pathname
  if(pathname===`/envoymart_spu_v3/_doc/${spuId}`||pathname===`/envoymart_spu_v3/_update/${spuId}`)return true
  if(!['/_bulk','/envoymart_spu_v3/_bulk'].includes(pathname))return false
  return body.toString('utf8').split('\n').some(line=>{
    try {const item=JSON.parse(line),action=item.index??item.create??item.update??item.delete;return action&&String(action._id)===String(spuId)&&(action._index==='envoymart_spu_v3'||pathname.startsWith('/envoymart_spu_v3/'))}
    catch{return false}
  })
}
export async function createProjectionFaultProxies({spuId}) {
  const events=[],sockets=new Set(),redisPairs=new Set()
  let fault=null,timer,closed=false
  const record=(kind,details={})=>events.push({kind,at:new Date().toISOString(),...details})
  const track=socket=>{if(!sockets.has(socket)){sockets.add(socket);socket.once('close',()=>sockets.delete(socket))}return socket}
  const es=http.createServer(async(request,response)=>{
    const chunks=[];let size=0
    try {
      for await(const chunk of request){size+=chunk.length;if(size>8*1024*1024)throw new Error('代理请求超过8MiB');chunks.push(chunk)}
      const body=Buffer.concat(chunks),pathname=new URL(request.url,'http://127.0.0.1').pathname
      if(fault==='es'&&isTargetEsWrite(request.method,request.url,body,spuId)){
        record('ES_WRITE_REJECTED',{method:request.method,pathname,spuId})
        response.writeHead(503,{'content-type':'application/json','x-elastic-product':'Elasticsearch'})
        response.end(JSON.stringify({error:{type:'t1_projection_fault',reason:'自有代理故障验收'},status:503}));return
      }
      const upstream=http.request({hostname:'127.0.0.1',port:9200,path:request.url,method:request.method,headers:{...request.headers,host:'127.0.0.1:9200'},timeout:5000},reply=>{
        record('ES_REQUEST_FORWARDED',{method:request.method,pathname,status:reply.statusCode})
        response.writeHead(reply.statusCode,reply.headers);reply.pipe(response)
      })
      upstream.once('socket',track);upstream.once('timeout',()=>upstream.destroy(new Error('上游有界等待超时')))
      upstream.once('error',()=>{if(!response.headersSent)response.writeHead(502);response.end()})
      response.once('close',()=>upstream.destroy());upstream.end(body)
    }catch{if(!response.headersSent)response.writeHead(502);response.end()}
  })
  es.on('connection',track)
  const redis=net.createServer(client=>{
    track(client)
    if(fault==='redis'){record('REDIS_CONNECTION_REJECTED');client.destroy();return}
    const upstream=track(net.connect({host:'127.0.0.1',port:6379})),pair={client,upstream}
    redisPairs.add(pair);record('REDIS_CONNECTION_FORWARDED')
    const close=()=>{redisPairs.delete(pair);client.destroy();upstream.destroy()}
    client.once('error',close);upstream.once('error',close);client.once('close',close);upstream.once('close',close)
    client.pipe(upstream);upstream.pipe(client)
  })
  const listen=(server,port)=>new Promise((done,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',()=>{server.removeListener('error',reject);done()})})
  const stop=server=>new Promise(done=>server.listening?server.close(done):done())
  const clear=reason=>{if(timer)clearTimeout(timer);timer=null;if(fault)record('FAULT_CLEARED',{fault,reason});fault=null}
  try{await listen(es,19200);await listen(redis,16379)}catch(error){for(const socket of sockets)socket.destroy();await Promise.all([stop(es),stop(redis)]);throw error}
  record('PROXIES_LISTENING',{...PROJECTION_PROXY,spuId})
  return {events,
    arm(kind){if(!['es','redis'].includes(kind)||fault||closed)throw new Error('代理故障状态不合法');fault=kind;record('FAULT_ARMED',{fault:kind,maximumMs:300000});
      if(kind==='redis')for(const pair of redisPairs){record('REDIS_CONNECTION_DROPPED');pair.client.destroy();pair.upstream.destroy()}
      timer=setTimeout(()=>clear('MAXIMUM_FAULT_WINDOW_EXPIRED'),300000);timer.unref()
    },
    active:()=>fault,
    clear,
    async close(){if(closed)return;closed=true;clear('FINALLY');for(const socket of sockets)socket.destroy();await Promise.all([stop(es),stop(redis)]);record('PROXIES_CLOSED')}
  }
}
