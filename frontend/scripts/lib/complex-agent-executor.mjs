/** 48 场景执行器；公开 API 与 Agent、控制注入分别留证，不使用模型文本代替业务状态。 */
export const WRITE_TOOLS = new Set(['cart_add', 'cart_update', 'cart_remove', 'cart_checkout', 'order_cancel', 'coupon_receive', 'ticket_create', 'after_sale_apply'])
const KINDS = new Set(['chat', 'bind-products', 'confirm', 'repeat-confirm', 'cancel', 'api', 'restart', 'refresh', 'fault', 'feedback'])
export const ADDRESS = { receiverName: '验收用户', receiverPhone: '13800000001', receiverProvince: '上海市', receiverCity: '上海市', receiverDistrict: '浦东新区', receiverDetail: '张江路 100 号 T1 验收室' }
const ck = (name, pass, actual) => ({ name, pass: Boolean(pass), actual })
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b)
const counts = (items) => items.map(({ skuId, quantity }) => ({ skuId, quantity })).sort((a, b) => a.skuId - b.skuId)
const fail = (reason, blocked = false) => { throw Object.assign(new Error(reason), { blocked }) }
const normalizedName = (name) => String(name ?? '').normalize('NFKC').replace(/\s+/g, '').toLowerCase()

/**
 * 否定辖域：`没有/无法/查不到 … 依据|证据|规定|要求` 这一整段宾语。
 *
 * 政策事实判据要问的是「这句话在**断言**某条政策」，而被否定的宾语里照样会
 * 出现政策词——「没有查到关于退货所需材料的具体依据」整句恰恰在说"不知道"，
 * 直接对整句跑关键词会把正确拒答判成编造。剥离后，句子里独立的政策断言
 * （「但必须提供发票」「退款在 7 天内完成」）仍照常命中。
 */
const NEGATED_OBJECT_SCOPE = /(?:没有|未|缺少|查不到|找不到|无法|不能|暂无|暂时没有)[^，。；、!?]{0,30}?(?:依据|证据|资料|说明|规定|要求|结论)/g

export function prepareCase(source) {
  if (!source.contract || !source.setup || !source.steps?.length || !source.expected?.authority?.length) fail(`${source.caseId} 缺执行契约`)
  const completed = new Set()
  for (const s of source.steps) {
    if (!s.id || completed.has(s.id) || (s.dependsOn ?? []).some((id) => !completed.has(id))) fail('步骤DAG含重复ID、缺前置或未来依赖')
    completed.add(s.id)
    if (!KINDS.has(s.kind)) fail(`未实现步骤 ${s.kind}`)
    if (s.kind === 'chat' && (!s.message || !Array.isArray(s.dag))) fail('对话缺消息/工具 DAG')
    if (s.evidence === 'citation-or-refusal' && source.caseId !== 'CA-048') fail('citation-or-refusal 仅用于 CA-048 的反馈前置回答')
    if (s.kind === 'confirm' && !s.actions?.length) fail('确认缺明确动作与参数来源')
    if (s.kind === 'api' && (!s.path || !s.scope)) fail('API 缺路径/能力边界')
    if (s.kind === 'api' && (s.method ?? 'GET') !== 'GET' && !s.userAction) fail(`API写步骤 ${s.id} 缺明确用户/管理员动作记录`)
  }
  return { kind: source.contract, write: source.write, steps: source.steps, scope: source.scope, requiredControls: [...new Set(source.steps.filter((s) => ['restart', 'fault'].includes(s.kind)).map((s) => s.kind))] }
}

/** 仅解析字段与显式下标；不执行表达式，完整占位保留数值类型。 */
export function resolveValue(value, state) {
  if (Array.isArray(value)) return value.map((v) => resolveValue(v, state))
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, resolveValue(v, state)]))
  if (typeof value !== 'string') return value
  const lookup = (path) => {
    if (!/^[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*|\[\d+\])*$/.test(path)) fail(`非法绑定 ${path}`)
    let found = state
    for (const key of path.replace(/\[(\d+)\]/g, '.$1').split('.')) found = found?.[key]
    if (found === undefined || found === null) fail(`缺绑定 ${path}`)
    return found
  }
  const full = value.match(/^\{\{([^{}]+)\}\}$/)
  return full ? lookup(full[1]) : value.replace(/\{\{([^{}]+)\}\}/g, (_, path) => String(lookup(path)))
}

