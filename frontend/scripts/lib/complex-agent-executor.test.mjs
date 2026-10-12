import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { executeCase, prepareCase, resolveValue, validateApproval, evaluateFinal, checkDag, usageEvidence, retrievalEvidenceValid } from './complex-agent-executor.mjs'

const fixture = JSON.parse(await readFile(new URL('../fixtures/complex-agent-dialogues.json', import.meta.url), 'utf8'))
const makeResult = (c) => ({ caseId: c.caseId, sessionId: 'mock-' + c.caseId, assertions: [], turns: [], toolCalls: [], setupEvidence: [], apiEvidence: [], approvals: [], parameterBindings: [], controls: [] })

/** 替代传输而非替代断言：执行真实setup和step循环，数据库由API操作更新，模型输出由逐例DAG回放。 */
function transport(source, result) {
  let nextId = 10; let pendingActions = []; let lastToken = null; const executed = new Set()
  const db = { cart: [], orders: [], coupons: [], tickets: [], afterSales: [], messages: [], addresses: [], traces: [], feedback: null }
  const clone = (v) => structuredClone(v)
  const newOrder = (body) => {
    const order = { id: ++nextId, status: 'CREATED', ...body, items: db.cart.map((r) => ({ ...r, id: ++nextId })), discountAmount: body.userCouponId ? 2000 : 0 }
    db.orders.push(order); db.cart = []
    if (body.userCouponId) db.coupons.find((c) => c.id === body.userCouponId).status = 'USED'
    return order
  }
  const mutateTool = (a) => {
    const p = a.arguments
    if (a.tool === 'cart_add') { const row = db.cart.find((r) => r.skuId === p.skuId); if (row) row.quantity += p.quantity; else db.cart.push({ id: ++nextId, skuId: p.skuId, quantity: p.quantity, selected: true }) }
    if (a.tool === 'cart_update') db.cart.find((r) => r.id === p.cartItemId).quantity = p.quantity
    if (a.tool === 'cart_remove') db.cart = db.cart.filter((r) => r.id !== p.cartItemId)
    if (a.tool === 'cart_checkout') newOrder(p)
    if (a.tool === 'order_cancel') db.orders.find((o) => o.id === p.orderId).status = 'CANCELLED'
    if (a.tool === 'coupon_receive') db.coupons.push({ id: ++nextId, couponId: p.couponId, status: 'UNUSED' })
    if (a.tool === 'ticket_create') db.tickets.push({ id: ++nextId, status: 'OPEN', ...p, messages: [{ content: p.content }] })
    if (a.tool === 'after_sale_apply') db.afterSales.push({ id: ++nextId, status: 'APPLIED', ...p, type: 'RETURN_REFUND' })
  }
  const api = async (path, options = {}) => {
    const body = options.body; const method = options.method ?? 'GET'
    let data; let code = 200
    if (path === '/auth/addresses') { if (body) db.addresses.push({ id: ++nextId, ...body }); data = body ? db.addresses.at(-1) : db.addresses }
    else if (path.startsWith('/products/skus')) data = [{ id: Number(path.split('=')[1]), stock: 100, status: 1, spuStatus: 1 }]
    else if (/^\/products\/\d+$/.test(path)) { const id = Number(path.split('/').at(-1)); data = { id, name: source.caseId === 'CA-029' ? (id === 1 ? '乳清蛋白粉' : '植物蛋白粉') : id === 1 ? '维生素C咀嚼片' : id === 2 ? '鱼油软胶囊' : '维生素C胶囊', skus: [{ id: id * 10, specText: '小瓶装', stock: 100, status: 1 }, { id: id * 10 + 1, specText: '大瓶装', stock: 100, status: 1 }] } }
    else if (path === '/cart/items') { db.cart.push({ id: ++nextId, selected: true, ...body }); data = db.cart.at(-1) }
    else if (path === '/cart') data = db.cart
    else if (path === '/orders/checkout') data = newOrder(body)
    else if (path === '/orders/preview') data = { coupons: db.coupons.map((c) => ({ ...c, usable: true })) }
    else if (path.startsWith('/orders?')) data = { records: db.orders }
    else if (/^\/orders\/\d+$/.test(path)) data = db.orders.find((o) => o.id === Number(path.split('/').at(-1)))
    else if (path.endsWith('/ship')) { data = db.orders.find((o) => o.id === Number(path.split('/').at(-2))); data.status = 'SHIPPED' }
    else if (path.endsWith('/receive') && path.startsWith('/orders')) { data = db.orders.find((o) => o.id === Number(path.split('/').at(-2))); data.status = 'RECEIVED' }
    else if (path.endsWith('/traces')) { db.traces.push(body); data = { traces: db.traces } }
    else if (path.endsWith('/logistics')) data = { traces: db.traces }
    else if (path === '/payments') data = { orderId: body.orderId }
    else if (path.endsWith('/mock-pay')) { db.orders.find((o) => o.id === Number(path.split('/').at(-2))).status = 'PAID'; data = {} }
    else if (path === '/coupons/available') data = [{ id: 1, name: '满减一', type: 'FIXED', remainingCount: 99 }, { id: 2, name: '满减二', type: 'FIXED', remainingCount: 99 }]
    else if (/^\/coupons\/\d+\/receive$/.test(path)) { data = { id: ++nextId, couponId: Number(path.split('/')[2]), status: 'UNUSED' }; db.coupons.push(data) }
    else if (path === '/coupons/mine') data = db.coupons
    else if (path.startsWith('/after-sales/preview')) data = { eligible: true }
    else if (path === '/after-sales') { if (body) { if (db.afterSales.some((a) => a.orderItemId === body.orderItemId)) { code = 409; data = null } else { data = { id: ++nextId, status: 'APPLIED', ...body }; db.afterSales.push(data) } } else data = db.afterSales }
    else if (path.includes('/after-sales/admin/')) { data = db.afterSales.find((a) => a.id === Number(path.split('/')[4])); data.status = 'REJECTED' }
    else if (/^\/after-sales\/\d+$/.test(path)) data = { afterSale: db.afterSales.find((a) => a.id === Number(path.split('/').at(-1))) }
    else if (path === '/tickets' && body) { const ticket = { id: ++nextId, status: 'OPEN', ...body, messages: [] }; db.tickets.push(ticket); data = { ticket, messages: [] } }
    else if (path.startsWith('/tickets?')) data = { records: db.tickets }
    else if (/\/tickets\/admin\//.test(path)) {
      const ticket = db.tickets.find((t) => t.id === Number(path.split('/')[4]))
      if (options.actor !== 'admin') { code = 403; data = null }
      else { ticket.status = 'PROCESSING'; ticket.messages.push({ content: body.content }); data = { ticket, messages: ticket.messages } }
    } else if (/^\/tickets\/\d+\/close$/.test(path)) { const t = db.tickets.find((t) => t.id === Number(path.split('/')[2])); t.status = 'CLOSED'; data = { ticket: t, messages: t.messages } }
    else if (/^\/tickets\/\d+$/.test(path)) { const t = db.tickets.find((t) => t.id === Number(path.split('/')[2])); data = { ticket: t, messages: t.messages } }
    else if (path.endsWith('/dismiss-approval')) { pendingActions = []; data = {} }
    else if (path.endsWith('/bad-case')) {
      if (method === 'PUT') db.feedback = { badCaseId: db.feedback?.badCaseId ?? 'bad-' + nextId, sessionId: result.sessionId,
        userMessageId: result.turns.at(-1).response.userMessageId, status: 'ACTIVE', ...body, assistantMessageId: path.split('/').at(-2) }
      if (method === 'DELETE' && db.feedback) db.feedback.status = 'REVOKED'
      data = db.feedback; if (!data && method === 'GET') code = 404
    } else if (path.endsWith('/bad-cases')) data = db.feedback ? [db.feedback] : []
    else if (path.endsWith('/messages')) data = db.messages
    else if (path === '/ai/chat') {
      const step = source.steps.find((s) => s.id === result.stage)
      const state = result.bindings
      data = { reply: step.evidence === 'refusal' ? '缺少证据，无法判断' : '退货规则原文[1]', knowledge: [{ docId: '退货规则', content: '原文', position: '第1条' }], assistantMessageId: 'a-' + (++nextId), userMessageId: 'u-' + nextId, usage: {}, toolCalls: [], recommendedProducts: [{ id: 1, name: '维生素C咀嚼片' }, { id: 3, name: '维生素C胶囊' }, { id: 2, name: '鱼油软胶囊' }] }
      if (source.caseId === 'CA-012') data.recommendedProducts = [{ id: 1, name: '维生素C咀嚼片' }, { id: 2, name: '鱼油软胶囊' }]
      if (source.caseId === 'CA-029') data.recommendedProducts = [{ id: 3, name: '植物蛋白粉' }, { id: 1, name: '乳清蛋白粉' }]
      if (body.approvalToken) {
        if (!executed.has(body.approvalToken)) {
          const failed = step.dag?.some((n) => n.outcome === 'failure')
          if (!failed) pendingActions.forEach(mutateTool)
          data.toolCalls = pendingActions.map((a) => ({ ...a, input: JSON.stringify(a.arguments), success: !failed, noData: false }))
          executed.add(body.approvalToken)
        }
        pendingActions = []
      } else {
        data.toolCalls = (step.dag ?? []).map((a) => ({ tool: a.tool, arguments: resolveValue(a.arguments, state), input: JSON.stringify(resolveValue(a.arguments, state)), success: a.outcome !== 'failure', noData: a.outcome === 'empty' }))
        if (step.actions) { pendingActions = resolveValue(step.actions, state); lastToken = 'token-' + nextId; data.approvalToken = lastToken; data.pendingActionDetails = clone(pendingActions) }
      }
      db.messages.push({ id: data.userMessageId }, { id: data.assistantMessageId, response: clone(data) })
    } else throw new Error('mock未支持公开接口：' + path)
    const requestId = 'request-' + (++nextId)
    if (path === '/ai/chat') { data.requestId = requestId; data.retrievalQuery = body.message; data.evidenceLevel = source.caseId === 'CA-048' ? 'SUFFICIENT' : 'WEAK' }
    return { data: clone(data), businessCode: code, httpStatus: code === 403 ? 403 : 200, requestId, latencyMs: 1 }
  }
  const ctx = { api, createBuyer: async () => {}, get: async (p) => (await api(p)).data, control: {
    restart: async ({ services }) => ({ ready: true, services: Object.fromEntries(services.map((s) => [s, { beforePid: 1, afterPid: 2 }])) }),
    fault: async ({ mode, failure, target }) => ({ verified: true, mode, failure, target }),
  } }
  ctx.poll = async (path, predicate) => { const data = await ctx.get(path); assert.ok(predicate(data)); return data }
  ctx.collectRetrievalEvidence=async({requestId,query})=>({available:true,requestId,query,queryEntries:[{query}],rerankEntries:[{candidates:3,kept:1}],gateEntries:[{level:'WEAK',chunks:1}]})
  return ctx
}

