<script setup lang="ts">
import { chat } from '@/api/ai'
import ChatMessageList from '@/components/ai/ChatMessageList.vue'
import QuickPromptBar from '@/components/ai/QuickPromptBar.vue'
import { useUserStore } from '@/stores'
import type { ChatMessage, Product } from '@/types/models'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const userStore = useUserStore()
const sessionId = `session-${Date.now()}`
const loading = ref(false)
const input = ref('')

const prompts = [
  '推荐适合学生党的百元内耳机',
  '活动满减规则是什么',
  '七天无理由退货怎么处理',
  '帮我查一下这个订单物流到哪了'
]

const messages = ref<ChatMessage[]>([
  {
    id: 'welcome',
    role: 'assistant',
    content: '欢迎来到 EnvoyMart 智能客服台。你可以问我商品推荐、活动规则、售后政策，或者让我帮你查询订单和物流。'
  }
])

const assistantCount = computed(() => messages.value.filter((item) => item.role === 'assistant').length)

async function sendMessage(message = input.value) {
  const content = message.trim()
  if (!content) return

  messages.value.push({
    id: `user-${Date.now()}`,
    role: 'user',
    content
  })
  input.value = ''
  loading.value = true
  try {
    const response = await chat({
      sessionId,
      message: content
    })
    messages.value.push({
      id: `assistant-${Date.now()}`,
      role: 'assistant',
      content: response.reply,
      knowledge: response.knowledge,
      toolCalls: response.toolCalls,
      recommendedProducts: response.recommendedProducts
    })
  } finally {
    loading.value = false
  }
}

function openProduct(product: Product) {
  router.push({
    path: '/shop',
    query: {
      keyword: product.name
    }
  })
}
</script>

<template>
  <div class="assistant-page">
    <header class="assistant-header">
      <div>
        <p class="eyebrow">AI Service Console</p>
        <h1>EnvoyMart 智能客服与导购</h1>
        <p class="subcopy">
          基于自研 Agent 框架，支持商品问答、活动答疑、订单查询与物流追踪等场景。
        </p>
      </div>
      <div class="header-actions">
        <el-button plain @click="router.push('/shop')">返回商城</el-button>
        <el-avatar :src="userStore.profile?.avatar" />
      </div>
    </header>

    <section class="assistant-stats">
      <div class="stat-card">
        <span>当前用户</span>
        <strong>{{ userStore.profile?.nickname || userStore.profile?.username }}</strong>
      </div>
      <div class="stat-card">
        <span>AI 回复数</span>
        <strong>{{ assistantCount }}</strong>
      </div>
      <div class="stat-card">
        <span>核心技术</span>
        <strong>Agent + RAG + Tool Calling</strong>
      </div>
    </section>

    <section class="assistant-panel">
      <QuickPromptBar :prompts="prompts" @select="sendMessage" />

      <ChatMessageList :messages="messages" @open-product="openProduct" />

      <div class="composer">
        <el-input
          v-model="input"
          :rows="4"
          type="textarea"
          placeholder="输入商品、活动、售后、订单或物流问题"
          @keydown.ctrl.enter.prevent="sendMessage()"
        />
        <div class="composer-actions">
          <span>Ctrl + Enter 发送</span>
          <el-button :loading="loading" type="primary" @click="sendMessage()">发送消息</el-button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.assistant-page {
  padding: 28px;
  display: grid;
  gap: 22px;
}

.assistant-header,
.assistant-stats {
  display: flex;
  justify-content: space-between;
  gap: 18px;
}

.assistant-header h1 {
  margin: 4px 0;
  font-size: clamp(28px, 4vw, 40px);
}

.eyebrow {
  margin: 0;
  color: var(--ys-primary);
  font-size: 12px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  font-weight: 700;
}

.subcopy {
  margin: 0;
  max-width: 740px;
  color: var(--ys-muted);
  line-height: 1.8;
}

.header-actions {
  display: flex;
  align-items: start;
  gap: 12px;
}

.assistant-stats {
  flex-wrap: wrap;
}

.stat-card {
  flex: 1;
  min-width: 220px;
  padding: 18px;
  border-radius: 20px;
  background: rgba(255, 251, 245, 0.92);
  border: 1px solid rgba(84, 55, 23, 0.08);
}

.stat-card span {
  color: var(--ys-muted);
}

.stat-card strong {
  display: block;
  margin-top: 10px;
  font-size: 22px;
}

.assistant-panel {
  display: grid;
  gap: 18px;
  padding: 22px;
  border-radius: 26px;
  background: rgba(255, 252, 247, 0.88);
  border: 1px solid rgba(84, 55, 23, 0.08);
  box-shadow: var(--ys-shadow);
}

.composer {
  display: grid;
  gap: 12px;
}

.composer-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--ys-muted);
}

@media (max-width: 720px) {
  .assistant-header {
    flex-direction: column;
  }

  .composer-actions {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
}
</style>
