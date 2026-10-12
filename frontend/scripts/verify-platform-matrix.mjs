import { readFixture, printContractSummary } from './lib/verify-util.mjs'
import { readFile, readdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

/** 只解析配置，不执行浏览器模块；布局与重定向不计为独立页面。 */
export function routePagesFromSource(source) {
  const tree = ts.createSourceFile('router.ts', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  if (tree.parseDiagnostics.length) throw new Error('路由源码无法解析，禁止按旧矩阵放行')
  const property = (object, key) => object.properties.find((p) => ts.isPropertyAssignment(p) && p.name.getText(tree).replace(/^['"]|['"]$/g, '') === key)?.initializer
  let routes
  const visit = (node) => {
    if (ts.isCallExpression(node) && node.expression.getText(tree) === 'createRouter') {
      const config = node.arguments[0]
      if (!config || !ts.isObjectLiteralExpression(config) || routes) throw new Error('路由配置必须有唯一可解析的 createRouter')
      routes = property(config, 'routes')
    }
    ts.forEachChild(node, visit)
  }
  visit(tree)
  if (!routes || !ts.isArrayLiteralExpression(routes)) throw new Error('路由数组不是静态配置，必须更新验收解析契约')
  const pages = []
  const walk = (array, parent = '') => {
    for (const route of array.elements) {
      if (!ts.isObjectLiteralExpression(route)) throw new Error('路由条目不是静态对象，禁止漏计')
      const rawPath = property(route, 'path')
      if (!rawPath || !ts.isStringLiteralLike(rawPath)) throw new Error('路由 path 不是字面量，禁止漏计')
      const path = rawPath.text.startsWith('/') ? rawPath.text : `${parent}/${rawPath.text}`.replace(/\/{2,}/g, '/')
      const normalized = path === '/' ? path : path.replace(/\/$/, '')
      const children = property(route, 'children')
      if (children) {
        if (!ts.isArrayLiteralExpression(children)) throw new Error('子路由不是静态数组，禁止漏计')
        walk(children, normalized)
      } else if (property(route, 'component') && !property(route, 'redirect')) pages.push(normalized)
    }
  }
  walk(routes)
  if (new Set(pages).size !== pages.length) throw new Error('真实页面路由重复，禁止用总数掩盖')
  return pages.sort()
}

export function routeCoverage(matrixRoutes, pages) {
  const declared = matrixRoutes.map((route) => route.path)
  return {
    missing: pages.filter((path) => !declared.includes(path)),
    nonexistent: declared.filter((path) => !pages.includes(path)),
    duplicates: declared.filter((path, index) => declared.indexOf(path) !== index),
  }
}

function uncomment(source) {
  return source.replace(/("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|\/\*[\s\S]*?\*\/|\/\/[^\r\n]*/g,
    (text, literal) => literal ?? text.replace(/[^\r\n]/g, ' '))
}

/** 当前控制器只使用静态单路径注解；遇到新形状显式失败，不能悄悄漏接口。 */
export function controllerApisFromSource(source, sourcePath = '') {
  const code = uncomment(source)
  if (!/@RestController\b/.test(code)) return []
  const classAt = code.search(/\bclass\s+\w+/)
  if (classAt < 0) throw new Error('控制器缺少 class：' + sourcePath)
  const mappings = [...code.matchAll(/@(?:org\.springframework\.web\.bind\.annotation\.)?(Request|Get|Post|Put|Delete|Patch)Mapping\b(?:\s*\(([^)]*)\))?/g)]
  const pathOf = (mapping) => {
    const options = mapping[2]?.trim() ?? ''
    if (!options) return ''
    if (options.includes('{') && !options.startsWith('"') && !/^(?:value|path)\s*=\s*"/.test(options)) throw new Error('不支持多路径注解：' + sourcePath)
    const path = options.match(/^(?:(?:value|path)\s*=\s*)?"([^"]*)"/)
    if (!path) throw new Error('不支持动态路径注解：' + sourcePath)
    return path[1]
  }
  const baseMapping = mappings.filter((mapping) => mapping.index < classAt)
  if (baseMapping.length > 1 || baseMapping.some((mapping) => mapping[1] !== 'Request')) throw new Error('不支持的类级映射：' + sourcePath)
  const base = baseMapping.length ? pathOf(baseMapping[0]) : ''
  return mappings.filter((mapping) => mapping.index > classAt).map((mapping) => {
    if (mapping[1] === 'Request') throw new Error('方法级 RequestMapping 需明确 HTTP method：' + sourcePath)
    const path = (base + '/' + pathOf(mapping)).replace(/\/{2,}/g, '/').replace(/\/$/, '') || '/'
    return { method: mapping[1].toUpperCase(), path, source: sourcePath, line: code.slice(0, mapping.index).split('\n').length }
  })
}

export async function sourceApiInventory(backendPath) {
  const controllers = []
  async function walk(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const path = resolve(directory, entry.name)
      if (entry.isDirectory()) {
        if (!['target', '.git', 'data'].includes(entry.name)) await walk(path)
      } else if (entry.name.endsWith('Controller.java') && path.replaceAll('\\', '/').includes('/src/main/java/')) controllers.push(path)
    }
  }
  await walk(backendPath)
  const gateway = uncomment(await readFile(resolve(backendPath, 'gateway-service/src/main/java/yumefusaka/envoymart/gateway/filter/JwtGatewayFilter.java'), 'utf8'))
  const prefixBlock = gateway.match(/INTERNAL_ONLY_PREFIXES\s*=\s*List\.of\(([\s\S]*?)\);/)
  if (!prefixBlock) throw new Error('无法读取网关内部接口排除契约')
  const internalPrefixes = [...prefixBlock[1].matchAll(/"([^"]+)"/g)].map((match) => match[1])
  const apis = []
  for (const controller of controllers.sort()) {
    const source = controller.slice(resolve(backendPath, '..').length + 1).replaceAll('\\', '/')
    apis.push(...controllerApisFromSource(await readFile(controller, 'utf8'), source))
  }
  return { publicApis: apis.filter((api) => !internalPrefixes.some((prefix) => api.path.startsWith(prefix))),
    internalApis: apis.filter((api) => internalPrefixes.some((prefix) => api.path.startsWith(prefix))) }
}

export function apiCoverage(contracts, apis) {
  const key = (api) => api.method + ' ' + api.path
  const declared = contracts.map(key)
  const actual = apis.map(key)
  return { missing: actual.filter((id) => !declared.includes(id)), nonexistent: declared.filter((id) => !actual.includes(id)),
    duplicates: declared.filter((id, index) => declared.indexOf(id) !== index) }
}

export async function validateEvidenceReferences(matrix, scriptsDirectory) {
  const contracts = [...Object.values(matrix.routeEvidence ?? {}).flatMap((states) => Object.values(states)), ...(matrix.apiContracts ?? [])]
  const failures = []
  const sources = new Map()
  const scriptSource = async (name) => {
    if (!/^verify-[a-z0-9-]+\.mjs$/.test(name) && name !== 'run-complex-agent-eval.mjs') throw new Error('证据脚本名不合法：' + name)
    if (!sources.has(name)) sources.set(name, await readFile(resolve(scriptsDirectory, name), 'utf8'))
    return sources.get(name)
  }
  for (const contract of contracts) {
    for (const name of contract.scripts ?? []) {
      try { await scriptSource(name) } catch (error) { failures.push(error.message) }
    }
    if (contract.status === 'SCRIPTED' && !contract.assertions?.length) failures.push('SCRIPTED缺逐项断言：' + (contract.path ?? contract.expectation))
    for (const assertion of contract.assertions ?? []) {
      try {
        const source = await scriptSource(assertion.script)
        if (!assertion.anchor || !source.includes(assertion.anchor) || !assertion.scope) failures.push('证据锚点缺失：' + assertion.script + ' ' + assertion.anchor)
      } catch (error) { failures.push(error.message) }
    }
  }
  return [...new Set(failures)]
}

async function main() {
  const matrix = await readFixture('platform-feature-matrix.json')
  const pages = routePagesFromSource(await readFile(new URL('../src/router/index.ts', import.meta.url), 'utf8'))
  const coverage = routeCoverage(matrix.routes, pages)
  const inventory = await sourceApiInventory(fileURLToPath(new URL('../../backend/', import.meta.url)))
  const apiDiff = apiCoverage(matrix.apiContracts ?? [], inventory.publicApis)
  let pass = 0; let fail = 0
  const ck = (name, ok, detail = '') => { if (ok) { console.log(`  PASS ${name}`); pass += 1 } else { console.log(`  FAIL ${name} ${detail}`); fail += 1 } }
  ck('真实叶子页面与矩阵均覆盖 38 个', pages.length === 38 && matrix.routes.length === 38, `源码=${pages.length} 矩阵=${matrix.routes.length}`)
  ck('矩阵逐项对应真实路由，无遗漏、虚构或重复', !coverage.missing.length && !coverage.nonexistent.length && !coverage.duplicates.length, JSON.stringify(coverage))
  ck('公开 API 方法与路径逐项对应控制器和网关边界', !apiDiff.missing.length && !apiDiff.nonexistent.length && !apiDiff.duplicates.length, JSON.stringify(apiDiff))
  const requiredStates = ['loading', 'empty', 'error', 'permission', 'write']
  ck('每个路由逐态声明验收及未覆盖边界', matrix.routes.every((route) => requiredStates.every((state) => {
    const evidence = matrix.routeEvidence?.[route.id]?.[state]
    return evidence && ['UNCOVERED', 'SCRIPTED', 'NOT_APPLICABLE'].includes(evidence.status)
      && Array.isArray(evidence.scripts) && Boolean(evidence.expectation) && Boolean(evidence.reason)
  })))
  ck('每个 API 有主写/只读语义和权威终态证据入口', matrix.apiContracts.every((api) => ['READ', 'WRITE', 'READ_ONLY_POST', 'MANUAL_MODEL_RUN'].includes(api.kind)
    && Boolean(api.expectation) && Boolean(api.authority) && ['UNCOVERED', 'SCRIPTED', 'MANUAL'].includes(api.status) && Array.isArray(api.scripts)))
  const evidenceFailures = await validateEvidenceReferences(matrix, fileURLToPath(new URL('./', import.meta.url)))
  ck('逐项断言锚点和候选脚本真实存在', !evidenceFailures.length, evidenceFailures.join('；'))
  ck('Agent 工具均声明能力状态', matrix.tools.length >= 15 && matrix.tools.every((tool) => ['AGENT_READ', 'AGENT_WRITE_CONFIRM'].includes(tool.state)))
  ck('写工具全部要求确认', matrix.tools.filter((tool) => tool.writes).every((tool) => tool.state === 'AGENT_WRITE_CONFIRM'))
  ck('明确排除 Multi-Agent', matrix.outOfScope.includes('multi_agent') && matrix.outOfScope.includes('sub_agent'))
  ck('能力状态没有未知值', matrix.routes.every((route) => matrix.capabilityStates.includes(route.capability)))
  const routeGaps = matrix.routes.flatMap((route) => requiredStates.filter((state) => matrix.routeEvidence?.[route.id]?.[state]?.status === 'UNCOVERED').map((state) => route.path + ' ' + state))
  const apiGaps = matrix.apiContracts.filter((api) => api.status === 'UNCOVERED').map((api) => api.method + ' ' + api.path)
  console.log('  运行态未覆盖页面状态=' + routeGaps.length + '；API=' + apiGaps.length + '；SCRIPTED仅表示已存在断言，仍需本次执行结果')
  if (process.argv.includes('--require-evidence')) {
    ck('页面全部必需态已有执行断言入口', !routeGaps.length, routeGaps.join('；'))
    ck('公开 API 全集已有运行证据入口', !apiGaps.length, apiGaps.join('；'))
    const hasRun = (evidence) => evidence?.lastEvidence?.result === 'PASS' && Boolean(evidence.lastEvidence.runId)
      && Boolean(evidence.lastEvidence.path) && Boolean(evidence.lastEvidence.limits)
    const missingRuns = [
      ...matrix.routes.flatMap((route) => requiredStates.filter((state) => {
        const evidence = matrix.routeEvidence[route.id][state]
        return evidence.status !== 'NOT_APPLICABLE' && !hasRun(evidence)
      }).map((state) => route.path + ' ' + state)),
      ...matrix.apiContracts.filter((api) => !hasRun(api)).map((api) => api.method + ' ' + api.path),
    ]
    ck('逐项关联真实运行报告及其证据边界', !missingRuns.length, missingRuns.join('；'))
  }
  printContractSummary('平台能力矩阵', pass, fail, `caseId=T1-MATRIX requestId=fixture dag=matrix finalState=${fail ? 'INVALID' : 'CONTRACT_VALID'}；不等同运行态通过`)
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main()
