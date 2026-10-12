/**
 * 全链路验收总入口 —— 一条命令跑完所有端到端脚本，给一张汇总表和唯一的退出码。
 *
 * **为什么是「调度脚本」而不是「把断言全抄一遍」**：每个 `verify-*.mjs` 都有自己的
 * 前置条件、数据影响与反向断言。重抄一遍等于把同一件事写成两份，两份迟早分叉——
 * 那正是批次 13f 在修的病根（同一个判断写在两条路上）。所以这里只做三件事：
 * **顺序调度、汇总、给一个非零退出码**。断言永远只有一份，就在各自的脚本里。
 *
 * **为什么要顺序跑**：所有脚本共用同一套后端与数据库（同一个账号的购物车、
 * 同一张券、同一批订单），并发会互相踩，失败还会互相污染难以定位。
 *
 * **为什么分组**：默认组只读，或以「只增不减」的方式造数据（订单流水、评价、工单）
 * ——跑一百遍也不会让演示库变得不能演示。`--slow` 组里的脚本会动运行环境
 * （`verify-downstream-retry` 要把 product-service 停掉换成桩，再拉回来），
 * 耗时以分钟计、跑完还要等服务重新注册，所以默认不跑、跑也排在最后。
 *
 * 注意：**跑之前后端九个服务与前端 dev server 都得起着**。脚本自己会报错退出，
 * 但那样一路红到底，看不出是哪一步的问题。
 *
 * 用法：
 *   node scripts/verify-all.mjs              # 默认组
 *   node scripts/verify-all.mjs --slow       # 含慢脚本（放最后）
 *   node scripts/verify-all.mjs --only=favorites,ticket   # 只跑文件名含这些子串的（英文，不是中文）
 *   node scripts/verify-all.mjs --list       # 只列要跑哪些，不动手
 */
import { spawnSync } from 'node:child_process'
import { readdirSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))

/** 会动运行环境（停服务换桩再拉回），默认不跑、跑也最后跑 */
const SLOW = ['verify-downstream-retry.mjs']

/** T1 确定性契约：不触发模型，默认纳入总入口，失败时应先于慢脚本暴露。 */
const T1_CONTRACT = [
  'verify-platform-matrix.mjs',
  'verify-complex-agent-dialogues.mjs',
  'verify-agent-side-effects.mjs',
  'verify-failure-recovery.mjs',
  'verify-persistence-restart.mjs',
  'verify-permission-matrix.mjs',
  'verify-approval-redis-boundaries.mjs',
]

/** 不是验收脚本的：夹具采集与截图工具，混进来只会让人以为它挂了；还有它自己 */
const NOT_VERIFY = ['capture-grounding-fixtures.mjs', 'verify-all.mjs']

const args = process.argv.slice(2)
const withSlow = args.includes('--slow')
const listOnly = args.includes('--list')
const onlyArg = args.find((a) => a.startsWith('--only='))
const apiEvidence = args.find((arg) => arg.startsWith('--api-evidence='))?.slice('--api-evidence='.length)
const receiptCapture = args.find((arg) => arg.startsWith('--write-receipts-capture='))?.slice('--write-receipts-capture='.length)
const receiptRestart = args.find((arg) => arg.startsWith('--write-receipts-restart='))?.slice('--write-receipts-restart='.length)
const paymentCapture = args.find((arg) => arg.startsWith('--payment-callback-capture='))?.slice('--payment-callback-capture='.length)
const refundCapture = args.find((arg) => arg.startsWith('--refund-retry-capture='))?.slice('--refund-retry-capture='.length)
const approvalCapture = args.find((arg) => arg.startsWith('--approval-recovery-capture='))?.slice('--approval-recovery-capture='.length)
const approvalRestart = args.find((arg) => arg.startsWith('--approval-recovery-restart='))?.slice('--approval-recovery-restart='.length)
const approvalRedisEvidenceArg = args.find((arg) => arg.startsWith('--approval-redis-evidence='))?.slice('--approval-redis-evidence='.length)
const approvalRedisEvidence = approvalRedisEvidenceArg ? resolve(approvalRedisEvidenceArg) : null
const aiPublicCapture = args.find((arg) => arg.startsWith('--ai-public-capture='))?.slice('--ai-public-capture='.length)
const pageWriteCapture = args.find((arg) => arg.startsWith('--page-write-capture='))?.slice('--page-write-capture='.length)
const persistenceBaseline = args.find((arg) => arg.startsWith('--persistence-baseline='))?.slice('--persistence-baseline='.length)
const seataEvidence = args.find((arg) => arg.startsWith('--seata-evidence='))?.slice('--seata-evidence='.length)
const firstCouponEvidence = args.find((arg) => arg.startsWith('--seata-first-coupon='))?.slice('--seata-first-coupon='.length)
const projectionRecoveryEvidence = args.find((arg) => arg.startsWith('--projection-recovery-evidence='))?.slice('--projection-recovery-evidence='.length)
const only = onlyArg
  ? onlyArg
      .slice('--only='.length)
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
  : null