test('48例均有执行器和前置/消息/DAG/审批/权威断言契约', () => {
  assert.equal(fixture.cases.length, 48)
  for (const c of fixture.cases) assert.ok(prepareCase(c).steps.length)
})

test('CA048充分证据只接受有原文定位的有效引用，不能接受拒答或越界引用', () => {
  const step = { id: 's2', evidence: 'citation-or-refusal', dag: [] }
  const good = { evidenceLevel: 'SUFFICIENT', reply: '退货规则原文[1]', knowledge: [{ docId: 'KB1', content: '原文', position: '第1条' }] }
  for (const [data, pass] of [[good, true], [{ ...good, reply: '无法确认' }, false], [{ ...good, reply: '规则[2]' }, false], [{ ...good, knowledge: [{ docId: 'KB1' }] }, false]]) {
    const result = { caseId: 'CA-048', assertions: [] }
    checkDag(step, data, {}, result)
    assert.equal(result.assertions.length, 1)
    assert.equal(result.assertions[0].pass, pass)
  }
})

test('CA048弱或无证据只接受明确拒答，不能用拒答前缀夹带政策结论', () => {
  const step = { id: 's2', evidence: 'citation-or-refusal', dag: [] }
  const refused = { evidenceLevel: 'WEAK', reply: '平台暂时没有查到明确依据，无法给出确切说明。请联系人工客服确认具体要求。', knowledge: [], unsupportedClaims: null }
  for (const [data, pass] of [[refused, true], [{ ...refused, evidenceLevel: 'NONE' }, true],
    [{ ...refused, evidenceLevel: undefined }, false], [{ ...refused, reply: '好的' }, false],
    [{ ...refused, reply: '无法确认。但必须提供发票和检测报告。' }, false],
    [{ ...refused, reply: '无法确认。退货需发票。' }, false],
    [{ ...refused, reply: '无法确认，但退款在7天内完成。' }, false],
    [{ ...refused, reply: '无法确认。退货规则[1]', knowledge: [{ content: '原文', docId: 'KB1', position: '第1条' }] }, false],
    [{ ...refused, unsupportedClaims: ['规则无依据'] }, false]]) {
    const result = { caseId: 'CA-048', assertions: [] }
    checkDag(step, data, {}, result)
    assert.equal(result.assertions.length, 1)
    assert.equal(result.assertions[0].pass, pass, data.reply)
  }
})

