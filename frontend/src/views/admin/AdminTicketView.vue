<script setup lang="ts">
/**
 * 客服工作台。
 *
 * 形状与其它管理页不同：不是「列表 + 抽屉」，而是**列表与对话并排**。
 * 客服的整班工作就是这个循环 —— 扫一眼队列、点开一条、读完、回一句、点下一条。
 * 每回复一次都要开关一次抽屉，一天下来是几百次多余的点击，
 * 而且关掉之后队列的滚动位置、当前筛选全丢了。
 *
 * 队列的默认视图是「待我回复」，不是「全部工单」：客服上班第一件事是捞自己的欠账，
 * 而不是从第一页翻到最后。这个筛选对应后端的球权列（`awaitingAdmin`）——
 * 「上一次说话的是用户」就意味着球在客服这边。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import {
  closeTicket,
  getTicketDetail,
  listTickets,
  replyTicket,
  resolveTicket,
} from '@/api/admin/ticket'
import { useAdminList } from '@/composables/useAdminList'
import { formatDate, formatDateTime } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import type {
  AdminTicketDetail,
  AdminTicketQuery,
  AdminTicketSummary,
  TicketSenderType,
} from '@/types/admin'

const {
  query,
  records,
  total,
  loading,
  error,
  search,
  resetFilters,
  currentPage,
  changePage,
  changeSize,
  load,
} = useAdminList<AdminTicketSummary, AdminTicketQuery>(
  listTickets,
  {
    keyword: '',
    userId: '',
    status: undefined,
    category: undefined,
    // 默认只看等客服回复的。改成「全部」是运营的主动选择，不是默认负担
    awaitingAdmin: true,
    page: 0,
    size: 20,
  },
  { immediate: false },
)

/** 球权筛选是三态的：等客服 / 已在等用户 / 全部。用字符串而不是 boolean 让「全部」有位置 */
type AwaitingFilter = 'waiting' | 'pending' | 'all'
const awaiting = ref<AwaitingFilter>('waiting')

function syncAwaiting() {
  if (awaiting.value === 'all') {
    query.value.awaitingAdmin = undefined
  } else {
    query.value.awaitingAdmin = awaiting.value === 'waiting'
  }
}

watch(awaiting, () => {
  syncAwaiting()
  void search()
})

onMounted(() => {
  syncAwaiting()
  void load(0)
})

async function onReset() {
  awaiting.value = 'waiting'
  await resetFilters()
  // resetFilters 把 awaitingAdmin 还原成初值(true)，与 awaiting 的初值一致，不用再同步
}

// ==================== 详情 ====================

const activeId = ref<number | null>(null)
const detail = ref<AdminTicketDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref<string | null>(null)

/** 回复框草稿按工单分开存：切走再切回来，没发出去的话还在 */
const drafts = ref<Record<number, string>>({})
const draft = computed({
  get: () => (activeId.value === null ? '' : (drafts.value[activeId.value] ?? '')),
  set: (value: string) => {
    if (activeId.value !== null) {
      drafts.value[activeId.value] = value
    }
  },
})

const sending = ref(false)

async function open(ticket: AdminTicketSummary) {
  activeId.value = ticket.id
  await loadDetail()
}

async function loadDetail() {
  if (activeId.value === null) {
    return
  }
  const id = activeId.value
  detailLoading.value = true
  detailError.value = null
  try {
    const result = await getTicketDetail(id)
    // 请求期间用户可能已经点了别的工单；慢的那次回来不该把当前这条顶掉
    if (activeId.value === id) {
      detail.value = result
    }
  } catch (e) {
    if (activeId.value === id) {
      detail.value = null
      detailError.value = e instanceof Error ? e.message : '加载失败'
    }
  } finally {
    if (activeId.value === id) {
      detailLoading.value = false
    }
  }
}

/**
 * 写回动作返回的最新详情 —— 但只在客服还停在这一条工单上时。
 * <p>
 * 三个动作都是「点一下、等接口、写详情」，而接口回来之前客服完全可以去点另一条工单。
 * 直接写下去就是把 A 工单的详情盖到 B 工单的界面上：没有报错、没有加载态，
 * 内容自己换了一份，看起来像是点错了或者数据串了。与 {@link loadDetail} 同一个判据。
 */
function applyDetail(id: number, result: AdminTicketDetail) {
  if (activeId.value === id) {
    detail.value = result
  }
}

// ==================== 动作 ====================

const CLOSED = 'CLOSED'

const closed = computed(() => detail.value?.ticket.status === CLOSED)