export function validateApproval(details, expected) {
  const actions = expected?.actions ?? expected
  if (!Array.isArray(actions) || details.length !== actions.length) fail('审批动作数不符；未提交令牌')
  const remaining = [...details]
  for (const a of actions) {
    const index = remaining.findIndex((d) => d.tool === a.tool && Object.entries(a.arguments).every(([k, v]) => String(d.arguments?.[k]) === String(v)))
    if (index < 0) fail(`审批 ${a.tool} 对象/数量/规格/地址不符；未提交令牌`)
    const d = remaining.splice(index, 1)[0]
    if (d.confirmable === false) fail('审批不可确认；未提交令牌')
    const presentation = new Set(['requestId', 'productName', 'specification', 'unitPrice', 'subtotal', 'orderNo'])
    if (Object.keys(d.arguments ?? {}).some((k) => !(k in a.arguments) && !presentation.has(k))) fail('审批出现未授权额外参数；未提交令牌')
  }
}

export async function verifyEffects(get) {
  return { capturedAt: new Date().toISOString(), cart: await get('/cart'), orders: (await get('/orders?tab=ALL&page=0&size=100')).records, coupons: await get('/coupons/mine'), tickets: (await get('/tickets?page=0&size=100')).records, afterSales: await get('/after-sales') }
}

export async function setupCase(source, ctx, result) {
  const state = { address: ADDRESS, sessionId: result.sessionId, products: [] }
  await ctx.createBuyer(source.caseId)
  const call = async (path, options = {}) => { const r = await ctx.api(path, options); result.setupEvidence.push({ path, ...r }); return r.data }
  const a = { receiverName: ADDRESS.receiverName, receiverPhone: ADDRESS.receiverPhone, province: ADDRESS.receiverProvince, city: ADDRESS.receiverCity, district: ADDRESS.receiverDistrict, detail: ADDRESS.receiverDetail, tag: '公司', isDefault: true }
  state.addressRecord = await call('/auth/addresses', { method: 'POST', body: a })
  if (source.setup.addressCount === 2) await call('/auth/addresses', { method: 'POST', body: { ...a, detail: '世纪大道 200 号 T1 验收室', tag: '家', isDefault: false } })
  for (const item of source.setup.cart ?? []) {
    const skus = await call(`/products/skus?ids=${item.skuId}`)
    if (skus.length !== 1 || skus[0].stock < item.quantity || skus[0].status !== 1 || skus[0].spuStatus !== 1) fail(`SKU ${item.skuId} 不满足初始条件`, true)
    await call('/cart/items', { method: 'POST', body: item })
  }
  state.cart = await call('/cart')
  if (source.setup.orderStatus) {
    if (!state.cart.length) fail('订单前置缺购物车 fixture')
    state.order = await call('/orders/checkout', { method: 'POST', body: { ...ADDRESS, requestId: `${result.sessionId}-setup` } })
    if (source.setup.orderStatus !== 'CREATED') {
      await call('/payments', { method: 'POST', body: { orderId: state.order.id, channel: 'MOCK', payType: 'MOCK' } })
      await call(`/payments/${state.order.id}/mock-pay`, { method: 'POST' })
      state.order = await ctx.poll(`/orders/${state.order.id}`, (o) => o.status === 'PAID')
    }
    if (['SHIPPED', 'RECEIVED'].includes(source.setup.orderStatus)) {
      await call(`/orders/admin/orders/${state.order.id}/ship`, { actor: 'admin', method: 'POST', body: { carrierCode: 'SF', carrierName: '顺丰速运', trackingNo: `T1-${state.order.id}` } })
      if (source.setup.orderStatus === 'RECEIVED') await call(`/orders/${state.order.id}/receive`, { method: 'POST' })
      state.order = await call(`/orders/${state.order.id}`)
    }
    if (state.order.status !== source.setup.orderStatus) fail('前置订单状态不符', true)
    state.orderItem = state.order.items[0]
    state.logistics = await call(`/orders/${state.order.id}/logistics`)
  }
  if (source.setup.coupon) {
    state.couponList = await call('/coupons/available')
    const available = state.couponList.filter((c) => !c.received && c.remainingCount > 0 && (!source.setup.couponType || c.type === source.setup.couponType))
    state.coupon = available[source.setup.couponIndex ?? 0]
    if (!state.coupon) fail('可领券不足，禁止替换场景目标', true)
    if (source.setup.receiveCoupon) state.userCoupon = await call(`/coupons/${state.coupon.id}/receive`, { method: 'POST' })
  }
  if (source.setup.afterSale) state.afterSale = await call('/after-sales', { method: 'POST', body: { orderItemId: state.orderItem.id, type: 'RETURN_REFUND', reason: '包装破损', qualityIssue: true } })
  if (source.setup.ticket) state.ticket = (await call('/tickets', { method: 'POST', body: { category: 'OTHER', title: '权限核验', content: '请客服说明审核状态' } })).ticket
  result.before = await verifyEffects(ctx.get)
  result.bindings = state
  return state
}

