import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createHash} from 'node:crypto'
import {validateProjectionSupplement} from './seata-projection-supplement.mjs'
const sha=value=>createHash('sha256').update(value).digest('hex')
function fixture(){
  const capturePath='E:/proof/capture.json',expectedLogPath='E:/proof/product-service.log',captureDigest='a'.repeat(64)
  const line='2026-10-11T00:00:02Z INFO 100 --- [product-service] [商品投影] 阶段=等待重试 failureStage=稳定来源消费事务 taskId=6 spuId=9 xid=same-xid errorType=QueryTimeoutException'
  const capture={status:'BLOCKED',projectionContract:'durable-source-v1',runId:'run',startedAt:'2026-10-11T00:00:00Z',xid:'same-xid',indexAssessment:{status:'BLOCKED',reason:'缺本来源任务在未决窗口实际经过消费屏障并等待的日志'},
    held:{kind:'GLOBAL_COMMIT_HELD',type:7,id:16,xid:'same-xid',at:'2026-10-11T00:00:01Z',digest:'b'.repeat(64)},
    proxyEvents:[{kind:'GLOBAL_COMMIT_RELEASED',type:7,id:16,xid:'same-xid',at:'2026-10-11T00:00:05Z'}],
    stockIndex:{before:{spuId:9},during:[{projection:{tasks:[{id:'6',xid:'same-xid'}]}}]},
    logs:{services:{product:['2026-10-11T00:00:01Z INFO 100 --- [product-service] branch register success xid=same-xid']}}}
  const snapshot={version:'seata-product-log-snapshot-v1',sourcePath:expectedLogPath,readAt:'2026-10-11T00:00:10Z',text:line+'\n'+line.replace('00:00:02','00:00:06')}
  snapshot.sourceFileDigest=sha(snapshot.text)
  const snapshotDigest=sha(JSON.stringify(snapshot))
  const supplement={version:'seata-projection-waiting-supplement-v1',sourcePath:capturePath,sourceDigest:captureDigest,sourceStatus:'BLOCKED',runId:'run',sourceStartedAt:capture.startedAt,xid:capture.xid,
    frameId:16,frameDigest:capture.held.digest,logSnapshotDigest:snapshotDigest,sourceLogDigest:snapshot.sourceFileDigest,sourceLogReadAt:snapshot.readAt,pid:'100',taskId:'6',spuId:9,
    heldAt:capture.held.at,releasedAt:capture.proxyEvents[0].at,waitingLines:[line]}
  return {capture,capturePath,captureDigest,supplement,snapshot,snapshotDigest,expectedLogPath}
}
test('严格原SHA/PID/task/持留窗绑定的真实日志只补缺失waiting，不改原capture',()=>{
  const f=fixture(),raw=JSON.stringify(f.capture),proof=validateProjectionSupplement(f)
  assert.equal(proof.status,'PASS');assert.equal(proof.waitingCount,1);assert.equal(JSON.stringify(f.capture),raw)
})
test('错SHA、原FAIL、错PID、提交后waiting、篡改日志与错误时点均不能洗绿',()=>{
  const mutations=[f=>f.captureDigest='0'.repeat(64),f=>f.capture.status='FAIL',f=>f.supplement.pid='999',
    f=>f.supplement.waitingLines=[f.snapshot.text.split('\n')[1]],f=>f.snapshot.text+='篡改',f=>{f.snapshot.readAt='unknown';f.supplement.sourceLogReadAt='unknown'},
    f=>f.supplement.sourceStartedAt='2026-10-12T00:00:00Z',f=>f.supplement.frameDigest='0'.repeat(64)]
  for(const mutate of mutations){const f=fixture();mutate(f);assert.notEqual(validateProjectionSupplement(f).status,'PASS')}
})
