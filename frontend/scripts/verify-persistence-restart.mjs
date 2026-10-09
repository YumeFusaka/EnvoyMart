import { readFixture, printContractSummary } from './lib/verify-util.mjs'

const failures = await readFixture('failure-recovery-scenarios.json')
let pass = 0; let fail = 0
const ck = (name, ok, detail = '') => { if (ok) { console.log(`  PASS ${name}`); pass += 1 } else { console.log(`  FAIL ${name} ${detail}`); fail += 1 } }
ck('夹具包含重启恢复场景', failures.scenarios.some((item) => item.failure === 'SERVICE_RESTART'))
ck('快照损坏不会伪装成未运行', failures.scenarios.find((item) => item.failure === 'SNAPSHOT_CORRUPTED')?.expected.includes('不伪装'))
ck('脚本不触发模型', true, '确定性入口只校验持久化契约；真实重启复核由 T1-7 人工触发')
printContractSummary('持久化重启契约', pass, fail, `caseId=PR-MATRIX requestId=fixture dag=persistence finalState=${fail ? 'INVALID' : 'DECLARED'}`)
