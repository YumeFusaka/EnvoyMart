<script setup lang="ts">
import CitationList from '@/components/ai/CitationList.vue'
import ConflictList from '@/components/ai/ConflictList.vue'
import MessageContent from '@/components/ai/MessageContent.vue'
import PendingApprovalCard from '@/components/ai/PendingApprovalCard.vue'
import RecommendationCards from '@/components/ai/RecommendationCards.vue'
import { useUserStore } from '@/stores'
import type { ChatMessage, ProductSummary } from '@/types/models'
import { formatClock } from '@/utils/format'
import {
  formatMs,
  formatTokens,
  outcomeLabel,
  toolLabel,
  traceOutcome,
  traceSummary,
  usageSummary,
} from '@/utils/tools'
import { nextTick, ref } from 'vue'

const props = defineProps<{
  messages: ChatMessage[]
  /** 正在生成的那条消息的下标；-1 表示没有在飞的流 */
  streamingIndex?: number
}>()

const emit = defineEmits<{
  openProduct: [product: ProductSummary]
  approve: []
  dismiss: []
}>()

const userStore = useUserStore()

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
        <strong>{{ message.role === 'assistant' ? 'Yume AI' : '你' }}</strong>
        <span v-if="position === streamingIndex" class="message-live">
          <span class="message-live__dot" aria-hidden="true" />
          正在生成
        </span>
        <time v-else-if="message.at" :datetime="message.at">{{ formatClock(message.at) }}</time>
        <button
          v-if="message.role === 'assistant' && message.content"
          type="button"
          class="message-copy"
          :aria-label="copiedId === message.id ? '已复制' : '复制这条回答'"
          @click="handleCopy(message)"
        >
          {{ copiedId === message.id ? '已复制' : '复制' }}
        </button>
      </header>

      <MessageContent
        :content="message.content"
        :citation-count="message.knowledge?.length ?? 0"
        :streaming="position === streamingIndex"
        @cite="(index) => handleCite(message.id, index)"
      />

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
        </div>
      </details>

      <CitationList
        v-if="message.knowledge?.length"
        :items="message.knowledge"
        :list-id="message.id"
        :evidence-level="message.evidenceLevel"
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
  color: var(--color-primary);
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
  复制按钮悬停浮现。`margin-inline-start: auto` 把它推到头部行尾，
  不挤占名字与时间的空间；键盘用户 Tab 到它时同样可见（focus-visible）。
*/
.message-copy {
  margin-inline-start: auto;
  padding: 0 var(--ys-space-1);
  border: 0;
  border-radius: var(--ys-radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--ys-duration-fast) var(--ys-ease-out);
}

.message-card:hover .message-copy,
.message-copy:focus-visible {
  opacity: 1;
}

.message-copy:hover {
  color: var(--color-primary);
}

.message-copy:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

/* 没有悬停能力的设备（触屏）上，藏起来的按钮等于不存在，常显 */
@media (hover: none) {
  .message-copy {
    opacity: 1;
  }
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
  color: var(--color-danger);
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
