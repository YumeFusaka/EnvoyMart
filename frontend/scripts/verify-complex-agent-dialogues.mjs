import { readFixture, printContractSummary, checkCaseContract } from './lib/verify-util.mjs'

const fixture = await readFixture('complex-agent-dialogues.json')
let pass = 0; let fail = 0
const ck = (name, ok, detail = '') => { if (ok) { console.log(`  PASS ${name}`); pass += 1 } else { console.log(`  FAIL ${name} ${detail}`); fail += 1 } }
const cases = fixture.cases
const multiTurn = cases.filter((item) => item.turns.length >= 3)
const multiTool = cases.filter((item) => item.tools.length >= 2)
const writes = cases.filter((item) => item.write)
const recovery = cases.filter((item) => item.recovery)
const restart = cases.filter((item) => item.restart)
ck('固定 48 条复杂场景', cases.length >= 48, `实际 ${cases.length}`)
ck('每条有 caseId/消息序列/工具 DAG', cases.every(checkCaseContract))
ck('至少 24 条三轮以上', multiTurn.length >= 24, `实际 ${multiTurn.length}`)
ck('至少 20 条多工具', multiTool.length >= 20, `实际 ${multiTool.length}`)
ck('至少 12 条确认写操作', writes.length >= 12, `实际 ${writes.length}`)
ck('至少 10 条失败恢复/重规划', recovery.length >= 10, `实际 ${recovery.length}`)
ck('至少 8 条重启恢复', restart.length >= 8, `实际 ${restart.length}`)
ck('写场景工具非空', writes.every((item) => item.tools.length > 0))
printContractSummary('复杂 Agent 场景契约', pass, fail, `caseId=CA-MATRIX requestId=fixture dag=declared finalState=${fail ? 'INVALID' : 'DECLARED'}`)
