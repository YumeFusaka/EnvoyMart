/** Seata 2.5 Netty V1 透明代理；仅缓留已观察的独立XID GlobalCommit原帧。 */
import net from 'node:net'
import { createHash } from 'node:crypto'
export const MAX_FRAME=8*1024*1024

export class FrameReader {
  pending=Buffer.alloc(0)
  push(chunk) {
    if(chunk.length>MAX_FRAME||this.pending.length+chunk.length>MAX_FRAME)throw new Error('协议缓冲超过8MiB上限')
    this.pending=Buffer.concat([this.pending,chunk]);const frames=[]
    while(this.pending.length>=7){
      if(this.pending[0]!==0xda||this.pending[1]!==0xda||this.pending[2]!==1)throw new Error('不是受支持的Seata Netty V1帧')
      const length=this.pending.readUInt32BE(3)
      if(length<16||length>MAX_FRAME)throw new Error('协议帧长度不合法')
      if(this.pending.length<length)break
      const frame=this.pending.subarray(0,length),header=frame.readUInt16BE(7)
      if(header<16||header>length)throw new Error('协议头长度不合法')
      frames.push(Buffer.from(frame));this.pending=this.pending.subarray(length)
    }
    return frames
  }
}

export function inspectFrame(frame) {
  const header=frame.readUInt16BE(7),transport=frame[9],codec=frame[10],compressor=frame[11],id=frame.readUInt32BE(12)
  const base={transport,codec,compressor,id,length:frame.length}
  if(transport===3||transport===4)return {...base,kind:'HEARTBEAT'}
  if(codec!==1||compressor!==0)return {...base,kind:'UNSUPPORTED_CODEC'}
  const body=frame.subarray(header)
  if(body.length<2)return {...base,kind:'MALFORMED_BODY'}
  const type=body.readUInt16BE(0)
  if(type!==7&&type!==9)return {...base,kind:type===59?'MERGED_UNSUPPORTED':'OTHER',type}
  if(body.length<6)return {...base,kind:'MALFORMED_BODY',type}
  const xidLength=body.readUInt16BE(2),extraOffset=4+xidLength
  if(xidLength===0||extraOffset+2>body.length)return {...base,kind:'MALFORMED_BODY',type}
  const extraLength=body.readUInt16BE(extraOffset)
  if(extraOffset+2+extraLength!==body.length)return {...base,kind:'MALFORMED_BODY',type}
  const xid=body.subarray(4,extraOffset).toString('utf8')
  if(!/^[A-Za-z0-9_.:-]{1,128}$/.test(xid))return {...base,kind:'MALFORMED_BODY',type}
  return {...base,type,xid,kind:transport===0&&type===7?'GLOBAL_COMMIT':'OTHER'}
}

/** 不创建网络连接；用于证明原帧、重试、释放和心跳的真实路由契约。 */
export class CommitGate {
  targetXid=null
  held=null
  events=[]
  constructor(holdMs=5000) {
    if(!Number.isInteger(holdMs)||holdMs<1||holdMs>70000)throw new Error('缓留时长不在已审阅范围')
    this.holdMs=holdMs
  }
  record(value) {
    if(this.events.length>=2048)throw new Error('代理取证事件超过上限')
    this.events.push({...value,at:new Date().toISOString()})
  }
  arm(xid) {
    if(this.targetXid||this.held||!/^\d+(?:\.\d+){3}:8091:\d+$/.test(xid))throw new Error('只能绑定真实观察的独立TC XID')
    this.targetXid=xid;this.record({kind:'ARMED',xid})
  }
  route(frame,write) {
    const info=inspectFrame(frame)
    if(info.kind==='UNSUPPORTED_CODEC'||info.kind==='MERGED_UNSUPPORTED'||info.kind==='MALFORMED_BODY')this.record({...info,kind:'BLOCKED_IDENTIFICATION',messageKind:info.kind})
    if(info.kind!=='GLOBAL_COMMIT'||info.xid!==this.targetXid){write(frame);return}
    if(this.held) {
      if(this.held.frames.length>=32||this.held.byteLength+frame.length>MAX_FRAME)throw new Error('同XID重试缓留超过上限')
      this.held.frames.push({frame:Buffer.from(frame),write});this.held.byteLength+=frame.length
      this.record({kind:'DUPLICATE_COMMIT_HELD',xid:info.xid,id:info.id});return
    }
    this.held={frames:[{frame:Buffer.from(frame),write}],byteLength:frame.length,info,
      timer:setTimeout(()=>this.release('BOUNDED_AUTO_RELEASE'),this.holdMs)}
    this.record({...info,kind:'GLOBAL_COMMIT_HELD',messageKind:info.kind,digest:createHash('sha256').update(frame).digest('hex')})
  }
  release(reason='EXPLICIT_RELEASE') {
    const current=this.held;this.held=null;this.targetXid=null
    if(!current)return false
    clearTimeout(current.timer)
    let forwarded=0
    for(const item of current.frames) {
      try{item.write(item.frame);forwarded++}catch(error){this.record({kind:'HELD_CONNECTION_LOST',xid:current.info.xid,type:error.code??error.name})}
    }
    this.record({...current.info,kind:'GLOBAL_COMMIT_RELEASED',messageKind:current.info.kind,reason,forwarded,retries:current.frames.length-1})
    return true
  }
}

export async function createSeataCommitProxy({listenPort=19091,targetPort=8091,holdMs=5000}={}) {
  if(listenPort!==19091||targetPort!==8091||!Number.isInteger(holdMs)||holdMs<1||holdMs>70000)throw new Error('代理参数不在已审阅范围')
  const connections=new Set(),gate=new CommitGate(holdMs);let closed=false
  const record=(value)=>gate.record(value)
  const server=net.createServer((client)=>{
    const upstream=net.connect({host:'127.0.0.1',port:8091});connections.add(client);connections.add(upstream)
    const reader=new FrameReader()
    const write=(frame)=>{if(upstream.destroyed)throw new Error('TC连接已经断开');if(!upstream.write(frame))client.pause()}
    upstream.on('drain',()=>client.resume())
    upstream.on('data',(chunk)=>{if(!client.write(chunk))upstream.pause()})
    client.on('drain',()=>upstream.resume())
    const cleanup=(error)=>{if(error)record({kind:'CONNECTION_ERROR',type:error.code??error.name});client.destroy();upstream.destroy();connections.delete(client);connections.delete(upstream)
    }
    client.on('error',cleanup);upstream.on('error',cleanup);client.on('end',()=>upstream.end());upstream.on('end',()=>client.end())
    client.on('close',()=>cleanup());upstream.on('close',()=>cleanup())
    client.on('data',(chunk)=>{
      try{for(const frame of reader.push(chunk)){
        gate.route(frame,write)
      }}catch(error){cleanup(error)}
    })
  })
  await new Promise((done,reject)=>{server.once('error',reject);server.listen(19091,'127.0.0.1',done)})
  return {
    events:gate.events,arm(xid){if(closed)throw new Error('代理已经关闭');gate.arm(xid)},
    release:(reason)=>gate.release(reason),held:()=>gate.held?{...gate.held.info}:null,
    async close(){if(closed)return;closed=true;gate.release('CLEANUP_RELEASE')
      const stopped=new Promise((done)=>server.close(done))
      const drained=[...connections].map((socket)=>new Promise((done)=>{
        if(socket.destroyed)return done()
        const timer=setTimeout(()=>{socket.destroy();done()},2000)
        socket.once('close',()=>{clearTimeout(timer);done()});socket.end()
      }))
      await Promise.all(drained);await stopped;record({kind:'CLOSED'})},
  }
}