test('citation-or-refusal仅CA048可使用，不放宽其他场景或医学CA028', () => {
  const unrelated = structuredClone(fixture.cases.find(c => c.caseId === 'CA-028'))
  unrelated.steps.find(s => s.kind === 'chat').evidence = 'citation-or-refusal'
  assert.throws(() => prepareCase(unrelated), /CA-048/)
  const result = { caseId: 'CA-028', assertions: [] }
  checkDag({ id: 's1', evidence: 'citation-or-refusal', dag: [] }, { evidenceLevel: 'WEAK', reply: '无法确认' }, {}, result)
  assert.ok(result.assertions.some(a => !a.pass))
})

test('CA048撤销后的状态、ID、消息选择和会话列表任一不一致都失败', async () => {
  const source = fixture.cases.find(c => c.caseId === 'CA-048')
  for (const corrupt of [value => ({ ...value, status: 'ACTIVE' }), value => ({ ...value, badCaseId: '另一个ID' }),
    value => ({ ...value, selectedMessageIds: value.selectedMessageIds.slice(1) }), value => ({ ...value, sessionId: '其他会话' }),
    value => ({ ...value, assistantMessageId: '其他消息' })]) {
    const result = makeResult(source); const ctx = transport(source, result); const original = ctx.api
    let revoked = false
    ctx.api = async (path, options) => {
      const response = await original(path, options)
      if (path.endsWith('/bad-case') && options?.method === 'DELETE') revoked = true
      if (revoked && path.endsWith('/bad-case') && (options?.method ?? 'GET') === 'GET') response.data = corrupt(response.data)
      return response
    }
    ctx.get = async path => (await ctx.api(path)).data
    await executeCase(source, ctx, result)
    assert.equal(result.status, 'FAIL')
    assert.ok(result.assertions.some(a => !a.pass && /撤销/.test(a.name)))
  }
  const result = makeResult(source); const ctx = transport(source, result); const original = ctx.get
  ctx.get = async path => path.endsWith('/bad-cases') ? [] : original(path)
  await executeCase(source, ctx, result)
  assert.equal(result.status, 'FAIL')
})

