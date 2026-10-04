/**
 * U12 · 重排在真实并发下会不会被 5 秒超时打成降级。
 *
 * 背景：线上 `RERANK_TIMEOUT_MS` 默认 5000（ai-service/application.yml），
 * 而所有评测夹具都硬编码 30 秒（RetrievalComparisonTest / RagAnswerQualityTest）。
 * 也就是说 **5 秒这一档从来没有在并发下验证过**——评测里跑得通不代表线上跑得通，
 * 因为夹具是串行的、机器是空的。这条脚本补的就是这个缺口。
 *
 * 做法：直接打百炼的真实重排接口（与 DashScopeReranker 同一端点、同一模型、
 * 同一 5 秒超时），并发 N 个请求，统计：
 *   - 成功率（HTTP 2xx 且解析出结果）
 *   - 降级率（超时 / 非 2xx / 空结果——即生产代码会走 degrade() 的那些情形）
 *   - 延迟分位（p50 / p95 / max），好知道 5 秒这条线离真实延迟有多远
 *
 * **为什么直连而不经过 ai-service**：ai-service 没有暴露重排的诊断接口，
 * 为了压测去加一个会改变线上行为的接口不值当；而重排的失败判定（超时、非 2xx、
 * 空结果）在客户端就能完整复现，与 DashScopeReranker.degrade() 的判据一一对应。
 *
 * **API 费用**：每次调用都是真实计费。默认 20 并发 × 3 轮 = 60 次重排，
 * 每次约数百 token，总计约 2~5 万 token 量级。跑之前会打印预估，跑完会打印实际。
 *
 * 前置条件：`backend/.env.local` 里有 RERANK_API_KEY（或 EMBEDDING_API_KEY）。
 * 用法：node scripts/verify-rerank-stress.mjs [并发数] [轮数]
 *   默认并发 20、轮数 3；例：node scripts/verify-rerank-stress.mjs 10 1
 */
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const BACKEND = resolve(HERE, '../../backend')

const ENDPOINT = process.env.RERANK_ENDPOINT ?? 'https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank'
const MODEL = process.env.RERANK_MODEL ?? 'gte-rerank-v2'
/** 与线上 RERANK_TIMEOUT_MS 默认值保持一致——这是本脚本要验证的那条线 */
const TIMEOUT_MS = Number(process.env.RERANK_TIMEOUT_MS ?? 5000)

const CONCURRENCY = Number(process.argv[2] ?? 20)
const ROUNDS = Number(process.argv[3] ?? 3)

/** 降级率阈值：并发下偶尔抖一下可接受，系统性降级不可接受 */
const DEGRADE_RATE_LIMIT = Number(process.env.DEGRADE_RATE_LIMIT ?? 0.05)

function readApiKey() {
  if (process.env.RERANK_API_KEY) {
    return process.env.RERANK_API_KEY
  }
  const envPath = resolve(BACKEND, '.env.local')
  const text = readFileSync(envPath, 'utf8')
  for (const line of text.split(/\r?\n/)) {
    const m = line.match(/^\s*export\s+RERANK_API_KEY\s*=\s*"?([^"]+)"?/)
    if (m) {
      return m[1]
    }
  }
  throw new Error(`没能从 ${envPath} 里读到 RERANK_API_KEY（用 --env 或环境变量传入）`)
}

/** 一批真实的、长度接近线上的候选文档——短文本重排太快，测不出并发压力 */
const CANDIDATES = [
  '乳清蛋白粉每 100 克含蛋白质 80 克，适合健身人群在训练后 30 分钟内冲服，温水不超过 40 度。',
  '维生素 C 咀嚼片每片含维生素 C 100 毫克，每日 1 片，餐后服用，避免与磺胺类药物同服。',
  '深海鱼油软胶囊富含 EPA 与 DHA，正在服用华法林等抗凝药物者应咨询医师后再服用。',
  '碳酸钙 D3 咀嚼片用于补充钙与维生素 D，高钙血症、肾结石患者禁用，不宜与四环素类同服。',
  '辅酶 Q10 软胶囊每日 1 粒，与他汀类药物联用时可能影响药效，建议间隔两小时以上。',
  '孕期复合营养包含叶酸、铁、钙与 DHA，孕早期每日 1 包，用温水冲调，开袋后当天服完。',
  '膳食纤维粉每袋含膳食纤维 5 克，温水冲服，服用后应多饮水，肠梗阻患者禁用。',
  '胶原蛋白肽粉每袋 5 克，可直接用水冲服，不建议睡前大量服用以免夜间起夜影响睡眠。',
]
const QUERIES = [
  '我在吃华法林，鱼油能吃吗',
  '补钙的同时要注意什么',
  '孕妇需要额外补哪些营养',
  '蛋白粉怎么吃效果更好',
  '他汀和阿莫西林一起吃有影响吗',
]

