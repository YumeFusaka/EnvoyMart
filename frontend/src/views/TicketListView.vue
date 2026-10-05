<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getTicketSummary, listMyTickets, ticketStatusTagType, ticketTurnText } from '@/api/ticket'
import { useTicketStore } from '@/stores'
import { formatDate } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import CreateTicketDialog from '@/components/ticket/CreateTicketDialog.vue'
import type { Ticket, TicketSummary } from '@/types/models'

const router = useRouter()
const ticketStore = useTicketStore()

const records = ref<Ticket[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const failed = ref(false)
const summary = ref<TicketSummary | null>(null)
const dialogOpen = ref(false)

const SIZE = 10

/**
 * 状态页签。**「全部」之外的四档就是工单状态机的全部取值**，
 * 而不是挑选过的几个——挑着展示的结果是某些工单在任何页签下都找不到。
 * <p>
 * 计数来自 `/tickets/summary` 的同一次查询，所以「全部」恒等于四档之和；
 * 拉不到计数时页签退化成纯文字（不显示括号里的 0），而不是显示四个假零。
 */
const TABS = computed(() => [
  { value: '', label: '全部', count: summary.value?.total },
  { value: 'OPEN', label: '待处理', count: summary.value?.open },
  { value: 'PROCESSING', label: '处理中', count: summary.value?.processing },
  { value: 'RESOLVED', label: '已解决', count: summary.value?.resolved },
  { value: 'CLOSED', label: '已关闭', count: summary.value?.closed },
])

const status = ref('')

async function load() {
  loading.value = true
  failed.value = false
  try {
    const [pageResult, summaryResult] = await Promise.all([
      listMyTickets({ status: status.value || undefined, page: page.value - 1, size: SIZE }),
      // 计数与列表并发拉：串行的话页签上的数字会比列表晚一拍，
      // 切换页签时能看到它们对不上的一瞬间
      getTicketSummary().catch(() => null),
    ])
    records.value = pageResult.records
    total.value = pageResult.total
    if (summaryResult) summary.value = summaryResult
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

function onTabChange(next: string) {
  status.value = next
  // 换筛选条件回到第一页：留在第 3 页看一个只有 2 条结果的筛选，会得到一片空白
  page.value = 1
  load()
}

function onPageChange(next: number) {
  page.value = next
  load()
}

function onCreated(id: number) {
  router.push(`/tickets/${id}`)
}

onMounted(load)

// 挂载期间订阅「客服回了话」的推送：列表上某条工单的球权可能随时翻到用户这边，
// 不订阅的话用户得手动刷新才知道 —— 而这一页正是最该实时的地方。
// 离开即断开（下面 onUnmounted），长连接只在需要它的这一页占着。
//
// 只在「等你回应」的**计数**变化时重拉列表：推送里的 id 列表足以判断有没有变化，
// 但真正要刷新的是这些行的状态标签与球权文案。用计数做键是为了让同一个事件
// 在角标与列表两处只触发一次刷新
let stopAwaiting: (() => void) | null = null
onMounted(() => {
  ticketStore.refresh()
  stopAwaiting = ticketStore.subscribe()
})
onUnmounted(() => stopAwaiting?.())

watch(
  () => ticketStore.awaitingMe,
  (next, prev) => {
    // 首次拿到值（prev 为 null）不算变化：那是初始快照，列表自己刚拉过
    if (prev !== null && next !== prev) load()
  }
)
</script>

<template>
  <div class="page tickets">
    <header class="page-header">
      <div>
        <p class="eyebrow">Support</p>
        <h1>我的工单</h1>
        <p class="tickets__hint">
          {{
            summary?.awaitingMe
              ? `${summary.awaitingMe} 条等你回应`
              : '有问题都可以在这里找客服，工单里能看到完整的沟通记录'
          }}
        </p>
      </div>
      <el-button type="primary" @click="dialogOpen = true">发起工单</el-button>
    </header>

    <nav class="tickets__tabs" aria-label="按状态筛选">
      <button
        v-for="tab in TABS"
        :key="tab.value"
        type="button"
        class="tickets__tab"
        :class="{ 'is-active': status === tab.value }"
        :aria-current="status === tab.value ? 'true' : undefined"
        @click="onTabChange(tab.value)"
      >
        {{ tab.label }}
        <span v-if="tab.count != null" class="tickets__tab-count">{{ tab.count }}</span>
      </button>
    </nav>

    <ErrorState v-if="failed" message="工单加载失败" :on-retry="load" />

    <template v-else>
      <div v-loading="loading" class="tickets__body">
        <el-empty v-if="!records.length && !loading" description="这里还没有工单">
          <el-button type="primary" @click="dialogOpen = true">发起工单</el-button>
        </el-empty>

        <ul v-else class="list">
          <li v-for="record in records" :key="record.id" class="item">
            <button type="button" class="item__main" @click="router.push(`/tickets/${record.id}`)">
              <span class="item__head">
                <el-tag size="small" effect="plain">{{ record.categoryText }}</el-tag>
                <span class="item__title">{{ record.title }}</span>
                <el-tag size="small" effect="light" :type="ticketStatusTagType(record.status)">
                  {{ record.statusText }}
                </el-tag>
              </span>

              <span class="item__meta">
                <span class="item__no">{{ record.ticketNo }}</span>
                <span v-if="record.orderNo" class="item__order">关联订单 {{ record.orderNo }}</span>
                <span class="item__time">最近更新 {{ formatDate(record.updatedAt) }}</span>
              </span>
            </button>

            <footer class="item__foot">
              <span
                class="item__turn"
                :class="{
                  'is-mine': ticketTurnText(record) !== '等客服回复' && record.status !== 'CLOSED',
                }"
              >
                {{ ticketTurnText(record) }}
              </span>
              <RouterLink class="item__link" :to="`/tickets/${record.id}`">查看会话</RouterLink>
            </footer>
          </li>
        </ul>
      </div>

      <el-pagination
        v-if="total > SIZE"
        class="tickets__pager"
        layout="prev, pager, next, total"
        background
        :total="total"
        :page-size="SIZE"
        :current-page="page"
        @current-change="onPageChange"
      />
    </template>

    <CreateTicketDialog v-model="dialogOpen" @created="onCreated" />
  </div>
</template>

<style scoped>
.tickets__hint {
  margin-top: var(--ys-space-2);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.tickets__tabs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
  margin-bottom: var(--ys-space-4);
}

.tickets__tab {
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-1);
  padding: var(--ys-space-1) var(--ys-space-3);
  min-height: 32px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-surface);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  cursor: pointer;
  transition:
    color var(--ys-duration-fast) var(--ys-ease-out),
    border-color var(--ys-duration-fast) var(--ys-ease-out),
    background var(--ys-duration-fast) var(--ys-ease-out);
}

