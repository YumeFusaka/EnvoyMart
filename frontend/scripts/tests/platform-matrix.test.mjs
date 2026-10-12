import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { routePagesFromSource, routeCoverage, controllerApisFromSource, sourceApiInventory, apiCoverage, validateEvidenceReferences } from '../verify-platform-matrix.mjs'

test('真实页面与平台矩阵逐项一致，不能以相同总数代替覆盖', async () => {
  const matrix = JSON.parse(await readFile(new URL('../fixtures/platform-feature-matrix.json', import.meta.url), 'utf8'))
  const pages = routePagesFromSource(await readFile(new URL('../../src/router/index.ts', import.meta.url), 'utf8'))
  assert.equal(pages.length, 38)
  assert.ok(pages.includes('/knowledge/:docNo'))
  assert.deepEqual(routeCoverage(matrix.routes, pages), { missing: [], nonexistent: [], duplicates: [] })
})

test('嵌套布局解析叶子页、绝对路径和空路径，排除重定向与注释伪路由', () => {
  const source = `createRouter({routes: [
    {path: '/', component: Layout, children: [{path: '', redirect: '/shop'}, {path: 'shop', component: Shop}]},
    {path: '/admin', component: AdminLayout, children: [{path: '', component: Dashboard}, {path: 'knowledge', component: KnowledgeLayout, children: [{path: '', component: Documents}, {path: '/detail/:id', component: Detail}]}]},
    // {path: '/假页面', component: Missing}
    {path: '/:pathMatch(.*)*', component: NotFound}
  ]})`
  assert.deepEqual(routePagesFromSource(source), ['/:pathMatch(.*)*', '/admin', '/admin/knowledge', '/detail/:id', '/shop'])
})

test('相同总数中的替换、重复和动态声明不会产生假绿灯', () => {
  assert.deepEqual(routeCoverage([{ path: '/shop' }, { path: '/fake' }], ['/shop', '/knowledge/:docNo']), {
    missing: ['/knowledge/:docNo'], nonexistent: ['/fake'], duplicates: [],
  })
  assert.deepEqual(routeCoverage([{ path: '/shop' }, { path: '/shop' }], ['/shop']).duplicates, ['/shop'])
  assert.throws(() => routePagesFromSource('createRouter({routes: getRoutes()})'), /静态配置/)
  assert.throws(() => routePagesFromSource("createRouter({routes: [{path: '/shop', component: Shop}, ...extra]})"), /静态对象/)
})

test('公开 API 清单覆盖控制器方法路径并遵循网关内部排除，不能只写买家主链', async () => {
  const matrix = JSON.parse(await readFile(new URL('../fixtures/platform-feature-matrix.json', import.meta.url), 'utf8'))
  const inventory = await sourceApiInventory(fileURLToPath(new URL('../../../backend/', import.meta.url)))
  assert.deepEqual(apiCoverage(matrix.apiContracts, inventory.publicApis), { missing: [], nonexistent: [], duplicates: [] })
  assert.ok(inventory.publicApis.some((api) => api.path === '/ai/sessions/{sessionId}/messages/{messageId}/bad-case' && api.method === 'PUT'))
  assert.ok(inventory.internalApis.some((api) => api.path === '/products/stock/deduct'))
  assert.ok(!matrix.apiContracts.some((api) => api.path === '/products/stock/deduct'))
  assert.equal(matrix.apiContracts.find((api) => api.path === '/orders/preview').kind, 'READ_ONLY_POST')
})

test('控制器注解支持空路径、显式value、完整限定名且忽略注释伪接口', () => {
  const source = `@RestController @RequestMapping("/ai/sessions") public class Sessions {
    // @DeleteMapping("/fake")
    @GetMapping public Object list() {return null;}
    @org.springframework.web.bind.annotation.PutMapping("/{id}/bad-case") public Object put() {return null;}
    @PostMapping(value="/stream", produces="text/event-stream") public Object stream() {return null;}
  }`
  assert.deepEqual(controllerApisFromSource(source).map(({ method, path }) => ({ method, path })), [
    { method: 'GET', path: '/ai/sessions' }, { method: 'PUT', path: '/ai/sessions/{id}/bad-case' }, { method: 'POST', path: '/ai/sessions/stream' },
  ])
  assert.throws(() => controllerApisFromSource('@RestController class Bad { @GetMapping(PATH) Object get() {} }'), /动态路径/)
  assert.deepEqual(apiCoverage([{ method: 'POST', path: '/same' }], [{ method: 'GET', path: '/same' }]), {
    missing: ['GET /same'], nonexistent: ['POST /same'], duplicates: [],
  })
})

test('38路由五类状态和所有主写动作明确证据边界，候选脚本不能当PASS', async () => {
  const matrix = JSON.parse(await readFile(new URL('../fixtures/platform-feature-matrix.json', import.meta.url), 'utf8'))
  for (const route of matrix.routes) for (const state of ['loading', 'empty', 'error', 'permission', 'write']) {
    const evidence = matrix.routeEvidence[route.id][state]
    assert.ok(['UNCOVERED', 'SCRIPTED', 'NOT_APPLICABLE'].includes(evidence.status))
    assert.ok(evidence.reason && evidence.expectation)
    for (const script of evidence.scripts) await readFile(new URL('../' + script, import.meta.url), 'utf8')
  }
  for (const api of matrix.apiContracts) {
    assert.ok(api.source && api.line && api.authority && api.expectation && api.reason)
    assert.ok(['UNCOVERED', 'SCRIPTED', 'MANUAL'].includes(api.status))
    for (const script of api.scripts) await readFile(new URL('../' + script, import.meta.url), 'utf8')
  }
  assert.deepEqual(await validateEvidenceReferences(matrix, fileURLToPath(new URL('../', import.meta.url))), [])
  const invalid = { apiContracts: [{ status: 'SCRIPTED', scripts: [], expectation: '没有断言' }] }
  assert.match((await validateEvidenceReferences(invalid, fileURLToPath(new URL('../', import.meta.url)))).join(''), /缺逐项断言/)
})
