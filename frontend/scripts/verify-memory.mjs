/**
 * 长期记忆的类型与时间落库（批次 7-E）。
 *
 * 记忆条目的失效是**双重静默**的：类型丢了不会报错，只会让「偏好不参与淘汰」这条策略
 * 失去依据；时间丢了也不报错，只会让所有历史条目都显得是「刚刚发生的」。
 * 两种错都只在很久以后、以「怎么把用户的长期偏好弄丢了」的形式显形，
 * 而那时已经查不出是哪一步丢的。所以这里在写入侧就把它钉住：
 *
 *   一、聊出长期偏好 —— 三轮对话把「学生党」「乳糖不耐受」这类长期陈述喂进去。
 *   二、条目进了库 —— 抽出来的内容确实落到了向量库，而不是只留在内存队列里。
 *   三、类型与时间随条目一起落库 —— metadata 里 type 与 timestamp 都在，且时间合理。
 *   四、偏好确实被识别出来了 —— 抽取器区分了「用户是什么样的人」与「发生过什么事」。
 *
 * 前置条件：后端九个服务已启动，Milvus 在 19530（REST 与 gRPC 同端口）。
 * 用法：node scripts/verify-memory.mjs
 */

const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const MILVUS = process.env.VERIFY_MILVUS ?? 'http://127.0.0.1:19530'
const COLLECTION = 'envoymart_memory'

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

/**
 * 每轮**新注册一个用户**来喂记忆，而不是用 admin。
 * <p>
 * 这个脚本说的每句话都是「长期特征」（学生党 / 预算 200 / 乳糖不耐受）——它们会被抽取成
 * 画像与长期记忆，**在之后的每一次对话里生效**。挂在共用账号上（原先就是 admin）等于给
 * 后续所有用该账号的脚本与演示注入一份「该用户乳糖不耐受、预算 200」的背景，
 * 实测已经打红过 verify-tool-trace：一句「推荐几款乳清蛋白粉」被个性化成了
 * 「≤200 元 + 不含乳糖」，商品全被筛空、trace 标成 noData。
 * **验收夹具的可见面不只在本轮**——记忆是跨会话的，用户就必须是未污染的。
 */
const session = await (async () => {
  const username = `mem${Date.now().toString().slice(-9)}`
  const registered = await fetch(`${GW}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: 'verify12345', nickname: '记忆验收用户' }),
  }).then((r) => r.json())
  if (registered.code !== 200) {
    throw new Error(`注册记忆验收用户失败：${registered.msg}`)
  }
  const res = await fetch(`${GW}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: 'verify12345' }),
  })
  const body = await res.json()
  if (body.code !== 200) throw new Error(`登录失败：${body.msg}（后端起了吗）`)
  return body.data
})()

/** 这一批条目的归属。网关把登录用户注入请求头，记忆按它隔离 */
const userId = String(session.user?.id ?? '')
if (!userId) {
  console.error(`FATAL: 登录响应里没有用户主键，没法按 docId 过滤记忆条目：${JSON.stringify(session.user)}`)
  process.exit(1)
}
const sessionId = `verify-memory-${Date.now()}`

async function chat(message) {
  const res = await fetch(`${GW}/ai/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${session.token}`,
    },
    body: JSON.stringify({ sessionId, message }),
  })
  const body = await res.json()
  if (body.code !== 200) throw new Error(`对话失败：${body.msg}`)
  return body.data
}

// ─────────── 一、聊出一个长期偏好 ───────────
// 抽取每 3 轮跑一次，所以这里必须凑满 3 轮；前两轮里那两句是刻意说的
// 「长期特征」，正常抽取器应当把它们归到 PREFERENCE 而不是普通事件
console.log('\n一、三轮对话（第三轮结束时触发抽取）')
await chat('我是学生党，预算比较紧张，一般只买 200 元以内的东西')
await chat('另外我对乳糖不耐受，喝了含乳糖的会不舒服')
const last = await chat('帮我推荐一款蛋白粉吧')
console.log(`  最后一轮回复来源标记：${last.evidenceLevel ?? '（未透出）'}`)

