/**
 * 商品推荐链路（批次 15）的验收 —— 多条件检索的那条路。
 *
 * <b>这里验的不是模型，是模型脚下那块地。</b>模型的工作是把一句人话拆进
 * query / 价格 / attributes / excludeKeywords 四个参数；它拆完之后真正执行的是
 * `/products/search` 这条 ES 查询。拆得再对，这条查询本身少了 AND、少了 mustNot、
 * 或者把区间判反，用户看到的就是「筛了等于没筛」而接口一路 200。
 * 所以下面每一条都锚在一个**能被证伪的集合关系**上，而不是「返回了东西」：
 *
 *   一、**逐条核对**：带条件的结果集 == 全集里逐条算出来合格的那些。
 *       抽查看不出「有两条不该在」，机器版的逐条核对才看得出。
 *   二、**对照实验**：同一句话整串交给旧的 MySQL 子串匹配，返回 0 条 ——
 *       证明「不是平台上没这东西，是那条路找不到」。
 *   三、**否定条件严格做差**：差集必须恰好是「可见文本含该词」的那些，
 *       多一条少一条都报出来。
 *   四、**属性筛选是 AND 不是 OR**：两个属性的结果集必须等于各自结果集的交集。
 *       OR 的写法不报任何错，只是把「同时满足」讲成了「满足任意一个」。
 *   五、**跨词界复合词**（钙片 vs 碳酸钙 D3 咀嚼片）：这条曾经恒返回 0，
 *       现在能命中，且命中的那条名字里**没有**连续的「钙片」二字 ——
 *       这正是它当初搜不到的原因，也是这条断言不会被单字巧合蒙混过去的地方。
 *   六、**图谱那半边的素材**：推荐出的商品经 `interaction_check` 能查到与药物的
 *       相互作用，且带着出处引用。模型有没有把这句话说给用户，属于回答质量，
 *       由 `verify-eval` 那一套看 —— 这一层只保证「素材是齐的」。
 *
 *   八、**模型那一段**（说一句人话 → 工具调用 → 回答）。上面七节验的是它脚下那块地，
 *       这一节验它真的踩上去了。两者都必要，且**不能互相代替**：曾经出现过
 *       「七节全绿、模型这一节全红」——`PageResult` 没有无参构造，
 *       工具从「按编号查一个商品」改走「分页检索」之后，Java 侧的应答就解不开了，
 *       而 HTTP 那七节用的是 JS 的 JSON.parse，一点异常都没有；用户看到的是
 *       模型礼貌地回一句「商品服务暂时不可用」。**同一份 JSON，两条路一个能读一个读不了。**
 *       这一节只调一次模型（两三句断言），跑一次几十秒。
 *
 * 数据影响：前七节只读（搜索会写入热门词 Redis ZSET，用的都是真实商品词，
 * 不是编造词 —— 编造词几轮跑下来会把首页「热门搜索」占满）；
 * 第八节会真的发起一轮对话，因而给 admin 落一条会话记录。
 *
 * 前置条件：九个服务 + 前端（仅商品与图谱这几个接口，用不到浏览器）已启动。
 *
 * 用法：
 *   node scripts/verify-recommend.mjs
 */
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'

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

/** 走网关，和前端、和 ai-service 的工具走的是同一条路 */
async function search(params, path = '/products/search') {
  const url = new URL(GW + path)
  for (const [key, value] of Object.entries(params)) {
    if (Array.isArray(value)) {
      value.forEach((single) => url.searchParams.append(key, single))
    } else if (value !== undefined && value !== null && value !== '') {
      url.searchParams.set(key, value)
    }
  }
  const res = await fetch(url)
  if (!res.ok) {
    throw new Error(`${path} 返回 ${res.status}`)
  }
  const body = await res.json()
  return body.data ?? { records: [], total: 0 }
}

const ids = (page) => page.records.map((p) => p.id).sort((a, b) => a - b)
const same = (a, b) => a.length === b.length && a.every((x, i) => x === b[i])
const 中文 = (s) => encodeURIComponent(s)

/** 详情里的参数是「名字 + 值」，多选的值是逗号分隔的一串 */
async function attributesOf(spuId) {
  const res = await fetch(`${GW}/products/${spuId}`)
  const detail = (await res.json()).data
  return detail.attributes ?? []
}
function hasAttribute(attributes, name, value) {
  return attributes.some(
    (a) => a.name === name && String(a.value).split(/[,，]/).map((s) => s.trim()).includes(value),
  )
}

console.log('\n批次 15 · 多条件商品推荐\n')