for (const c of fixture.cases) test('契约回放 ' + c.caseId, async () => {
  const result = makeResult(c)
  await executeCase(c, transport(c, result), result)
  assert.equal(result.status, 'PASS', JSON.stringify(result.assertions.filter((a) => !a.pass)))
  assert.ok(result.setupEvidence.length)
})

test('缺真实控制适配器在setup之前BLOCKED，不接受自然语言重启', async () => {
  const c = fixture.cases.find((c) => c.caseId === 'CA-017')
  let created = false
  await assert.rejects(executeCase(c, { createBuyer: async () => { created = true } }, makeResult(c)), (e) => e.blocked && /控制适配器/.test(e.message))
  assert.equal(created, false)
})

test('审批对象、数量及额外字段不可被错误模型输出放行', () => {
  const wanted = [{ tool: 'cart_update', arguments: { cartItemId: 1, quantity: 2 } }]
  assert.doesNotThrow(() => validateApproval(wanted, wanted))
  for (const args of [{ cartItemId: 11, quantity: 2 }, { cartItemId: 1, quantity: 3 }, { cartItemId: 1, quantity: 2, remark: '额外' }]) assert.throws(() => validateApproval([{ tool: 'cart_update', arguments: args }], wanted))
})

test('工具success不能代替权威购物车变化', async () => {
  const c = fixture.cases.find((c) => c.caseId === 'CA-003'); const r = makeResult(c)
  await executeCase(c, transport(c, r), r)
  r.after.cart = structuredClone(r.before.cart)
  const checks = await evaluateFinal(c, r.bindings, r, { get: async () => {} })
  assert.equal(checks.find((a) => a.name.startsWith('权威购物车')).pass, false)
})