/** 每个脚本的超时。下游重试那个要停服务、等重启，给足；其余超过 10 分钟就是卡死了 */
const TIMEOUT_MS = 15 * 60 * 1000
const DEFAULT_TIMEOUT_MS = 10 * 60 * 1000

function plan() {
  const discovered = readdirSync(HERE)
    .filter((f) => f.startsWith('verify-') && f.endsWith('.mjs'))
    .filter((f) => !NOT_VERIFY.includes(f))
    .filter((f) => !only || only.some((k) => f.includes(k)))
    .sort()
  const all = only ? discovered : [...new Set([...T1_CONTRACT, ...discovered])]
  const fast = all
    .filter((f) => !SLOW.includes(f))
    .sort((a, b) => {
      const ai = T1_CONTRACT.indexOf(a)
      const bi = T1_CONTRACT.indexOf(b)
      if (ai >= 0 && bi < 0) return -1
      if (ai < 0 && bi >= 0) return 1
      if (ai >= 0 && bi >= 0) return ai - bi
      return a.localeCompare(b)
    })
  const slow = all.filter((f) => SLOW.includes(f))
  return withSlow ? [...fast, ...slow] : fast
}

/** 从输出里读最后一个「N 通过 / M 失败」——多个的脚本里最后一个才是总数 */
function tally(output) {
  const matches = [...output.matchAll(/(\d+)\s*通过\s*\/\s*(\d+)\s*失败/g)]
  if (matches.length === 0) {
    return null
  }
  const last = matches[matches.length - 1]
  return { pass: Number(last[1]), fail: Number(last[2]) }
}

const scripts = plan()
if (scripts.length === 0) {
  console.error('没有匹配到任何验收脚本（--only 写错了？）')
  process.exit(1)
}

console.log(`将顺序执行 ${scripts.length} 个验收脚本：`)
for (const s of scripts) {
  console.log(`  ${s}${SLOW.includes(s) ? '  （慢：会重启 product-service）' : ''}`)
}
if (listOnly) {
  process.exit(0)
}
console.log('')

const results = []
const startedAt = Date.now()
const runId = randomUUID()
const evidenceDirectory = resolve(HERE, '../.evidence')
mkdirSync(evidenceDirectory, { recursive: true })
const evidencePath = resolve(evidenceDirectory, 'verify-all-' + runId + '.json')
function saveEvidence() {
  writeFileSync(evidencePath, JSON.stringify({ version: 1, runId, startedAt: new Date(startedAt).toISOString(),
    updatedAt: new Date().toISOString(), results: results.map(({ script, ok, counts, seconds, output, timedOut, status }) =>
      ({ script, ok, counts, seconds, output, timedOut, status })) }, null, 2))
}

