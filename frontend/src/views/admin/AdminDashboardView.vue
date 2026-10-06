<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ChatLineSquare, Goods, List, RefreshLeft, Service, User } from '@element-plus/icons-vue'
import { listOrders } from '@/api/admin/order'
import { useAdminStore } from '@/stores'

/**
 * 管理台概览。
 * <p>
 * 它回答的只有一个问题：**今天先干哪几件**。所以排在最前的是三个欠账数字，
 * 而不是「注册用户总数」「商品总数」这类只涨不跌、看完不知道该干什么的指标。
 *
 * 三张欠账卡取的是 store 里的同一份计数 —— 侧栏徽标与这里必须一致，
 * 否则会出现「侧栏显示 3、卡片显示 2」这种谁都不信的界面。
 */

const router = useRouter()
const adminStore = useAdminStore()

interface PendingCard {
  key: 'pendingShipment' | 'pendingAfterSale' | 'pendingTicket'
  title: string
  hint: string
  to: string
  query: Record<string, string>
  tone: 'primary' | 'warning' | 'danger'
  action: string
}

const cards: PendingCard[] = [
  {
    key: 'pendingShipment',
    title: '待发货',
    hint: '已付款、等出库',
    to: '/admin/orders',
    query: { status: 'PAID' },
    tone: 'primary',
    action: '去发货',
  },
  {
    key: 'pendingAfterSale',
    title: '待审核售后',
    hint: '用户已提交、等裁决',
    to: '/admin/after-sales',
    query: { status: 'APPLIED' },
    tone: 'warning',
    action: '去审核',
  },
  {
    key: 'pendingTicket',
    title: '待客服回复',
    hint: '球权在用户侧的工单',
    to: '/admin/tickets',
    query: { awaiting: '1' },
    tone: 'danger',
    action: '去回复',
  },
]

/** 订单在途分布。四个状态各查一次总数，只取 total 所以 size 给 1 */
const stages = ref<{ label: string; status: string; total: number | null }[]>([
  { label: '已付款待发货', status: 'PAID', total: null },
  { label: '已发货在途', status: 'SHIPPED', total: null },
  { label: '已收货待评价', status: 'RECEIVED', total: null },
  { label: '退款处理中', status: 'REFUNDING', total: null },
])

const quickLinks = [
  { to: '/admin/products', label: '商品管理', icon: Goods },
  { to: '/admin/catalog', label: '类目与品牌', icon: List },
  { to: '/admin/reviews', label: '评价管理', icon: ChatLineSquare },
  { to: '/admin/users', label: '用户管理', icon: User },
  { to: '/admin/tickets', label: '客服工单', icon: Service },
  { to: '/admin/after-sales', label: '售后工作台', icon: RefreshLeft },
]

function open(card: PendingCard) {
  router.push({ path: card.to, query: card.query })
}

/**
 * 在途分布是否已加载完成。
 *
 * 四个状态是四次独立请求，任何一次失败都不该让整页报错——其余三格的数据照样有用。
 * 但「加载中」和「查不到」必须分得开：不加这个标志，两者都表现为 `total` 为 null、
 * 界面上同样是一串「—」，用户无从判断该等一等还是该点重试。
 *
 * 这正是「看起来正常的空」的典型形态：接口挂了与还没回来长得一模一样。
 */
const stagesLoading = ref(true)

/** 有几个状态没查回来。> 0 时页面必须显式提示，不能让它静静地少几格数字 */
const stagesFailed = ref(0)

async function loadStages() {
  stagesLoading.value = true
  stagesFailed.value = 0
  const results = await Promise.allSettled(
    stages.value.map(async (stage) => {
      const result = await listOrders({ status: stage.status, page: 0, size: 1 })
      stage.total = result.total
    }),
  )
  stagesFailed.value = results.filter((r) => r.status === 'rejected').length
  stagesLoading.value = false
}

onMounted(async () => {
  adminStore.refresh()
  await loadStages()
})
</script>

<template>
  <div class="dashboard">
    <section class="dashboard__pending" aria-label="待办">
      <button
        v-for="card in cards"
        :key="card.key"
        type="button"
        class="pending-card"
        :class="[`pending-card--${card.tone}`, { 'pending-card--empty': !(adminStore[card.key] ?? 0) }]"
        @click="open(card)"
      >
        <span class="pending-card__label">{{ card.title }}</span>
        <strong class="pending-card__value">
          {{ adminStore[card.key] ?? '—' }}
        </strong>
        <span class="pending-card__hint">{{ card.hint }}</span>
        <span class="pending-card__action">{{
          (adminStore[card.key] ?? 0) > 0 ? `${card.action} →` : '暂无待办'
        }}</span>
      </button>
    </section>

    <section class="admin-panel dashboard__stages" aria-label="订单在途">
      <div class="admin-toolbar">
        <h2 class="admin-toolbar__title">订单在途</h2>
        <span class="admin-toolbar__count">按状态统计，点任意一格进对应的订单列表</span>
      </div>
      <div
        v-if="stagesFailed > 0"
        class="stage-alert"
        role="status"
        aria-label="在途统计加载不完整"
      >
        <span class="stage-alert__text">
          有 {{ stagesFailed }} 个状态的统计没取回来，下面的数字可能不全
        </span>
        <button type="button" class="stage-alert__retry" @click="loadStages">重试</button>
      </div>
      <div class="stage-strip">
        <button
          v-for="stage in stages"
          :key="stage.status"
          type="button"
          class="stage"
          :class="{ 'stage--loading': stagesLoading }"
          :disabled="stagesLoading"
          @click="router.push({ path: '/admin/orders', query: { status: stage.status } })"
        >
          <span class="stage__value">
            <!-- 加载中用骨架条而不是「—」：破折号是「没有」，加载中是「还不知道」 -->
            <span v-if="stagesLoading" class="stage__skeleton" aria-hidden="true" />
            <template v-else>{{ stage.total ?? '—' }}</template>
          </span>
          <span class="stage__label">{{ stage.label }}</span>
        </button>
      </div>
    </section>

    <section class="admin-panel dashboard__links" aria-label="快捷入口">
      <div class="admin-toolbar">
        <h2 class="admin-toolbar__title">快捷入口</h2>
      </div>
      <div class="link-grid">
        <button
          v-for="link in quickLinks"
          :key="link.to"
          type="button"
          class="link-grid__item"
          @click="router.push(link.to)"
        >
          <el-icon :size="18"><component :is="link.icon" /></el-icon>
          <span>{{ link.label }}</span>
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-5);
}

