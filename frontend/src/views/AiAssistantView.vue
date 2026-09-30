<script setup lang="ts">
import { chatStream } from '@/api/ai'
import ChatMessageList from '@/components/ai/ChatMessageList.vue'
import QuickPromptBar from '@/components/ai/QuickPromptBar.vue'
import { useUserStore } from '@/stores'
import type { ChatMessage, ProductSummary } from '@/types/models'
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
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

/**
 * 用户手打确认词时必须真的置位 `approved`。
 * <p>
 * 服务端的确认提示就写着「回复「确认执行」」，而 `approved` 是请求体里一个独立的布尔位——
 * 前端不翻译这句话，它就会带着 `approved=false` 重新规划，**再次撞上同一个闸口**，
 * 用户看到的是一模一样的提示，永远取消不掉订单。
 * <p>
 * 判据收得很紧：必须是「最新一条助手消息正挂着待确认」**且**「整句就是一个确认词」。
 * 不做成全局关键词——那样用户在别的语境里回一句「好的」，就可能批准一次高危操作。
 */
const CONFIRM_PATTERN = /^(确认|确认执行|确定|是|好的|好|yes|ok)[。！!，,]*$/i

/** 当前在飞的流。用户点「停止」或离开页面时用它取消 */
let abortController: AbortController | null = null

async function sendMessage(message = input.value, approved = false) {
  const content = message.trim()
  if (!content) return
  // 上一轮还在生成时不接新的发送：两条流会各自往自己的占位消息里写，界面看似正常，
  // 但 `approved` 是随消息走的——确认词可能被配到错误的那一轮上
  if (loading.value) return

  const latest = messages.value[messages.value.length - 1]
  const confirming =
    !approved && Boolean(latest?.pendingActions?.length) && CONFIRM_PATTERN.test(content)

  messages.value.push({
    id: `user-${Date.now()}`,
    role: 'user',
    content,
  })
  input.value = ''
  loading.value = true
  abortController = new AbortController()

  // 先插入占位的助手消息，随后按流式增量填充
  const assistantMessage = reactive<ChatMessage>({
    id: `assistant-${Date.now()}`,
    role: 'assistant',
    content: '',
  })
  messages.value.push(assistantMessage)

  try {
    await chatStream(
      { sessionId, message: content, approved: approved || confirming },
      {
        onDelta: (text) => {
          assistantMessage.content += text
        },
        onDone: (response) => {
          assistantMessage.content = response.reply || assistantMessage.content
          assistantMessage.knowledge = response.knowledge
          assistantMessage.toolCalls = response.toolCalls
          assistantMessage.recommendedProducts = response.recommendedProducts
          // 非空即本轮被中断：回复是确认提示，没有任何工具真正执行过
          assistantMessage.pendingActions = response.pendingActions ?? undefined
          assistantMessage.evidenceLevel = response.evidenceLevel ?? undefined
          // 后置校验的两项结果。流式下 delta 已经渲染过了，`content` 的赋值在上面
          // ——它会把没有出处的句子擦掉，用户看到的最终文本与校验结果是一致的
          assistantMessage.unsupportedClaims = response.unsupportedClaims ?? undefined
          assistantMessage.unsupportedStripped = response.unsupportedStripped
          assistantMessage.ungrounded = response.ungrounded
          assistantMessage.conflicts = response.conflicts ?? undefined
          assistantMessage.usage = response.usage
        },
        onError: (msg) => {
          assistantMessage.content = msg
        },
      },
      abortController.signal,
    )
  } catch (e) {
    if (e instanceof Error && e.name === 'AbortError') {
      // 用户主动停止不是故障：半截回答照留，但必须标出来 ——
      // 不标的话它看起来像一段说完了的完整回答
      assistantMessage.content = assistantMessage.content
        ? `${assistantMessage.content}\n\n（已停止生成）`
        : '（已停止生成）'
    } else {
      assistantMessage.content = '智能助手暂时不可用，请稍后再试。'
    }
  } finally {
    loading.value = false
    abortController = null
  }
}

function handleStop() {
  abortController?.abort()
}

// 离开页面就取消在飞的流：不取消的话它会继续读，写进一个已经不在屏幕上的消息里
onBeforeUnmount(() => abortController?.abort())

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

/** 先收起卡片再重发：新消息一入列，这张卡就不是「最新一条」，会立刻变成过期的灰态 */
function handleApprove() {
  const latest = messages.value[messages.value.length - 1]
  if (latest) {
    latest.pendingActions = undefined
  }
  void sendMessage('确认执行', true)
}

/**
 * 取消只是本地收起卡片，<b>不发任何请求</b>——服务端那次计划已经丢弃，没有副作用要撤销，
 * 也没有「拒绝」这个接口可调。
 * <p>
 * 但要把这条回复的正文改掉：它还写着「确认无误请点击确认执行」，
 * 而按钮已经没了。留着那句话，用户会以为是自己看漏了一个按钮。
 */
function handleDismiss() {
  const latest = messages.value[messages.value.length - 1]
  if (latest) {
    latest.pendingActions = undefined
    latest.content = '已取消，本次没有执行任何操作。'
  }
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

      <ChatMessageList
        :messages="messages"
        @open-product="openProduct"
        @approve="handleApprove"
        @dismiss="handleDismiss"
      />

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
          <!-- 生成中把发送换成停止：按钮同时承担「这一轮还没完」的状态提示 -->
          <el-button v-if="loading" plain @click="handleStop">停止生成</el-button>
          <el-button v-else type="primary" @click="sendMessage()">发送消息</el-button>
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