// ─────────── 一、多条件逐条核对 ───────────
console.log('【多条件：价格 + 正向属性 + 否定式硬条件】')
const 条件 = {
  keyword: '钙片',
  maxPrice: 20000, // 200 元
  attributes: ['适用人群:孕妇', '是否含乳糖:不含'],
}
const 带条件 = await search({ ...条件, size: 50 })
const 全集 = await search({ keyword: '钙片', size: 50 })

ck('「孕妇能吃的钙片，200 以内，不含乳糖」有结果', 带条件.records.length > 0, `total=${带条件.total}`)

// 逐条核对：每一条都去查它的详情，确认价格与两个属性都成立
const 违例 = []
for (const product of 带条件.records) {
  const attributes = await attributesOf(product.id)
  const reasons = []
  if (product.minPrice > 20000) reasons.push(`最低价 ${product.minPrice / 100} 元 > 200`)
  if (!hasAttribute(attributes, '适用人群', '孕妇')) reasons.push('适用人群里没有孕妇')
  if (!hasAttribute(attributes, '是否含乳糖', '不含')) reasons.push('不是「不含乳糖」')
  if (reasons.length) 违例.push(`SPU${product.id} ${product.name}：${reasons.join('；')}`)
}
ck('结果里每一条都满足全部条件（逐条查详情核对，不是抽查）', 违例.length === 0, 违例.join('\n        '))

// 反向核对：全集里被筛掉的，必须至少违反一条；一条都不违反却不见了，才是真漏
const 漏掉 = []
for (const product of 全集.records) {
  const attributes = await attributesOf(product.id)
  const 合格 =
    product.minPrice <= 20000 &&
    hasAttribute(attributes, '适用人群', '孕妇') &&
    hasAttribute(attributes, '是否含乳糖', '不含')
  if (合格 && !ids(带条件).includes(product.id)) 漏掉.push(`SPU${product.id} ${product.name}`)
}
ck('全集里合格的一条都没漏', 漏掉.length === 0, 漏掉.join(' | '))

// ─────────── 二、对照实验：整串交给旧路 ───────────
console.log('\n【对照：同一句话整串下去】')
const 整串 = '孕妇能吃的钙片,200以内,不含乳糖'
const 旧路 = await search({ keyword: 整串, size: 50 }, '/products')
ck(
  '整串走 MySQL 子串匹配返回 0 条（「不是没有这东西，是那条路找不到」）',
  旧路.total === 0,
  `total=${旧路.total}：${旧路.records.map((p) => p.name).join(' | ')}`,
)
ck('拆成条件后同一条路能查到', 带条件.total > 0, `拆之前 0 条，拆之后 ${带条件.total} 条`)

// ─────────── 三、否定条件严格做差 ───────────
console.log('\n【否定条件：差集要恰好】')
for (const [关键词, 排除词] of [
  ['维生素', '软糖'],
  ['蛋白粉', '乳清'],
]) {
  const 前 = await search({ keyword: 关键词, size: 50 })
  const 后 = await search({ keyword: 关键词, excludeKeywords: 排除词, size: 50 })
  const 差集 = ids(前).filter((id) => !ids(后).includes(id))
  // 可见文本 = 名字与副标题：模型和用户都看得到的那部分。
  // 详情正文里出现的词也会被排除（后端在全部检索字段上做差），所以这里只断言
  // 「被排掉的每一条都能在可见文本里找到该词」，反方向不做强断言
  const 被排掉的 = 前.records.filter((p) => 差集.includes(p.id))
  const 说不清 = 被排掉的
    .filter((p) => !`${p.name}${p.subtitle ?? ''}`.includes(排除词))
    .map((p) => `SPU${p.id} ${p.name}`)
  ck(
    `「${关键词}」排除「${排除词}」：确实少了 ${差集.length} 条`,
    差集.length > 0,
    `排除前后都是 ${前.total} 条 —— 否定条件没有生效`,
  )
  ck(
    `被排掉的每一条都找得到「${排除词}」二字（不是误伤）`,
    说不清.length === 0,
    `这些被排掉了却看不出为什么：${说不清.join(' | ')}`,
  )
  ck(
    `剩下的没有一条漏网`,
    后.records.every((p) => !`${p.name}${p.subtitle ?? ''}`.includes(排除词)),
    后.records.filter((p) => `${p.name}${p.subtitle ?? ''}`.includes(排除词)).map((p) => p.name).join(' | '),
  )
}