export function checkDag(step, data, state, result) {
  const calls = data.toolCalls ?? []
  const parseArguments = (call) => {
    if (call.arguments && typeof call.arguments === 'object') return call.arguments
    try { return JSON.parse(call.input) } catch { /* Java Map文本 */ }
    const input = String(call.input ?? '')
    if (!input.startsWith('{') || !input.endsWith('}')) return null
    return Object.fromEntries(input.slice(1, -1).split(/,\s*(?=[A-Za-z][A-Za-z0-9]*=)/).map((pair) => {
      const index = pair.indexOf('=')
      return index < 0 ? [pair, undefined] : [pair.slice(0, index), pair.slice(index + 1)]
    }))
  }
  const outcomeMatches = (node, call) => node.outcome === 'failure' ? call.success === false : node.outcome === 'empty' ? call.success === true && call.noData === true : call.success === true && call.noData !== true
  const nodes = (step.dag ?? []).map((node) => ({ node, args: resolveValue(node.arguments ?? {}, state) }))
  const candidates = nodes.map(({ node, args }) => calls.flatMap((call, candidateIndex) => {
      if (call.tool !== node.tool || !outcomeMatches(node, call)) return []
      const parsed = parseArguments(call)
      return parsed && Object.entries(args).every(([key, value]) => String(parsed[key]) === String(value)) ? [candidateIndex] : []
  }))
  // 增广匹配保留多重集语义；宽条件节点不能抢走唯一可满足窄条件节点的调用。
  const owners = new Map()
  const allocate = (nodeIndex, visited) => {
    for (const callIndex of candidates[nodeIndex]) {
      if (visited.has(callIndex)) continue
      visited.add(callIndex)
      if (!owners.has(callIndex) || allocate(owners.get(callIndex), visited)) {
        owners.set(callIndex, nodeIndex)
        return true
      }
    }
    return false
  }
  nodes.forEach((_, index) => allocate(index, new Set()))
  const assignments = new Map([...owners].map(([callIndex, nodeIndex]) => [nodeIndex, callIndex]))
  for (const [nodeIndex, { node, args }] of nodes.entries()) {
    const index = assignments.get(nodeIndex) ?? -1
    const matched = index >= 0 ? [calls[index]] : []
    result.assertions.push(ck(`DAG ${step.id}/${node.tool} ${node.outcome ?? 'success'}（同次调用参数联合）`, index >= 0, matched.length ? matched : calls))
    for (const [key, value] of Object.entries(args)) result.assertions.push(ck(`参数来源 ${step.id}/${node.tool}.${key}=${node.arguments[key]}`, index >= 0 && String(parseArguments(calls[index])?.[key]) === String(value), matched.length ? matched : calls.map((c) => c.input)))
  }
  if (step.evidence === 'citation' || step.evidence === 'citation-or-refusal') {
    const entries = data.knowledge ?? []
    const citations = [...String(data.reply ?? '').matchAll(/\[(\d+)\]/g)].map((m) => Number(m[1]))
    const validCitation = entries.length && entries.every((e) => e.content && (e.docId || e.docNo) && (e.position || e.chunkId)) && citations.length && citations.every((n) => n >= 1 && n <= entries.length)
    if (step.evidence === 'citation') result.assertions.push(ck('回答原文/定位/引用有效', validCitation))
    else {
      const reply = String(data.reply ?? '')
      const explicitRefusal = /(?:没有|未|缺少).{0,10}(?:依据|证据)|无法.{0,8}(?:判断|确认|说明)|不能.{0,8}(?:判断|确认|说明)/.test(reply)
      // 本分支验收反馈的前置消息，不把礼貌性的拒答前缀当成后续政策编造的通行证。
      //
      // 判据是「这句话在**断言**某条政策事实」，所以必须先去掉被否定的那部分宾语：
      // 拒答句「没有查到关于退货所需材料的具体依据」里，政策词全都落在「依据」的
      // 定语里，整句恰恰是在说"不知道"——直接对整句跑关键词会把正确拒答判成编造。
      // 剥离只作用于否定辖域（没有/无法…依据|要求|规定…），句子里真出现独立的
      // 政策断言（「但必须提供发票」「退款在 7 天内完成」）仍会照常命中。
      const policyFacts = reply.split(/[。！？!?；;\n]/).filter(sentence => !/是否|哪些|吗|？/.test(sentence)).some(sentence =>
        /(?:必须|须|需|需要|要求|应当).{0,8}(?:提供|提交|准备|保留|寄回).{0,15}(?:发票|凭证|照片|视频|报告|材料|包装)|(?:退货|退款|售后).{0,8}(?:必须|须|需|要求).{0,8}(?:发票|凭证|照片|视频|报告|材料|包装)|(?:退款|退货|售后).{0,15}(?:\d+|[一二三四五六七八九十]+)\s*(?:天|日|小时|元|%)|(?:支持|允许|不支持|不允许).{0,12}(?:退货|退款)|可以放心|绝对安全|没有任何风险/
          .test(sentence.replace(NEGATED_OBJECT_SCOPE, '')))
      const clean = !(data.unsupportedClaims ?? []).length && !(data.factMismatches ?? []).length
      const refusalValid = ['NONE', 'WEAK'].includes(data.evidenceLevel) && explicitRefusal && !policyFacts && citations.length === 0 && clean
      const pass = result.caseId === 'CA-048' && (data.evidenceLevel === 'SUFFICIENT' ? validCitation && clean : refusalValid)
      result.assertions.push(ck('CA048 按证据等级验证有效引用或明确拒答且无未支撑政策事实', pass,
        { evidenceLevel: data.evidenceLevel, branch: data.evidenceLevel === 'SUFFICIENT' ? 'CITATION_REQUIRED' : 'REFUSAL_REQUIRED', citations, explicitRefusal, policyFacts, clean }))
    }
  }
  if (step.evidence === 'refusal') result.assertions.push(ck('缺证据明确不确定且不编造安全结论', /无.{0,8}证据|未.{0,8}(证据|记录)|无法.{0,8}(判断|确认)|不能.{0,8}(判断|确认)|缺少.{0,8}证据/.test(data.reply ?? '') && !/可以放心|绝对安全|没有任何风险/.test(data.reply ?? '')))
}

