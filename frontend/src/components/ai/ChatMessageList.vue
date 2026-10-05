<script setup lang="ts">
import type { ToolProgressEvent } from '@/api/ai'
import CitationList from '@/components/ai/CitationList.vue'
import ConflictList from '@/components/ai/ConflictList.vue'
import MessageContent from '@/components/ai/MessageContent.vue'
import PendingApprovalCard from '@/components/ai/PendingApprovalCard.vue'
import RecommendationCards from '@/components/ai/RecommendationCards.vue'
import { useUserStore } from '@/stores'
import type { ChatMessage, ProductSummary } from '@/types/models'
import { formatChatStamp } from '@/utils/format'
import {
  type TraceOutcome,
  factEntries,
  formatMs,
  formatTokens,
  outcomeLabel,
  toolLabel,
  traceOutcome,
  traceSummary,
  usageSummary,
} from '@/utils/tools'
import { nextTick, ref } from 'vue'

defineProps<{
  messages: ChatMessage[]
  /** 正在生成的那条消息的下标；-1 表示没有在飞的流 */
  streamingIndex?: number
  /**
   * 流式期间的工具实时进度：`phase=start` 是「执行中」，`finish` 是结果态。
   * 仅作数据来源，可见性由 {@link liveToolsIndex} 决定。
   */
  liveTools?: ToolProgressEvent[]
  /**
   * 实时 chip 归属的那条消息下标；-1 表示没有。
   * <p>
   * 与 `streamingIndex` 分开是必须的：流一结束 `streamingIndex` 立刻回到 -1，
   * chip 会在结果态闪现之前就消失；这条下标由父组件在「还有 chip 要展示」的
   * 整个窗口内保持指向最后一条消息（含结束后的 600ms 收尾），到点随 chip 一起收。
   */
  liveToolsIndex?: number
}>()

/**
 * 实时 chip 的结局。「执行中」是第四态——start 已到、finish 未到。
 * 后三态与正式轨迹共用 {@link TraceOutcome}：同一件事在两个时刻（进行中/已归档）
 * 必须是同一套语义，「无结果」不能因为来早了几秒就被渲染成绿色对勾。
 */
function liveToolState(item: ToolProgressEvent): TraceOutcome | 'running' {
  if (item.phase === 'start') return 'running'
  if (!item.success) return 'fail'
  return item.noData ? 'empty' : 'ok'
}

function liveToolStateLabel(item: ToolProgressEvent): string {
  const state = liveToolState(item)
  return state === 'running' ? '执行中' : outcomeLabel(state)
}

const emit = defineEmits<{
  openProduct: [product: ProductSummary]
  approve: []
  dismiss: []
  regenerate: [messageId: string]
}>()

/**
 * 「重新生成」只出现在最后一条回答上，且流不在飞时。
 * 中间某条重写会让它后面的回答全部对不上它——那是一次分叉，不是一次重试，
 * 这个界面没有分叉的能力，就不该提供分叉的按钮。
 */
function regenerable(message: ChatMessage, position: number, count: number, streamingIndex: number) {
  return (
    message.role === 'assistant' &&
    position === count - 1 &&
    streamingIndex < 0
  )
}

const userStore = useUserStore()

/**
 * 任务阶段 → 一句给用户看的话。
 * <p>
 * 只对「还没收尾」的阶段出文案：`DONE` 返回空串，因为它是默认结局，
 * 给每一轮正常结束的回答挂一句「已完成」是纯噪音。
 * 文案锚在后端下发的枚举名上，而不是从回复内容里猜——阶段是稳定的结构字段，
 * 模型换个说法不会让它跟着变。
 */
function stageNotice(message: ChatMessage): string {
  switch (message.stage) {
    case 'PLANNING':
      return '正在规划…'
    case 'EXECUTING':
      return '正在执行…'
    case 'CHECKING':
      return '正在核对结果…'
    // 这一条与确认卡片同时出现：卡片说「批哪几次调用」，它说「为什么停在这」
    case 'WAITING_USER':
      return '等待你确认后才会继续'
    default:
      // DONE 与未知取值都返回空：前端不认识的新阶段不该被猜成一句话，
      // 那会把一次后端新增的阶段显示成错误文案
      return ''
  }
}

