<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  appendTicketMessage,
  closeTicket,
  getTicketDetail,
  reopenTicket,
  ticketStatusTagType,
} from '@/api/ticket'
import { formatDateTime } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import CreateTicketDialog from '@/components/ticket/CreateTicketDialog.vue'
import { useTicketStore } from '@/stores'
import type { TicketDetail } from '@/types/models'

/**
 * 工单详情：一次对话，不是一张表单。
 *
 * 所以排版跟着聊天的读法走 —— 消息流占主列、输入框钉在底部，
 * 而不是把「工单字段」铺成一个详情表格再在角落里塞个回复框。
 * 用户来这里是为了看**客服说了什么**，字段（编号、分类、时间）只是脚注。
 */
const route = useRoute()
const router = useRouter()
const ticketStore = useTicketStore()

const detail = ref<TicketDetail | null>(null)
const loading = ref(true)
const failed = ref(false)
const draft = ref('')
const sending = ref(false)
const acting = ref(false)
const newDialogOpen = ref(false)
const stream = ref<HTMLElement | null>(null)

const ticketId = computed(() => Number(route.params.id))
const ticket = computed(() => detail.value?.ticket ?? null)
const closed = computed(() => ticket.value?.status === 'CLOSED')
const resolved = computed(() => ticket.value?.status === 'RESOLVED')

async function load(options: { silent?: boolean } = {}) {
  if (!options.silent) {
    loading.value = true
    failed.value = false
  }
  try {
    detail.value = await getTicketDetail(ticketId.value)
    await scrollToLatest()
  } catch {
    // 静默刷新失败就保留屏幕上已有的内容 —— 把看得好好的会话换成一片错误态，
    // 比「刷新没成功」本身糟糕得多
    if (!options.silent) failed.value = true
  } finally {
    loading.value = false
  }
}

/** 新消息落在底部，所以每次会话变化都要把视口带过去，否则用户看到的是几天前的那句 */
async function scrollToLatest() {
  await nextTick()
  const element = stream.value
  if (element) element.scrollTop = element.scrollHeight
}

async function send() {
  const content = draft.value.trim()
  if (!content) return
  sending.value = true
  try {
    detail.value = await appendTicketMessage(ticketId.value, content)
    draft.value = ''
    await scrollToLatest()
    ticketStore.refresh()
  } catch (error) {
    // 409 = 工单在这期间被关掉了（客服结案 / 超时自动关闭）。就地重拉一次，
    // 让页面立刻变成「已关闭」的样子，而不是留着一个还能打字的输入框骗用户继续写
    if ((error as { code?: number }).code === 409) {
      await load({ silent: true })
      await scrollToLatest()
    }
  } finally {
    sending.value = false
  }
}

async function close() {
  const confirmed = await ElMessageBox.confirm(
    resolved.value
      ? '确认问题已经解决？工单关闭后不能再追加消息。'
      : '关闭这条工单？关闭后不能再追加消息，有新问题需要重新发起。',
    resolved.value ? '确认解决' : '关闭工单',
    {
      confirmButtonText: resolved.value ? '确认解决' : '关闭工单',
      cancelButtonText: '再想想',
      type: 'warning',
    },
  ).catch(() => false)
  if (!confirmed) return

  acting.value = true
  try {
    detail.value = await closeTicket(ticketId.value)
    ElMessage.success(resolved.value ? '已确认解决' : '工单已关闭')
    await scrollToLatest()
    ticketStore.refresh()
  } catch {
    // 拦截器已提示。刷新一次把页面拉回服务端的真实状态
    await load({ silent: true })
  } finally {
    acting.value = false
  }
}

async function reopen() {
  const input = await ElMessageBox.prompt(
    '问题还在的话，说一句现在的情况（可留空）。工单会回到客服的处理队列。',
    '重新打开',
    {
      confirmButtonText: '重新打开',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '例如：退款还是没有到账',
      inputValidator: (value: string) => (value?.length ?? 0) <= 2000 || '最多 2000 字',
    },
  ).catch(() => null)
  if (!input) return

  acting.value = true
  try {
    const content = String(input.value ?? '').trim()
    detail.value = await reopenTicket(ticketId.value, content || undefined)
    ElMessage.success('已重新打开，客服会看到')
    await scrollToLatest()
    ticketStore.refresh()
  } catch {
    await load({ silent: true })
  } finally {
    acting.value = false
  }
}

