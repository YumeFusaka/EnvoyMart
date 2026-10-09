import { readFixture, printContractSummary } from './lib/verify-util.mjs'

const matrix = await readFixture('platform-feature-matrix.json')
let pass = 0; let fail = 0
const ck = (name, ok, detail = '') => { if (ok) { console.log(`  PASS ${name}`); pass += 1 } else { console.log(`  FAIL ${name} ${detail}`); fail += 1 } }
ck('路由覆盖 38 个', matrix.routes.length === 38, `实际 ${matrix.routes.length}`)
ck('Agent 工具均声明能力状态', matrix.tools.length >= 15 && matrix.tools.every((tool) => ['AGENT_READ', 'AGENT_WRITE_CONFIRM'].includes(tool.state)))
ck('写工具全部要求确认', matrix.tools.filter((tool) => tool.writes).every((tool) => tool.state === 'AGENT_WRITE_CONFIRM'))
ck('明确排除 Multi-Agent', matrix.outOfScope.includes('multi_agent') && matrix.outOfScope.includes('sub_agent'))
ck('能力状态没有未知值', matrix.routes.every((route) => matrix.capabilityStates.includes(route.capability)))
printContractSummary('平台能力矩阵', pass, fail, `caseId=T1-MATRIX requestId=fixture dag=matrix finalState=${fail ? 'INVALID' : 'VALID'}`)