async function send() {
  const content = draft.value.trim()
  if (!content || activeId.value === null) {
    return
  }
  const id = activeId.value
  sending.value = true
  try {
    applyDetail(id, await replyTicket(id, content))
    drafts.value[id] = ''
    ElMessage.success('已回复')
    await load()
  } catch {
    // 工单已被关闭之类的拒绝原因由后端给出，拦截器已展示；草稿留着让客服复制走
  } finally {
    sending.value = false
  }
}

async function resolve() {
  if (activeId.value === null) {
    return
  }
  const id = activeId.value
  const content = draft.value.trim()
  try {
    await ElMessageBox.confirm(
      '标记解决后，工单在 7 天内没有被用户重新打开就会自动关闭。' +
        (content ? '当前回复框里的内容会作为解决说明发给用户。' : '回复框是空的，将不带说明。'),
      '标记已解决',
      { type: 'info', confirmButtonText: '标记解决', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  sending.value = true
  try {
    applyDetail(id, await resolveTicket(id, content || undefined))
    drafts.value[id] = ''
    ElMessage.success('已标记解决')
    await load()
  } catch {
    // 同回复
  } finally {
    sending.value = false
  }
}

async function close() {
  if (activeId.value === null) {
    return
  }
  const id = activeId.value
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      '关闭原因会作为一条客服消息落进对话里 —— 用户是自己点进来的，看不到任何后台日志。',
      '关闭工单',
      {
        inputPlaceholder: '如：同一问题重复提交',
        inputValidator: (value) => (value && value.trim() ? true : '必须填原因'),
        confirmButtonText: '关闭工单',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
    reason = result.value.trim()
  } catch {
    return
  }
  sending.value = true
  try {
    applyDetail(id, await closeTicket(id, reason))
    ElMessage.success('工单已关闭')
    await load()
  } catch {
    // 同回复
  } finally {
    sending.value = false
  }
}

// ==================== 展示 ====================

const categoryOptions = [
  { label: '订单问题', value: 'ORDER' },
  { label: '退款售后', value: 'REFUND' },
  { label: '商品咨询', value: 'PRODUCT' },
  { label: '其他', value: 'OTHER' },
]

const statusOptions = [
  { label: '待处理', value: 'OPEN' },
  { label: '处理中', value: 'PROCESSING' },
  { label: '已解决', value: 'RESOLVED' },
  { label: '已关闭', value: 'CLOSED' },
]

function statusTagType(status: string): 'success' | 'warning' | 'info' | 'primary' {
  switch (status) {
    case 'OPEN':
      return 'warning'
    case 'PROCESSING':
      return 'primary'
    case 'RESOLVED':
      return 'success'
    default:
      return 'info'
  }
}

function senderLabel(type: TicketSenderType, senderId: string | null): string {
  switch (type) {
    case 'USER':
      return senderId ? `用户 ${senderId}` : '用户'
    case 'ADMIN':
      return senderId ? `客服 ${senderId}` : '客服'
    default:
      return '系统'
  }
}

/** 球权落在谁那边。列表上这比状态更能说明「这条现在该谁动」 */
function ballSide(ticket: AdminTicketSummary): string {
  if (ticket.status === CLOSED) {
    return '已关闭'
  }
  if (ticket.lastReplyBy === 'USER') {
    return '等客服回复'
  }
  if (ticket.lastReplyBy === null) {
    return '还没人回复'
  }
  return '等用户回复'
}

function isWaiting(ticket: AdminTicketSummary): boolean {
  return ticket.status !== CLOSED && ticket.lastReplyBy === 'USER'
}
</script>

<template>
  <div class="admin-panel">
    <div class="admin-filters">
      <div class="admin-filters__item admin-filters__item--wide">
        <label class="admin-filters__label" for="ticket-keyword">关键词</label>
        <el-input
          id="ticket-keyword"
          v-model="query.keyword"
          placeholder="工单号 / 标题 / 正文 / 订单号"
          clearable
          @keyup.enter="search"
        />
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="ticket-category">分类</label>
        <el-select id="ticket-category" v-model="query.category" clearable placeholder="全部分类">
          <el-option
            v-for="c in categoryOptions"
            :key="c.value"
            :label="c.label"
            :value="c.value"
          />
        </el-select>
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="ticket-status">状态</label>
        <el-select id="ticket-status" v-model="query.status" clearable placeholder="全部状态">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="ticket-user">用户 ID</label>
        <el-input
          id="ticket-user"
          v-model="query.userId"
          placeholder="如 u1001"
          clearable
          @keyup.enter="search"
        />
      </div>

      <div class="admin-filters__actions">
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="onReset">重置</el-button>
      </div>
    </div>

    <div class="admin-toolbar">
      <h2 class="admin-toolbar__title">客服工单</h2>
      <el-radio-group v-model="awaiting" size="small">
        <el-radio-button value="waiting">待我回复</el-radio-button>
        <el-radio-button value="pending">等用户回复</el-radio-button>
        <el-radio-button value="all">全部</el-radio-button>
      </el-radio-group>
      <span class="admin-toolbar__count">共 {{ total }} 条</span>
    </div>

    <ErrorState v-if="error" :message="error" :on-retry="() => load()" />

    <div v-else class="workbench">
      <div class="workbench__queue">
        <div v-loading="loading" class="queue">
          <button
            v-for="ticket in records"
            :key="ticket.id"
            type="button"
            class="queue__item"
            :class="{ 'queue__item--active': ticket.id === activeId }"
            @click="open(ticket)"
          >
            <div class="queue__head">
              <span class="queue__title admin-cell--ellipsis">{{ ticket.title }}</span>
              <el-tag v-if="isWaiting(ticket)" type="warning" effect="plain" size="small"
                >待回复</el-tag
              >
            </div>
            <div class="queue__meta">
              <span class="queue__no admin-cell--tiny">{{ ticket.ticketNo }}</span>
              <span class="queue__time admin-cell--tiny"
                >{{ formatDate(ticket.updatedAt) }}</span
              >
            </div>
            <div class="queue__meta">
              <span class="queue__cat admin-cell--tiny">{{ ticket.categoryText }}</span>
              <span class="queue__ball admin-cell--tiny">{{ ballSide(ticket) }}</span>
            </div>
          </button>

          <p v-if="!loading && records.length === 0" class="admin-empty">
            {{ awaiting === 'waiting' ? '没有等你回复的工单 —— 队列是空的' : '没有符合条件的工单' }}
          </p>
        </div>

        <div class="admin-pager">
          <el-pagination
            :current-page="currentPage"
            :page-size="query.size"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="prev, pager, next"
            small
            background
            @current-change="changePage"
            @size-change="changeSize"
          />
        </div>
      </div>

      <div class="workbench__thread">
        <el-skeleton v-if="detailLoading" :rows="6" animated />

        <ErrorState v-else-if="detailError" :message="detailError" :on-retry="loadDetail" />

        <div v-else-if="!detail" class="thread__placeholder">
          <p class="thread__placeholder-title">选一条工单开始处理</p>
          <p class="thread__placeholder-hint">
            左边队列默认只列「等你回复」的那几条 —— 球在客服这边，才需要动作。
          </p>
        </div>

        <template v-else>
          <header class="thread__head">
            <div class="thread__title">
              <h3 class="admin-section__title">{{ detail.ticket.title }}</h3>
              <el-tag :type="statusTagType(detail.ticket.status)" effect="plain" size="small">
                {{ detail.ticket.statusText }}
              </el-tag>
            </div>
            <div class="thread__meta">
              <span class="admin-cell--tiny">{{ detail.ticket.ticketNo }}</span>
              <span class="admin-cell--tiny">{{ detail.ticket.categoryText }}</span>
              <span class="admin-cell--tiny">用户 {{ detail.ticket.userId }}</span>
              <span v-if="detail.ticket.orderNo" class="admin-cell--tiny"
                >订单 {{ detail.ticket.orderNo }}</span
              >
              <span class="admin-cell--tiny">提起于 {{ formatDate(detail.ticket.createdAt) }}</span>
            </div>
            <p v-if="detail.closeReason" class="thread__closed">
              关闭原因：{{ detail.closeReason }}
            </p>
          </header>

          <div class="thread__messages">
            <article
              v-for="message in detail.messages"
              :key="message.id"
              class="bubble"
              :class="`bubble--${message.senderType.toLowerCase()}`"
            >
              <div class="bubble__head">
                <span class="bubble__who">{{
                  senderLabel(message.senderType, message.senderId)
                }}</span>
                <span class="admin-cell--tiny">{{ formatDateTime(message.createdAt) }}</span>
              </div>
              <p class="bubble__body">{{ message.content }}</p>
            </article>
          </div>

          <footer class="thread__compose">
            <template v-if="closed">
              <p class="admin-dialog__hint">
                这条工单已经关闭，不能再回复。用户需要重新提起一条新工单 ——
                让关闭变成可逆的，等于把「结案」这个词作废。
              </p>
            </template>
            <template v-else>
              <el-input
                v-model="draft"
                type="textarea"
                :rows="4"
                maxlength="1000"
                show-word-limit
                placeholder="回复用户。回复会自动接手这条工单，状态推到「处理中」。"
                @keydown.ctrl.enter="send"
                @keydown.meta.enter="send"
              />
              <div class="compose__actions">
                <span class="admin-dialog__hint">Ctrl / ⌘ + Enter 直接发送</span>
                <div>
                  <el-button :disabled="sending" @click="close">关闭工单</el-button>
                  <el-button :disabled="sending || !draft.trim()" @click="resolve"
                    >标记已解决</el-button
                  >
                  <el-button
                    type="primary"
                    :loading="sending"
                    :disabled="!draft.trim()"
                    @click="send"
                  >
                    回复
                  </el-button>
                </div>
              </div>
            </template>
          </footer>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.workbench {
  display: grid;
  grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);
  gap: var(--ys-space-4);
  align-items: start;
}

@media (max-width: 1024px) {
  .workbench {
    grid-template-columns: 1fr;
  }
}

.workbench__queue {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-2);
}

.queue {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-1);
  max-height: 62vh;
  overflow-y: auto;
  padding: var(--ys-space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface);
}

