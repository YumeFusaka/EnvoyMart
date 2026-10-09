import { readFixture, printContractSummary } from './lib/verify-util.mjs'

const fixture = await readFixture('failure-recovery-scenarios.json')
let pass = 0; let fail = 0
const ck = (name, ok, detail = '') => { if (ok) { console.log(`  PASS ${name}`); pass += 1 } else { console.log(`  FAIL ${name} ${detail}`); fail += 1 } }
const required = ['EMPTY_RESULT', 'BUSINESS_REJECTED', 'CONNECTION_FAILED', 'TIMEOUT_UNKNOWN_RESULT', 'DUPLICATE_CONFIRMATION', 'CONFIRM_CANCELLED', 'PLAN_UNRESOLVED_REFERENCE', 'SERVICE_RESTART', 'PERMISSION_DENIED', 'SNAPSHOT_CORRUPTED']
ck('失败分类覆盖完整', required.every((kind) => fixture.scenarios.some((item) => item.failure === kind)))
ck('每条失败场景有处理契约', fixture.scenarios.every((item) => item.caseId && item.expected))
ck('重复确认有幂等语义', fixture.scenarios.find((item) => item.failure === 'DUPLICATE_CONFIRMATION')?.expected.includes('一次'))
ck('超时有未知结果查询语义', fixture.scenarios.find((item) => item.failure === 'TIMEOUT_UNKNOWN_RESULT')?.expected.includes('权威状态'))
printContractSummary('失败恢复契约', pass, fail, `caseId=FR-MATRIX requestId=fixture dag=recovery finalState=${fail ? 'INVALID' : 'DECLARED'}`)
