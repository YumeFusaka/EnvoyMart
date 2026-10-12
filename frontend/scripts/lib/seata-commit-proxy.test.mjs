import {test} from 'node:test'
import assert from 'node:assert/strict'
import {FrameReader,inspectFrame,MAX_FRAME,CommitGate} from './seata-commit-proxy.mjs'
function frame({transport=0,codec=1,compressor=0,type=7,xid='172.18.0.5:8091:123',header=16}={}){
  const body=Buffer.alloc(6+Buffer.byteLength(xid));body.writeUInt16BE(type,0);body.writeUInt16BE(Buffer.byteLength(xid),2);body.write(xid,4)
  const bytes=Buffer.alloc(header+body.length);bytes[0]=bytes[1]=0xda;bytes[2]=1;bytes.writeUInt32BE(bytes.length,3);bytes.writeUInt16BE(header,7);bytes[9]=transport;bytes[10]=codec;bytes[11]=compressor;bytes.writeUInt32BE(42,12);body.copy(bytes,header);return bytes
}
test('粘包和每个位置分包均保留逐字原帧',()=>{const a=frame(),b=frame({type:9});for(let split=1;split<a.length;split++){const reader=new FrameReader();assert.deepEqual(reader.push(a.subarray(0,split)),[]);assert.deepEqual(reader.push(Buffer.concat([a.subarray(split),b])),[a,b])}})
test('GlobalCommit业务类型是body7而不是头transport7',()=>{assert.equal(inspectFrame(frame()).kind,'GLOBAL_COMMIT');assert.equal(inspectFrame(frame({transport:7})).kind,'OTHER');assert.equal(inspectFrame(frame({header:24})).kind,'GLOBAL_COMMIT')})
test('未知codec压缩透传识别为未覆盖而不猜提交',()=>{assert.equal(inspectFrame(frame({codec:22})).kind,'UNSUPPORTED_CODEC');assert.equal(inspectFrame(frame({compressor:1})).kind,'UNSUPPORTED_CODEC')})
test('心跳3和4必须保留正常转发',()=>{assert.equal(inspectFrame(frame({transport:3})).kind,'HEARTBEAT');assert.equal(inspectFrame(frame({transport:4})).kind,'HEARTBEAT')})
test('magic版本长度与边界溢出必须拒绝',()=>{for(const mode of ['magic','version','length','header']){const bytes=frame();if(mode==='magic')bytes[0]=0;if(mode==='version')bytes[2]=0;if(mode==='length')bytes.writeUInt32BE(MAX_FRAME+1,3);if(mode==='header')bytes.writeUInt16BE(1,7);assert.throws(()=>new FrameReader().push(bytes))}})
test('不完整body不能误判为commit',()=>{const bytes=frame();bytes.writeUInt16BE(999,18);assert.equal(inspectFrame(bytes).kind,'MALFORMED_BODY')})
test('只缓留指定XID提交，其他XID、响应、回滚和心跳原样通过',()=>{
  const gate=new CommitGate(),out=[];gate.arm('172.18.0.5:8091:123')
  const bytes=[frame({xid:'172.18.0.5:8091:456'}),frame({transport:1}),frame({type:9}),frame({transport:3}),frame({transport:4})]
  for(const item of bytes)gate.route(item,(raw)=>out.push(raw))
  const target=frame();gate.route(target,(raw)=>out.push(raw));assert.deepEqual(out,bytes)
  gate.release();assert.deepEqual(out,[...bytes,target])
})
test('原帧释放后相同XID重试直接透传，不会重新缓留',()=>{
  const gate=new CommitGate(),out=[];gate.arm('172.18.0.5:8091:123');const raw=frame()
  gate.route(raw,(bytes)=>out.push(bytes));gate.route(raw,(bytes)=>out.push(bytes));gate.release()
  gate.route(raw,(bytes)=>out.push(bytes));assert.deepEqual(out,[raw,raw,raw]);assert.equal(gate.held,null)
})
test('首连接丢失时仍释放另一连接重试并记录失败，不抛出定时器异常',()=>{
  const gate=new CommitGate(),out=[];gate.arm('172.18.0.5:8091:123')
  gate.route(frame(),()=>{throw new Error('已断开')});gate.route(frame(),(raw)=>out.push(raw));gate.release()
  assert.equal(out.length,1);assert.equal(gate.events.at(-1).forwarded,1);assert.ok(gate.events.some((event)=>event.kind==='HELD_CONNECTION_LOST'))
})
test('定时释放有界且取消重复缓留',async()=>{
  const gate=new CommitGate(5),out=[];gate.arm('172.18.0.5:8091:123');gate.route(frame(),(raw)=>out.push(raw))
  await new Promise((done)=>setTimeout(done,20));assert.equal(out.length,1);assert.equal(gate.targetXid,null);assert.equal(gate.events.at(-1).reason,'BOUNDED_AUTO_RELEASE')
})
test('取证事件kind不能被业务frame.kind覆盖，保留真实held与release事件',()=>{
  const gate=new CommitGate(),out=[];gate.arm('172.18.0.5:8091:123');gate.route(frame(),(raw)=>out.push(raw))
  const held=gate.events.at(-1);gate.release()
  assert.equal(held.kind,'GLOBAL_COMMIT_HELD');assert.equal(held.messageKind,'GLOBAL_COMMIT')
  assert.equal(gate.events.at(-1).kind,'GLOBAL_COMMIT_RELEASED');assert.equal(gate.events.at(-1).messageKind,'GLOBAL_COMMIT')
})