.queue__item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: var(--ys-space-2) var(--ys-space-3);
  border: 1px solid transparent;
  border-radius: var(--ys-radius-sm);
  background: transparent;
  text-align: start;
  cursor: pointer;
  transition: background var(--ys-duration-fast) var(--ys-ease-out);
}

.queue__item:hover {
  background: var(--color-bg-sunken);
}

.queue__item:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.queue__item--active {
  border-color: var(--color-accent);
  background: var(--color-bg-sunken);
}

.queue__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-2);
}

.queue__title {
  font-weight: 600;
  font-size: var(--ys-font-sm);
}

/* 两行元信息用同一套两列网格，而不是 space-between。
   space-between 把左右两项推到各自边缘，左项长度一变（工单号 20 位、用户 ID 4 位），
   右项起始位置就跟着漂，一列工单扫下来「分类」根本不在一条竖线上 */
.queue__meta {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: baseline;
  gap: var(--ys-space-2);
}

.queue__no,
.queue__cat {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.queue__time,
.queue__ball {
  text-align: end;
  white-space: nowrap;
}

.workbench__thread {
  display: flex;
  flex-direction: column;
  min-height: 62vh;
  padding: var(--ys-space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface);
}

/* 未选中任何工单时的空态：竖直居中，别让一句提示孤零零挂在左上角。
   右侧这块有 800px 宽，居中是唯一能让它看起来「是有意留白」而不是「没渲染完」的排法 */
.thread__placeholder {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--ys-space-2);
  text-align: center;
}