// ─────────── 四、属性筛选是 AND 不是 OR ───────────
console.log('\n【属性筛选：同时满足，不是满足任意一个】')
// 探测轴要选「两侧都真实存在、交集非空且不自证」的组合：
// 「剂型:软胶囊 ∩ 适用人群:成人」= 4 条（软胶囊 4 条里 4 条都是成人款，交集=前者）。
// 曾用「剂型:软胶囊 ∩ 适用人群:孕妇」——它交集恒为 0（演示库里孕妇款全是片剂），
// 于是「交集非空」这半条断言把数据巧合当成了契约。断言要锚在契约上，不是锚在碰巧的数据上。
const 轴一 = await search({ attributes: ['剂型:软胶囊'], size: 50 })
const 轴二 = await search({ attributes: ['适用人群:成人'], size: 50 })
const 两者都要 = await search({ attributes: ['剂型:软胶囊', '适用人群:成人'], size: 50 })
const 交集 = ids(轴一).filter((id) => ids(轴二).includes(id))
ck(
  '两个属性同时给出 == 各自结果集的交集',
  same(ids(两者都要), 交集),
  `交集 [${交集}] vs 实际 [${ids(两者都要)}] —— 差的是「满足任意一个」，那是 OR`,
)
ck('两个属性各自都筛掉了一些（不然上一条是空转）',
  交集.length > 0 && 交集.length <= ids(轴一).length && 轴二.total > 交集.length,
  `软胶囊 ${轴一.total} 条、成人 ${轴二.total} 条、交集 ${交集.length} 条`)

const 不存在的值 = await search({ attributes: ['适用人群:孕夫'], size: 50 })
ck(
  '不存在的属性值筛空，而不是退回全量',
  不存在的值.total === 0,
  `total=${不存在的值.total} —— 写错的值静默失效会让用户以为「平台上没有」`,
)

// ─────────── 五、跨词界的复合词 ───────────
console.log('\n【跨词界复合词：钙片】')
const 钙片 = await search({ keyword: '钙片', size: 50 })
const 碳酸钙 = 钙片.records.find((p) => p.name.includes('碳酸钙'))
ck('「钙片」能搜到「碳酸钙 D3 咀嚼片」', Boolean(碳酸钙), `返回：${钙片.records.map((p) => p.name).join(' | ')}`)
ck(
  '而且那条的名字里没有连续的「钙片」二字（证明不是子串巧合）',
  碳酸钙 !== undefined && !碳酸钙.name.includes('钙片'),
  `名字是「${碳酸钙?.name}」`,
)

// ─────────── 六、价格区间是「有交集」不是「落在区间内」 ───────────
console.log('\n【价格区间：SPU 的价格是一个区间】')
const 区间内 = await search({ keyword: '维生素', maxPrice: 10000, size: 50 })
const 越界 = 区间内.records.filter((p) => p.minPrice > 10000)
ck('100 元以内的结果，最低价都在 100 元以内', 越界.length === 0, 越界.map((p) => `${p.name} ¥${p.minPrice / 100}`).join(' | '))
// 「碳酸钙 D3 咀嚼片」的两个规格一个在 100 元以内、一个在以上。判据写成「minPrice 落在区间内」
// 会把整条漏掉 —— 而它在列表页上看起来就是「这个商品不存在」
const 跨区间 = await search({ keyword: '碳酸钙', maxPrice: 10000, size: 50 })
const 碳酸钙跨区间 = 跨区间.records.find((p) => p.name.includes('碳酸钙'))
ck(
  '价格跨区间的商品按「有交集」保留（低价规格在区间内就该出现）',
  碳酸钙跨区间 !== undefined && 碳酸钙跨区间.maxPrice > 10000,
  碳酸钙跨区间
    ? `命中，价格区间 ${碳酸钙跨区间.minPrice / 100}–${碳酸钙跨区间.maxPrice / 100} 元`
    : `没命中，返回：${跨区间.records.map((p) => p.name).join(' | ') || '（空）'}`,
)

// ─────────── 七、图谱那半边：相互作用素材 ───────────
console.log('\n【图谱联动：推荐结果里有没有可查的相互作用】')
const 报告 = await (
  await fetch(`${GW}/knowledge/graph/interactions?items=${中文('SPU7')},${中文('华法林')}`)
).json()
const 图谱 = 报告.data
ck('图谱可用', 图谱.available === true, 图谱.note ?? '')
const 鱼油 = (图谱.items ?? []).find((i) => i.input === 'SPU7')
ck('推荐得出的商品能对上图上的实体', 鱼油?.found === true, `输入 SPU7 → found=${鱼油?.found}`)
const 风险 = 鱼油?.risks ?? []
ck('这个商品与华法林之间查得到相互作用', 风险.length > 0, `risks=${风险.length}`)
ck(
  '而且带着出处（文档 + 片段 + 原文引用）',
  风险.length > 0 && 风险.every((r) => r.docId && r.chunkId && r.quote),
  风险.map((r) => `${r.effect} @ ${r.docId}`).join(' | '),
)
// 图上没有的商品必须是「明确说没有」，不能是「有搜到东西但说不上来」。
// 判据是自洽而不是「SPU25 一定在图上」：图谱是模型从知识文档里抽出来的，
// 有没有收录取决于**有没有写过那份文档**，与商品在不在售无关 ——
// 写成「SPU25 必须 found」的话，这条断言会因为数据层的收尾而变红，
// 而红的原因与它想守的「不许编」无关（13b 的教训：脚本自己变成假红灯）
const 未收录 = await (
  await fetch(`${GW}/knowledge/graph/interactions?items=${中文('SPU25')},${中文('SPU9')}`)
).json()
const 条目 = 未收录.data.items ?? []
ck(
  '图谱没收录的商品，回的是「没有」而不是编出来的风险',
  条目.every((i) => i.found === false || (i.substances.length > 0 && i.risks.every((r) => r.docId && r.quote))),
  条目.map((i) => `${i.input}: found=${i.found} 风险${i.risks.length}条`).join(' | '),
)
ck(
  '每次询问都带「这一步做没做成」的标记',
  未收录.data.available === true && 条目.length === 2,
  条目.map((i) => `${i.input}:${i.found}`).join(' '),
)