/** 服务usageOf在TokenLedger为空时返回null；只接受已验证原审批的固定执行收尾分支为零用量：完成清理（DONE）或未完成保留**原**令牌（WAITING_USER）。 */
export function usageEvidence(data, approvalCarried, carriedToken = null) {
  if (data.usage && typeof data.usage === 'object') return { mode: 'REPORTED', valid: true, usage: data.usage, proof: '服务端本轮TokenLedger返回用量' }
  const approvedWritesOnly = (data.toolCalls ?? []).length > 0 && data.toolCalls.every((call) => WRITE_TOOLS.has(call.tool))
  const fixedApproval = approvalCarried && data.stage === 'DONE' && !data.approvalToken && approvedWritesOnly
  // 执行非全 COMMITTED（UNKNOWN/REJECTED）时冻结规格要求保留**逐字相同的原**令牌并停在 WAITING_USER，
  // 同样不跑规划/续作模型；换发新令牌不属于这个分支。
  const recoveryPending = approvalCarried && data.stage === 'WAITING_USER' && carriedToken && data.approvalToken === carriedToken && approvedWritesOnly
  if (data.usage == null && (fixedApproval || recoveryPending)) return {
    mode: 'NO_MODEL_CALL', valid: true,
    usage: { promptTokens: 0, completionTokens: 0, totalTokens: 0, costCny: 0, models: [], unpricedModels: [] },
    proof: fixedApproval
      ? '已核对原审批令牌；Agent.executeApproved固定执行分支DONE、只含已批准写工具；AiAssistantServiceImpl.usageOf空TokenLedger明确返回null，无规划/续作模型调用'
      : '已核对原审批令牌逐字一致；执行未完成按规格保留原令牌并停在WAITING_USER，未跑续作模型任务；AiAssistantServiceImpl.usageOf空TokenLedger明确返回null，无规划/续作模型调用',
    rawUsage: null,
  }
  return { mode: 'UNRECORDED', valid: false, usage: null, proof: '没有用量对象，且未证明无模型调用分支；不得猜测0tokens' }
}

export function retrievalEvidenceValid(data, evidence, requestId, expected) {
  if (!evidence?.available || evidence.requestId !== requestId || data.requestId !== requestId || evidence.query !== data.retrievalQuery) return false
  if (!expected.queryIncludes.every(part => data.retrievalQuery?.includes(part))) return false
  const gate = evidence.gateEntries.at(-1)
  const rerank = evidence.rerankEntries.at(-1)
  return expected.levels.includes(data.evidenceLevel) && gate?.level === data.evidenceLevel && gate.chunks === (data.knowledge ?? []).length
    && rerank?.kept === gate.chunks && evidence.queryEntries.some(entry=>entry.query === data.retrievalQuery)
}