.dashboard__pending {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--ys-space-4);
}

.pending-card {
  display: grid;
  gap: var(--ys-space-1);
  padding: var(--ys-space-5);
  border: 1px solid var(--color-border);
  border-radius: var(--card-radius);
  background: var(--color-bg-surface);
  box-shadow: var(--card-shadow);
  text-align: start;
  cursor: pointer;
  transition:
    border-color var(--ys-duration-fast) var(--ys-ease-out),
    transform var(--ys-duration-fast) var(--ys-ease-out);
}

.pending-card:hover {
  border-color: var(--color-primary-border);
  transform: translateY(-2px);
}

.pending-card:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

/* 左侧色条区分三种欠账的紧急度。
   颜色不是唯一线索 —— 卡片标题本身就写了它是什么，色条只是加速扫读 */
.pending-card {
  border-inline-start: 4px solid var(--color-border-strong);
}

.pending-card--primary {
  border-inline-start-color: var(--color-primary);
}

.pending-card--warning {
  border-inline-start-color: var(--color-warning);
}

/* 欠账为 0 时把左侧色条降为中性：红/橙条在「没有欠账」的卡片上
   会制造虚假的紧迫感，一眼扫过去以为有事要做 */
.pending-card--empty {
  border-inline-start-color: var(--color-border-strong);
}

.pending-card--danger {
  border-inline-start-color: var(--color-danger);
}

.pending-card__label {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.pending-card__value {
  font-size: var(--ys-font-3xl);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: var(--ys-leading-tight);
}

.pending-card__hint {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.pending-card__action {
  margin-top: var(--ys-space-2);
  color: var(--color-primary-strong);
  font-size: var(--ys-font-sm);
  font-weight: 600;
}

/* 0 待办的卡片动作文案降为弱色：主色加粗的「去发货 →」会让空卡片
   看起来也有活要干 */
.pending-card--empty .pending-card__action {
  color: var(--color-text-muted);
  font-weight: 400;
}

.stage-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: var(--ys-space-3);
  padding: 0 var(--ys-space-5) var(--ys-space-5);
}

.stage {
  display: grid;
  gap: 2px;
  padding: var(--ys-space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
  text-align: start;
  cursor: pointer;
  transition: border-color var(--ys-duration-fast) var(--ys-ease-out);
}

.stage:hover {
  border-color: var(--color-primary-border);
}

.stage__value {
  font-size: var(--ys-font-xl);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

/* 加载中的骨架条：与数字同高，避免数据回来时整行跳动 */
.stage__skeleton {
  display: inline-block;
  width: 2.2em;
  height: 1em;
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-sunken);
  animation: stage-skeleton-pulse 1.2s ease-in-out infinite;
}

.stage--loading {
  cursor: progress;
}

@keyframes stage-skeleton-pulse {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 1;
  }
}

/* 部分统计失败时的提示条。用 warning 语义色而不是 danger：
   四格里有几格没回来，其余仍然可用，整页并没有坏掉 */
.stage-alert {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-3);
  margin-block-end: var(--ys-space-3);
  padding: var(--ys-space-3) var(--ys-space-4);
  border: 1px solid var(--color-warning-strong);
  border-radius: var(--ys-radius-md);
  background: var(--color-warning-subtle);
  color: var(--color-warning-strong);
  font-size: var(--ys-font-sm);
}

.stage-alert__retry {
  flex: none;
  padding: var(--ys-space-1) var(--ys-space-3);
  border: 1px solid currentColor;
  border-radius: var(--ys-radius-sm);
  background: transparent;
  color: inherit;
  font-size: inherit;
  cursor: pointer;
}

.stage-alert__retry:hover {
  background: color-mix(in srgb, currentColor 12%, transparent);
}

.stage-alert__retry:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

/* 动效降级：脉动只是「正在加载」的修饰，关掉它不影响可读性 */
@media (prefers-reduced-motion: reduce) {
  .stage__skeleton {
    animation: none;
    opacity: 0.8;
  }
}

.stage__label {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.link-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--ys-space-3);
  padding: 0 var(--ys-space-5) var(--ys-space-5);
}

.link-grid__item {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  min-height: 44px;
  padding: 0 var(--ys-space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface);
  color: var(--color-text-primary);
  font-size: var(--ys-font-base);
  cursor: pointer;
  transition:
    border-color var(--ys-duration-fast) var(--ys-ease-out),
    color var(--ys-duration-fast) var(--ys-ease-out);
}

.link-grid__item:hover {
  border-color: var(--color-primary);
  color: var(--color-primary-strong);
}
</style>