.thread__placeholder-title {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-base);
  font-weight: 600;
}

.thread__placeholder-hint {
  max-width: 32ch;
  color: var(--color-text-muted);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
}

.thread__head {
  padding-bottom: var(--ys-space-3);
  border-bottom: 1px solid var(--color-border);
}

.thread__title {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
}

.thread__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-3);
  margin-top: var(--ys-space-1);
}

.thread__closed {
  margin-top: var(--ys-space-2);
  padding: var(--ys-space-2) var(--ys-space-3);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-sunken);
  color: var(--color-text-muted);
  font-size: var(--ys-font-sm);
}

.thread__messages {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-3);
  flex: 1;
  max-height: 46vh;
  overflow-y: auto;
  padding-block: var(--ys-space-3);
}

.bubble {
  max-width: 78%;
  padding: var(--ys-space-3);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-sunken);
}

/* 用户消息靠左、客服消息靠右：翻聊天记录时「谁说的」应该一眼可辨，不用去读昵称 */
.bubble--user {
  align-self: flex-start;
}

.bubble--admin {
  align-self: flex-end;
  background: var(--color-accent-subtle);
}

.bubble--system {
  align-self: center;
  max-width: 100%;
  background: transparent;
  border: 1px dashed var(--color-border);
  color: var(--color-text-muted);
}

.bubble__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ys-space-3);
  margin-bottom: var(--ys-space-1);
}

.bubble__who {
  font-size: var(--ys-font-xs);
  font-weight: 600;
}

.bubble__body {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
}

.thread__compose {
  padding-top: var(--ys-space-3);
  border-top: 1px solid var(--color-border);
}

.compose__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-3);
  margin-top: var(--ys-space-2);
}

.compose__actions > div {
  display: flex;
  gap: var(--ys-space-2);
}
</style>
