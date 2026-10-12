/** 人工API全集收口：隔离账号、自有夹具、写后权威读回；不自动调用模型或改知识语料。 */
import { randomUUID, createHash } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { ApiEvidence, assert, rows, redact, pollAuthority, pngFixture, validateReadResponse, verifyExternalEvidence, verifyOwnedDocumentFixture } from './lib/public-api-evidence.mjs'
import { sourceApiInventory, apiCoverage } from './verify-platform-matrix.mjs'
import { captureFixtureSearch, confirmFixtureSearch, cleanupFixtureSearches } from './lib/fixture-hot-keywords.mjs'

const args = process.argv.slice(2)
if (!args.every((arg) => arg === '--help' || arg === '--contract-only' || arg === '--read-only' || arg.startsWith('--evidence='))) throw new Error('仅支持 --contract-only、--read-only、--evidence=<逐接口证据JSON>、--help')
if (args.includes('--help')) {
  console.log('node scripts/verify-public-api-contracts.mjs [--contract-only] [--evidence=.evidence/public-api-external.json]\n真实模式通过网关注册两个隔离买家和一个角色测试账号，创建独立类目/品牌/商品及订单；不会删除文件、读取.env、触发模型或知识重建。外部证据逐method/path必须有runId/requestIds/PASS/path，不接受总通过数。')
  process.exit(0)
}
const ROOT = fileURLToPath(new URL('../', import.meta.url))
const matrix = JSON.parse(await readFile(new URL('./fixtures/platform-feature-matrix.json', import.meta.url), 'utf8'))
const inventory = await sourceApiInventory(resolve(ROOT, '../backend'))
const difference = apiCoverage(matrix.apiContracts, inventory.publicApis)
assert(!Object.values(difference).some((items) => items.length), 'API矩阵与当前源码不一致：' + JSON.stringify(difference))
if (args.includes('--contract-only')) {
  console.log('API全集契约=' + inventory.publicApis.length + '；源码方法/路径一致；未执行业务、模型或写入，不能记真实PASS')
  process.exit(0)
}
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
assert(['localhost', '127.0.0.1'].includes(new URL(GW).hostname), '验收仅允许明确的本地网关，禁止上传外部站点')
const runId = randomUUID()
const startedAt = new Date().toISOString()
const versionFiles = ['scripts/verify-public-api-contracts.mjs', 'scripts/lib/public-api-evidence.mjs', 'scripts/fixtures/platform-feature-matrix.json']
const sourceVersion = Object.fromEntries(await Promise.all(versionFiles.map(async (path) => [path, createHash('sha256').update(await readFile(resolve(ROOT, path))).digest('hex')])))
const prefix = 't1api_' + runId.slice(0, 8)
const tokens = {}
const state = {}
state.fixtureSearchLedger = []
const groups = []
const output = resolve(ROOT, '.evidence', 'public-api-' + runId + '.json')
let lastAiCall = 0
const evidence = new ApiEvidence(matrix.apiContracts, async (method, path, options) => {
  if (method === 'GET') captureFixtureSearch(state.fixtureSearchLedger, path, prefix, options.requestId)
  if (path.startsWith('/ai/')) {
    const delay = 1100 - (Date.now() - lastAiCall)
    if (delay > 0) await new Promise((done) => setTimeout(done, delay))
    lastAiCall = Date.now()
  }
  const response = await fetch(GW + path, {
    method, signal: AbortSignal.timeout(60000),
    headers: { 'X-Request-Id': options.requestId, ...(tokens[options.role ?? 'buyer'] ? { Authorization: 'Bearer ' + tokens[options.role ?? 'buyer'] } : {}), ...(options.form ? {} : { 'Content-Type': 'application/json' }) },
    ...(options.form ? { body: options.form } : options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
  })
  if (options.stream) {
    assert(response.ok && response.headers.get('content-type')?.includes('text/event-stream'), 'SSE未返回正确状态与类型')
    const reader = response.body.getReader()
    let text = ''
    try {
      while (!text.includes('data:')) {
        const part = await reader.read()
        assert(!part.done, 'SSE没有初始快照便关闭')
        text += new TextDecoder().decode(part.value)
        assert(text.length < 32000, 'SSE初始快照超过验收界限')
      }
      const value = text.match(/data:\s*(.+)/)?.[1]
      assert(value, 'SSE缺data字段')
      return { data: JSON.parse(value), code: 200, message: 'SSE初始快照', httpStatus: response.status }
    } finally { await reader.cancel() }
  }
  const payload = await response.json()
  confirmFixtureSearch(state.fixtureSearchLedger, options.requestId, payload.code)
  return { data: payload.data, code: payload.code ?? response.status, message: payload.msg, httpStatus: response.status }
}, runId)
const call = (key, path, body, role = 'buyer', allowedCodes) => evidence.call(key, path, { body, role, allowedCodes })
const read = (key, path, role = 'buyer') => call(key, path, undefined, role)
const check = (key, name, condition, calls, detail) => evidence.check(key, name, condition, calls, detail)
const getRows = async (key, path, role = 'buyer') => { const result = await read(key, path, role); rows(result.data); return result }
if (args.includes('--read-only')) {
  const login = await call('POST /auth/login', '/auth/login', { username: 'alice', password: '123456' }, 'anonymous')
  tokens.buyer = login.data.token
  for (const [key, path] of [['GET /favorites/check', '/favorites/check?spuIds=1'], ['GET /ai/eval/retrieval/report', '/ai/eval/retrieval/report']]) {
    const result = await read(key, path)
    check(key, '修正DTO后的真实只读响应契约', validateReadResponse(key.slice(4), result.data), [result])
  }
  await mkdir(resolve(ROOT, '.evidence'), { recursive: true })
  await writeFile(output, JSON.stringify({ ...evidence.report(), mode: 'LIVE_READ_ONLY_REGRESSION', sourceVersion, startedAt, finishedAt: new Date().toISOString(), limits: ['只复验两个已有真实响应形状，不重复业务写或调用模型；其余UNEXECUTED不计通过'] }, null, 2) + '\n')
  console.log('只读回归2/2 PASS；证据=' + output)
  process.exit(0)
}
async function group(name, work) {
  const start = Date.now()
  try { await work(); groups.push({ name, status: 'PASS', durationMs: Date.now() - start }); console.log('PASS ' + name) }
  catch (error) { groups.push({ name, status: 'FAIL', reason: error.message, durationMs: Date.now() - start }); console.log('FAIL ' + name + ' ' + error.message) }
  await mkdir(resolve(ROOT, '.evidence'), { recursive: true })
  await writeFile(output, JSON.stringify({ ...evidence.report(), startedAt, updatedAt: new Date().toISOString(), sourceVersion, groups, fixtures: redact(state), finished: false }, null, 2) + '\n', 'utf8')
}
function requireFixture(field) { assert(state[field] != null, '前置夹具缺失：' + field); return state[field] }
const address = { receiverName: '接口验收', receiverPhone: '13800000000', province: '上海市', city: '上海市', district: '浦东新区', detail: '隔离接口测试地址', tag: '验收', isDefault: true }
const checkoutAddress = { receiverName: address.receiverName, receiverPhone: address.receiverPhone, receiverProvince: address.province, receiverCity: address.city, receiverDistrict: address.district, receiverDetail: address.detail }
const flatten = (items) => items.flatMap((item) => [item, ...flatten(item.children ?? [])])
async function deniedUnchanged(key, path, body, authorityKey, authorityPath, role = 'other', authorityRole = 'buyer') {
  const before = await read(authorityKey, authorityPath, authorityRole)
  const attempt = await call(key, path, body, role, [400, 401, 403, 404, 409])
  const after = await read(authorityKey, authorityPath, authorityRole)
  assert(JSON.stringify(before.data) === JSON.stringify(after.data), '被拒请求改变权威数据：' + key)
  return [attempt, before, after]
}
async function buy() {
  const sku = requireFixture('sku')
  for (const item of rows((await read('GET /cart', '/cart')).data)) await call('DELETE /cart/items/{id}', '/cart/items/' + item.id)
  await call('POST /cart/items', '/cart/items', { skuId: sku, quantity: 1 })
  const request = { ...checkoutAddress, requestId: 'api-' + randomUUID() }
  const preview = await call('POST /orders/preview', '/orders/preview', {})
  const before = await getRows('GET /cart', '/cart')
  check('POST /orders/preview', '预览金额正确且购物车仍原样', preview.data.payAmount === before.data.reduce((sum, item) => sum + item.subtotal, 0), [preview, before])
  const result = await call('POST /orders/checkout', '/orders/checkout', request)
  result.fixtureRequest = request
  assert(result.data?.id && result.data.items?.length === 1, '下单未返回唯一订单/商品行')
  return result
}
async function paidReceived() {
  const order = await buy()
  const payment = await call('POST /payments', '/payments', { orderId: order.data.id, channel: 'MOCK' })
  const paid = await call('POST /payments/{orderId}/mock-pay', '/payments/' + order.data.id + '/mock-pay')
  const authority = await pollAuthority(() => read('GET /orders/{id}', '/orders/' + order.data.id), (value) => value.status === 'PAID')
  check('POST /payments', '金额取自订单，支付记录属于隔离买家', payment.data.orderId === order.data.id && payment.data.amount === order.data.payAmount, [order, payment, authority])
  check('POST /payments/{orderId}/mock-pay', '支付回执SUCCESS且订单MQ投影PAID', paid.data.status === 'SUCCESS' && authority.data.status === 'PAID', [paid, authority])
  const ship = await call('POST /orders/admin/orders/{id}/ship', '/orders/admin/orders/' + order.data.id + '/ship', { carrierCode: 'SF', carrierName: '顺丰速运', trackingNo: prefix + '-' + order.data.id }, 'admin')
  const shipped = await read('GET /orders/{id}', '/orders/' + order.data.id)
  check('POST /orders/admin/orders/{id}/ship', '独立订单真实发货为SHIPPED', shipped.data.status === 'SHIPPED', [ship, shipped])
  const receive = await call('POST /orders/{id}/receive', '/orders/' + order.data.id + '/receive')
  const received = await read('GET /orders/{id}', '/orders/' + order.data.id)
  check('POST /orders/{id}/receive', '买家确认收货RECEIVED', received.data.status === 'RECEIVED', [receive, received])
  return received
}

await group('01身份与管理员权限', async () => {
  for (const [role, suffix] of [['buyer', 'a'], ['other', 'b'], ['roleTest', 'r']]) {
    const password = 'T1' + randomUUID().replaceAll('-', '').slice(0, 16)
    const username = prefix + suffix
    const created = await call('POST /auth/register', '/auth/register', { username, password, nickname: '独立API验收' }, 'anonymous')
    assert(created.data?.token && created.data.user?.id && created.data.user.roleName === 'USER', '注册未返回普通买家身份')
    tokens[role] = created.data.token
    state[role + 'User'] = created.data.user
    const login = await call('POST /auth/login', '/auth/login', { username, password }, 'anonymous')
    check('POST /auth/register', '新账号可登录且编号与注册一致', login.data.user.id === created.data.user.id, [created, login])
    check('POST /auth/login', '登录返回同一用户与令牌', Boolean(login.data.token) && login.data.user.id === created.data.user.id, [created, login])
  }
  const admin = await call('POST /auth/login', '/auth/login', { username: process.env.VERIFY_ADMIN_USERNAME ?? 'admin', password: process.env.VERIFY_ADMIN_PASSWORD ?? '123456' }, 'anonymous')
  assert(admin.data?.user?.roleName === 'ADMIN' && admin.data.token, '管理员登录角色不符')
  tokens.admin = admin.data.token
  const id = state.roleTestUser.id
  const detail = await read('GET /auth/admin/users/{id}', '/auth/admin/users/' + id, 'admin')
  check('GET /auth/admin/users/{id}', '独立角色账号详情一致', detail.data.user.id === id, [detail])
  for (const status of [0, 1]) {
    const updated = await call('PUT /auth/admin/users/{id}/status', '/auth/admin/users/' + id + '/status', { status, reason: '独立验收禁用启用' }, 'admin')
    const current = await read('GET /auth/admin/users/{id}', '/auth/admin/users/' + id, 'admin')
    check('PUT /auth/admin/users/{id}/status', '禁用/启用后权威用户状态一致', current.data.user.status === status, [updated, current])
  }
  for (const role of ['ADMIN', 'USER']) {
    const updated = await call('PUT /auth/admin/users/{id}/role', '/auth/admin/users/' + id + '/role', { role }, 'admin')
    const current = await read('GET /auth/admin/users/{id}', '/auth/admin/users/' + id, 'admin')
    check('PUT /auth/admin/users/{id}/role', '只改独立账号角色且权威结果一致', current.data.user.roleName === role, [updated, current])
  }
})
await group('02地址归属与默认切换', async () => {
  requireFixture('buyerUser')
  const first = await call('POST /auth/addresses', '/auth/addresses', address)
  const second = await call('POST /auth/addresses', '/auth/addresses', { ...address, receiverName: '第二地址', isDefault: false })
  state.addressId = first.data.id
  const all = await getRows('GET /auth/addresses', '/auth/addresses')
  check('POST /auth/addresses', '独立账号只新增两个地址且默认唯一', rows(all.data).length === 2 && rows(all.data).filter((item) => item.isDefault === 1).length === 1, [first, second, all])
  const changed = await call('PUT /auth/addresses/{id}', '/auth/addresses/' + first.data.id, { ...address, receiverName: '已改地址' })
  const current = await read('GET /auth/addresses/{id}', '/auth/addresses/' + first.data.id)
  check('PUT /auth/addresses/{id}', '姓名修改真实落盘', current.data.receiverName === '已改地址', [changed, current])
  check('GET /auth/addresses/{id}', '地址归属与稳定编号', current.data.id === first.data.id, [current])
  const switched = await call('PUT /auth/addresses/{id}/default', '/auth/addresses/' + second.data.id + '/default')
  const defaults = await getRows('GET /auth/addresses', '/auth/addresses')
  check('PUT /auth/addresses/{id}/default', '切换后仅第二地址默认', rows(defaults.data).filter((item) => item.isDefault === 1).map((item) => item.id).join(',') === String(second.data.id), [switched, defaults])
  await deniedUnchanged('PUT /auth/addresses/{id}', '/auth/addresses/' + first.data.id, { ...address, receiverName: '越权不会落盘' }, 'GET /auth/addresses/{id}', '/auth/addresses/' + first.data.id)
  const removed = await call('DELETE /auth/addresses/{id}', '/auth/addresses/' + second.data.id)
  const remaining = await getRows('GET /auth/addresses', '/auth/addresses')
  check('DELETE /auth/addresses/{id}', '只删除自有第二地址且剩余第一地址', rows(remaining.data).length === 1 && remaining.data[0].id === first.data.id, [removed, remaining])
  check('GET /auth/addresses', '列表已真实读回新增/更新/删除结果', rows(remaining.data).length === 1, [remaining])
})
await group('03类目品牌属性完整CRUD', async () => {
  assert(tokens.admin, '管理员夹具缺失')
  const category = await call('POST /categories/admin', '/categories/admin', { parentId: 0, name: prefix, status: 0, sort: 900 }, 'admin')
  state.categoryId = category.data
  const brand = await call('POST /brands/admin', '/brands/admin', { name: prefix, status: 0, description: '独立验收' }, 'admin')
  state.brandId = brand.data
  const tree = await getRows('GET /categories/admin/tree', '/categories/admin/tree', 'admin')
  const brands = await getRows('GET /brands/admin', '/brands/admin', 'admin')
  check('POST /categories/admin', '新类目ID可在权威树定位', flatten(tree.data).some((item) => item.id === category.data && item.name === prefix), [category, tree])
  check('POST /brands/admin', '新品牌可在权威列表唯一定位', brands.data.filter((item) => item.id === brand.data && item.name === prefix).length === 1, [brand, brands])
  const modifiedCategory = await call('PUT /categories/admin/{id}', '/categories/admin/' + category.data, { parentId: 0, name: prefix + '改', sort: 901, status: 0 }, 'admin')
  const modifiedBrand = await call('PUT /brands/admin/{id}', '/brands/admin/' + brand.data, { name: prefix + '改', status: 0, description: '已改说明' }, 'admin')
  const categoryNow = await getRows('GET /categories/admin/tree', '/categories/admin/tree', 'admin')
  const brandNow = await getRows('GET /brands/admin', '/brands/admin', 'admin')
  check('PUT /categories/admin/{id}', '类目改名和排序落盘', flatten(categoryNow.data).some((item) => item.id === category.data && item.name === prefix + '改'), [modifiedCategory, categoryNow])
  check('PUT /brands/admin/{id}', '品牌改名与说明落盘', brandNow.data.some((item) => item.id === brand.data && item.description === '已改说明'), [modifiedBrand, brandNow])
  const attribute = await call('POST /categories/admin/{id}/attributes', '/categories/admin/' + category.data + '/attributes', { name: '验收属性', inputType: 'text', unit: '克' }, 'admin')
  const changedAttribute = await call('PUT /categories/admin/attributes/{attributeId}', '/categories/admin/attributes/' + attribute.data, { name: '已改属性', inputType: 'text', unit: '毫克' }, 'admin')
  const attributes = await getRows('GET /categories/admin/{id}/attributes', '/categories/admin/' + category.data + '/attributes', 'admin')
  check('POST /categories/admin/{id}/attributes', '只新增一项模板且编号一致', attributes.data.length === 1 && attributes.data[0].id === attribute.data, [attribute, attributes])
  check('PUT /categories/admin/attributes/{attributeId}', '参数名和单位修改落盘', attributes.data[0].name === '已改属性' && attributes.data[0].unit === '毫克', [changedAttribute, attributes])
  const deletedAttribute = await call('DELETE /categories/admin/attributes/{attributeId}', '/categories/admin/attributes/' + attribute.data, undefined, 'admin')
  const after = await getRows('GET /categories/admin/{id}/attributes', '/categories/admin/' + category.data + '/attributes', 'admin')
  check('DELETE /categories/admin/attributes/{attributeId}', '删除自有未引用模板后为空', after.data.length === 0, [deletedAttribute, after])
  // 删除契约使用额外未引用资源，不删除稍后商品所用类目品牌。
  const disposableCategory = await call('POST /categories/admin', '/categories/admin', { name: prefix + '待删', parentId: 0 }, 'admin')
  const disposableBrand = await call('POST /brands/admin', '/brands/admin', { name: prefix + '待删' }, 'admin')
  const deleteCategory = await call('DELETE /categories/admin/{id}', '/categories/admin/' + disposableCategory.data, undefined, 'admin')
  const deleteBrand = await call('DELETE /brands/admin/{id}', '/brands/admin/' + disposableBrand.data, undefined, 'admin')
  const treesAfter = await getRows('GET /categories/admin/tree', '/categories/admin/tree', 'admin')
  const brandsAfter = await getRows('GET /brands/admin', '/brands/admin', 'admin')
  check('DELETE /categories/admin/{id}', '仅删除自有无引用类目', !flatten(treesAfter.data).some((item) => item.id === disposableCategory.data), [deleteCategory, treesAfter])
  check('DELETE /brands/admin/{id}', '仅删除自有无引用品牌', !brandsAfter.data.some((item) => item.id === disposableBrand.data), [deleteBrand, brandsAfter])
})
await group('04商品规格库存与删除边界', async () => {
  const image = pngFixture()
  const form = new FormData(); form.append('file', new Blob([image], { type: 'image/png' }), prefix + '.png')
  const media = await evidence.call('POST /products/admin/media/images', '/products/admin/media/images', { form, role: 'admin' })
  assert(/^\/media\/[\w/-]+\.png$/.test(media.data?.url), '上传回执不是同站PNG资源')
  const loaded = await fetch(GW + media.data.url, { signal: AbortSignal.timeout(10000) })
  const bytes = Buffer.from(await loaded.arrayBuffer())
  check('POST /products/admin/media/images', '真实上传可从同站读取原PNG字节', loaded.ok && bytes.equals(image), [media], { assetPath: media.data.url, bytes: bytes.length })
  const request = { name: prefix + '商品', categoryId: requireFixture('categoryId'), brandId: requireFixture('brandId'), status: 0, tags: [], images: [], specs: [{ name: '规格', values: ['一件'] }], attributes: [], skus: [{ price: 1200, originalPrice: 1400, stock: 40, status: 1, specValues: { 规格: '一件' } }] }
  const created = await call('POST /products/admin/spus', '/products/admin/spus', request, 'admin')
  state.spu = created.data
  const detail = await read('GET /products/admin/spus/{id}', '/products/admin/spus/' + state.spu, 'admin')
  assert(detail.data.skus.length === 1, '规格未唯一建立')
  state.sku = detail.data.skus[0].id
  check('POST /products/admin/spus', 'SPU规格SKU一次提交后权威唯一', detail.data.name === request.name && detail.data.skus[0].price === 1200, [created, detail])
  const updated = await call('PUT /products/admin/spus/{id}', '/products/admin/spus/' + state.spu, { ...request, name: prefix + '商品改', skus: [{ ...request.skus[0], id: state.sku }] }, 'admin')
  const changed = await read('GET /products/admin/spus/{id}', '/products/admin/spus/' + state.spu, 'admin')
  check('PUT /products/admin/spus/{id}', '更新保留SKU编号和价格', changed.data.name === prefix + '商品改' && changed.data.skus[0].id === state.sku && changed.data.skus[0].price === 1200, [updated, changed])
  const stock = await call('PUT /products/admin/skus/{skuId}/stock', '/products/admin/skus/' + state.sku + '/stock', { stock: 50, remark: '独立库存验收' }, 'admin')
  const inventoryNow = await read('GET /products/admin/spus/{id}', '/products/admin/spus/' + state.spu, 'admin')
  check('PUT /products/admin/skus/{skuId}/stock', '调整目标库存为50', inventoryNow.data.skus[0].stock === 50, [stock, inventoryNow])
  const on = await call('PUT /products/admin/spus/{id}/status', '/products/admin/spus/' + state.spu + '/status?status=1', undefined, 'admin')
  const publicDetail = await read('GET /products/{id}', '/products/' + state.spu, 'anonymous')
  check('PUT /products/admin/spus/{id}/status', '自有商品上架后匿名详情可读', publicDetail.data.id === state.spu && publicDetail.data.skus[0].id === state.sku, [on, publicDetail])
  await deniedUnchanged('PUT /products/admin/skus/{skuId}/stock', '/products/admin/skus/' + state.sku + '/stock', { stock: 999 }, 'GET /products/admin/spus/{id}', '/products/admin/spus/' + state.spu, 'buyer', 'admin')
  const disposable = await call('POST /products/admin/spus', '/products/admin/spus', { ...request, name: prefix + '未引用待删' }, 'admin')
  const deleted = await call('DELETE /products/admin/spus/{id}', '/products/admin/spus/' + disposable.data, undefined, 'admin')
  const remaining = await getRows('GET /products/admin/spus', '/products/admin/spus?page=0&size=100&keyword=' + encodeURIComponent(prefix), 'admin')
  check('DELETE /products/admin/spus/{id}', '只删自有未被订单引用商品', !rows(remaining.data).some((item) => item.id === disposable.data) && rows(remaining.data).some((item) => item.id === state.spu), [deleted, remaining])
})
await group('05购物车增改删与选择', async () => {
  const added = await call('POST /cart/items', '/cart/items', { skuId: requireFixture('sku'), quantity: 2 })
  const before = await getRows('GET /cart', '/cart')
  assert(before.data.length === 1, '独立买家购物车应只有一行')
  const id = before.data[0].id
  check('POST /cart/items', 'SKU规格数量金额真实读回', before.data[0].skuId === state.sku && before.data[0].quantity === 2 && before.data[0].subtotal === before.data[0].price * 2, [added, before])
  const updated = await call('PUT /cart/items/{id}', '/cart/items/' + id, { quantity: 3 })
  const after = await getRows('GET /cart', '/cart')
  check('PUT /cart/items/{id}', '目标数量3真实落盘', after.data[0].quantity === 3, [updated, after])
  await deniedUnchanged('PUT /cart/items/{id}', '/cart/items/' + id, { quantity: 99 }, 'GET /cart', '/cart')
  for (const [key, path] of [['PUT /cart/selection', '/cart/selection'], ['PUT /cart/items/{id}/selected', '/cart/items/' + id + '/selected']]) {
    const selection = await call(key, path, { selected: false })
    const result = await getRows('GET /cart', '/cart')
    check(key, '选择标记false真实读回', result.data[0].selected === false, [selection, result])
  }
  const removed = await call('DELETE /cart/items/{id}', '/cart/items/' + id)
  const empty = await getRows('GET /cart', '/cart')
  check('DELETE /cart/items/{id}', '删除自有行后购物车为空', empty.data.length === 0, [removed, empty])
  const favorite = await call('POST /favorites/{spuId}', '/favorites/' + state.spu)
  const favoriteRepeat = await call('POST /favorites/{spuId}', '/favorites/' + state.spu)
  const favorites = await getRows('GET /favorites', '/favorites?page=0&size=100')
  check('POST /favorites/{spuId}', '重复收藏唯一一条且商品编号正确', rows(favorites.data).filter((item) => item.spuId === state.spu || item.product?.id === state.spu).length === 1, [favorite, favoriteRepeat, favorites])
  const removeFavorite = await call('DELETE /favorites/{spuId}', '/favorites/' + state.spu)
  const favoritesAfter = await getRows('GET /favorites', '/favorites?page=0&size=100')
  check('DELETE /favorites/{spuId}', '取消收藏后自有夹具不存在', !rows(favoritesAfter.data).some((item) => item.spuId === state.spu || item.product?.id === state.spu), [removeFavorite, favoritesAfter])
})
await group('06订单预览支付和取消', async () => {
  const order = await buy(); state.createdOrder = order.data
  const repeat = await call('POST /orders/checkout', '/orders/checkout', order.fixtureRequest)
  assert(repeat.data.id === order.data.id, '重复下单没有返回首次订单')
  const list = await getRows('GET /orders', '/orders?page=0&size=100')
  check('POST /orders/checkout', '独立订单商品和金额读回', rows(list.data).some((item) => item.id === order.data.id) && order.data.items[0].skuId === state.sku && order.data.totalAmount === 1200, [order, list])
  // 使用订单号另一入口取消，权威结果与ID入口共用。
  const cancelled = await call('POST /orders/by-no/{orderNo}/cancel', '/orders/by-no/' + order.data.orderNo + '/cancel')
  const terminal = await read('GET /orders/{id}', '/orders/' + order.data.id)
  check('POST /orders/by-no/{orderNo}/cancel', '订单号取消后同一ID为CANCELLED', terminal.data.status === 'CANCELLED', [cancelled, terminal])
  const order2 = await buy()
  const cancel = await call('POST /orders/{id}/cancel', '/orders/' + order2.data.id + '/cancel')
  const result = await read('GET /orders/{id}', '/orders/' + order2.data.id)
  check('POST /orders/{id}/cancel', 'ID取消后同订单终态一致', result.data.status === 'CANCELLED', [cancel, result])
  const order3 = await buy(); state.paidOrder = order3.data
  const payment = await call('POST /payments', '/payments', { orderId: order3.data.id, channel: 'MOCK' })
  const paymentNow = await read('GET /payments/{orderId}', '/payments/' + order3.data.id)
  check('POST /payments', '支付单金额和订单一致且单号稳定', paymentNow.data.id === payment.data.id && paymentNow.data.amount === order3.data.payAmount, [payment, paymentNow])
  const unsigned = await call('POST /payments/callback', '/payments/callback', { orderId: order3.data.id, transactionNo: prefix, status: 'SUCCESS' }, 'anonymous', [400, 401, 403])
  const untouched = await read('GET /payments/{orderId}', '/payments/' + order3.data.id)
  assert(untouched.data.status === 'PENDING', '未签名回调改变支付状态')
  state.callbackRejection = { requestId: unsigned.requestId, unchanged: untouched.data.status }
  evidence.manual('POST /payments/callback', '已验证未签名回调拒绝与支付不变；签名成功请求必须用mock-pay的同链路日志/外部逐接口证据，不读取密钥')
  const mock = await call('POST /payments/{orderId}/mock-pay', '/payments/' + order3.data.id + '/mock-pay')
  const paid = await pollAuthority(() => read('GET /orders/{id}', '/orders/' + order3.data.id), (value) => value.status === 'PAID')
  check('POST /payments/{orderId}/mock-pay', '真实支付成功与订单MQ投影', mock.data.status === 'SUCCESS' && paid.data.status === 'PAID', [mock, paid])
  await deniedUnchanged('POST /orders/{id}/cancel', '/orders/' + order3.data.id + '/cancel', undefined, 'GET /orders/{id}', '/orders/' + order3.data.id)
  const remark = await call('PUT /orders/admin/orders/{id}/remark', '/orders/admin/orders/' + order3.data.id + '/remark', { remark: '独立接口备注' }, 'admin')
  const detail = await read('GET /orders/admin/orders/{id}', '/orders/admin/orders/' + order3.data.id, 'admin')
  check('PUT /orders/admin/orders/{id}/remark', '管理员备注权威落盘', detail.data.adminRemark === '独立接口备注', [remark, detail])
})
await group('07物流发货补录收货', async () => {
  const order = requireFixture('paidOrder')
  const ship = await call('POST /orders/admin/orders/{id}/ship', '/orders/admin/orders/' + order.id + '/ship', { carrierCode: 'SF', carrierName: '顺丰速运', trackingNo: prefix + '-' + order.id }, 'admin')
  const logisticsBefore = await read('GET /orders/{id}/logistics', '/orders/' + order.id + '/logistics')
  check('POST /orders/admin/orders/{id}/ship', '发货轨迹真实存在且运单一致', logisticsBefore.data.trackingNo === prefix + '-' + order.id && logisticsBefore.data.steps.length > 0, [ship, logisticsBefore])
  const trace = await call('POST /orders/admin/orders/{id}/traces', '/orders/admin/orders/' + order.id + '/traces', { status: 'IN_TRANSIT', location: '上海验收分拨', description: prefix + '物流节点' }, 'admin')
  const logistics = await read('GET /orders/{id}/logistics', '/orders/' + order.id + '/logistics')
  const status = await read('GET /orders/{id}', '/orders/' + order.id)
  check('POST /orders/admin/orders/{id}/traces', '补录新增一条轨迹但订单仍SHIPPED', logistics.data.steps.length === logisticsBefore.data.steps.length + 1 && logistics.data.steps.some((step) => step.location === '上海验收分拨') && status.data.status === 'SHIPPED', [trace, logistics, status])
  const receive = await call('POST /orders/{id}/receive', '/orders/' + order.id + '/receive')
  const received = await read('GET /orders/{id}', '/orders/' + order.id)
  state.receivedOrder = received.data
  check('POST /orders/{id}/receive', '收货状态RECEIVED且商品行不变', received.data.status === 'RECEIVED' && received.data.items[0].skuId === state.sku, [receive, received])
})
await group('08评价投票商家回复与状态', async () => {
  const order = requireFixture('receivedOrder')
  const request = { orderId: order.id, orderItemId: order.items[0].id, rating: 4, content: prefix + '独立评价', anonymous: false, images: [] }
  const created = await call('POST /reviews', '/reviews', request)
  state.reviewId = created.data.id
  const mine = await getRows('GET /reviews/mine', '/reviews/mine?page=0&size=100')
  check('POST /reviews', '评价只新增一条且订单行/内容一致', rows(mine.data).filter((item) => item.id === created.data.id && item.content === request.content && item.orderItemId === request.orderItemId).length === 1, [created, mine])
  const published = await call('PUT /reviews/admin/reviews/{id}/status', '/reviews/admin/reviews/' + created.data.id + '/status', { status: 'PUBLISHED' }, 'admin')
  const publishedDetail = await read('GET /reviews/admin/reviews/{id}', '/reviews/admin/reviews/' + created.data.id, 'admin')
  check('PUT /reviews/admin/reviews/{id}/status', '新账号保护评价经管理员通过后可公开', publishedDetail.data.review.status === 'PUBLISHED', [published, publishedDetail])
  const useful = await call('POST /reviews/{id}/useful', '/reviews/' + created.data.id + '/useful', undefined, 'other')
  const duplicate = await call('POST /reviews/{id}/useful', '/reviews/' + created.data.id + '/useful', undefined, 'other', [409])
  const detail = await read('GET /reviews/admin/reviews/{id}', '/reviews/admin/reviews/' + created.data.id, 'admin')
  check('POST /reviews/{id}/useful', '同账号重复投票409且计数只加1', detail.data.review.usefulCount === 1, [useful, duplicate, detail])
  const reply = await call('PUT /reviews/admin/reviews/{id}/reply', '/reviews/admin/reviews/' + created.data.id + '/reply', { content: prefix + '商家回复' }, 'admin')
  const replied = await read('GET /reviews/admin/reviews/{id}', '/reviews/admin/reviews/' + created.data.id, 'admin')
  check('PUT /reviews/admin/reviews/{id}/reply', '商家回复原文落盘', replied.data.review.replyContent === prefix + '商家回复', [reply, replied])
  for (const status of ['HIDDEN', 'PUBLISHED']) {
    const update = await call('PUT /reviews/admin/reviews/{id}/status', '/reviews/admin/reviews/' + created.data.id + '/status', { status, reason: '独立验收隐藏与恢复' }, 'admin')
    const authoritative = await read('GET /reviews/admin/reviews/{id}', '/reviews/admin/reviews/' + created.data.id, 'admin')
    check('PUT /reviews/admin/reviews/{id}/status', '评价权威状态' + status, authoritative.data.review.status === status, [update, authoritative])
  }
})
await group('09优惠券真实领取', async () => {
  const available = await getRows('GET /coupons/available', '/coupons/available')
  const coupon = rows(available.data).find((item) => item.id && item.status !== 0 && (item.remainingCount == null || item.remainingCount > 0))
  assert(coupon, '没有可领取模板，不能替换成任意券ID')
  state.couponId = coupon.id
  const received = await call('POST /coupons/{id}/receive', '/coupons/' + coupon.id + '/receive')
  const mine = await getRows('GET /coupons/mine', '/coupons/mine?status=UNUSED')
  check('POST /coupons/{id}/receive', '领取后券包唯一新增正确模板', rows(mine.data).filter((item) => item.couponId === coupon.id).length === 1, [received, mine])
  check('GET /coupons/available', '模板与领取回执同一ID', received.data.couponId === coupon.id, [available, received])
})
await group('10售后申请取消审核退款', async () => {
  const received = await paidReceived()
  const preview = await read('GET /after-sales/preview', '/after-sales/preview?orderItemId=' + received.data.items[0].id + '&type=RETURN_REFUND&qualityIssue=true')
  assert(preview.data.eligible === true, '真实新收货订单售后前提不成立')
  check('GET /after-sales/preview', '真实收货订单可申请且最大退款不超过实付', preview.data.eligible === true && preview.data.maxRefundAmount <= received.data.items[0].subtotal, [received, preview])
  const request = { orderItemId: received.data.items[0].id, type: 'RETURN_REFUND', reason: '包装破损', description: prefix + '售后原文', qualityIssue: true }
  const applied = await call('POST /after-sales', '/after-sales', request)
  const cancel = await call('POST /after-sales/{id}/cancel', '/after-sales/' + applied.data.id + '/cancel')
  const cancelled = await read('GET /after-sales/{id}', '/after-sales/' + applied.data.id)
  check('POST /after-sales/{id}/cancel', '自有申请撤销为CANCELLED', cancelled.data.afterSale?.status === 'CANCELLED' || cancelled.data.status === 'CANCELLED', [cancel, cancelled])
  const order2 = await paidReceived()
  const applied2 = await call('POST /after-sales', '/after-sales', { ...request, orderItemId: order2.data.items[0].id })
  state.afterSaleId = applied2.data.id
  const audit = await call('POST /after-sales/admin/after-sales/{id}/audit', '/after-sales/admin/after-sales/' + state.afterSaleId + '/audit?approved=true&remark=' + encodeURIComponent('独立验收同意'), undefined, 'admin')
  const approved = await read('GET /after-sales/{id}', '/after-sales/' + state.afterSaleId)
  check('POST /after-sales/admin/after-sales/{id}/audit', '审核后权威APPROVED', (approved.data.afterSale ?? approved.data).status === 'APPROVED', [audit, approved])
  const back = await call('POST /after-sales/{id}/ship-back', '/after-sales/' + state.afterSaleId + '/ship-back', { carrier: '顺丰速运', trackingNo: prefix + '-return' })
  const returning = await read('GET /after-sales/{id}', '/after-sales/' + state.afterSaleId)
  check('POST /after-sales/{id}/ship-back', '寄回原运单与RETURNING状态落盘', (returning.data.afterSale ?? returning.data).status === 'RETURNING', [back, returning])
  const finish = await call('POST /after-sales/admin/after-sales/{id}/received', '/after-sales/admin/after-sales/' + state.afterSaleId + '/received', undefined, 'admin')
  const refunds = await pollAuthority(() => read('GET /payments/refunds/{orderId}', '/payments/refunds/' + order2.data.id), (value) => Array.isArray(value) && value.length === 1)
  const final = await read('GET /after-sales/{id}', '/after-sales/' + state.afterSaleId)
  check('POST /after-sales/admin/after-sales/{id}/received', '售后完成且只产生一次正确退款', (final.data.afterSale ?? final.data).status === 'FINISHED' && refunds.data[0].afterSaleId === state.afterSaleId && refunds.data[0].amount === applied2.data.refundAmount, [finish, final, refunds])
  check('POST /after-sales', '申请原文/行ID真实读回', (final.data.afterSale ?? final.data).orderItemId === order2.data.items[0].id, [applied2, final])
  evidence.block('POST /after-sales/admin/after-sales/{id}/retry-refund', '本次真实退款成功，没有FAILED退款夹具；不能把终态409当成功重试证明')
})
await group('11工单消息球权解决关闭重开', async () => {
  const create = await call('POST /tickets', '/tickets', { category: 'OTHER', title: prefix + '工单', content: prefix + '问题原文' })
  const id = create.data.ticket.id; state.ticketId = id
  const first = await read('GET /tickets/{id}', '/tickets/' + id)
  check('POST /tickets', '只建一张工单且第一条原文一致', first.data.ticket.id === id && first.data.messages.length === 1 && first.data.messages[0].content === prefix + '问题原文', [create, first])
  const message = await call('POST /tickets/{id}/messages', '/tickets/' + id + '/messages', { content: prefix + '补充' })
  const buyerMessage = await read('GET /tickets/{id}', '/tickets/' + id)
  check('POST /tickets/{id}/messages', '买家补充只增加一条原文', buyerMessage.data.messages.length === 2 && buyerMessage.data.messages.at(-1).content === prefix + '补充', [message, buyerMessage])
  await deniedUnchanged('POST /tickets/{id}/messages', '/tickets/' + id + '/messages', { content: '越权' }, 'GET /tickets/{id}', '/tickets/' + id)
  const reply = await call('POST /tickets/admin/tickets/{id}/reply', '/tickets/admin/tickets/' + id + '/reply', { content: prefix + '客服原文' }, 'admin')
  const replied = await read('GET /tickets/{id}', '/tickets/' + id)
  check('POST /tickets/admin/tickets/{id}/reply', '客服回复推进PROCESSING并落原文', replied.data.ticket.status === 'PROCESSING' && replied.data.messages.at(-1).content === prefix + '客服原文', [reply, replied])
  const stream = await evidence.call('GET /tickets/stream', '/tickets/stream', { stream: true })
  const awaiting = await read('GET /tickets/summary', '/tickets/summary')
  check('GET /tickets/stream', 'SSE初始球权与同用户summary一致并只包含本工单', stream.data.awaitingMe === awaiting.data.awaitingMe && stream.data.ticketIds.includes(id), [stream, awaiting])
  const resolveTicket = await call('POST /tickets/admin/tickets/{id}/resolve', '/tickets/admin/tickets/' + id + '/resolve', { content: prefix + '解决说明' }, 'admin')
  const resolved = await read('GET /tickets/{id}', '/tickets/' + id)
  check('POST /tickets/admin/tickets/{id}/resolve', '解决状态与说明落盘', resolved.data.ticket.status === 'RESOLVED', [resolveTicket, resolved])
  const reopen = await call('POST /tickets/{id}/reopen', '/tickets/' + id + '/reopen', { content: prefix + '重开说明' })
  const reopened = await read('GET /tickets/{id}', '/tickets/' + id)
  check('POST /tickets/{id}/reopen', '重开恢复PROCESSING且说明落盘', reopened.data.ticket.status === 'PROCESSING', [reopen, reopened])
  const close = await call('POST /tickets/{id}/close', '/tickets/' + id + '/close')
  const closed = await read('GET /tickets/{id}', '/tickets/' + id)
  check('POST /tickets/{id}/close', '买家关闭CLOSED终态', closed.data.ticket.status === 'CLOSED', [close, closed])
  const create2 = await call('POST /tickets', '/tickets', { category: 'OTHER', title: prefix + '客服关闭', content: prefix + '客服关闭问题' })
  const adminClose = await call('POST /tickets/admin/tickets/{id}/close', '/tickets/admin/tickets/' + create2.data.ticket.id + '/close', { reason: '独立API终态关闭' }, 'admin')
  const adminClosed = await read('GET /tickets/{id}', '/tickets/' + create2.data.ticket.id)
  check('POST /tickets/admin/tickets/{id}/close', '管理员真实关闭而非仅拦截接线', adminClosed.data.ticket.status === 'CLOSED', [adminClose, adminClosed])
})

// 公开读接口逐条使用本次fixture绑定，无法绑定不发占位ID。
await group('12公共读取与诊断契约', async () => {
  const subject = requireFixture('spu')
  const documentRequest = { title: prefix + '接口验收说明', source: 'manual', scope: 'after_sale', version: 'api-v1', tags: ['接口验收'], content: '这是一份独立接口验收说明。验收操作只用于验证文档写入、切片和商品归属，不对真实业务作出政策承诺。', subjectSpuIds: [subject] }
  const created = await call('POST /knowledge/admin/documents', '/knowledge/admin/documents', documentRequest, 'admin')
  state.ownedDocNo = created.data
  state.ownedDocTitle = documentRequest.title
  state.ownedDocCreationRequestId = created.requestId
  assert(/^KB-\d{4,}$/.test(created.data), '创建文档未返回真实编号')
  const ownCreated = await read('GET /knowledge/documents/{docNo}', '/knowledge/documents/' + state.ownedDocNo, 'anonymous')
  assert(verifyOwnedDocumentFixture(ownCreated.data, state, evidence.calls, [0, 1]), '文档停用前原POST/编号/标题/状态归属不符')
  // 新文档默认启用；这里立刻停用，后台模型和索引重建由主会话明确触发。
  const stopped = await call('PUT /knowledge/admin/documents/{docNo}/status', '/knowledge/admin/documents/' + state.ownedDocNo + '/status?status=0', undefined, 'admin')
  const ownStopped = await read('GET /knowledge/documents/{docNo}', '/knowledge/documents/' + state.ownedDocNo, 'anonymous')
  assert(verifyOwnedDocumentFixture(ownStopped.data, state, evidence.calls), '文档更新前自有归属或停用状态不符')
  const updated = await call('POST /knowledge/admin/documents', '/knowledge/admin/documents', { ...documentRequest, docNo: state.ownedDocNo, version: 'api-v2', content: documentRequest.content + '修改后的版本应保留停用状态。' }, 'admin')
  const testDocument = await read('GET /knowledge/documents/{docNo}', '/knowledge/documents/' + created.data, 'anonymous')
  check('POST /knowledge/admin/documents', '新建与更新保持同编号、正文版本和停用状态', updated.data === created.data && testDocument.data.version === 'api-v2' && testDocument.data.status === 0 && testDocument.data.content.endsWith('修改后的版本应保留停用状态。'), [created, updated, testDocument])
  check('PUT /knowledge/admin/documents/{docNo}/status', '停用文档仍可溯源读取但不当活跃语料', testDocument.data.status === 0, [stopped, testDocument])
  const subjects = await getRows('GET /knowledge/admin/documents/{docNo}/bindings', '/knowledge/admin/documents/' + created.data + '/bindings', 'admin')
  const productBindings = await getRows('GET /knowledge/admin/products/{spuId}/bindings', '/knowledge/admin/products/' + subject + '/bindings', 'admin')
  check('GET /knowledge/admin/documents/{docNo}/bindings', '文档归属精确为本轮自有商品', subjects.data.length === 1 && subjects.data[0] === subject, [created, subjects])
  check('GET /knowledge/admin/products/{spuId}/bindings', '商品归属反向可读同一文档', productBindings.data.includes(created.data), [created, productBindings])
  assert(testDocument.data.chunks.length > 0, '正文写入没有产生切片')
  const testChunk = await read('GET /knowledge/chunks/{chunkId}', '/knowledge/chunks/' + encodeURIComponent(testDocument.data.chunks[0].chunkId), 'anonymous')
  check('GET /knowledge/chunks/{chunkId}', '切片文档版本与偏移原文一致', testChunk.data.docNo === created.data && testChunk.data.version === 'api-v2' && testChunk.data.documentContent === testDocument.data.content && testChunk.data.charOffset >= 0 && testChunk.data.charEnd <= testDocument.data.content.length, [testDocument, testChunk])
  const documents = await getRows('GET /knowledge/documents', '/knowledge/documents', 'anonymous')
  const doc = rows(documents.data)[0]
  if (doc) {
    state.readDocNo = doc.docNo
    const detail = await read('GET /knowledge/documents/{docNo}', '/knowledge/documents/' + encodeURIComponent(doc.docNo), 'anonymous')
    check('GET /knowledge/documents/{docNo}', '文档编号版本和原文完整', detail.data.docNo === doc.docNo && typeof detail.data.content === 'string' && detail.data.version === doc.version, [documents, detail])
    state.chunkId = detail.data.chunks?.[0]?.chunkId
  }
  const failures = await getRows('GET /knowledge/admin/graph/failures', '/knowledge/admin/graph/failures?limit=20', 'admin')
  state.failureId = rows(failures.data)[0]?.id
  const placeholders = { id: state.spu, spuId: state.spu, skuId: state.sku, orderId: state.receivedOrder?.id ?? state.paidOrder?.id, docNo: state.readDocNo, chunkId: state.chunkId, spuKey: 'SPU' + state.spu, attributeId: undefined }
  const query = { '/products/summaries': '?ids=' + state.spu, '/products/skus': '?ids=' + state.sku, '/favorites/check': '?spuIds=' + state.spu, '/products/suggest': '?q=' + encodeURIComponent('鱼油'), '/products/search': '?keyword=' + encodeURIComponent('鱼油'), '/knowledge/graph/entity': '?name=' + encodeURIComponent('华法林') + '&depth=2', '/knowledge/graph/search': '?keyword=' + encodeURIComponent('维生素'), '/knowledge/graph/recall': '?query=' + encodeURIComponent('华法林和鱼油') + '&limit=5', '/knowledge/graph/interactions': '?items=' + encodeURIComponent('华法林,鱼油') }
  const skipped = new Set(['/ai/sessions/{sessionId}/messages', '/ai/sessions/{sessionId}/bad-cases', '/ai/sessions/{sessionId}/messages/{messageId}/bad-case', '/tickets/stream'])
  for (const contract of matrix.apiContracts.filter((item) => item.method === 'GET')) {
    const key = 'GET ' + contract.path
    if (evidence.contracts.get(key).status === 'PASS') continue
    if (skipped.has(contract.path)) { evidence.block(key, '需要实际会话或SSE专项证据，不能用不存在的session占位'); continue }
    const ids = { ...placeholders }
    if (contract.path.startsWith('/knowledge/admin/documents/')) ids.docNo = state.ownedDocNo
    if (contract.path.startsWith('/orders/')) ids.id = state.receivedOrder?.id ?? state.paidOrder?.id
    if (contract.path.startsWith('/tickets/')) ids.id = state.ticketId
    if (contract.path.startsWith('/after-sales/')) ids.id = state.afterSaleId
    if (contract.path.startsWith('/reviews/admin/')) ids.id = state.reviewId
    if (contract.path.startsWith('/auth/addresses/')) ids.id = state.addressId
    if (contract.path.startsWith('/auth/admin/')) ids.id = state.roleTestUser?.id
    if (contract.path.startsWith('/categories/admin/')) ids.id = state.categoryId
    if (contract.path.startsWith('/ai/admin/bad-cases/')) ids.id = undefined
    if (contract.path.startsWith('/knowledge/admin/graph/failures/')) ids.id = state.failureId
    let path = contract.path.replace(/\{([^}]+)\}/g, (_, field) => ids[field] == null ? '[MISSING]' : encodeURIComponent(ids[field]))
    if (path.includes('[MISSING]') || contract.path === '/after-sales/preview') { evidence.block(key, '缺对应真实fixture：' + contract.path); continue }
    path += query[contract.path] ?? ''
    const role = /\/admin(?:\/|$)/.test(path) ? 'admin' : contract.path.startsWith('/knowledge/') || ['/products', '/brands', '/categories/tree'].includes(contract.path) ? 'anonymous' : 'buyer'
    try {
      const result = await read(key, path, role)
      check(key, '已绑定真实对象的只读响应契约字段完整', validateReadResponse(contract.path, result.data), [result], { contract: contract.path, emptyIsValid: Array.isArray(result.data) && result.data.length === 0 })
      if (/\/admin(?:\/|$)/.test(path)) {
        const denied = await call(key, path, undefined, 'buyer', [401, 403])
        const anonymous = await call(key, path, undefined, 'anonymous', [401, 403])
        check(key, '管理员读接口拒绝普通用户和匿名身份', denied.code !== 200 && anonymous.code !== 200, [denied, anonymous])
      }
    } catch (error) { evidence.block(key, error.message) }
  }
})