export async function executeCase(source, ctx, result) {
  const plan = prepareCase(source)
  for (const control of plan.requiredControls) if (typeof ctx.control?.[control] !== 'function') fail(`缺真实 ${control} 控制适配器；未注入故障/重启`, true)
  const state = await setupCase(source, ctx, result)
  let pending = null; let lastApproval = null; let faultActive = null; let primaryError = null; let recoveryError = null
  const chat = async (step, options = {}) => {
    const message = resolveValue(step.message, state)
    const r = await ctx.api('/ai/chat', { method: 'POST', body: { sessionId: result.sessionId, message, ...options } })
    const data = r.data
    const usage = usageEvidence(data, Boolean(options.approvalToken), options.approvalToken ?? null)
    const t = { ...r, index: result.turns.length + 1, message, status: 'RECEIVED', approvalCarried: Boolean(options.approvalToken), stepId: step.id, response: data, usage: usage.usage, usageEvidence: usage }
    result.turns.push(t)
    result.toolCalls.push(...(data.toolCalls ?? []).map((c) => ({ ...c, stepId: step.id, requestId: r.requestId, approvalCarried: t.approvalCarried })))
    result.assertions.push(ck(`对话 ${step.id} 身份/requestId/用量分支证据`, r.requestId && data.assistantMessageId && data.userMessageId && usage.valid, usage))
    if (!options.approvalToken && (data.toolCalls ?? []).some((c) => WRITE_TOOLS.has(c.tool) && c.success)) fail('未审批却执行写工具')
    checkDag(step, data, state, result)
    if (step.retrievalEvidence) {
      const evidence = typeof ctx.collectRetrievalEvidence === 'function'
        ? await ctx.collectRetrievalEvidence({ requestId: r.requestId, query: data.retrievalQuery })
        : { available: false, unavailable: '没有真实入口RAG日志读取器' }
      result.retrievalEvidence ??= []
      result.retrievalEvidence.push({ stepId: step.id, ...evidence })
      result.assertions.push(ck(`入口RAG ${step.id} 同requestId查询/重排/证据门执行`, retrievalEvidenceValid(data, evidence, r.requestId, step.retrievalEvidence), evidence))
    }
    if (step.store) state[step.store] = data
    state.last = data
    pending = data.approvalToken ? { token: data.approvalToken, details: data.pendingActionDetails ?? [], assistantMessageId: data.assistantMessageId } : null
    await ctx.save?.()
    return data
  }
  try {
    for (const step of source.steps) {
      result.stage = step.id
      if (step.kind === 'chat') {
        const data = await chat(step)
        if (step.actions) { if (!pending) fail(`步骤 ${step.id} 未返回审批`); validateApproval(pending.details, resolveValue(step.actions, state)) }
        if (step.clarification === 'required') result.assertions.push(ck(`澄清 ${step.id} 未审批/未写入`, !pending && !(data.toolCalls ?? []).some((c) => WRITE_TOOLS.has(c.tool))))
      } else if (step.kind === 'bind-products') {
        const list = state[step.from]?.recommendedProducts ?? []
        state.products = []
        for (const wanted of step.selections) {
          let index = wanted.nameIncludes && !wanted.matchAtOriginalIndex ? list.findIndex((summary) => normalizedName(summary.name).includes(normalizedName(wanted.nameIncludes))) : wanted.index
          let summary = list[index]
          let correction = null
          if (!summary?.id) fail(wanted.nameIncludes ? `真实候选没有用户指定品类${wanted.nameIncludes}，不能审批` : `真实列表缺第 ${wanted.index + 1} 候选`, Boolean(wanted.nameIncludes))
          if (wanted.nameIncludes && !normalizedName(summary.name).includes(normalizedName(wanted.nameIncludes))) {
            result.prerequisiteConflicts ??= []
            correction = { stepId: step.id, source: `${step.from}.recommendedProducts[${index}]`, expectedCategory: wanted.nameIncludes, originalOrdinal: index + 1, actual: { id: summary.id, name: summary.name }, policy: '原列表指代与商品类别不一致，需要用户澄清；不换目标、不批准错误对象' }
            result.prerequisiteConflicts.push(correction)
            if (source.caseId !== 'CA-012' || wanted.allowNameClarification !== true) fail(`原列表第${index + 1}个“${summary.name}”不是${wanted.nameIncludes}，需用户澄清，不能审批`, true)
            index = list.findIndex((item) => normalizedName(item.name).includes(normalizedName(wanted.nameIncludes)))
            summary = list[index]
            if (!summary?.id) fail(`原列表没有${wanted.nameIncludes}可供用户澄清选择，不能审批`, true)
            correction.clarificationTarget = { source: `${step.from}.recommendedProducts[${index}]`, id: summary.id, name: summary.name, ordinal: index + 1 }
          }
          const detail = await ctx.get(`/products/${summary.id}`)
          if (wanted.nameIncludes && !normalizedName(detail.name).includes(normalizedName(wanted.nameIncludes))) fail(`商品详情不含${wanted.nameIncludes}，不能审批`, true)
          const sku = (detail.skus ?? []).filter((s) => s.stock >= wanted.quantity && s.status !== 0).sort((a, b) => a.id - b.id)[wanted.skuIndex ?? 0]
          if (!sku || (wanted.requiredSkuCount && detail.skus.length < wanted.requiredSkuCount)) fail('候选缺用户指定规格/库存', true)
          const rawSpecText = typeof sku.specText === 'string' ? sku.specText : null
          if (!rawSpecText?.trim() && detail.skus.length !== 1) fail('多规格商品缺少所选规格文字，不能猜测用户选择', true)
          const specText = rawSpecText?.trim() ? rawSpecText : '单一规格（商品只有一个在售规格，未标注规格文字）'
          const clarification = correction ? `我纠正原序号：原列表第${correction.originalOrdinal}个实际是“${correction.actual.name}”；我明确要${wanted.nameIncludes}，选择原列表第${index + 1}个“${detail.name ?? summary.name}”，不是“${correction.actual.name}”。` : `我明确选择原列表第${index + 1}个“${detail.name ?? summary.name}”。`
          state.products.push({ spuId: summary.id, name: detail.name ?? summary.name, skuId: sku.id, specText, rawSpecText, singleSku: detail.skus.length === 1, quantity: wanted.quantity, ordinal: index + 1, clarification })
          result.parameterBindings.push({ stepId: step.id, source: `${step.from}.recommendedProducts[${index}] -> /products/${summary.id}.skus[${wanted.skuIndex ?? 0}]`, categoryConstraint: wanted.nameIncludes ?? null, target: state.products.at(-1) })
        }
      } else if (step.kind === 'confirm') {
        if (!pending) fail('确认缺服务端审批令牌')
        const actions = resolveValue(step.actions, state)
        validateApproval(pending.details, actions)
        lastApproval = { ...pending, actions }
        const data = await chat({ ...step, message: step.message ?? '确认', dag: step.dag ?? actions.map((a) => ({ tool: a.tool, arguments: a.arguments })) }, { approvalToken: pending.token })
        result.approvals.push({ stepId: step.id, actions, details: lastApproval.details, confirmed: true, expectedSuccess: step.dag?.some((n) => n.outcome === 'failure') !== true, operationIdEvidence: '响应未暴露operationId；需按requestId核对服务端日志，原审批令牌仅内存保存' })
        // 执行非全 COMMITTED（UNKNOWN/REJECTED）时，冻结规格要求保留**原**令牌与恢复载荷、停在 WAITING_USER；
        // 只有换发新令牌、已完成仍带令牌、缺恢复载荷才是缺陷——旧断言只看"有没有令牌"，把规格行为误判成了失败。
        if (data.approvalToken) {
          const retained = data.approvalToken === lastApproval.token && data.stage === 'WAITING_USER' && (data.pendingActionDetails ?? []).length > 0
          result.assertions.push(ck(`确认 ${step.id} 未完成保留原令牌并停在WAITING_USER`, retained, { stage: data.stage, tokenReplaced: data.approvalToken !== lastApproval.token, details: data.pendingActionDetails }))
        } else {
          result.assertions.push(ck(`确认 ${step.id} 完成后清掉审批令牌`, data.stage === 'DONE', { stage: data.stage }))
        }
        if (step.capture === 'order') {
          const added = (await ctx.get('/orders?tab=ALL&page=0&size=100')).records.filter((o) => !result.before.orders.some((b) => b.id === o.id))
          if (added.length !== 1) fail('结算未唯一新增订单')
          state.createdOrder = await ctx.get(`/orders/${added[0].id}`)
        }
      } else if (step.kind === 'repeat-confirm') {
        if (!lastApproval) fail('重复确认缺原审批批次')
        const before = await verifyEffects(ctx.get)
        const r = await ctx.api('/ai/chat', { method: 'POST', body: { sessionId: result.sessionId, message: '确认', approvalToken: lastApproval.token }, allowCodes: [200, 400, 409] })
        const after = await verifyEffects(ctx.get)
        result.apiEvidence.push({ stepId: step.id, ...r, tokenReplayed: true })
        result.assertions.push(ck('同审批令牌重放无第二次副作用', equal({ ...before, capturedAt: '' }, { ...after, capturedAt: '' })))
      } else if (step.kind === 'cancel') {
        if (!pending) fail('取消缺待审批批次')
        await ctx.api(`/ai/sessions/${result.sessionId}/messages/${pending.assistantMessageId}/dismiss-approval`, { method: 'POST' })
        result.approvals.push({ stepId: step.id, details: pending.details, confirmed: false, cancelled: true })
        pending = null
        await chat({ ...step, message: '取消刚才的操作，不执行加购。', dag: [] })
      } else if (step.kind === 'api') {
        const r = await ctx.api(resolveValue(step.path, state), resolveValue({ method: step.method ?? 'GET', body: step.body, actor: step.actor ?? 'buyer', allowCodes: step.codes ?? [200] }, state))
        result.apiEvidence.push({ stepId: step.id, scope: step.scope, userAction: step.userAction, ...r })
        if (step.store) state[step.store] = r.data
        result.assertions.push(ck(`API ${step.id} 状态码`, (step.codes ?? [200]).includes(r.businessCode), r.businessCode))
        for (const [field, expected] of Object.entries(step.assert ?? {})) result.assertions.push(ck(`API ${step.id} ${field}`, equal(resolveValue(`{{${step.store}.${field}}}`, state), resolveValue(expected, state))))
      } else if (step.kind === 'refresh') {
        const messages = await ctx.get(`/ai/sessions/${result.sessionId}/messages`)
        const ids = result.turns.flatMap((t) => [t.response.userMessageId, t.response.assistantMessageId])
        result.assertions.push(ck('历史刷新保留权威消息 ID', ids.every((id) => messages.some((m) => m.id === id))))
        if (pending) {
          const stored = messages.find((m) => m.id === pending.assistantMessageId)?.response
          result.assertions.push(ck('恢复待审批批次参数不变', stored?.approvalToken === pending.token && equal(stored?.pendingActionDetails, pending.details)))
        }
        result.apiEvidence.push({ stepId: step.id, scope: 'API_ONLY（重新读取；浏览器布局另验）', messages })
      } else if (step.kind === 'restart') {
        const e = await ctx.control.restart({ services: step.services, caseId: source.caseId, sessionId: result.sessionId })
        if (!e?.ready || !step.services.every((s) => e.services?.[s]?.beforePid && e.services[s].afterPid && e.services[s].beforePid !== e.services[s].afterPid)) fail('重启缺进程替换/就绪证据')
        result.controls.push({ stepId: step.id, kind: 'restart', ...e })
      } else if (step.kind === 'fault') {
        const e = await ctx.control.fault({ ...step, caseId: source.caseId, sessionId: result.sessionId })
        if (!e?.verified || e.mode !== step.mode || e.failure !== step.failure || e.target !== step.target) fail('控制适配器未证明指定故障真实改变')
        faultActive = step.mode === 'enable' ? step : null
        result.controls.push({ stepId: step.id, kind: 'fault', ...e })
      } else if (step.kind === 'feedback') {
        const messages = await ctx.get(`/ai/sessions/${result.sessionId}/messages`)
        const id = result.turns.at(-1).response.assistantMessageId
        const path = `/ai/sessions/${result.sessionId}/messages/${id}/bad-case`
        const ids = messages.map((m) => m.id)
        const saved = await ctx.api(path, { method: 'PUT', body: { reasonCodes: ['OTHER'], selectedMessageIds: ids, comment: 'T1 完整会话反馈验收' } })
        const restored = await ctx.get(path)
        const savedRecord = saved.data
        const expectedUserId = result.turns.at(-1).response.userMessageId
        const identityMatches = record => Boolean(savedRecord?.badCaseId) && record?.badCaseId === savedRecord.badCaseId
          && record.userId === savedRecord.userId
          && record.sessionId === result.sessionId && record.assistantMessageId === id
          && record.userMessageId === expectedUserId && equal(record.selectedMessageIds, ids)
        result.assertions.push(ck('完整会话反馈刷新后保留稳定记录与权威消息 ID/选择', identityMatches(restored) && restored.status === 'ACTIVE', restored))
        result.apiEvidence.push({ stepId: step.id, saved, restored, selectedMessageIds: ids })
        await ctx.api(path, { method: 'DELETE' })
        const revokedResponse = await ctx.api(path)
        const revoked = revokedResponse.data
        const sessionCases = await ctx.get(`/ai/sessions/${result.sessionId}/bad-cases`)
        const matchingCases = sessionCases.filter(record => record.badCaseId === savedRecord?.badCaseId)
        result.assertions.push(ck('撤销后GET200保留REVOKED状态、稳定ID与原消息选择', revokedResponse.businessCode === 200
          && identityMatches(revoked) && revoked.status === 'REVOKED', revokedResponse))
        result.assertions.push(ck('撤销后会话反馈列表与单条REVOKED记录一致', matchingCases.length === 1
          && identityMatches(matchingCases[0]) && equal(matchingCases[0], revoked), sessionCases))
        result.apiEvidence.push({ stepId: step.id, action: 'REVOKE_AND_RELOAD', revokedResponse, sessionCases, selectedMessageIds: ids })
      }
      await ctx.save?.()
    }
  } catch (error) {
    primaryError = error
  } finally {
    if (faultActive) {
      try {
        const e = await ctx.control.fault({ ...faultActive, mode: 'disable', caseId: source.caseId, sessionId: result.sessionId })
        result.controls.push({ kind: 'fault-finally-restore', ...e })
        if (!e?.verified) recoveryError = new Error('故障恢复失败；需检查服务状态')
      } catch (restoreError) {
        result.controls.push({ kind: 'fault-finally-restore', verified: false, error: restoreError.message })
        recoveryError = restoreError
      }
    }
  }
  if (primaryError && recoveryError) throw new AggregateError([primaryError, recoveryError], `场景执行失败：${primaryError.message}；故障恢复失败：${recoveryError.message}`)
  if (recoveryError) throw recoveryError
  if (primaryError) throw primaryError
  result.after = await verifyEffects(ctx.get)
  result.assertions.push(...await evaluateFinal(source, state, result, ctx))
  result.status = result.assertions.every((a) => a.pass) ? 'PASS' : 'FAIL'
  result.reason = result.status === 'PASS' ? '执行/审批/权威状态断言通过' : result.assertions.filter((a) => !a.pass).map((a) => a.name).join('；')
  return result
}