/** 当前被点亮的引用角标。`[n]` 点下去要能一眼看到它对应的是哪张卡片 */
const activeCite = ref<{ messageId: string; index: number } | null>(null)

async function handleCite(messageId: string, index: number) {
  activeCite.value = { messageId, index }
  await nextTick()
  const target = document.getElementById(`cite-${messageId}-${index}`)
  target?.scrollIntoView({
    block: 'nearest',
    // 动效降级是硬要求：开「减少动态效果」的用户不该被强制看一段滚动动画
    behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
  })
}

/** 刚复制过的那条。1.5 秒后复位，让按钮文字回到「复制」 */
const copiedId = ref<string | null>(null)
let copiedTimer: number | undefined

/**
 * 复制整条回答。失败**静默**：剪贴板权限被浏览器拒绝（非安全上下文、无用户手势）
 * 是环境问题，弹一个报错框对用户毫无帮助 —— 他重试一次也不会成功。
 */
async function handleCopy(message: ChatMessage) {
  try {
    await navigator.clipboard.writeText(message.content)
  } catch {
    return
  }
  copiedId.value = message.id
  window.clearTimeout(copiedTimer)
  copiedTimer = window.setTimeout(() => {
    copiedId.value = null
  }, 1500)
}
</script>

<template>
  <!--
    role="log"：对话是按时间追加的记录流，读屏用户在助手回答到达时应当被播报，
    而不是需要手动导航进消息区才知道有新内容（它隐含 aria-live="polite"）
  -->
  <div class="message-list" role="log" aria-label="对话记录">
    <article
      v-for="(message, position) in messages"
      :key="message.id"
      :class="['message-card', message.role]"
    >
      <header class="message-head">
        <span v-if="message.role === 'assistant'" class="message-avatar is-assistant" aria-hidden="true">
          EM
        </span>
        <img
          v-else-if="userStore.profile?.avatar"
          class="message-avatar"
          :src="userStore.profile.avatar"
          alt=""
        />
        <span v-else class="message-avatar is-user" aria-hidden="true">你</span>
        <strong>{{ message.role === 'assistant' ? 'EnvoyMart AI' : '你' }}</strong>
        <span v-if="position === streamingIndex" class="message-live">
          <span class="message-live__dot" aria-hidden="true" />
          正在生成
        </span>
        <!--
          用会话列表那套「今天给钟点、昨天/更早给日期」的格式：恢复三天前的会话时，
          每条都显示 14:32 而无从判断是哪天——日期粒度只在当天消息上才是多余的
        -->
        <time v-else-if="message.at" :datetime="message.at">{{ formatChatStamp(message.at) }}</time>
      </header>

      <!--
        流式期间的工具实时进度。出首个正文块之前可能要先跑几个工具，
        没有这段界面在首字到达前是一片空白，用户不知道 Agent 在忙什么。
        只在正在生成的那条消息上出现；正文一开始流式输出，它就和正文同上同下。
      -->
      <ol
        v-if="position === liveToolsIndex && liveTools?.length"
        class="live-tools"
        aria-label="工具执行进度"
      >
        <li
          v-for="(item, index) in liveTools"
          :key="`${item.tool}-${index}`"
          :class="['live-tool', `is-${liveToolState(item)}`]"
        >
          <span class="live-tool__dot" aria-hidden="true" />
          <span class="live-tool__label">{{ toolLabel(item.tool) }}</span>
          <span class="live-tool__state">{{ liveToolStateLabel(item) }}</span>
          <span v-if="item.phase === 'finish' && item.latencyMs != null" class="live-tool__ms">
            {{ formatMs(item.latencyMs) }}
          </span>
        </li>
      </ol>

      <MessageContent
        :content="message.content"
        :citation-count="message.knowledge?.length ?? 0"
        :streaming="position === streamingIndex"
        :plain="message.role === 'user'"
        @cite="(index) => handleCite(message.id, index)"
      />

      <!--
        断流与主动停止：半截正文照留在上面，这里只补一句它为什么停在半路。
        不覆盖正文是刻意的——断的是连接，不是用户已经读到一半的内容。
      -->
      <p v-if="message.error" class="message-notice is-error">{{ message.error }}</p>
      <p v-else-if="message.stopped" class="message-notice">已停止生成</p>
      <!--
        任务阶段。只在「还没跑完」时出现：DONE 是默认结局，为一轮正常结束的回答
        挂一句「已完成」是噪音，用户关心的是「它还在做什么」或「它卡在等谁」。
        文案取的是阶段本身而不是模型措辞——阶段是后端下发的稳定枚举，
        不会因为模型换了个说法就跟着变。
      -->
      <p
        v-if="stageNotice(message)"
        class="message-notice is-stage"
        :class="{ 'is-waiting': message.stage === 'WAITING_USER' }"
      >
        {{ stageNotice(message) }}
      </p>

      <!--
        消息级操作。悬停浮现（触屏常显）：ChatGPT 的一排操作按钮是肌肉记忆，
        缺了它整条消息就只是"一段文字"，不是"一条对话消息"。
        生成中不出现——半截回答的"复制"给的是半截内容。
      -->
      <div
        v-if="position !== streamingIndex && (message.content || message.error)"
        class="message-actions"
      >
        <button
          type="button"
          class="message-action"
          :aria-label="copiedId === message.id ? '已复制' : '复制这条消息'"
          @click="handleCopy(message)"
        >
          {{ copiedId === message.id ? '已复制' : '复制' }}
        </button>
        <button
          v-if="regenerable(message, position, messages.length, streamingIndex ?? -1)"
          type="button"
          class="message-action"
          @click="emit('regenerate', message.id)"
        >
          重新生成
        </button>
      </div>

      <!--
        整篇无依据的声明<b>一直摊开</b>，且排在冲突与逐句说明之前：它改变的是
        「这段话能不能被当成平台的说法」，用户读第一句时就需要知道，折叠起来等于没说。
        只在「一个引用都没有、也没有工具执行」时出现——订单、物流那几轮的事实
        来自工具返回，不该被扣上这顶帽子。
      -->
      <p v-if="message.ungrounded" class="ungrounded">
        以上内容没有平台知识库依据，由模型根据自身知识生成，不代表平台规则。
      </p>

      <!--
        另外两块「关于回答本身」的说明，紧贴正文：它们改变的是这段文字该被信几分，
        摆到依据列表后面就变成脚注了，而用户读第一句时就需要知道。
      -->
      <ConflictList
        v-if="message.conflicts?.length"
        :conflicts="message.conflicts"
        :knowledge="message.knowledge"
      />

      <!--
        用原生 details 而不是一直摊开：它是给想核对的人看的，不关心的人不该被它挡住。
        文案分两种，因为两种情形对用户的含义完全不同——「已经替你拿掉了」与
        「还留在上面，请自己判断」是两件事，用同一句话会把后者说成前者。
      -->
      <details v-if="message.unsupportedClaims?.length" class="grounding">
        <summary>
          {{
            message.unsupportedStripped
              ? `已移除 ${message.unsupportedClaims.length} 句没有知识库依据的内容`
              : `${message.unsupportedClaims.length} 句内容没有找到知识库依据`
          }}
        </summary>
        <ul class="grounding__list">
          <li v-for="(claim, i) in message.unsupportedClaims" :key="i">{{ claim }}</li>
        </ul>
      </details>

      <!--
        事实不符单独一条，不与「没有依据」合并：没有依据是「这话没人背书」，
        与工具事实不符是「这话与订单实际数字冲突」——后者用户是照着去付款、去对账的，
        说得轻了等于没提醒。这里没有「仍留在上面」的分支：事实只有一种，对不上就一定是错的。
      -->
      <details v-if="message.factMismatches?.length" class="grounding grounding--fact">
        <summary>
          已更正 {{ message.factMismatches.length }} 处与订单实际数据不符的说法
        </summary>
        <ul class="grounding__list">
          <li v-for="(mismatch, i) in message.factMismatches" :key="i">{{ mismatch }}</li>
        </ul>
      </details>

      <PendingApprovalCard
        v-if="message.pendingActions?.length"
        :actions="message.pendingActions"
        :active="position === messages.length - 1"
        @approve="emit('approve')"
        @dismiss="emit('dismiss')"
      />

      <!--
        工具轨迹用原生 details：它自带键盘可达与展开态语义，
        比手写一个「点标题切换 v-if」少一半代码，还白拿一层可访问性。
      -->
      <details v-if="message.toolCalls?.length" class="trace">
        <summary>
          工具轨迹 {{ message.toolCalls.length }} 次
          <span class="trace__total">{{ traceSummary(message.toolCalls) }}</span>
        </summary>
        <div v-for="(call, i) in message.toolCalls" :key="`${message.id}-${i}`" class="trace__item">
          <p class="trace__name">
            <span>{{ toolLabel(call.tool) }}</span>
            <span :class="['trace__badge', `is-${traceOutcome(call)}`]">
              {{ outcomeLabel(traceOutcome(call)) }}
            </span>
            <span class="trace__ms">{{ formatMs(call.latencyMs) }}</span>
          </p>
          <p class="trace__io"><span>入参</span>{{ call.input }}</p>
          <p class="trace__io trace__io--output"><span>返回</span>{{ call.output }}</p>
          <!--
            工具当场确立的事实，单独一栏。回答里凡是提到这些字段的地方，
            后端都拿这一栏的值逐条比过，对不上的句子会被删掉。
          -->
          <div v-if="factEntries(call).length" class="trace__facts">
            <dl class="trace__facts-list">
              <template v-for="[label, value] in factEntries(call)" :key="label">
                <dt>{{ label }}</dt>
                <dd>{{ value }}</dd>
              </template>
            </dl>
            <p class="trace__facts-note">回答中提到以上字段时，以此处的取值为准。</p>
          </div>
        </div>
      </details>

      <!--
        零命中也要渲染：这时它唯一的内容是「按『…』检索」那一行，
        而那一行恰恰是「为什么没查到」的答案。只在确定没有改写时（既不命中、又没改写句）
        才整块省略 —— 那种情况下这一块没有任何可说的东西。
      -->
      <CitationList
        v-if="message.knowledge?.length || message.retrievalQuery"
        :items="message.knowledge ?? []"
        :list-id="message.id"
        :evidence-level="message.evidenceLevel"
        :retrieval-query="message.retrievalQuery"
        :expansion="message.expansion"
        :active-index="activeCite?.messageId === message.id ? activeCite.index : null"
      />

      <!--
        用量放在最后：它是「这段回答花了多少」，属于读完之后才关心的事，
        摆到正文前面会让每一轮对话都从一串数字开始。
        收起时只给一行，展开才是按模型的明细——多数人不需要明细，
        需要的人（对成本敏感）会去展开。
      -->
      <details v-if="message.usage" class="usage">
        <summary>{{ usageSummary(message.usage) }}</summary>
        <dl class="usage__detail">
          <!--
            输出为 0 时不写「输出 0」：重排与向量化根本没有输出侧，那不是「这次没输出」。
            写出来会让读者以为漏统计了。
          -->
          <div v-for="model in message.usage.models" :key="model.model" class="usage__row">
            <dt>{{ model.model }}</dt>
            <dd>
              输入 {{ formatTokens(model.promptTokens)
              }}<template v-if="model.completionTokens > 0">
                · 输出 {{ formatTokens(model.completionTokens) }}</template>
            </dd>
          </div>
        </dl>
        <p v-if="message.usage.unpricedModels.length" class="usage__note">
          {{ message.usage.unpricedModels.join('、') }} 未配置单价，未计入金额。
        </p>
        <!--
          「估算」这件事必须写在明面上。金额是拿配置里的单价乘出来的，
          真实账单还有阶梯价与缓存折扣；不写这一句，它就会被当成事实引用。
        -->
        <p class="usage__note">按配置单价估算，实际费用以服务商账单为准。</p>
      </details>

      <RecommendationCards
        v-if="message.recommendedProducts?.length"
        :products="message.recommendedProducts"
        @open="(product) => emit('openProduct', product)"
      />
    </article>
  </div>