function senderName(message: { senderType: string }): string {
  if (message.senderType === 'USER') return '我'
  if (message.senderType === 'ADMIN') return '客服'
  return '系统'
}

function onCreated(id: number) {
  router.push(`/tickets/${id}`)
}

// 详情页之间互相跳（点列表再进来）时组件会被复用，params 变了要重拉
watch(ticketId, () => load())

// 挂载期间订阅推送：用户可能正开着这一页等客服回话，推送到了就静默重拉，
// 会话流自己长出新消息 —— 不必手动点「刷新」。离开即断开
let stopAwaiting: (() => void) | null = null
onMounted(() => {
  load()
  stopAwaiting = ticketStore.subscribe()
})
onUnmounted(() => stopAwaiting?.())

// 计数变化 = 有工单的状态/球权变了。静默重拉，保留屏幕上已有的内容直到新数据到位
watch(
  () => ticketStore.awaitingMe,
  (next, prev) => {
    if (prev !== null && next !== prev) load({ silent: true })
  }
)
</script>

<template>
  <div class="page ticket-detail">
    <el-skeleton v-if="loading" :rows="8" animated />

    <ErrorState v-else-if="failed" message="工单加载失败" :on-retry="() => load()" />

    <template v-else-if="ticket">
      <nav class="crumbs">
        <RouterLink to="/tickets" class="crumbs__link">我的工单</RouterLink>
        <span aria-hidden="true">/</span>
        <span class="crumbs__current">{{ ticket.ticketNo }}</span>
      </nav>

      <header class="head">
        <div class="head__main">
          <h1 class="head__title">{{ ticket.title }}</h1>
          <div class="head__tags">
            <el-tag size="small" effect="light" :type="ticketStatusTagType(ticket.status)">
              {{ ticket.statusText }}
            </el-tag>
            <el-tag size="small" effect="plain">{{ ticket.categoryText }}</el-tag>
            <RouterLink v-if="ticket.orderId" class="head__order" :to="`/orders/${ticket.orderId}`">
              订单 {{ ticket.orderNo }}
            </RouterLink>
          </div>
        </div>
        <div class="head__actions">
          <el-button :disabled="acting" @click="load({ silent: true })">刷新</el-button>
          <el-button v-if="resolved" type="primary" :disabled="acting" @click="close">
            确认解决
          </el-button>
          <el-button v-else-if="!closed" :disabled="acting" @click="close">关闭工单</el-button>
          <el-button v-if="resolved" :disabled="acting" @click="reopen">还有问题</el-button>
        </div>
      </header>

      <p class="head__meta">
        提起于 {{ formatDateTime(ticket.createdAt) }}
        <template v-if="ticket.resolvedAt">
          · 客服标记解决 {{ formatDateTime(ticket.resolvedAt) }}</template
        >
        <template v-if="ticket.closedAt"> · 关闭于 {{ formatDateTime(ticket.closedAt) }}</template>
      </p>

      <!-- 顶部这条只说「现在是什么状态」，发起新工单的入口在底部输入框的位置 ——
           同一句话出现两遍，用户会以为要分别做两件事 -->
      <p v-if="closed" class="notice notice--closed">
        <strong>工单已关闭</strong>
        <span>{{ ticket.closeReason || '已结束' }}，不能再追加消息。</span>
      </p>

      <p v-else-if="resolved" class="notice notice--resolved">
        客服标记了「已解决」。没问题就点<strong>确认解决</strong>结案；问题还在就点
        <strong>还有问题</strong>，工单会回到处理队列。
      </p>

      <section ref="stream" class="stream" aria-live="polite">
        <article
          v-for="message in detail!.messages"
          :key="message.id"
          class="bubble"
          :class="`bubble--${message.senderType.toLowerCase()}`"
        >
          <p class="bubble__body">{{ message.content }}</p>
          <p class="bubble__foot">
            <span class="bubble__who">{{ senderName(message) }}</span>
            <span>{{ formatDateTime(message.createdAt) }}</span>
          </p>
        </article>
      </section>

      <footer class="compose">
        <template v-if="closed">
          <p class="compose__blocked">
            不能再追加消息了。有新问题
            <el-button link type="primary" @click="newDialogOpen = true">发起新工单</el-button>
            —— 每段沟通各留一条记录，比在旧会话里续写更好查。
          </p>
        </template>
        <template v-else>
          <el-input
            v-model="draft"
            type="textarea"
            :rows="3"
            maxlength="2000"
            show-word-limit
            :placeholder="
              ticket.lastReplyBy === 'ADMIN'
                ? '客服已回复，补充说明或确认情况'
                : '补充说明。客服看到后会接手这条工单。'
            "
            @keydown.ctrl.enter="send"
            @keydown.meta.enter="send"
          />
          <div class="compose__actions">
            <span class="compose__hint">Ctrl / ⌘ + Enter 直接发送</span>
            <el-button type="primary" :loading="sending" :disabled="!draft.trim()" @click="send">
              发送
            </el-button>
          </div>
        </template>
      </footer>

      <CreateTicketDialog v-model="newDialogOpen" @created="onCreated" />
    </template>
  </div>
