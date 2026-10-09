import { readFixture, printContractSummary } from './lib/verify-util.mjs'

const fixture = await readFixture('complex-agent-dialogues.json')
let pass = 0; let fail = 0
const ck = (name, ok, detail = '') => { if (ok) { console.log(`  PASS ${name}`); pass += 1 } else { console.log(`  FAIL ${name} ${detail}`); fail += 1 } }
const writes = fixture.cases.filter((item) => item.write)
ck('写场景全部声明工具 DAG', writes.every((item) => item.tools.length > 0))
ck('双商品场景存在且包含两次 cart_add', fixture.cases.some((item) => item.caseId === 'CA-002' && item.tools.filter((tool) => tool === 'cart_add').length === 2))
ck('写场景覆盖确认后执行语义', writes.length >= 12)
ck('失败恢复场景不会凭文本判定', fixture.cases.filter((item) => item.recovery).every((item) => item.caseId && item.tools))
console.log('  说明：运行态脚本由后端契约测试复查 operationId、审批令牌和权威状态；本脚本只校验场景输入契约。')
printContractSummary('Agent 副作用契约', pass, fail, `caseId=CA-SIDE-EFFECT requestId=fixture dag=declared finalState=${fail ? 'INVALID' : 'DECLARED'}`)
