import { readFixture, printContractSummary } from './lib/verify-util.mjs'

const matrix = await readFixture('platform-feature-matrix.json')
const cases = await readFixture('complex-agent-dialogues.json')
let pass = 0; let fail = 0
const ck = (name, ok, detail = '') => { if (ok) { console.log(`  PASS ${name}`); pass += 1 } else { console.log(`  FAIL ${name} ${detail}`); fail += 1 } }
ck('买家和管理端路由均有权限面', matrix.routes.some((route) => route.surface === 'buyer') && matrix.routes.some((route) => route.surface === 'admin'))
ck('权限失败场景已登记', cases.cases.some((item) => item.caseId === 'CA-047'))
ck('跨用户消息/会话越权属于失败分类', (await readFixture('failure-recovery-scenarios.json')).scenarios.some((item) => item.failure === 'PERMISSION_DENIED'))
ck('Multi-Agent 不在能力表', !matrix.tools.some((tool) => /agent/i.test(tool.name) && !['product_search'].includes(tool.name)))
printContractSummary('权限矩阵契约', pass, fail, `caseId=PM-MATRIX requestId=fixture dag=permission finalState=${fail ? 'INVALID' : 'DECLARED'}`)
