import {createHash} from 'node:crypto'
import {resolve} from 'node:path'
import {projectionWaitingInWindow} from './seata-projection-waiting.mjs'
const sha=value=>createHash('sha256').update(value).digest('hex')
/** 原capture仍保持BLOCKED；只允许绑定原始日志的缺失等待证据独立重判。 */
export function validateProjectionSupplement({capture,capturePath,captureDigest,supplement,snapshot,snapshotDigest,expectedLogPath}) {
  const fail=reason=>({status:'FAIL',reason})
  if(capture.status!=='BLOCKED'||capture.projectionContract!=='durable-source-v1'||capture.indexAssessment?.status!=='BLOCKED'
    ||!['缺本来源任务在未决窗口实际经过消费屏障并等待的日志','缺本来源任务在原持留窗内实际经过消费屏障等待的日志'].includes(capture.indexAssessment.reason))return fail('补证只能修原日志遗漏BLOCKED，不能覆盖真实FAIL或其它缺口')
  if(supplement.version!=='seata-projection-waiting-supplement-v1'||typeof supplement.sourcePath!=='string'||supplement.runId!==capture.runId||supplement.sourceDigest!==captureDigest
    ||resolve(supplement.sourcePath)!==resolve(capturePath)||supplement.sourceStatus!==capture.status||supplement.sourceStartedAt!==capture.startedAt
    ||supplement.xid!==capture.xid||supplement.frameId!==capture.held?.id||supplement.frameDigest!==capture.held?.digest)return fail('补证未严格绑定原capture路径/SHA/runId/启动时间/XID/原帧')
  if(snapshot.version!=='seata-product-log-snapshot-v1'||typeof snapshot.sourcePath!=='string'||typeof snapshot.text!=='string'||!Number.isFinite(Date.parse(snapshot.readAt))||supplement.logSnapshotDigest!==snapshotDigest
    ||resolve(snapshot.sourcePath)!==resolve(expectedLogPath)||sha(snapshot.text)!==snapshot.sourceFileDigest||supplement.sourceLogDigest!==snapshot.sourceFileDigest
    ||supplement.sourceLogReadAt!==snapshot.readAt)return fail('补证源日志快照路径、SHA或读取时点不匹配')
  const proof=projectionWaitingInWindow(capture,snapshot.text.split(/\r?\n/))
  if(proof.status!=='PASS')return proof
  if(proof.pid!==supplement.pid||proof.taskId!==supplement.taskId||proof.spuId!==supplement.spuId||proof.heldAt!==supplement.heldAt||proof.releasedAt!==supplement.releasedAt
    ||JSON.stringify(proof.lines)!==JSON.stringify(supplement.waitingLines)||Date.parse(snapshot.readAt)<Date.parse(proof.releasedAt))return fail('补证未按原PID/task/XID严格持留窗选择实际等待日志')
  return {status:'PASS',projectionDuringLog:proof.lines,pid:proof.pid,taskId:proof.taskId,waitingCount:proof.lines.length}
}