// ─────────── 八、模型那一段：一句话 → 四个参数 → 真查到 ───────────
console.log('\n【模型端到端：一句话进去，四个条件出来】')
const 登录 = await (
  await fetch(`${GW}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: '123456' }),
  })
).json()
// 这一段的三条断言同源（都源自这一次 /ai/chat），一次模型抖动会三连红（U75）。
// 但「加一次重试」不能把失败证据抹掉：第一次请求的完整响应先留档，
// 重试成功时把它打出来——U75 当时吃亏就吃在「首次失败输出被 tail 截断，根因只能推断」。
const 提问 = '帮我找孕妇能吃的钙片，200 元以内，不含乳糖'
async function 问一次(sessionId) {
  const 响应 = await fetch(`${GW}/ai/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${登录.data.token}` },
    body: JSON.stringify({ sessionId, message: 提问 }),
  })
  return 响应.json()
}
const 首答 = await 问一次(`verify-recommend-${Date.now()}`)
let 会话 = 首答
if (!((首答.data ?? {}).toolCalls ?? []).some((t) => t.tool === 'product_search' && t.success === true)) {
  console.log('  首次未拿到成功的商品检索，重试一次；首次响应留档如下：')
  console.log(JSON.stringify(首答).slice(0, 800))
  会话 = await 问一次(`verify-recommend-retry-${Date.now()}`)
}
const 对话 = 会话.data ?? {}
const 商品调用 = (对话.toolCalls ?? []).filter((t) => t.tool === 'product_search')
const 成功的那次 = 商品调用.find((t) => t.success === true)
console.log(`  工具：${JSON.stringify(商品调用.map((t) => (t.success ? '成功' : '失败')))}`)
console.log(`  输出：${String(成功的那次?.output ?? 商品调用[0]?.output ?? '').replace(/\s+/g, ' ').slice(0, 160)}`)
console.log(`  回答：${String(对话.reply ?? '').replace(/\s+/g, ' ').slice(0, 160)}`)

ck('模型真的调到了商品检索，而且它没报失败', Boolean(成功的那次),
  `工具轨迹：${JSON.stringify(商品调用.map((t) => t.error ?? 'ok'))} —— `
    + '「调了但失败」与「没调」要分开看：前者是链路问题，后者是提示词问题')

// 断言锚在「符合全部条件的那个商品」上，而不是「模型有没有把条件写进参数」：
// 后者取决于它这一轮怎么措辞（换个说法就红），前者是事实——孕妇钙片（SPU25）
// 是全集里唯一同时满足「孕妇适用、不含乳糖、200 元以内」的那一款，
// 工具输出里出现它就说明条件真的生效了（而不是只拿关键词捞了一把）
ck('检索结果里出现了唯一同时满足三条的那款（SPU25）',
  String(成功的那次?.output ?? '').includes('SPU25'),
  `工具输出：${String(成功的那次?.output ?? '').replace(/\s+/g, ' ').slice(0, 200)}`)

// 说查不到 ≠ 真查不到。解码失败时工具会回一句「暂时不可用」，
// 模型照实转述——「链路断了」与「平台上没有」在用户眼里必须是两句话
const 说成没有 = /没有找到|没有相关|暂时不可用|稍后再试|连不上|连接异常/.test(String(对话.reply ?? ''))
ck('回答没有把「没查到」说成结果', !说成没有 || Boolean(成功的那次),
  `工具成功=${Boolean(成功的那次)}，回答：${String(对话.reply ?? '').slice(0, 160)}`)

// ─────────── 收尾 ───────────
console.log(`\n通过 ${pass} / 失败 ${fail}`)
process.exit(fail === 0 ? 0 : 1)