</template>

<style scoped>
.message-list {
  display: grid;
  gap: var(--ys-space-6);
}

.message-card {
  display: grid;
  gap: var(--ys-space-2);
  line-height: var(--ys-leading-base);
}

/*
  助手回答不做气泡、不做卡片边框：整页白底就是它的底。
  对话流里每一条都套个盒子，读起来像一串公告；让文本直接铺在页面上，
  视线只需要跟着往下走 —— 这是主流对话产品的排版基线。
*/
.message-card.assistant {
  max-width: 100%;
}

/* 用户自己的话才配气泡，且靠右——扫一眼就能分辨「谁在说」 */
.message-card.user {
  justify-self: end;
  max-width: min(86%, 640px);
  padding: var(--ys-space-3) var(--ys-space-4);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--ys-radius-lg);
  background: var(--color-primary-subtle);
}

.message-head {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  min-height: 24px;
}

/* 用户消息头像同侧靠右，与气泡对齐 */
.message-card.user .message-head {
  flex-direction: row-reverse;
}

.message-head strong {
  font-size: var(--ys-font-sm);
  color: var(--color-text-secondary);
}

.message-avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 24px;
  height: 24px;
  border-radius: var(--ys-radius-full);
  object-fit: cover;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.message-avatar.is-assistant {
  background: linear-gradient(135deg, var(--color-primary), var(--color-accent));
  color: var(--color-text-on-primary);
}