for (const [index, script] of scripts.entries()) {
  const label = `[${index + 1}/${scripts.length}] ${script}`
  console.log(`\n\x1b[36m▶ ${label}\x1b[0m`)
  const began = Date.now()
  // stdout 也收进来（而不是 inherit）：失败时只把失败那个的输出打出来，
  // 二十几个脚本各打几百行的话，真正的红在哪一行要靠翻很久
  let scriptArgs = []
  if (script === 'verify-public-api-contracts.mjs' && apiEvidence) scriptArgs = ['--evidence=' + apiEvidence]
  if (script === 'verify-write-operation-receipts.mjs' && receiptCapture) scriptArgs = ['--compare=' + receiptCapture, ...(receiptRestart ? ['--restart-evidence=' + receiptRestart] : [])]
  if (script === 'verify-payment-callback-live.mjs' && paymentCapture) scriptArgs = ['--compare=' + paymentCapture]
  if (script === 'verify-refund-retry-live.mjs' && refundCapture) scriptArgs = ['--compare=' + refundCapture]
  if (script === 'verify-approval-recovery-live.mjs' && approvalCapture) scriptArgs = ['--compare=' + approvalCapture, ...(approvalRestart ? ['--restart-evidence=' + approvalRestart] : [])]
  if (script === 'verify-approval-redis-boundaries.mjs' && approvalRedisEvidence) scriptArgs = ['--evidence=' + approvalRedisEvidence]
  if (script === 'verify-ai-public-api-live.mjs' && aiPublicCapture) scriptArgs = ['--compare=' + aiPublicCapture]
  if (script === 'verify-platform-writes-ui.mjs' && pageWriteCapture) scriptArgs = ['--compare=' + pageWriteCapture]
  if (script === 'verify-persistence-restart.mjs' && persistenceBaseline) scriptArgs = ['--compare', '--baseline=' + persistenceBaseline]
  if (seataEvidence && [
    'verify-write-receipt-phase1.mjs',
    'verify-write-receipt-seata.mjs',
    'verify-write-receipt-seata-late-coupon.mjs',
    'verify-write-receipt-seata-coupon-rollback.mjs',
  ].includes(script)) scriptArgs = ['--compare=' + seataEvidence]
  if (script === 'verify-write-receipt-first-coupon.mjs' && firstCouponEvidence) scriptArgs = ['--compare=' + firstCouponEvidence]
  if (script === 'verify-product-projection-recovery.mjs' && projectionRecoveryEvidence) scriptArgs = ['--compare=' + projectionRecoveryEvidence, '--require-both']
  const run = spawnSync('node', [script, ...scriptArgs], {
    cwd: HERE,
    encoding: 'utf8',
    timeout: SLOW.includes(script) ? TIMEOUT_MS : DEFAULT_TIMEOUT_MS,
    maxBuffer: 64 * 1024 * 1024,
  })
  const seconds = ((Date.now() - began) / 1000).toFixed(1)
  const output = `${run.stdout ?? ''}${run.stderr ?? ''}`
  const counts = tally(output)
  const timedOut = run.error?.code === 'ETIMEDOUT'
  const ok = run.status === 0 && !timedOut

  results.push({ script, ok, counts, seconds, output, timedOut, status: run.status })
  saveEvidence()

  if (ok) {
    console.log(
      `\x1b[32m✔\x1b[0m ${label} ${counts ? `（${counts.pass} 通过）` : ''} ${seconds}s`,
    )
  } else {
    console.log(`\x1b[31m✘\x1b[0m ${label} ${seconds}s —— 退出码 ${run.status}${timedOut ? '（超时）' : ''}`)
    // 失败的那个把完整输出贴出来：定错要靠它，而不是靠再跑一遍
    console.log('─'.repeat(72))
    console.log(output.trimEnd())
    console.log('─'.repeat(72))
  }
}

const passed = results.filter((r) => r.ok)
const failed = results.filter((r) => !r.ok)
const assertions = results.reduce(
  (acc, r) => (r.counts ? { pass: acc.pass + r.counts.pass, fail: acc.fail + r.counts.fail } : acc),
  { pass: 0, fail: 0 },
)
const untallied = results.filter((r) => !r.counts)
const totalSeconds = ((Date.now() - startedAt) / 1000).toFixed(1)
console.log('逐脚本运行证据：' + evidencePath)

console.log(`\n${'='.repeat(72)}`)
console.log('全链路验收汇总')
console.log('='.repeat(72))
for (const r of results) {
  const mark = r.ok ? '\x1b[32m✔\x1b[0m' : '\x1b[31m✘\x1b[0m'
  const count = r.counts ? `${String(r.counts.pass).padStart(4)} 通过` : '     ——'
  console.log(`${mark} ${count}  ${r.seconds.padStart(7)}s  ${r.script}`)
}
console.log('-'.repeat(72))
console.log(
  `脚本 ${passed.length}/${results.length} 通过` +
    (failed.length ? `，失败：${failed.map((r) => r.script).join('、')}` : '') +
    `　合计 ${assertions.pass} 条断言通过` +
    (assertions.fail ? ` / ${assertions.fail} 条失败` : '') +
    `　耗时 ${totalSeconds}s`,
)
if (untallied.length) {
  console.log(
    `（${untallied.length} 个脚本没打印「N 通过 / M 失败」汇总行，其断言数未计入：` +
      `${untallied.map((r) => r.script).join('、')}——它们仍以退出码判定）`,
  )
}
if (!withSlow && SLOW.length) {
  console.log(`（未跑需动运行环境的慢脚本：${SLOW.join('、')}；需要时加 --slow）`)
}

process.exit(failed.length === 0 ? 0 : 1)
