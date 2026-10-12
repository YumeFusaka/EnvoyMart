import { createServer, request as httpRequest } from 'node:http'
import { spawnSync } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { resolve } from 'node:path'
import { digest } from './write-operation-receipts.mjs'

export const fingerprint = (value) => createHash('sha256').update(String(value)).digest('hex')

/** 比较记录单独保存，源报告的字节摘要与状态作为不可覆盖的证据引用。 */
export function comparisonReport(capturePath, bytes, snapshot, directory) {
  const comparisonRunId = randomUUID()
  return { publicPath: resolve(directory, 'approval-recovery-compare-' + comparisonRunId + '.json'), comparisonRunId,
    sourceCapture: { path: resolve(capturePath), sha256: fingerprint(bytes), runId: snapshot.runId, status: snapshot.status } }
}

/** 只处理独立验收请求，先取得完整上游成功响应，再丢弃返回；不合格响应按原样转发。 */
export function createLostResponseProxy({ targetPort, mode, userId, operationId, couponId, parametersHash, authFingerprint, tokenFingerprint, verifyPromotionCommit, capture }) {
  if (!['ORDER_COMMITTED_UNKNOWN', 'PROMOTION_COMMITTED_UNKNOWN', 'CLIENT_COMMITTED_UNKNOWN'].includes(mode)) throw new Error('丢响应模式不在白名单')
  const downstream = mode !== 'CLIENT_COMMITTED_UNKNOWN'
  if (downstream && (!/^[A-Za-z0-9_-]{1,100}$/.test(String(userId)) || !operationId || !/^[a-f0-9]{64}$/.test(parametersHash ?? ''))) throw new Error('下游代理缺独立用户、原操作标识或规范参数哈希')
  if (mode === 'PROMOTION_COMMITTED_UNKNOWN' && (!Number.isSafeInteger(couponId) || couponId <= 0 || typeof verifyPromotionCommit !== 'function')) throw new Error('营销代理缺原couponId或该账号唯一持券权威核验')
  if (mode === 'CLIENT_COMMITTED_UNKNOWN' && (!authFingerprint || !tokenFingerprint)) throw new Error('客户端代理缺认证/原审批指纹')
  const evidence = { mode, forwarded: 0, dropped: [], blockedQueries: [], blockedWrites: [], proofRejected: [], queryHeld: true }
  const server = createServer((incoming, outgoing) => {
    const chunks = []
    incoming.on('data', (chunk) => chunks.push(chunk))
    incoming.on('end', () => {
      const body = Buffer.concat(chunks)
      const url = new URL(incoming.url, 'http://127.0.0.1')
      const actorMatch = downstream && String(incoming.headers['x-user-id']) === String(userId)
      const matchedWrite = downstream
        ? actorMatch && incoming.method === 'POST' && url.pathname === (mode === 'ORDER_COMMITTED_UNKNOWN' ? '/tickets' : `/coupons/${couponId}/receive`) && incoming.headers['x-operation-id'] === operationId
        : incoming.method === 'POST' && url.pathname === '/ai/chat'
          && fingerprint(incoming.headers.authorization ?? '') === authFingerprint && tokenFromBody(body) === tokenFingerprint
      const receiptPrefix = mode === 'PROMOTION_COMMITTED_UNKNOWN' ? '/coupons/internal/operation-receipts/' : '/orders/internal/operation-receipts/'
      const matchedQuery = actorMatch && incoming.method === 'GET' && url.pathname.startsWith(receiptPrefix)
        && decodeURIComponent(url.pathname.slice(receiptPrefix.length)) === operationId
        && (mode !== 'PROMOTION_COMMITTED_UNKNOWN' || url.searchParams.get('toolName') === 'coupon_receive' && url.searchParams.get('parametersHash') === parametersHash)
      if (downstream && matchedWrite && evidence.dropped.length && evidence.queryHeld) {
        evidence.blockedWrites.push({ path: url.pathname, requestId: String(incoming.headers['x-request-id'] ?? ''), at: new Date().toISOString() })
        outgoing.writeHead(503, { 'content-type': 'application/json' }); outgoing.end(JSON.stringify({ code: 503, msg: '验收：原操作已提交，传输重试出口暂不可用', data: null })); return
      }
      if (matchedQuery && evidence.dropped.length && evidence.queryHeld) {
        evidence.blockedQueries.push({ path: url.pathname, requestId: String(incoming.headers['x-request-id'] ?? ''), at: new Date().toISOString() })
        outgoing.writeHead(503, { 'content-type': 'application/json' }); outgoing.end(JSON.stringify({ code: 503, msg: '验收：已提交回执查询出口暂不可用', data: null })); return
      }
      evidence.forwarded++
      const upstream = httpRequest({ hostname: '127.0.0.1', port: targetPort, method: incoming.method,
        path: incoming.url, headers: { ...incoming.headers, host: '127.0.0.1:' + targetPort } }, (response) => {
        const output = []
        response.on('data', (chunk) => output.push(chunk))
        response.on('end', async () => {
          const bytes = Buffer.concat(output)
          let payload
          try { payload = JSON.parse(bytes) } catch { payload = null }
          const receiptComplete = downstream && response.statusCode === 200 && payload?.code === 200 && response.headers['x-operation-id'] === operationId
              && response.headers['x-operation-receipt-version'] === '1' && response.headers['x-operation-replayed'] === 'false'
              && response.headers['x-operation-parameters-hash'] === parametersHash
          let completeCommit = mode === 'ORDER_COMMITTED_UNKNOWN'
            ? receiptComplete && payload.data?.ticket?.id > 0 && Array.isArray(payload.data.messages) && payload.data.messages.length === 1
            : mode === 'PROMOTION_COMMITTED_UNKNOWN'
              ? receiptComplete && Number.isSafeInteger(payload.data?.id) && payload.data.id > 0 && payload.data.couponId === couponId
              : response.statusCode === 200 && payload?.code === 200 && payload.data?.approvalStatus === 'CONFIRMED'
              && payload.data?.toolCalls?.length === 1 && payload.data.toolCalls[0].operationState === 'COMMITTED'
          let promotionProof
          if (matchedWrite && mode === 'PROMOTION_COMMITTED_UNKNOWN' && completeCommit && evidence.dropped.length === 0) {
            try {
              promotionProof = await verifyPromotionCommit(payload.data)
              completeCommit = String(promotionProof?.userId) === String(userId) && promotionProof?.couponId === couponId
                && promotionProof.userCouponId === payload.data.id && promotionProof.count === 1
                && typeof promotionProof.requestId === 'string' && promotionProof.requestId.length > 0
            } catch { completeCommit = false }
            if (!completeCommit) evidence.proofRejected.push({ requestId: String(incoming.headers['x-request-id'] ?? ''), reason: '自有账号唯一持券权威核验未通过' })
          }
          if (matchedWrite && evidence.dropped.length === 0 && completeCommit) {
            const item = { method: incoming.method, path: url.pathname, requestId: String(incoming.headers['x-request-id'] ?? ''),
              httpStatus: response.statusCode, businessCode: payload.code, forwarded: true, fullResponseReceived: true,
              responseDigest: digest(payload), rawResponseDigest: fingerprint(bytes), operationId: downstream ? operationId : payload.data.toolCalls[0].operationId,
              parametersHash: response.headers['x-operation-parameters-hash'] ?? null,
              ...(promotionProof ? { authority: { actorFingerprint: fingerprint(userId), couponId, userCouponId: promotionProof.userCouponId, count: promotionProof.count, requestId: promotionProof.requestId } } : {}), at: new Date().toISOString() }
            evidence.dropped.push(item)
            capture?.({ status: response.statusCode, headers: response.headers, body: payload }, item)
            outgoing.destroy(); incoming.destroy(); return
          }
          outgoing.writeHead(response.statusCode ?? 502, response.headers); outgoing.end(bytes)
        })
      })
      upstream.setTimeout(180000, () => upstream.destroy(new Error('上游超时')))
      upstream.on('error', () => { if (!outgoing.headersSent) outgoing.writeHead(502); outgoing.end() })
      upstream.end(body)
    })
  })
  server.controlEvidence = evidence
  server.releaseQueries = () => { evidence.queryHeld = false }
  return server
}

