<script setup lang="ts">
import CitationList from '@/components/ai/CitationList.vue'
import MessageContent from '@/components/ai/MessageContent.vue'
import PendingApprovalCard from '@/components/ai/PendingApprovalCard.vue'
import RecommendationCards from '@/components/ai/RecommendationCards.vue'
import type { ChatMessage, ProductSummary } from '@/types/models'
import { toolLabel } from '@/utils/tools'
import { nextTick, ref } from 'vue'

defineProps<{
  messages: ChatMessage[]
}>()

const emit = defineEmits<{
  openProduct: [product: ProductSummary]
  approve: []
  dismiss: []
}>()

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
</script>

<template>
  <div class="message-list">
    <article
      v-for="(message, position) in messages"
      :key="message.id"
      :class="['message-card', message.role]"
    >
      <header>
        <strong>{{ message.role === 'assistant' ? 'Yume AI' : '你' }}</strong>
      </header>

      <MessageContent
        :content="message.content"
        :citation-count="message.knowledge?.length ?? 0"
        @cite="(index) => handleCite(message.id, index)"
      />

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
        <summary>工具轨迹 {{ message.toolCalls.length }} 次</summary>
        <div v-for="(call, i) in message.toolCalls" :key="`${message.id}-${i}`" class="trace__item">
          <p class="trace__name">{{ toolLabel(call.tool) }}</p>
          <p class="trace__io"><span>入参</span>{{ call.input }}</p>
          <p class="trace__io"><span>返回</span>{{ call.output }}</p>
        </div>
      </details>

      <CitationList
        v-if="message.knowledge?.length"
        :items="message.knowledge"
        :list-id="message.id"
        :evidence-level="message.evidenceLevel"
        :active-index="activeCite?.messageId === message.id ? activeCite.index : null"
      />

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
  gap: var(--ys-space-5);
}

.message-card {
  padding: var(--ys-space-5);
  border-radius: var(--ys-radius-lg);
  line-height: var(--ys-leading-base);
}

.message-card.user {
  background: var(--color-primary-subtle);
  border: 1px solid var(--color-primary-border);
}

.message-card.assistant {
  background: var(--color-bg-surface);
  border: var(--card-border);
}

.message-card header strong {
  font-size: var(--ys-font-sm);
  color: var(--color-text-secondary);
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

.trace__item {
  margin-top: var(--ys-space-2);
  padding-top: var(--ys-space-2);
  border-top: 1px dashed var(--color-border);
}

.trace__name {
  margin: 0;
  font-size: var(--ys-font-sm);
  font-weight: 600;
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

.trace__io span {
  color: var(--color-text-muted);
  font-family: var(--ys-font-sans);
}
</style>