await group('隔离夹具收尾', async () => {
  state.hotKeywordCleanup = cleanupFixtureSearches(state.fixtureSearchLedger)
  if (state.ownedDocNo) {
    const owned = await read('GET /knowledge/documents/{docNo}', '/knowledge/documents/' + state.ownedDocNo, 'anonymous')
    assert(verifyOwnedDocumentFixture(owned.data, state, evidence.calls, [0, 1]), '收尾文档不属于本轮原POST，拒绝改动')
    if (owned.data.status !== 0) await call('PUT /knowledge/admin/documents/{docNo}/status', '/knowledge/admin/documents/' + state.ownedDocNo + '/status?status=0', undefined, 'admin')
    const stopped = await read('GET /knowledge/documents/{docNo}', '/knowledge/documents/' + state.ownedDocNo, 'anonymous')
    assert(verifyOwnedDocumentFixture(stopped.data, state, evidence.calls), '自有文档收尾后仍启用或归属不符')
  }
  if (state.spu) {
    const offline = await call('PUT /products/admin/spus/{id}/status', '/products/admin/spus/' + state.spu + '/status?status=0', undefined, 'admin')
    const current = await read('GET /products/admin/spus/{id}', '/products/admin/spus/' + state.spu, 'admin')
    check('PUT /products/admin/spus/{id}/status', '验收商品收尾下架且历史订单引用保留', current.data.status === 0 && current.data.id === state.spu, [offline, current])
  }
  for (const item of state.buyerUser ? rows((await read('GET /cart', '/cart')).data) : []) {
    const removed = await call('DELETE /cart/items/{id}', '/cart/items/' + item.id)
    const after = await getRows('GET /cart', '/cart')
    check('DELETE /cart/items/{id}', '只清理隔离账号本轮购物车残留', !after.data.some((row) => row.id === item.id), [removed, after])
  }
  const publicCategories = await getRows('GET /categories/tree', '/categories/tree', 'anonymous')
  const publicBrands = await getRows('GET /brands', '/brands', 'anonymous')
  assert(!flatten(publicCategories.data).some((item) => item.id === state.categoryId), '验收类目泄漏到公开导航')
  assert(!publicBrands.data.some((item) => item.id === state.brandId), '验收品牌泄漏到公开筛选')
})

