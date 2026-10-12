import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'

export const HOT_KEYWORD_KEY = 'envoymart:hot-keywords'
const OWNER = /^t1(?:ui|api)_[0-9a-f]{8}$/

/** 只接受本轮随机前缀的ES第一页查询，管理端查询与自然词不获得清理权限。 */
export function fixtureSearchWord(path, ownerPrefix) {
  if (!OWNER.test(ownerPrefix ?? '')) return null
  const url = new URL(path, 'http://127.0.0.1')
  if (url.pathname !== '/products/search' || Number(url.searchParams.get('page') ?? '0') !== 0) return null
  const word = url.searchParams.get('keyword')?.trim().replace(/\s+/g, ' ')
  return word && word.length <= 32 && word.startsWith(ownerPrefix) ? word : null
}

export function fixtureRedis(command, ...args) {
  const source = readFileSync(new URL('../../../backend/product-service/src/main/java/yumefusaka/envoymart/productservice/search/HotKeywordService.java', import.meta.url), 'utf8')
  if (!source.includes('private static final String KEY = "' + HOT_KEYWORD_KEY + '";')) throw new Error('热搜键与源码不一致，拒绝操作')
  if (!['ZSCORE', 'ZREM', 'ZINCRBY'].includes(command)) throw new Error('不允许操作整份热搜榜单')
  const output = execFileSync('docker', ['exec', 'envoymart-redis', 'redis-cli', '--raw', command, HOT_KEYWORD_KEY, ...args.map(String)], { encoding: 'utf8', timeout: 15000, windowsHide: true }).trim()
  if (/^(?:ERR|NOAUTH|WRONGTYPE)\b/.test(output)) throw new Error('Redis操作被拒绝，停止且不读取凭据：' + output)
  return output
}

function score(redis, word) {
  const result = redis('ZSCORE', word)
  if (result === '' || result === '(nil)') return 0
  const number = Number(result)
  if (!Number.isFinite(number) || number < 0) throw new Error('热搜分数非法，拒绝清理')
  return number
}

export function captureFixtureSearch(ledger, path, ownerPrefix, requestId, redis = fixtureRedis) {
  const word = fixtureSearchWord(path, ownerPrefix)
  if (!word) return
  if (!requestId) throw new Error('自有人工搜索缺requestId，不能建立清理增量归属')
  let entry = ledger.find((item) => item.word === word)
  if (!entry) {
    entry = { word, ownerPrefix, baselineScore: score(redis, word), requests: [] }
    ledger.push(entry)
  }
  entry.requests.push({ path, requestId, confirmed: false })
}

export function confirmFixtureSearch(ledger, requestId, code) {
  for (const entry of ledger) for (const request of entry.requests) if (request.requestId === requestId) request.confirmed = code === 200
}

/** 保留已有分数；只归还本轮已确认的增量，零基线且全部来自本轮时才移除成员。 */
export function cleanupFixtureSearches(ledger, redis = fixtureRedis) {
  return ledger.map((entry) => {
    const exactAudit = entry.word === 't1ui_' && entry.authorization === 'EXPLICIT_CURRENT_TASK_AUDIT_QUERY'
      && entry.source?.path === '/products/search?keyword=t1ui_&page=0' && entry.source?.code === 200
    const owned = OWNER.test(entry.ownerPrefix ?? '') && entry.requests.every((request) => fixtureSearchWord(request.path, entry.ownerPrefix) === entry.word)
    if ((!owned && !exactAudit) || !Number.isFinite(entry.baselineScore) || entry.baselineScore < 0) throw new Error('缺独立来源或合法基线，拒绝清理热搜：' + entry.word)
    const delta = entry.requests.filter((request) => request.confirmed).length
    const before = score(redis, entry.word)
    const restore = Math.min(delta, Math.max(0, before - entry.baselineScore))
    let action = 'KEEP'
    if (restore > 0) {
      if (entry.baselineScore === 0 && before === restore) { redis('ZREM', entry.word); action = 'ZREM' }
      else { redis('ZINCRBY', -restore, entry.word); action = 'RESTORE_OWN_DELTA' }
    }
    const after = score(redis, entry.word)
    if (after < entry.baselineScore) throw new Error('清理触及原有分数：' + entry.word)
    return { ...entry, before, confirmedDelta: delta, restoredDelta: restore, action, after }
  })
}
