/**
 * 检索侧的对话历史与按需再检索（批次 12b）。
 *
 * 这一批修的是两处「检索看不见上下文」的病，两处都只在多轮或证据不足时才显形：
 *
 *   一、指代追问 —— 「那它呢」字面上没有实体。改写必须发生在**检索侧**（RAG 检索、
 *       情节记忆召回、意图路由），回答侧本来就带完整历史。所以要验的不是「模型答得
 *       像不像懂上下文」，而是「检索这一轮到底拿什么句子去查的」——看 retrievalQuery。
 *   二、按需再检索 —— 入口只检索一次用户原话，多跳问题的后半跳没人问。工具在册不等于
 *       会被调用：WEAK / NONE 分支此前给的是「如实说没查到」这份自洽的行动方案，
 *       模型照着办很合理，实测一轮 ReAct 都没触发过。
 *
 * 第四条还钉住了一个实测到的假动作：模型没调工具，却回答「我已尝试用更具体的检索词
 * （如"宠物食品 召回"）再次查询，仍无匹配条目」——用户读到一次不存在的检索过程。
 * 提示词已经收紧，这里用机器判据兜底：**说查过，就必须真查过**。
 *
 * 前置条件：后端九个服务已启动。
 * 用法：node scripts/verify-agent-retrieval.mjs
 */

const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const LOG = process.env.VERIFY_AI_LOG ?? '../logs/logs-local/ai-service.log'

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

