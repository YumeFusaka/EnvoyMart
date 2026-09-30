/**
 * 采集「回答质量评测」夹具 —— 用真实模型跑一遍标注问题集，把答案与证据冻结成 JSON。
 *
 * 为什么要有这个脚本：四项质量指标（幻觉率 / 引用准确率 / 拒答准确率 / 多跳命中率）
 * 要进 CI 门禁，而 CI 里没有模型、也没有知识库。做法是把**真实运行的输出**（答案 +
 * 本轮证据 + 证据门判定）冻结成夹具，CI 里重放校验链（CitationVerifier /
 * EvidenceGate），测的是判定逻辑的回归；真实模型质量另有 live 入口
 * （`POST /ai/admin/eval/grounding/run`），用同一套判定算同一组指标。
 *
 * 两件事必须说清楚，否则这份夹具会被读成它没有的意思：
 *   1. **答案不是标准答案**，是某一时刻模型的真实输出。它可能有错——这正是它作为
 *      回归基线的价值：判定链在这些真实输出上给出什么分数，改变判定逻辑时分数动了没有。
 *   2. **夹具要在语料换代 / 提示词大改之后重新采集**，否则它测的是"上一版"的答案。
 *
 * 前置条件：9 个服务全部在跑（`backend/run-local.sh all`），网关在 8080。
 * 运行：`node frontend/scripts/capture-grounding-fixtures.mjs [--dry]`
 * 产物：`backend/agent-core/src/main/resources/eval/grounding-fixtures.json`
 *   —— 这个文件进仓库（是夹具，不是运行期产物）；本脚本**不在 CI 跑**。
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const OUT = resolve(HERE, '../../backend/agent-core/src/main/resources/eval/grounding-fixtures.json')
const GW = process.env.ENVOYMART_GW ?? 'http://localhost:8080'
const DRY = process.argv.includes('--dry')

/**
 * 采集时用的是哪个模型 —— 报告页要跟着数字一起显示它，所以不能写"见配置文件"。
 * 顺序：环境变量 → backend/.env.local（服务实际读的那个文件）→ unknown。
 * 只取 LLM_MODEL 一行，不碰同文件里的任何密钥。
 */
