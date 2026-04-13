<script setup lang="ts">
import RecommendationCards from '@/components/ai/RecommendationCards.vue'
import type { ChatMessage, Product } from '@/types/models'

defineProps<{
  messages: ChatMessage[]
}>()

const emit = defineEmits<{
  openProduct: [product: Product]
}>()
</script>

<template>
  <div class="message-list">
    <article
      v-for="message in messages"
      :key="message.id"
      :class="['message-card', message.role]"
    >
      <header>
        <strong>{{ message.role === 'assistant' ? 'Yume AI' : '你' }}</strong>
      </header>
      <p>{{ message.content }}</p>

      <div v-if="message.knowledge?.length" class="supplement-box">
        <h4>知识检索</h4>
        <div v-for="item in message.knowledge" :key="`${message.id}-${item.title}`" class="supplement-item">
          <strong>{{ item.title }}</strong>
          <span>{{ item.content }}</span>
        </div>
      </div>

      <div v-if="message.toolCalls?.length" class="supplement-box">
        <h4>工具调用</h4>
        <div v-for="tool in message.toolCalls" :key="`${message.id}-${tool.tool}`" class="supplement-item">
          <strong>{{ tool.tool }}</strong>
          <span>{{ tool.output }}</span>
        </div>
      </div>

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
  gap: 18px;
}

.message-card {
  padding: 18px;
  border-radius: 20px;
  line-height: 1.7;
}

.message-card.user {
  background: rgba(182, 91, 46, 0.12);
}

.message-card.assistant {
  background: rgba(255, 251, 245, 0.9);
  border: 1px solid rgba(84, 55, 23, 0.08);
}

.message-card p {
  margin: 10px 0 0;
  white-space: pre-wrap;
}

.supplement-box {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed rgba(84, 55, 23, 0.12);
}

.supplement-box h4 {
  margin: 0 0 10px;
}

.supplement-item {
  display: grid;
  gap: 4px;
  margin-bottom: 10px;
}

.supplement-item span {
  color: var(--ys-muted);
}
</style>