.message-avatar.is-user {
  background: var(--color-bg-sunken);
  color: var(--color-text-secondary);
}

.message-head time {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  font-variant-numeric: tabular-nums;
}

/* 「正在生成」替代时间显示：这一轮还没结束这件事，比当前钟点重要 */
.message-live {
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-1);
  color: var(--color-primary-strong);
  font-size: var(--ys-font-xs);
}

.message-live__dot {
  width: 6px;
  height: 6px;
  border-radius: var(--ys-radius-full);
  background: currentColor;
  animation: live-pulse 1s ease-in-out infinite;
}

@keyframes live-pulse {
  50% {
    opacity: 0.25;
  }
}

@media (prefers-reduced-motion: reduce) {
  .message-live__dot {
    animation: none;
  }
}

/*
  实时工具 chip：与头部「正在生成」同一条视觉语言（小圆点 + 小字），
  不给它卡片和阴影——它是过程的注脚，正文才是主角。
  状态不靠颜色单独表达：每枚 chip 都带文字结局（执行中/成功/无结果/失败），
  色觉障碍用户读到的是同一份信息。
*/
.live-tools {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.live-tool {
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-1);
  padding: var(--ys-space-1) var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-surface);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
  animation: live-tool-in var(--ys-duration-fast) var(--ys-ease-out);
}