function resolveModel() {
  if (process.env.LLM_MODEL) {
    return process.env.LLM_MODEL
  }
  try {
    const env = readFileSync(resolve(HERE, '../../backend/.env.local'), 'utf8')
    const line = env.split('\n').find((l) => /^\s*(export\s+)?LLM_MODEL=/.test(l))
    return line
      ? line.slice(line.indexOf('=') + 1).trim().replace(/^["']|["']$/g, '')
      : 'unknown'
  } catch {
    return 'unknown'
  }
}

/**
 * 标注问题集。
 *
 * - `kind`：ANSWERABLE（语料里有据可答）/ UNANSWERABLE（语料里没有，该拒答）/
 *   MULTI_HOP（结论要跨两篇以上文档才成立）
 * - `expectRefuse`：拒答门的期望判定。门判 NONE 或 WEAK 记为"拒答侧"——
 *   两者的区别只是"什么都没召回到"还是"召回到了但相关度不够"，
 *   对用户都是"平台没有权威依据可给"。
 * - `mustMention`：**锚点词，逐字取自事实源文档**（不是从模型答案里抄的）。
 *   引用准确率靠它判定：句子里出现锚点，它标注的那条证据里就必须逐字含这个串——
 *   否则就是"引用指错了地方"。多跳命中率也靠它：结论要点必须全部落到答案里。
 */
const CASES = [
  // —— 单跳：语料里明确写着答案，用来测"该答的答了没有、引的准不准" ——
  {
    id: 'G-01',
    question: '保健食品的标签上必须标注哪句警示用语？',
    kind: 'ANSWERABLE',
    expectRefuse: false,
    mustMention: ['不能代替药物'],
  },
  {
    id: 'G-02',
    question: '平台支持几天无理由退货？从哪天开始算？',
    kind: 'ANSWERABLE',
    expectRefuse: false,
    mustMention: ['七日', '次日零时'],
  },
  {
    id: 'G-03',
    question: '退货的运费由谁承担？',
    kind: 'ANSWERABLE',
    expectRefuse: false,
    mustMention: ['运费'],
  },
  {
    id: 'G-04',
    question: '退款一般多久到账？',
    kind: 'ANSWERABLE',
    expectRefuse: false,
    mustMention: ['工作日'],
  },
  {
    id: 'G-05',
    question: '维生素 D3 软胶囊每天吃多少？',
    kind: 'ANSWERABLE',
    expectRefuse: false,
    mustMention: ['国际单位', 'IU'],
  },
  {
    id: 'G-06',
    question: '铁叶酸片每片含多少铁和叶酸？',
    kind: 'ANSWERABLE',
    expectRefuse: false,
    mustMention: ['10 毫克', '400 微克'],
  },
  {
    id: 'G-07',
    question: '益生菌冻干粉开封以后怎么贮存？',
    kind: 'ANSWERABLE',
    expectRefuse: false,
    mustMention: ['冷藏'],
  },
  {
    id: 'G-08',
    question: '乳清蛋白粉一次建议吃多少？',
    kind: 'ANSWERABLE',
    expectRefuse: false,
    mustMention: ['克'],
  },
  {
    id: 'G-09',
    question: '哪些商品不适用七天无理由退货？',
    kind: 'ANSWERABLE',
    expectRefuse: false,
    mustMention: ['拆封', '特殊医学用途配方食品'],
  },
  {
    id: 'G-10',
    question: '会员等级是怎么划分的？不同等级有什么权益？',
    kind: 'ANSWERABLE',
    expectRefuse: false,
    mustMention: ['会员'],
  },
  {
    id: 'G-11',
    question: '优惠券可以叠加使用吗？',
    kind: 'ANSWERABLE',
    expectRefuse: false,
    mustMention: ['叠加'],
  },
  {
    id: 'G-12',
    question: '快递签收的时候发现包装破损怎么办？',
    kind: 'ANSWERABLE',
    expectRefuse: false,
    mustMention: ['签收'],
  },

  // —— 多跳：结论必须跨文档成立，单看任何一篇都答不全 ——
  {
    id: 'G-13',
    question: '我在吃富马酸亚铁补铁，同时还在吃左旋多巴，会有相互作用吗？',
    kind: 'MULTI_HOP',
    expectRefuse: false,
    mustMention: ['左旋多巴', '2 小时'],
  },
  {
    id: 'G-14',
    question: '维生素 D 的每日可耐受最高摄入量是多少？我在吃维生素 D3 软胶囊，会超量吗？',
    kind: 'MULTI_HOP',
    expectRefuse: false,
    // 只留数值形式：「2000IU」与「国际单位」是同一要点的两种写法（文档里两种都有），
    // 两个都要求等于考词汇而不是考能力——多跳命中率要测的是"要点有没有跨文档给全"
    mustMention: ['2000IU'],
  },
  {
    id: 'G-15',
    question: '我买的维生素 D3 软胶囊已经拆封了，还能七天无理由退货吗？',
    kind: 'MULTI_HOP',
    expectRefuse: false,
    mustMention: ['拆封'],
  },
  {
    id: 'G-16',
    question: '哺乳期女性在吃深海鱼油，同时需要吃抗凝药，说明书里有什么提示？',
    kind: 'MULTI_HOP',
    expectRefuse: false,
    mustMention: ['华法林', '出血'],
  },
  {
    id: 'G-17',
    question: '我在补钙，同时需要吃抗生素，两者要怎么安排时间？',
    kind: 'MULTI_HOP',
    expectRefuse: false,
    mustMention: ['2 小时', '抗生素'],
  },
  {
    id: 'G-18',
    question: '备孕女性每天需要多少叶酸？最高不能超过多少？',
    kind: 'MULTI_HOP',
    expectRefuse: false,
    mustMention: ['400 微克', '1000 微克'],
  },

  // —— 库外：语料里根本没有，必须拒答而不是编 ——
  {
    id: 'G-19',
    question: '蓝牙耳机怎么连接手机？',
    kind: 'UNANSWERABLE',
    expectRefuse: true,
    mustMention: [],
  },
  {
    id: 'G-20',
    question: '你们平台支持比特币支付吗？',
    kind: 'UNANSWERABLE',
    expectRefuse: true,
    mustMention: [],
  },
  {
    id: 'G-21',
    question: 'iPhone 16 Pro 的官方保修政策是什么？',
    kind: 'UNANSWERABLE',
    expectRefuse: true,
    mustMention: [],
  },
  {
    id: 'G-22',
    question: '你们家地板清洁剂的配方成分是什么？',
    kind: 'UNANSWERABLE',
    expectRefuse: true,
    mustMention: [],
  },
  {
    id: 'G-23',
    question: '火星旅行套餐的退款政策是怎么规定的？',
    kind: 'UNANSWERABLE',
    expectRefuse: true,
    mustMention: [],
  },
  {
    id: 'G-24',
    question: '帮我预约下周一到三甲医院看内分泌科，要挂专家号。',
    kind: 'UNANSWERABLE',
    expectRefuse: true,
    mustMention: [],
  },
]

async function apiLogin(username, password) {
  const res = await fetch(`${GW}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const body = await res.json()
  if (body.code !== 200) throw new Error(`登录失败：${username} -> ${body.code} ${body.msg}`)
  return body.data
}

async function chat(token, sessionId, message) {
  const started = Date.now()
  const res = await fetch(`${GW}/ai/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ sessionId, message, approved: false }),
  })
  const latencyMs = Date.now() - started
  const body = await res.json()
  if (body.code !== 200) throw new Error(`对话失败：${message} -> ${body.code} ${body.msg}`)
  return { data: body.data, latencyMs }
}

