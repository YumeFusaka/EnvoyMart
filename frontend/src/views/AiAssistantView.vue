<script setup lang="ts">
import { chatStream } from '@/api/ai'
import ChatMessageList from '@/components/ai/ChatMessageList.vue'
import QuickPromptBar from '@/components/ai/QuickPromptBar.vue'
import { useUserStore } from '@/stores'
import type { ChatMessage, ProductSummary } from '@/types/models'
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const input = ref('')

/**
 * 会话 id 要跟着用户留下来，而不是每次进页面重新生成。
 * <p>
 * 原先写的是 `session-${Date.now()}` —— 每次点进导航都换一个，于是**去商品页看一眼
 * 再回来，上下文就没了**。而多轮对话的记忆是按 sessionId 挂在服务端的：
 * 前端换号，服务端那边就换了一段记忆，用户看到的是「它突然不记得我刚才说过什么」。
 * <p>
 * 用 sessionStorage 而不是 localStorage：关掉标签页就结束一段会话是符合直觉的，
 * 而且下一段会话本来就该是干净的上下文。按 userId 分开，避免换账号后接上前一个人的记忆。
 */
const SESSION_KEY = 'envoymart.ai.session'
function resolveSessionId(): string {
  const owner = userStore.profile?.id ?? 'anonymous'
  const key = `${SESSION_KEY}.${owner}`
  const existing = sessionStorage.getItem(key)
  if (existing) return existing
  // 会话号带上用户，服务端排查「这段对话是谁的」时不必再查一次表
  const created = `${owner}-${Date.now()}`
  sessionStorage.setItem(key, created)
  return created
}
const sessionId = resolveSessionId()

const prompts = [
  '推荐适合学生党的百元内耳机',
  '活动满减规则是什么',
  '七天无理由退货怎么处理',
  '帮我查一下这个订单物流到哪了',
]

const messages = ref<ChatMessage[]>([
  {
    id: 'welcome',
    role: 'assistant',
    content:
      '欢迎来到 EnvoyMart 智能客服台。你可以问我商品推荐、活动规则、售后政策，或者让我帮你查询订单和物流。',
  },
])

const assistantCount = computed(
  () => messages.value.filter((item) => item.role === 'assistant').length,
)

async function sendMessage(message = input.value) {
  const content = message.trim()
  if (!content) return

  messages.value.push({
    id: `user-${Date.now()}`,
    role: 'user',
    content,
  })
  input.value = ''
  loading.value = true

  // 先插入占位的助手消息，随后按流式增量填充
  const assistantMessage = reactive<ChatMessage>({
    id: `assistant-${Date.now()}`,
    role: 'assistant',
    content: '',
  })
  messages.value.push(assistantMessage)

  try {
    await chatStream(
      { sessionId, message: content },
      {
        onDelta: (text) => {
          assistantMessage.content += text
        },
        onDone: (response) => {
          assistantMessage.content = response.reply || assistantMessage.content
          assistantMessage.knowledge = response.knowledge
          assistantMessage.toolCalls = response.toolCalls
          assistantMessage.recommendedProducts = response.recommendedProducts
        },
        onError: (msg) => {
          assistantMessage.content = msg
        },
      },
    )
  } catch {
    assistantMessage.content = '智能助手暂时不可用，请稍后再试。'
  } finally {
    loading.value = false
  }
}

/**
 * 推荐卡片点开要进**商品详情**，不是拿商品名去搜索。
 * <p>
 * 原先跳的是 `/shop?keyword=<商品名>` —— 用户点的是一个具体商品，落到的却是一页
 * 搜索结果；商品名稍有出入（规格、副标题）就一条都搜不到，看到的是空列表。
 * 推荐卡片带着 id，直接进详情是唯一不会错的做法。
 */
function openProduct(product: ProductSummary) {
  router.push({ name: 'product-detail', params: { id: product.id } })
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
  color: var(--color-primary);
  font-size: 12px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  font-weight: 700;
}

.subcopy {
  margin: 0;
  max-width: 740px;
  color: var(--color-text-secondary);
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
  color: var(--color-text-secondary);
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
  box-shadow: var(--ys-shadow-modal);
}

.composer {
  display: grid;
  gap: 12px;
}

.composer-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--color-text-secondary);
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