// 不从页面状态报告、HTTP200或脚本总数推测剩余接口成功。
for (const contract of matrix.apiContracts) {
  const key = contract.method + ' ' + contract.path
  if (contract.kind === 'MANUAL_MODEL_RUN') evidence.manual(key, '须人工真实评测/重建runId与完整请求/用量证据，默认不触发模型')
  else if (evidence.contracts.get(key).status === 'UNEXECUTED') evidence.block(key, key.startsWith('POST /ai/') || key.startsWith('PUT /ai/') || key.startsWith('DELETE /ai/') || key.startsWith('GET /ai/admin/bad-cases/')
    ? '需真实模型会话/稳定消息/审批和反馈标注夹具，并关联已执行专项的逐接口证据；默认不调用模型或操作别人会话'
    : '本组前提或该主写恢复场景尚无权威fixture：' + contract.authority)
}
const externalArgument = args.find((arg) => arg.startsWith('--evidence='))?.slice(11)
if (externalArgument) {
  const externalPath = resolve(ROOT, externalArgument)
  assert(externalPath.startsWith(resolve(ROOT, '.evidence') + '\\') || externalPath.startsWith(resolve(ROOT, '.evidence') + '/'), '外部证据仅从frontend/.evidence读取')
  const external = JSON.parse(await readFile(externalPath, 'utf8'))
  assert(Array.isArray(external.endpoints), '外部证据必须逐method/path，不接受汇总PASS数')
  for (const endpoint of external.endpoints) if (evidence.contracts.has(endpoint.key)) {
    const proof = endpoint.evidence
    if (!proof?.path) { evidence.manual(endpoint.key, endpoint.reason ?? '缺原始报告路径'); continue }
    const sourcePath = resolve(ROOT, proof.path)
    assert(sourcePath.startsWith(resolve(ROOT, '.evidence') + '\\') || sourcePath.startsWith(resolve(ROOT, '.evidence') + '/'), '原始报告只允许读取忽略证据目录')
    const original = JSON.parse(await readFile(sourcePath, 'utf8'))
    const verifiedSource = verifyExternalEvidence(endpoint.key, proof, original)
    evidence.manual(endpoint.key, '外部证据须匹配原始runId、逐接口PASS断言与真实请求，不能只填汇总', { ...proof, verifiedSource })
  }
}
const report = { ...evidence.report(), startedAt, finishedAt: new Date().toISOString(), groups, fixtureOwner: state.buyerUser?.id,
  sourceVersion, fixtures: redact(state),
  limits: ['公开读默认仅验证本次响应契约，业务语义由对应逐接口专项补充', '拒绝分支不代替成功主写', '不触发模型、不读取密钥、不伪造支付签名', '没有FAILED退款fixture不记重试成功', '运行产物只放忽略目录'] }
await mkdir(resolve(ROOT, '.evidence'), { recursive: true })
await writeFile(output, JSON.stringify(report, null, 2) + '\n', 'utf8')
console.log(JSON.stringify(report.summary) + '\n逐接口报告=' + output)
if (groups.some((item) => item.status !== 'PASS') || report.endpoints.some((item) => item.status !== 'PASS')) process.exitCode = 1