@keyframes live-tool-in {
  from {
    opacity: 0;
    transform: translateY(2px);
  }
}

.live-tool__dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: var(--ys-radius-full);
  background: currentColor;
}

/* 执行中：复用头部那个呼吸点——同一件事（还在跑）用同一个动效 */
.live-tool.is-running {
  border-color: var(--color-primary-border);
  background: var(--color-primary-subtle);
  color: var(--color-primary-strong);
}

.live-tool.is-running .live-tool__dot {
  animation: live-pulse 1s ease-in-out infinite;
}

.live-tool.is-ok {
  border-color: var(--color-success-subtle);
  background: var(--color-success-subtle);
  color: var(--color-success-strong);
}

.live-tool.is-empty {
  border-color: var(--color-warning-subtle);
  background: var(--color-warning-subtle);
  color: var(--color-warning-strong);
}

.live-tool.is-fail {
  border-color: var(--color-danger-subtle);
  background: var(--color-danger-subtle);
  color: var(--color-danger-strong);
}

.live-tool__ms {
  opacity: 0.75;
  font-variant-numeric: tabular-nums;
}

@media (prefers-reduced-motion: reduce) {
  .live-tool {
    animation: none;
  }

  .live-tool.is-running .live-tool__dot {
    animation: none;
  }
}