let pass = 0
let fail = 0
function ck(name, condition, detail = '') {
  if (condition) {
    console.log(`  \x1b[32mPASS\x1b[0m ${name}`)
    pass += 1
  } else {
    console.log(`  \x1b[31mFAIL\x1b[0m ${name}${detail ? `\n        ${detail}` : ''}`)
    fail += 1
  }
}

const apiKey = readApiKey()
console.log(`端点      ${ENDPOINT}`)
console.log(`模型      ${MODEL}`)
console.log(`超时      ${TIMEOUT_MS} ms（与线上默认一致）`)
console.log(`并发      ${CONCURRENCY}`)
console.log(`轮数      ${ROUNDS}`)
console.log(`总调用    ${CONCURRENCY * ROUNDS} 次重排（真实计费）`)
console.log('')

/** 单次调用：复刻 DashScopeReranker 的请求体与降级判据 */
async function oneCall(i) {
  const query = QUERIES[i % QUERIES.length]
  const startedAt = Date.now()
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
  try {
    const res = await fetch(ENDPOINT, {
      method: 'POST',
      signal: controller.signal,
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: MODEL,
        input: { query, documents: CANDIDATES },
        parameters: { top_n: 3, return_documents: false },
      }),
    })
    const latency = Date.now() - startedAt
    if (res.status / 100 !== 2) {
      return { ok: false, latency, reason: `http ${res.status}`, tokens: 0 }
    }
    const body = await res.json()
    const results = body?.output?.results
    const tokens = body?.usage?.total_tokens ?? 0
    if (!Array.isArray(results) || results.length === 0) {
      return { ok: false, latency, reason: '空结果', tokens }
    }
    return { ok: true, latency, reason: null, tokens }
  } catch (e) {
    // AbortController 触发时 e.name === 'AbortError'，正是线上那 5 秒超时的等价物
    return {
      ok: false,
      latency: Date.now() - startedAt,
      reason: e.name === 'AbortError' ? `超时 ${TIMEOUT_MS}ms` : String(e.message ?? e),
      tokens: 0,
    }
  } finally {
    clearTimeout(timer)
  }
}

const latencies = []
const failures = []
let totalTokens = 0
let ok = 0

for (let round = 1; round <= ROUNDS; round += 1) {
  const startedAt = Date.now()
  const results = await Promise.all(
    Array.from({ length: CONCURRENCY }, (_, i) => oneCall(i + round)),
  )
  const roundWall = Date.now() - startedAt
  for (const r of results) {
    latencies.push(r.latency)
    totalTokens += r.tokens
    if (r.ok) {
      ok += 1
    } else {
      failures.push(r.reason)
    }
  }
  const degraded = results.filter((r) => !r.ok).length
  console.log(
    `第 ${round} 轮：${CONCURRENCY} 并发，整批耗时 ${roundWall} ms，降级 ${degraded} 次` +
      (degraded ? `（${results.filter((r) => !r.ok).map((r) => r.reason).join(', ')}）` : ''),
  )
}

const total = CONCURRENCY * ROUNDS
const degradeRate = (total - ok) / total
latencies.sort((a, b) => a - b)
const p = (q) => latencies[Math.min(latencies.length - 1, Math.floor(latencies.length * q))]

console.log('')
console.log(`成功      ${ok} / ${total}`)
console.log(`降级      ${total - ok} / ${total}（${(degradeRate * 100).toFixed(1)}%）`)
console.log(`延迟      p50 ${p(0.5)}ms  p95 ${p(0.95)}ms  max ${latencies[latencies.length - 1]}ms`)
console.log(`token     约 ${totalTokens}（本次真实消耗）`)
console.log('')

ck(
  `降级率 ${(degradeRate * 100).toFixed(1)}% 低于阈值 ${(DEGRADE_RATE_LIMIT * 100).toFixed(0)}%`,
  degradeRate < DEGRADE_RATE_LIMIT,
  failures.length ? `降级原因：${[...new Set(failures)].join(', ')}` : '',
)
ck(
  `无一次因 ${TIMEOUT_MS}ms 超时而降级`,
  !failures.some((r) => r.startsWith('超时')),
  failures.filter((r) => r.startsWith('超时')).length + ' 次超时——说明线上 5 秒这条线在并发下不够用',
)
ck(
  `p95 延迟 ${p(0.95)}ms 离 ${TIMEOUT_MS}ms 超时线有 2 倍以上余量`,
  p(0.95) * 2 < TIMEOUT_MS,
  `p95=${p(0.95)}ms，超时线=${TIMEOUT_MS}ms`,
)

console.log(`\n${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)