test('模板禁止表达式与无下标列表字段，保留ID数值', () => {
  assert.equal(resolveValue('{{cart[0].id}}', { cart: [{ id: 12 }] }), 12)
  assert.throws(() => resolveValue('{{cart.id}}', { cart: [{ id: 12 }] }))
  assert.throws(() => resolveValue('{{process.env}}', {}))
})

test('DAG参数与outcome必须由同一次调用共同满足', () => {
  const result = { assertions: [] }
  checkDag({ id: 's1', dag: [{ tool: 'order_query', arguments: { orderId: 7 } }] }, { toolCalls: [
    { tool: 'order_query', input: '{orderId=7}', success: false, noData: false },
    { tool: 'order_query', input: '{orderId=999}', success: true, noData: false },
  ] }, {}, result)
  assert.ok(result.assertions.some((a) => !a.pass))
})

test('DAG全部参数必须共同满足，不能各取一条调用拼接', () => {
  const result = { assertions: [] }
  checkDag({ id: 's1', dag: [{ tool: 'cart_add', arguments: { skuId: 7, quantity: 2 } }] }, { toolCalls: [
    { tool: 'cart_add', input: '{skuId=7, quantity=1}', success: true, noData: false },
    { tool: 'cart_add', input: '{skuId=999, quantity=2}', success: true, noData: false },
  ] }, {}, result)
  assert.ok(result.assertions.some((a) => !a.pass))
})

test('重复DAG节点不能复用同一次调用，empty也必须success', () => {
  const node = { tool: 'cart_add', arguments: { skuId: 7, quantity: 1 } }
  const result = { assertions: [] }
  checkDag({ id: 's1', dag: [node, node] }, { toolCalls: [{ tool: 'cart_add', input: '{skuId=7, quantity=1}', success: true, noData: false }] }, {}, result)
  assert.ok(result.assertions.some((a) => !a.pass))
  const empty = { assertions: [] }
  checkDag({ id: 's1', dag: [{ tool: 'product_search', outcome: 'empty' }] }, { toolCalls: [{ tool: 'product_search', success: false, noData: true }] }, {}, empty)
  assert.ok(empty.assertions.some((a) => !a.pass))
})

test('多重集匹配不让宽条件节点抢走窄条件唯一调用', () => {
  const result = { assertions: [] }
  checkDag({ id: 's1', dag: [{ tool: 'order_query' }, { tool: 'order_query', arguments: { orderId: 7 } }] }, { toolCalls: [
    { tool: 'order_query', input: '{orderId=7}', success: true, noData: false },
    { tool: 'order_query', input: '{orderId=999}', success: true, noData: false },
  ] }, {}, result)
  assert.ok(result.assertions.every((a) => a.pass))
})