/** Milvus 的 JSON 字段经 REST 返回时是**字符串**，不是对象——不解析就会一路取到 undefined */
function metadataOf(row) {
  try {
    return typeof row.metadata === 'string' ? JSON.parse(row.metadata) : (row.metadata ?? {})
  } catch {
    return {}
  }
}

// 抽取是对话末尾同步做的，但向量写入是异步落地
const deadline = Date.now() + 30_000
let stored = []
while (Date.now() < deadline) {
  const res = await fetch(`${MILVUS}/v2/vectordb/entities/query`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      collectionName: COLLECTION,
      filter: 'id != ""',
      outputFields: ['id', 'text', 'metadata'],
      limit: 200,
    }),
  })
  const body = await res.json()
  const all = body.data ?? []
  stored = all.map((row) => ({ ...row, meta: metadataOf(row) })).filter((row) => row.meta.docId === userId)
  if (stored.length > 0) break
  await new Promise((r) => setTimeout(r, 2000))
}

// ─────────── 二、条目落了库 ───────────
console.log('\n二、条目落库')
ck('该用户的记忆条目进了向量库', stored.length > 0,
  `envoymart_memory 里没有 docId=${userId} 的条目（抽取没触发？Milvus 没连上？）`)
if (stored.length === 0) {
  console.log(`\n${pass} 通过 / ${fail} 失败`)
  process.exit(1)
}
console.log(`  该用户当前共 ${stored.length} 条：`)
for (const row of stored) {
  console.log(`    [${row.meta.type ?? '无类型'}] ${String(row.text).slice(0, 40)}`)
}

// ─────────── 三、类型与时间随条目落库 ───────────
// 这一条是本次改造的核心：只写正文时，读回来的条目一律是「此刻发生的一件普通事」，
// 偏好不参与淘汰的策略随之失效，且没有任何一处会报错
console.log('\n三、类型与时间随条目一起落库')
const missingType = stored.filter((row) => !row.meta.type)
const missingTime = stored.filter((row) => !row.meta.timestamp)
ck('每条都带着类型', missingType.length === 0,
  `${missingType.length} 条没有 type（读回来会被当成普通事件）`)
ck('每条都带着写入时间', missingTime.length === 0,
  `${missingTime.length} 条没有 timestamp（读回来会被当成「此刻」）`)

// 下界取项目纪元（2026-01-01）而不是「最近一天」：这条要抓的是单位错（秒 / 微秒，
// 差 3 个数量级）与跑飞的时钟（未来时刻），不是「有多新」——记忆本来就跨天保留，
// 拿 24 小时窗口去卡，隔夜留下的条目会全部被误判成坏值（实测踩到：09-29 的条目
// 在 10-01 的回归里报红，看起来像时间戳坏了，其实是断言把「历史」当成了「异常」）
const now = Date.now()
const EPOCH = Date.UTC(2026, 0, 1)
const badTime = stored.filter((row) => {
  const t = Number(row.meta.timestamp)
  return !Number.isFinite(t) || t > now + 60_000 || t < EPOCH
})
ck('时间戳是合理的毫秒时刻', badTime.length === 0,
  badTime.map((r) => r.meta.timestamp).join('/'))

const known = new Set(['PREFERENCE', 'FACT', 'SUMMARY'])
const unknownType = stored.filter((row) => !known.has(row.meta.type))
ck('类型都是已知取值', unknownType.length === 0,
  unknownType.map((r) => r.meta.type).join('/'))

// ─────────── 四、长期偏好被识别出来了 ───────────
// 这一条依赖模型的判断，失败不一定是代码错——但要让它可见：如果从来抽不出 PREFERENCE，
// 「偏好不参与淘汰」这条策略就等于没有输入
console.log('\n四、抽取器区分了偏好与事件')
const preferences = stored.filter((row) => row.meta.type === 'PREFERENCE')
ck('至少抽出一条 PREFERENCE', preferences.length > 0,
  `这次全是 FACT（${stored.length} 条）。看上面二、打印的正文：若确有「学生党/乳糖不耐受」这类`
    + '长期陈述，说明抽取提示词的类型判断没生效')

console.log(`\n${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