/*
  消息操作行：复制 / 重新生成，悬停浮现。
  位置跟着 ChatGPT 的肌肉记忆走——在回答正文下面，不在头部角落；
  键盘用户 Tab 到按钮时同样可见（focus-within / focus-visible）。
*/
.message-actions {
  display: flex;
  gap: var(--ys-space-1);
  margin-top: var(--ys-space-2);
  opacity: 0;
  transition: opacity var(--ys-duration-fast) var(--ys-ease-out);
}

.message-card:hover .message-actions,
.message-actions:focus-within {
  opacity: 1;
}

.message-action {
  padding: 2px var(--ys-space-2);
  border: 1px solid transparent;
  border-radius: var(--ys-radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  cursor: pointer;
  transition:
    color var(--ys-duration-fast) var(--ys-ease-out),
    border-color var(--ys-duration-fast) var(--ys-ease-out);
}

.message-action:hover {
  border-color: var(--color-border);
  color: var(--color-primary-strong);
}

.message-action:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

/* 没有悬停能力的设备（触屏）上，藏起来的按钮等于不存在，常显 */
@media (hover: none) {
  .message-actions {
    opacity: 1;
  }
}

/*
  断流 / 主动停止的说明条。与正文分开：正文是模型写的，这条是系统说的，
  用背景色而不是换行正文去区分，也让「复制」复制不到它。
*/
.message-notice {
  margin: var(--ys-space-2) 0 0;
  padding: var(--ys-space-1) var(--ys-space-3);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface-muted);
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.message-notice.is-error {
  background: var(--color-danger-subtle);
  color: var(--color-danger-strong);
}

/*
  阶段提示。用主色而不是警示色：它不是错误，是「正在做/在等你」——
  给它挂红色会让一次正常的中断看起来像出了故障。
  等待确认那一档单独加重（左边一条竖线），因为它要求用户动作，
  而其余几档只是让他知道系统还在跑。
*/
.message-notice.is-stage {
  background: var(--color-primary-subtle);
  color: var(--color-primary-strong);
}

.message-notice.is-stage.is-waiting {
  border-inline-start: 3px solid var(--color-primary);
  background: var(--color-bg-surface-muted);
  color: var(--color-text-secondary);
}

.grounding {
  margin-top: var(--ys-space-3);
  padding: var(--ys-space-3);
  border: 1px dashed var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
}

/*
  警示而非报错：模型并没有伪装成平台依据（多数时候它自己也会说明），
  用 danger 色会把它渲染成一次事故。左描边而不是整框，是为了与逐句说明的
  虚线框区分开——这条说的是整段，那条说的是几句。
*/
.ungrounded {
  margin: var(--ys-space-3) 0 0;
  padding: var(--ys-space-3) var(--ys-space-4);
  border-inline-start: 3px solid var(--color-warning);
  border-radius: var(--ys-radius-sm);
  background: var(--color-warning-subtle);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
}

.grounding summary {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  cursor: pointer;
}

.grounding summary:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
  border-radius: var(--ys-radius-sm);
}

/*
  实线而不是虚线：虚线的两条都在说「这些内容不可靠，你自己看」，
  这条说的是「平台已经替你改过一个实实在在的数字」。边框从 neutral 提到 warning，
  但不用 danger——它不是系统故障，是模型说错了一句话、已被拦住。
  必须排在 .grounding 与 .grounding summary 之后：同特异度下后写的赢。
*/
.grounding--fact {
  border-style: solid;
  border-color: color-mix(in srgb, var(--color-warning) 45%, transparent);
}

.grounding--fact summary {
  color: var(--color-text-primary);
  font-weight: 500;
}

.grounding__list {
  display: grid;
  gap: var(--ys-space-2);
  margin: var(--ys-space-2) 0 0;
  padding-left: var(--ys-space-5);
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
}

.trace {
  margin-top: var(--ys-space-3);
  padding: var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
}

.trace summary {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  cursor: pointer;
}

.trace summary:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
  border-radius: var(--ys-radius-sm);
}