test('主体失败与finally恢复失败都保留，不能只交付恢复错误', async () => {
  const c = fixture.cases.find((c) => c.caseId === 'CA-023'); const r = makeResult(c)
  const ctx = transport(c, r); const original = ctx.api
  ctx.api = async (path, options) => {
    if (path === '/ai/chat' && r.stage === c.steps.find((s) => s.kind === 'chat' && s.actions).id) throw new Error('主体审批失败')
    return original(path, options)
  }
  ctx.control.fault = async ({ mode, failure, target }) => {
    if (mode === 'disable') throw new Error('恢复失败')
    return { verified: true, mode, failure, target }
  }
  await assert.rejects(executeCase(c, ctx, r), (e) => e instanceof AggregateError && e.errors.some((x) => x.message === '主体审批失败') && e.errors.some((x) => x.message === '恢复失败'))
})

test('确认纯执行usage null由明确无模型分支映射零，普通问答缺用量仍未覆盖', () => {
  const fixed = usageEvidence({ usage: null, stage: 'DONE', toolCalls: [{ tool: 'coupon_receive', success: true }] }, true)
  assert.equal(fixed.valid, true)
  assert.equal(fixed.mode, 'NO_MODEL_CALL')
  assert.equal(fixed.usage.totalTokens, 0)
  assert.match(fixed.proof, /executeApproved/)
  assert.equal(usageEvidence({ usage: null, stage: 'DONE', toolCalls: [{ tool: 'knowledge_search', success: true }] }, true).valid, false)
  assert.equal(usageEvidence({ usage: null, stage: 'DONE', toolCalls: [{ tool: 'coupon_receive', success: true }] }, false).valid, false)
  assert.equal(usageEvidence({ usage: null, stage: 'WAITING_USER', toolCalls: [{ tool: 'coupon_receive', success: true }] }, true).valid, false)
})

test('CA002跨查询候选合并仍绑定一款维C一款鱼油并明确真实序数，不喂SKU', async () => {
  const c = fixture.cases.find(c => c.caseId === 'CA-002'); const r = makeResult(c)
  await executeCase(c, transport(c, r), r)
  assert.equal(r.status, 'PASS')
  assert.match(r.bindings.products[0].name, /维生素C/)
  assert.match(r.bindings.products[1].name, /鱼油/)
  assert.deepEqual(r.bindings.products.map(p => p.ordinal), [1, 3])
  assert.doesNotMatch(r.turns.find(t=>t.message.includes('我明确要')).message, /SKU/)
})

test('CA002缺鱼油不能用第二款维C替代，缺品类在审批前BLOCKED', async () => {
  const c = fixture.cases.find(c => c.caseId === 'CA-002'); const r = makeResult(c)
  const ctx = transport(c, r); const base = ctx.api
  ctx.api = async (path, options) => {
    const response = await base(path, options)
    if (path === '/ai/chat') response.data.recommendedProducts = [{ id: 1, name: '维生素C咀嚼片' }, { id: 3, name: '维生素C胶囊' }]
    return response
  }
  await assert.rejects(executeCase(c, ctx, r), e=>e.blocked && /鱼油/.test(e.message))
  assert.equal(r.approvals.length, 0)
})

test('单SKU空文字只能标单一规格，多SKU空文字不得擅补', async () => {
  const c = fixture.cases.find(c=>c.caseId==='CA-011'); const r = makeResult(c)
  const ctx = transport(c, r); const get = ctx.get
  ctx.get = async path => { const detail = await get(path); if (path.startsWith('/products/')) detail.skus=[{...detail.skus[0],specText:''}]; return detail }
  await executeCase(c, ctx, r)
  assert.equal(r.bindings.products[0].rawSpecText, '')
  assert.match(r.bindings.products[0].specText, /单一规格.*未标注/)
  assert.ok(r.turns.every(t=>!t.message.includes('规格“”')))
  const multiple = makeResult(c); const unsafe = transport(c, multiple); const base = unsafe.get
  unsafe.get = async path => { const detail = await base(path); if(path.startsWith('/products/'))detail.skus[0].specText='';return detail }
  await assert.rejects(executeCase(c, unsafe, multiple), e=>e.blocked && /多规格/.test(e.message))
  assert.equal(multiple.approvals.length,0)
})