function tokenFromBody(body) {
  try { return fingerprint(JSON.parse(body).approvalToken ?? '') } catch { return null }
}

/** 原始 Redis HGET 只在内存解析；不输出 token、签名载荷、个人参数或失败 stderr。 */
export function readApprovalSummary(userId, sessionId, token) {
  if (!/^[A-Za-z0-9_-]{1,100}$/.test(String(userId)) || !/^[A-Za-z0-9_-]{1,100}$/.test(sessionId)) throw new Error('审批只读键身份不合法')
  const key = `chat:approval:${userId}:${sessionId}:${fingerprint(token)}`
  const command = (args) => {
    const result = spawnSync('docker', ['exec', 'envoymart-redis', 'redis-cli', '--raw', ...args], { encoding: 'utf8', timeout: 15000, windowsHide: true })
    if (result.status !== 0 || result.error || /^(?:NOAUTH|ERR|WRONGTYPE)/.test(result.stdout ?? '')) throw Object.assign(new Error('审批 Redis 精确键只读不可用，未读取配置或尝试认证'), { blocked: true })
    return result.stdout.trim()
  }
  const type = command(['TYPE', key])
  const raw = command(['HGET', key, 'record'])
  if (type !== 'hash' || !raw) throw new Error('审批授权账本不是可核验的 HASH')
  let record
  try { record = JSON.parse(raw) } catch { throw new Error('审批授权账本无法解析') }
  if (record.token !== token) throw new Error('审批授权账本 token 指纹不一致')
  return { keyDigest: fingerprint(key), type, status: record.status, acceptedAt: record.acceptedAt,
    leaseUntil: record.leaseUntil, ownerFingerprint: record.ownerNonce ? fingerprint(record.ownerNonce) : null,
    originalMessageId: record.original?.id, originalTurnId: record.original?.response?.turnId,
    tokenFingerprint: fingerprint(token), resultState: record.result?.approvalStatus ?? null,
    resultOperations: (record.result?.toolCalls ?? []).map(({ operationId, operationState, receiptReplayed }) => ({ operationId, operationState, receiptReplayed })) }
}

export function assertOriginalRecovery(response, state) {
  if (response.assistantMessageId !== state.messageId || response.turnId !== state.turnId || response.approvalToken !== state.approvalToken
    || response.approvalStatus !== 'CONFIRMED' || response.usage != null || response.toolCalls?.length !== 1
    || response.toolCalls[0].operationId !== state.action.operationId || response.toolCalls[0].operationState !== 'COMMITTED'
    || response.toolCalls[0].receiptReplayed !== true) throw new Error('原签名批次恢复身份/回执/零模型用量契约不符')
}