.trace__total {
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.trace__item {
  margin-top: var(--ys-space-2);
  padding-top: var(--ys-space-2);
  border-top: 1px dashed var(--color-border);
}

.trace__name {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  margin: 0;
  font-size: var(--ys-font-sm);
  font-weight: 600;
}

/* 三态各自的底色。成功最轻——它是常态，不该抢走对工具名的注意力；
   「无结果」用警告色而不是危险色：它是有效答案，不是故障。 */
.trace__badge {
  padding: 0 var(--ys-space-1);
  border-radius: var(--ys-radius-sm);
  font-size: var(--ys-font-xs);
  font-weight: 500;
}

/* 徽标是 12px 小字叠在各自的浅色底上：500 档叠浅底只有 ~4:1（压不过 4.5:1 的线），
   所以这里用深一档的 strong 色 —— 颜色语义不变，对比度达标 */
.trace__badge.is-ok {
  color: var(--color-success-strong);
  background: var(--color-success-subtle);
}

.trace__badge.is-empty {
  color: var(--color-warning-strong);
  background: var(--color-warning-subtle);
}

.trace__badge.is-fail {
  color: var(--color-danger-strong);
  background: var(--color-danger-subtle);
}

.trace__ms {
  margin-inline-start: auto;
  color: var(--color-text-muted);
  font-family: var(--ys-font-mono);
  font-size: var(--ys-font-xs);
  font-weight: 400;
  font-variant-numeric: tabular-nums;
}

.trace__io {
  display: grid;
  grid-template-columns: 40px 1fr;
  gap: var(--ys-space-2);
  margin: var(--ys-space-1) 0 0;
  color: var(--color-text-secondary);
  font-family: var(--ys-font-mono);
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
  white-space: pre-wrap;
  word-break: break-all;
}

/*
  返回可能长达数千字符（服务端已在上游封顶）。展开后不限高的话，
  一次商品检索就能把整页对话顶出屏幕——轨迹是给人"扫一眼"的，不是给人读长文的。
  限高 + 滚动：默认看到的是一屏以内，想看全文就在框里滚。
*/
.trace__io--output {
  max-block-size: 12rem;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.trace__io span {
  color: var(--color-text-muted);
  font-family: var(--ys-font-sans);
}

/*
  事实栏与「返回」原文分开：原文是模型看过的原始文本，这一栏是机器据此认下来的结论。
  两列对齐而不是写成一句话，是为了让「回答里的数字」和「这里的数字」能竖着扫一眼对上。
*/
.trace__facts {
  margin: var(--ys-space-2) 0 0;
  padding: var(--ys-space-2) var(--ys-space-3);
  border-inline-start: 2px solid var(--color-border-strong);
  background: var(--color-bg-surface-muted);
  border-radius: var(--ys-radius-sm);
}

.trace__facts-list {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--ys-space-1) var(--ys-space-3);
  margin: 0;
  font-size: var(--ys-font-xs);
}

.trace__facts-list dt {
  color: var(--color-text-muted);
}

.trace__facts-list dd {
  margin: 0;
  color: var(--color-text-primary);
  font-family: var(--ys-font-mono);
  overflow-wrap: anywhere;
}

.trace__facts-note {
  margin: var(--ys-space-2) 0 0;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
}

/*
  用量比轨迹更轻：轨迹是「这一轮发生了什么」（排障要看的），用量是「这一轮花了多少」。
  用最弱的文字色、不加边框，读不读都不影响对回答的理解。
*/
.usage {
  margin-top: var(--ys-space-3);
}

.usage summary {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  font-variant-numeric: tabular-nums;
  cursor: pointer;
}

.usage summary:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
  border-radius: var(--ys-radius-sm);
}

.usage__detail {
  display: grid;
  gap: var(--ys-space-1);
  margin: var(--ys-space-2) 0 0;
  padding-left: var(--ys-space-5);
  font-size: var(--ys-font-xs);
}

.usage__row {
  display: flex;
  gap: var(--ys-space-2);
}

.usage__row dt {
  color: var(--color-text-secondary);
  font-family: var(--ys-font-mono);
}

.usage__row dd {
  margin: 0;
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.usage__note {
  margin: var(--ys-space-2) 0 0;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
}
</style>