/** 冻结成夹具条目：只留判定链需要的字段，去掉了 id/用量这类每次都会变的东西 */
function toFixtureCase(spec, data, latencyMs) {
  return {
    id: spec.id,
    question: spec.question,
    kind: spec.kind,
    expectRefuse: spec.expectRefuse,
    mustMention: spec.mustMention,
    evidence: (data.knowledge ?? []).map((chunk) => ({
      chunkId: chunk.chunkId,
      docId: chunk.docId,
      title: chunk.title,
      source: chunk.source,
      score: chunk.score,
      reranked: chunk.reranked,
      content: chunk.content,
    })),
    answer: data.reply ?? '',
    evidenceLevel: data.evidenceLevel ?? null,
    // 工具路径的判据：跑过工具的那一轮，事实来自工具返回，没有引用是正常的
    toolEvidence: (data.toolCalls ?? []).length > 0,
    unsupportedClaims: data.unsupportedClaims ?? [],
    ungrounded: Boolean(data.ungrounded),
    latencyMs,
  }
}

const session = await apiLogin('alice', '123456')
console.log(`[采集] 登录 ok，user=${session.profile?.username ?? 'alice'}，共 ${CASES.length} 条用例`)

const stamp = Date.now()
const results = []
for (const [index, spec] of CASES.entries()) {
  const sessionId = `eval-grounding-${stamp}-${index + 1}`
  try {
    const { data, latencyMs } = await chat(session.token, sessionId, spec.question)
    const item = toFixtureCase(spec, data, latencyMs)
    results.push(item)
    console.log(
      `[${spec.id}] ${spec.kind.padEnd(11)} level=${String(item.evidenceLevel).padEnd(10)} ` +
        `证据=${String(item.evidence.length).padStart(2)} 未支撑=${item.unsupportedClaims.length} ` +
        `无依据=${item.ungrounded ? 'Y' : 'n'} ${latencyMs}ms | ${spec.question}`,
    )
  } catch (e) {
    // 采集失败要立刻炸：少一条用例的夹具是"看起来完整"的那种坏
    console.error(`[${spec.id}] 采集失败：${e.message}`)
    process.exitCode = 1
    break
  }
}

if (process.exitCode) {
  console.error(`[采集] 中断，已采集 ${results.length}/${CASES.length} 条，未写文件`)
} else if (DRY) {
  console.log(`[采集] --dry：${results.length} 条全部成功，未写文件`)
} else {
  const fixture = {
    capturedAt: new Date().toISOString(),
    model: resolveModel(),
    note:
      '真实模型输出快照，用于回答质量回归门禁。答案不是标准答案，是某一时刻的真实输出；' +
      '语料换代或提示词大改之后要重新采集（frontend/scripts/capture-grounding-fixtures.mjs）。',
    cases: results,
  }
  writeFileSync(OUT, `${JSON.stringify(fixture, null, 2)}\n`, 'utf8')
  console.log(`[采集] 写入 ${OUT}（${results.length} 条）`)
}