const session = await (async () => {
  const res = await fetch(`${GW}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: '123456' }),
  })
  const body = await res.json()
  if (body.code !== 200) throw new Error(`登录失败：${body.msg}（后端起了吗）`)
  return body.data
})()

async function chat(sessionId, message) {
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

/** 切片标题/位置串拼在一起，用于判断这一轮命中是不是同一批文档 */
const titlesOf = (data) => (data.knowledge ?? []).map((k) => `${k.title ?? ''}${k.position ?? ''}`).join(' | ')
const toolsOf = (data) => (data.toolCalls ?? []).map((t) => t.tool)

// ── 复刻生产侧 CitationVerifier 的标题口径（collectTitles + collectToolTitles）──
// 判「这句话引用的文档名平台声明过没有」必须用同一把尺子，否则会把
// 「模型提到一个平台从未声明的文档名后被剔除」也算成误伤——那恰恰是闸门该干的活
const TITLE = /《([^》]{1,60})》/g
const norm = (s) => s.replace(/\s+/g, '')
const titlesIn = (text) => [...String(text ?? '').matchAll(TITLE)].map((m) => norm(m[1]))

const citableOf = (d) => {
  const set = new Set()
  for (const k of d.knowledge ?? []) {
    if (k.title) set.add(norm(k.title))
    for (const t of [...titlesIn(k.title), ...titlesIn(k.position)]) set.add(t)
  }
  for (const t of d.toolCalls ?? []) {
    for (const line of String(t.output ?? '').split('\n')) {
      const at = line.indexOf('出处：')
      if (at < 0) continue
      const rest = line.slice(at + 3)
      const quote = rest.search(/[「“"]/) // 引文里的书名不是平台的出处
      for (const x of titlesIn(quote < 0 ? rest : rest.slice(0, quote))) set.add(x)
    }
  }
  return set
}

/**
 * 剔除是逐句做的，而回答的结构单位是块——某个标题下唯一的正文被抠掉后，
 * 用户看到的是「⚠️ 注意：」下面直接跟着「✅ 建议：」，一个空壳标题。
 * 这比留着那句没出处的话更糟：读者会以为平台确实有这么一段注意事项，只是没渲染出来。
 */
const orphanHeading = (text) => {
  const lines = String(text ?? '').split('\n')
  const isHeading = (l) => l.length > 0 && l.length <= 30 && /[:：][*_]*$/.test(l)
  for (let i = 0; i < lines.length; i++) {
    if (!isHeading(lines[i].trim())) continue
    let j = i + 1
    while (j < lines.length && !lines[j].trim()) j += 1
    if (j >= lines.length || isHeading(lines[j].trim())) return lines[i].trim()
  }
  return null
}

// ─────────── 一、首轮：没有可消解的指代，不该改写 ───────────
console.log('\n一、首轮提问（无历史，不应改写）')
const s1 = `verify-retrieval-${Date.now()}`
const first = await chat(s1, '维生素 D3 每天吃多少合适？')
console.log(`  证据门=${first.evidenceLevel} 改写句=${JSON.stringify(first.retrievalQuery)}`)
console.log(`  命中：${titlesOf(first).slice(0, 120)}`)

ck('首轮检索到了知识依据', (first.knowledge ?? []).length > 0,
  `证据门=${first.evidenceLevel}（知识库没索引上？检索链路断了？）`)
ck('首轮不做改写', !first.retrievalQuery,
  `首轮没有可消解的指代，改写是纯浪费：收到 ${JSON.stringify(first.retrievalQuery)}`)

// ─────────── 二、指代追问：改写必须作用在检索侧 ───────────
console.log('\n二、指代追问「那它和钙片能一起吃吗？」')
const second = await chat(s1, '那它和钙片能一起吃吗？')
console.log(`  改写句=${JSON.stringify(second.retrievalQuery)}`)
console.log(`  命中：${titlesOf(second).slice(0, 120)}`)

ck('下发了本轮实际使用的检索句', Boolean(second.retrievalQuery),
  'retrievalQuery 为空 = 检索侧拿「那它和钙片能一起吃吗？」去查了，字面上没有实体')
ck('改写句补全了代词指向的实体', /维生素\s*D3|维生素D3/i.test(second.retrievalQuery ?? ''),
  `改写句里没有首轮的实体：${JSON.stringify(second.retrievalQuery)}`)
ck('改写句不是原话', second.retrievalQuery !== '那它和钙片能一起吃吗？')
ck('追问的证据仍是同一批文档', /维生素\s*D3/.test(titlesOf(second)),
  `命中的是：${titlesOf(second).slice(0, 160)}`)

// ─────────── 三、日志证据：改写在链路上真的发生过 ───────────
// 响应字段只能证明「回答时认为查过什么」，日志才能证明「检索真的用了它」
console.log('\n三、日志里的改写记录')
const { readFileSync } = await import('node:fs')
const { fileURLToPath } = await import('node:url')
const { dirname, resolve } = await import('node:path')
let logText = ''
for (const p of [LOG, resolve(dirname(fileURLToPath(import.meta.url)), '../..', LOG)]) {
  try {
    // 只取尾部：日志会一直长，而这里要找的是刚刚那几轮
    logText = readFileSync(p, 'utf8').slice(-4_000_000)
    break
  } catch {
    /* 换下一个候选路径 */
  }
}
if (!logText) {
  console.log(`  \x1b[33mSKIP\x1b[0m 读不到日志 ${LOG}——跳过这一节（服务在别的机器上时属正常）`)
} else {
  const tail = logText
  const rewritten = tail.includes('[QueryRewrite]')
  ck('ai-service 日志里有 [QueryRewrite] 记录', rewritten,
    '改写没走到日志这一层（模型不支持推理？改写异常退回原句？）')
  if (rewritten) {
    const line = tail.split('\n').filter((l) => l.includes('[QueryRewrite]')).pop()
    console.log(`  ${line.trim().slice(0, 200)}`)
  }
}

// ─────────── 四、按需再检索：证据不足时要真的再查一次 ───────────
// 问一个知识库里没有的类目：入口检索必然不足（WEAK），模型只有两条路——换词再查一次，
// 或直接拒答。给三次机会取其一：单次是否触发仍受模型判断影响，但「一次都不触发」
// 说明提示词里那条出路又退回了可选项（改造前实测 2/6，就是这个症状）
console.log('\n四、证据不足时的按需再检索')
const s2 = `verify-retrieval-tool-${Date.now()}`
const probeQuestions = [
  '平台对宠物食品的召回政策是怎么规定的？',
  '关于鹦鹉粮的保质期规定是什么？',
  '平台对渔具类商品的售后规则是什么？',
]
const answers = []
for (const q of probeQuestions) {
  answers.push(await chat(s2, q))
}
const withSearch = answers.filter((d) => toolsOf(d).includes('knowledge_search'))
console.log(`  再检索触发 ${withSearch.length}/${answers.length}`)
for (const d of answers) {
  console.log(`  门=${d.evidenceLevel} 工具=${JSON.stringify(toolsOf(d))} 答=${String(d.reply).replace(/\s+/g, ' ').slice(0, 90)}`)
}
const sample = withSearch[0] ?? answers[0]
for (const t of sample.toolCalls ?? []) {
  console.log(`   ↳ ${t.tool} success=${t.success} noData=${t.noData} → ${String(t.output ?? '').slice(0, 120).replace(/\n/g, ' ⏎ ')}`)
}

ck('证据不足时模型会主动再检索一次', withSearch.length > 0,
  `三次都没调用 knowledge_search（门=${answers.map((d) => d.evidenceLevel).join('/')}）——`
    + 'WEAK/NONE 分支给出的只是「如实说没查到」这份自洽方案，模型没有理由去调工具')

// 说查过 ≠ 真查过（实测踩到过：模型答「我已尝试用更具体的检索词再次查询」，轨迹为空）
const CLAIMS_SEARCH = /我已?(尝试|检索|查询|二次)|换(了)?[一-龥]{0,8}检索词|再次?(查询|检索)|重新检索/
const fabricated = answers.filter((d) => CLAIMS_SEARCH.test(String(d.reply ?? '')) && !toolsOf(d).includes('knowledge_search'))
ck('回答里没有虚构的检索动作', fabricated.length === 0,
  `${fabricated.length} 条回答声称查过而工具轨迹为空——用户读到的是一次不存在的检索过程`)

// ─────────── 五、工具检索到的《文档名》引用不被误伤 ───────────
// 工具片段没有 [n] 编号可标，只能写《文档名》；引用校验只采信「出处：」行声明过的标题。
// 这一步退化，表现是模型规规矩矩标了出处，句子却被当成编造剔除——用户看到一段被挖空的话。
//
// 判据必须与生产侧同尺：只把「引用了平台声明过的标题却仍被剔除」算误伤。
// 模型写《宠物用品类目管理规范》这类平台从未声明过的文档名后被剔除，是闸门该干的活
const all = [first, second, ...answers]
const strippedWithTitle = all.filter((d) => {
  if (!d.unsupportedStripped) return false
  const citable = citableOf(d)
  return (d.unsupportedClaims ?? []).some((s) => titlesIn(s).some((t) => citable.has(t)))
})
console.log('\n五、工具链路的《文档名》引用')
ck('没有平台声明过的《文档名》被当成无出处剔除', strippedWithTitle.length === 0,
  strippedWithTitle.map((d) => (d.unsupportedClaims ?? []).join(' / ')).join(' ｜ '))

// 剔除按句做、结构按块长：某标题下唯一的正文被抠掉后，正文里会留下一个空壳标题
const orphans = all.map((d) => orphanHeading(d.reply)).filter(Boolean)
ck('剔除之后正文里没有留下空标题', orphans.length === 0,
  `孤儿标题：${orphans.join(' ｜ ')}——用户会以为平台确实有这么一段内容，只是没渲染出来`)

// 「整篇无依据」只该落在「没有引用、也没有工具记录」的回答上（判据见 CitationVerifier）
const mislabeled = all.filter(
  (d) => d.ungrounded === true && /《[^》]{1,60}》/.test(String(d.reply ?? '')),
)
ck('「整篇无依据」不会误标带出处的回答', mislabeled.length === 0,
  `${mislabeled.length} 条回答里有《文档名》出处，却被判成整篇无依据——用户会看到一句不该出现的免责声明`)

console.log(`\n${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