export async function evaluateFinal(source, state, result, ctx) {
  const e = resolveValue(source.expected, state); const before = result.before; const after = result.after; const checks = []
  const cart = structuredClone(before.cart)
  for (const m of e.cart ?? []) {
    const index = cart.findIndex((r) => m.cartItemId ? r.id === m.cartItemId : r.skuId === m.skuId)
    if (m.op === 'add') { if (index >= 0) cart[index].quantity += m.quantity; else cart.push({ skuId: m.skuId, quantity: m.quantity, selected: true }) }
    else if (m.op === 'remove') cart.splice(index, 1)
    else if (m.op === 'update') cart[index].quantity = m.quantity
    else if (m.op === 'checkout') cart.splice(0, cart.length, ...cart.filter((r) => !r.selected))
  }
  checks.push(ck('权威购物车 SKU/数量/勾选精确符合意图', equal(counts(cart), counts(after.cart)) && cart.every((r) => after.cart.find((a) => a.skuId === r.skuId)?.selected === r.selected), after.cart))
  for (const domain of ['orders', 'coupons', 'tickets', 'afterSales']) {
    const contract = e[domain]
    if (!contract) { checks.push(ck(`${domain} 无意外副作用`, equal(before[domain], after[domain]))); continue }
    const added = after[domain].filter((r) => !before[domain].some((b) => b.id === r.id))
    if (contract.added !== undefined) checks.push(ck(`${domain} 新增数量`, added.length === contract.added, added))
    if (contract.status) checks.push(ck(`${domain} 新增状态`, added.every((r) => r.status === contract.status), added))
    for (const row of added) for (const [k, v] of Object.entries(contract.fields ?? {})) checks.push(ck(`${domain} 新增 ${k} 绑定`, equal(row[k], v), row[k]))
    if (domain === 'orders' && added.length) {
      const o = await ctx.get(`/orders/${added[0].id}`)
      checks.push(ck('订单商品/数量/地址符合明确选择', equal(counts(o.items), counts(before.cart.filter((r) => r.selected))) && Object.entries(ADDRESS).every(([k, v]) => o[k] === v), o))
      if (contract.usedCoupon) checks.push(ck('指定用户券真实抵扣并核销', o.discountAmount > 0 && after.coupons.find((c) => c.id === contract.usedCoupon)?.status === 'USED'))
    }
    if (contract.id) checks.push(ck(`${domain} 目标终态`, after[domain].find((r) => r.id === contract.id)?.status === contract.targetStatus))
    if (domain === 'tickets' && added.length) {
      const detail = await ctx.get(`/tickets/${added[0].id}`)
      checks.push(ck('工单标题、内容、分类与用户原文一致', Object.entries(contract.fields ?? {}).every(([k, v]) => equal(detail.ticket[k], v)) && JSON.stringify(detail.messages).includes(contract.content), detail))
    }
  }
  if (e.logistics) checks.push(ck('补录物流落盘且订单状态不变', JSON.stringify(await ctx.get(`/orders/${state.order.id}/logistics`)).includes(e.logistics.description) && (await ctx.get(`/orders/${state.order.id}`)).status === before.orders.find((o) => o.id === state.order.id).status))
  if (e.ticketDetail) checks.push(ck('客服回复真实落盘', JSON.stringify((await ctx.get(`/tickets/${state.createdTicket?.id ?? state.ticket.id}`)).messages).includes(e.ticketDetail.content)))
  const writes = result.toolCalls.filter((c) => WRITE_TOOLS.has(c.tool) && c.success)
  const actions = result.approvals.filter((a) => a.confirmed && a.expectedSuccess).flatMap((a) => a.actions)
  checks.push(ck('成功写工具次数等于确认动作且均携带审批', writes.length === actions.length && writes.every((w) => w.approvalCarried), writes))
  checks.push(ck('无残留待审批', !state.last?.approvalToken))
  return checks
}
