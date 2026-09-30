/**
 * 验收脚本读后端日志的公共部分。
 *
 * 这里的日志不是"顺带看一眼"的输出，而是**独立于接口的第二个证据源**：
 * 接口返回的数字由服务端自己算、自己报，日志里的每一行则由每次调用各自写下。
 * 两边能对上，才说明中间没有一笔被吞掉。
 */
import { existsSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))

/** 后端各服务的日志目录（run-local.sh 里 LOG_DIR=../logs/logs-local，相对 backend/） */
export const LOG_DIR = resolve(HERE, '../../../logs/logs-local')

/**
 * 读某个服务日志里带这个标识的行。
 * <p>
 * 轮询而不是读一次：日志落盘比 HTTP 响应晚一拍，立刻读多半是空的，
 * 而那看起来和「MDC 没写进去」一模一样。
 */
export async function logLines(service, requestId, timeoutMs = 4000) {
  const file = resolve(LOG_DIR, `${service}.log`)
  if (!existsSync(file)) {
    return []
  }
  let lines = []
  for (let waited = 0; waited <= timeoutMs; waited += 400) {
    lines = readFileSync(file, 'utf8')
      .split('\n')
      .filter((line) => line.includes(`[${requestId}]`))
    if (lines.length > 0) {
      return lines
    }
    await new Promise((r) => setTimeout(r, 400))
  }
  return lines
}