test('CA012原第二张不是鱼油时保留冲突证据，用户澄清同原列表鱼油后完成两次审批', async () => {
  const c=fixture.cases.find(c=>c.caseId==='CA-012');const r=makeResult(c);const ctx=transport(c,r);const api=ctx.api
  ctx.api=async(path,options)=>{const reply=await api(path,options);if(path==='/ai/chat')reply.data.recommendedProducts=[{id:1,name:'维生素C咀嚼片'},{id:3,name:'维生素AD滴剂'},{id:2,name:'鱼油软胶囊'}];return reply}
  await executeCase(c,ctx,r)
  assert.equal(r.status,'PASS')
  assert.equal(r.prerequisiteConflicts[0].actual.name,'维生素AD滴剂')
  assert.equal(r.prerequisiteConflicts[0].clarificationTarget.ordinal,3)
  assert.match(r.turns.find(t=>t.message.includes('我纠正原序号')).message,/第3个“鱼油软胶囊”/)
  assert.doesNotMatch(r.turns.find(t=>t.message.includes('我纠正原序号')).message,/SKU/)
  assert.equal(r.approvals.length,2)
})

test('CA012没有鱼油或模型给AD审批时不携错误令牌，不降最终期待',async()=>{
  const c=fixture.cases.find(c=>c.caseId==='CA-012')
  const missing=makeResult(c),noFish=transport(c,missing),base=noFish.api
  noFish.api=async(path,options)=>{const reply=await base(path,options);if(path==='/ai/chat')reply.data.recommendedProducts=[{id:1,name:'维生素C咀嚼片'},{id:3,name:'维生素AD滴剂'}];return reply}
  await assert.rejects(executeCase(c,noFish,missing),e=>e.blocked&&/没有鱼油/.test(e.message))
  assert.equal(missing.approvals.length,0)
  const wrong=makeResult(c),unsafe=transport(c,wrong),api=unsafe.api
  const intentId=c.steps.filter(s=>s.kind==='chat'&&s.actions)[1].id
  unsafe.api=async(path,options)=>{const reply=await api(path,options);if(path==='/ai/chat'&&wrong.stage===intentId)reply.data.pendingActionDetails=[{tool:'cart_add',arguments:{skuId:30,quantity:1}}];return reply}
  await assert.rejects(executeCase(c,unsafe,wrong),/审批 cart_add/)
  assert.equal(wrong.approvals.length,1)
  assert.equal(wrong.turns.filter(t=>t.approvalCarried).length,1)
})

test('CA029按实际商品名定位第2规格，不能误取第2商品SKU',async()=>{
  const c=fixture.cases.find(c=>c.caseId==='CA-029');const r=makeResult(c)
  await executeCase(c,transport(c,r),r)
  assert.equal(r.status,'PASS')
  assert.equal(r.bindings.products[0].name,'乳清蛋白粉')
  assert.equal(r.bindings.products[0].ordinal,2)
  assert.equal(r.bindings.products[0].skuId,11)
  assert.doesNotMatch(r.turns[1].message,/SKU/)
})

test('入口RAG证据不得用别轮日志、未执行或不一致证据门替代',()=>{
  const expected={queryIncludes:['甲','乙'],levels:['WEAK','NONE']}
  const data={requestId:'r',retrievalQuery:'甲和乙能同服吗',evidenceLevel:'WEAK',knowledge:[{}]}
  const proof={available:true,requestId:'r',query:data.retrievalQuery,queryEntries:[{query:data.retrievalQuery}],rerankEntries:[{kept:1}],gateEntries:[{level:'WEAK',chunks:1}]}
  assert.equal(retrievalEvidenceValid(data,proof,'r',expected),true)
  for(const altered of [{...proof,available:false},{...proof,requestId:'旧请求'},{...proof,query:'旧问题'},{...proof,gateEntries:[{level:'NONE',chunks:0}]},{...proof,rerankEntries:[{kept:3}]}])assert.equal(retrievalEvidenceValid(data,altered,'r',expected),false)
})