</template>

<style scoped>
.ticket-detail {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.crumbs {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  margin-bottom: var(--ys-space-3);
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.crumbs__link {
  color: var(--color-primary);
  text-decoration: none;
}

.crumbs__link:hover {
  text-decoration: underline;
}

.crumbs__current {
  font-family: var(--ys-font-mono);
}

.head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ys-space-3);
}

.head__main {
  display: grid;
  gap: var(--ys-space-2);
  min-width: 0;
}

.head__title {
  font-size: var(--ys-font-xl);
  font-weight: 600;
  line-height: var(--ys-leading-tight);
}

.head__tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ys-space-2);
}

.head__order {
  font-size: var(--ys-font-xs);
  color: var(--color-primary);
  text-decoration: none;
}

.head__order:hover {
  text-decoration: underline;
}

.head__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
}

.head__meta {
  margin-top: var(--ys-space-2);
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.notice {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ys-space-1);
  padding: var(--ys-space-3);
  margin-top: var(--ys-space-4);
  border-radius: var(--ys-radius-md);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-tight);
}

.notice--closed {
  background: var(--color-bg-surface-muted);
  color: var(--color-text-secondary);
}

.notice--resolved {
  background: var(--color-success-subtle);
  color: var(--color-success-strong);
}

.stream {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-3);
  margin-top: var(--ys-space-4);
  padding-block: var(--ys-space-2);
  max-height: 56vh;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.bubble {
  max-width: 76%;
  padding: var(--ys-space-3);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-sunken);
}

/* 自己说的话靠右、客服靠左：读聊天记录时「谁说的」应该一眼可辨 */
.bubble--user {
  align-self: flex-end;
  background: var(--color-primary-subtle);
}

.bubble--admin {
  align-self: flex-start;
}

.bubble--system {
  align-self: center;
  max-width: 100%;
  padding: var(--ys-space-1) var(--ys-space-3);
  border: 1px dashed var(--color-border);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.bubble__body {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
  color: var(--color-text-primary);
}

.bubble__foot {
  display: flex;
  align-items: baseline;
  gap: var(--ys-space-2);
  margin-top: var(--ys-space-2);
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.bubble__who {
  font-weight: 600;
}

.bubble--user .bubble__foot,
.bubble--user .bubble__body {
  text-align: end;
}

.compose {
  margin-top: var(--ys-space-4);
  padding-top: var(--ys-space-4);
  border-top: 1px solid var(--color-border);
}

.compose__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-3);
  margin-top: var(--ys-space-2);
}

.compose__hint,
.compose__blocked {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.compose__blocked {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
  line-height: var(--ys-leading-tight);
}
</style>
