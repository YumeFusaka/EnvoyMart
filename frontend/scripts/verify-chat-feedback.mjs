/**
 * T1-1 消息身份与反馈契约的确定性检查。
 *
 * 不触发模型：检查源码契约，避免把网络/模型波动误报成点踩功能失败。
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(fileURLToPath(new URL('.', import.meta.url)), '..')
const read = (path) => readFileSync(resolve(root, path), 'utf8')
const checks = [
  ['响应暴露 turnId', read('src/types/models.ts').includes('turnId: string')],
  ['响应暴露 userMessageId', read('src/types/models.ts').includes('userMessageId: string')],
  ['响应暴露 assistantMessageId', read('src/types/models.ts').includes('assistantMessageId: string')],
  ['反馈选择完整 messages', read('src/components/ai/ChatMessageList.vue').includes('v-for="candidate in messages"')],
  ['刷新反馈带序列保护', read('src/components/ai/ChatMessageList.vue').includes('feedbackRestoreSeq')],
  ['流式完成替换服务端助手 ID', read('src/views/AiAssistantView.vue').includes('assistantMessage.id = response.assistantMessageId')],
  ['重新生成保留原消息对象', read('src/views/AiAssistantView.vue').includes('const assistantMessage = target')],
  ['API 反馈携带 selectedMessageIds', read('src/api/ai.ts').includes('selectedMessageIds: string[]')],
]
let failed = 0
for (const [name, ok] of checks) {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}`)
  if (!ok) failed += 1
}
console.log(`结果：${checks.length - failed} 通过 / ${failed} 失败`)
process.exit(failed ? 1 : 0)