.tickets__tab:hover {
  border-color: var(--color-border-strong);
  color: var(--color-text-primary);
}

.tickets__tab.is-active {
  border-color: var(--color-primary);
  background: var(--color-primary-subtle);
  color: var(--color-primary-strong);
  font-weight: 600;
}

.tickets__tab:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.tickets__tab-count {
  font-variant-numeric: tabular-nums;
  opacity: 0.75;
}

.tickets__body {
  min-height: 240px;
}

.list {
  display: grid;
  gap: var(--ys-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.item {
  display: grid;
  gap: var(--ys-space-2);
  padding: var(--ys-space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface);
  transition: border-color var(--ys-duration-fast) var(--ys-ease-out);
}

.item:hover {
  border-color: var(--color-border-strong);
}

/* 整块可点：工单卡片的信息密度不低，逼用户瞄准标题才能进详情是没必要的 */
.item__main {
  display: grid;
  gap: var(--ys-space-2);
  width: 100%;
  padding: 0;
  border: none;
  background: none;
  text-align: start;
  cursor: pointer;
}

.item__main:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
  border-radius: var(--ys-radius-sm);
}

.item__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ys-space-2);
}

.item__title {
  font-weight: 600;
  color: var(--color-text-primary);
}

.item__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-3);
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.item__no {
  font-family: var(--ys-font-mono);
}

.item__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: var(--ys-space-2);
  border-top: 1px solid var(--color-border);
  font-size: var(--ys-font-xs);
}

.item__turn {
  color: var(--color-text-muted);
}

/* 球权在自己这边时才着色：一屏全是彩色标签等于没有重点 */
.item__turn.is-mine {
  color: var(--color-primary-strong);
  font-weight: 600;
}

/*
 * 「查看会话」是整行里唯一的入口，但纯文字只有 48x19 —— 低于基线要求的 24px 高。
 * 用垂直内边距把命中区撑到 24px，同时用负外边距抵消它对行高的影响：
 * 观感完全不变，可点面变大（触控板上不再需要瞄着点）。
 */
.item__link {
  display: inline-block;
  padding: var(--ys-space-1) 0;
  margin: calc(var(--ys-space-1) * -1) 0;
  color: var(--color-primary-strong);
  text-decoration: none;
}

.item__link:hover {
  text-decoration: underline;
}

.tickets__pager {
  justify-content: center;
  margin-top: var(--ys-space-4);
}
</style>
