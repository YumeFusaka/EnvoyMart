<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelOrder, listOrders } from '@/api/order'
import { formatPrice } from '@/api/product'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { Order, OrderStatus } from '@/types/models'

const router = useRouter()

const orders = ref<Order[]>([])
const loading = ref(false)
const failed = ref(false)
const activeStatus = ref<OrderStatus | 'ALL'>('ALL')

/**
 * 当前时间。`payable()` 与倒计时都依赖它：订单超时那一刻界面上要有变化，
 * 「去支付」不能一直亮着。每秒推进一次只为倒计时的秒级跳动，纯本地时间，不轮询服务端。
 */
const now = ref(Date.now())

let timer: ReturnType<typeof setInterval> | undefined

/**
 * 状态分组。用「分组」而不是枚举出全部 9 个状态当筛选项：
 * 用户想找的是「待付款的」「在路上的」，不是 REFUNDING 与 REFUNDED 的区别。
 */
const STATUS_TABS: { value: OrderStatus | 'ALL'; label: string; match: OrderStatus[] }[] = [
  { value: 'ALL', label: '全部', match: [] },
  { value: 'CREATED', label: '待付款', match: ['CREATED'] },
  { value: 'PAID', label: '待发货', match: ['PAID'] },
  { value: 'SHIPPED', label: '待收货', match: ['SHIPPED'] },
  { value: 'RECEIVED', label: '已完成', match: ['RECEIVED', 'COMPLETED'] },
  { value: 'CANCELLED', label: '退款/取消', match: ['CANCELLED', 'CLOSED', 'REFUNDING', 'REFUNDED'] },
]

const filtered = computed(() => {
  const tab = STATUS_TABS.find((item) => item.value === activeStatus.value)
  if (!tab || tab.match.length === 0) {
    return orders.value
  }
  return orders.value.filter((order) => tab.match.includes(order.status))
})

function countOf(tab: (typeof STATUS_TABS)[number]): number {
  if (tab.match.length === 0) {
    return orders.value.length
  }
  return orders.value.filter((order) => tab.match.includes(order.status)).length
}

/** 未支付且未超时 —— 只有这种订单还能付款 */
function payable(order: Order): boolean {
  return (
    order.status === 'CREATED' && (!order.expireAt || new Date(order.expireAt).getTime() > now.value)
  )
}

/** 待付款与待发货都可取消：已支付的取消会同步发起全额退款 */
function cancellable(order: Order): boolean {
  return order.status === 'CREATED' || order.status === 'PAID'
}

/**
 * 退款中 = 上次退款没走完（失败或响应丢了）。后端对 REFUNDING 的取消就是重试入口，
 * 且退款请求带幂等键，重试不会退第二笔；界面必须把它露出来，否则用户只能干等。
 */
function refundRetryable(order: Order): boolean {
  return order.status === 'REFUNDING'
}

/** 待付款订单的支付剩余时间，形如 `12:34`；无期限或已超时返回空串 */
function countdown(order: Order): string {
  if (!order.expireAt) {
    return ''
  }
  const ms = new Date(order.expireAt).getTime() - now.value
  if (ms <= 0) {
    return ''
  }
  const seconds = Math.floor(ms / 1000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

/** 剩余不足 5 分钟标红：给用户一个「该付了」的视觉信号 */
function expiringSoon(order: Order): boolean {
  return !!order.expireAt && new Date(order.expireAt).getTime() - now.value < 5 * 60_000
}

async function load() {
  loading.value = true
  failed.value = false
  try {
    orders.value = await listOrders()
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

/** 取消/退款重试的文案：三种入口（未支付、已支付、退款重试）说三种话 */
function cancelCopy(order: Order) {
  if (order.status === 'REFUNDING') {
    return {
      title: '重试退款',
      confirm: `订单 ${order.orderNo} 的退款还未完成，将重新发起全额退款。确定重试吗？`,
      confirmButton: '重试退款',
    }
  }
  if (order.status === 'PAID') {
    return {
      title: '取消并退款',
      confirm: `订单 ${order.orderNo} 已支付，取消后将发起全额退款，款项原路退回。确定取消吗？`,
      confirmButton: '取消并退款',
    }
  }
  return {
    title: '取消订单',
    confirm: `确定取消订单 ${order.orderNo} 吗？`,
    confirmButton: '确认取消',
  }
}

async function handleCancel(order: Order) {
  const retry = order.status === 'REFUNDING'
  const paid = order.status === 'PAID'
  const copy = cancelCopy(order)
  try {
    await ElMessageBox.confirm(copy.confirm, copy.title, {
      type: 'warning',
      confirmButtonText: copy.confirmButton,
      cancelButtonText: '再想想',
    })
  } catch {
    return
  }
  const updated = await cancelOrder(order.id)
  if (retry) {
    // 重试的结果以订单最终状态为准：可能这次成功了，也可能仍停在处理中
    ElMessage.success(updated.status === 'REFUNDED' ? '退款已完成，款项原路退回' : '退款受理中，请稍后查看')
  } else {
    ElMessage.success(paid ? '订单已取消，退款将原路退回' : '订单已取消，库存已回补')
  }
  await load()
}

onMounted(() => {
  load()
  // 每秒推进「当前时间」：待付款倒计时需要秒级跳动，超时后「去支付」随之自动消失。
  // 不轮询订单本身：关单是服务端的事，界面只负责把过期的操作收起来
  timer = setInterval(() => {
    now.value = Date.now()
  }, 1_000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
  }
})
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Orders</p>
        <h1>我的订单</h1>
      </div>
    </header>

    <nav class="tabs" aria-label="订单状态筛选">
      <button
        v-for="tab in STATUS_TABS"
        :key="tab.value"
        type="button"
        class="tabs__item"
        :class="{ 'is-active': activeStatus === tab.value }"
        @click="activeStatus = tab.value"
      >
        {{ tab.label }}
        <span v-if="countOf(tab) > 0" class="tabs__count">{{ countOf(tab) }}</span>
      </button>
    </nav>

    <div v-loading="loading" class="orders">
      <ErrorState v-if="failed" message="订单加载失败" :on-retry="load" />

      <el-empty v-else-if="!loading && filtered.length === 0" description="没有相关订单">
        <el-button type="primary" @click="router.push('/shop')">去逛逛</el-button>
      </el-empty>

      <article v-for="order in filtered" :key="order.id" class="surface order">
        <header class="order__head">
          <div class="order__meta">
            <span class="order__no">{{ order.orderNo }}</span>
            <span class="order__time">{{ order.createdAt?.replace('T', ' ') }}</span>
            <span
              v-if="payable(order) && countdown(order)"
              class="order__countdown"
              :class="{ 'is-urgent': expiringSoon(order) }"
            >
              支付剩余 {{ countdown(order) }}
            </span>
          </div>
          <el-tag :type="payable(order) ? 'warning' : 'info'" effect="light">
            {{ order.statusText }}
          </el-tag>
        </header>

        <ul class="order__items">
          <li
            v-for="item in order.items"
            :key="item.id"
            class="order__item"
            @click="router.push(`/products/${item.spuId}`)"
          >
            <img v-if="item.skuImage" :src="item.skuImage" :alt="item.spuName" />
            <div class="order__item-info">
              <p class="order__item-name">{{ item.spuName }}</p>
              <p v-if="item.skuSpecText" class="order__item-spec">{{ item.skuSpecText }}</p>
            </div>
            <span class="order__item-qty">× {{ item.quantity }}</span>
          </li>
        </ul>

        <footer class="order__foot">
          <span class="order__amount">
            实付 <strong>{{ formatPrice(order.payAmount) }}</strong>
          </span>
          <div class="order__actions">
            <el-button link @click="router.push(`/orders/${order.id}`)">订单详情</el-button>
            <el-button v-if="cancellable(order)" link type="danger" @click="handleCancel(order)">
              {{ order.status === 'PAID' ? '取消并退款' : '取消订单' }}
            </el-button>
            <el-button v-if="refundRetryable(order)" link type="danger" @click="handleCancel(order)">
              重试退款
            </el-button>
            <el-button v-if="payable(order)" type="primary" @click="router.push(`/payment?orderId=${order.id}`)">
              去支付
            </el-button>
          </div>
        </footer>
      </article>
    </div>
  </div>
</template>

<style scoped>
.tabs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
}

.tabs__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-surface);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  cursor: pointer;
  transition: all var(--ys-duration-fast) var(--ys-ease-out);
}

.tabs__item.is-active {
  border-color: var(--color-primary);
  background: var(--color-primary-subtle);
  color: var(--color-primary);
  font-weight: 600;
}

.tabs__count {
  min-width: 18px;
  padding: 0 5px;
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-sunken);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  text-align: center;
}

.orders {
  display: grid;
  gap: var(--ys-space-4);
  min-height: 200px;
}

.order {
  display: grid;
  gap: var(--ys-space-3);
}

.order__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-3);
  padding-bottom: var(--ys-space-3);
  border-bottom: 1px solid var(--color-border);
}

.order__meta {
  display: flex;
  align-items: baseline;
  gap: var(--ys-space-3);
  flex-wrap: wrap;
}

.order__no {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.order__time {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.order__countdown {
  color: var(--color-warning);
  font-size: var(--ys-font-xs);
  font-variant-numeric: tabular-nums;
}

.order__countdown.is-urgent {
  color: var(--color-danger);
  font-weight: 600;
}

.order__items {
  display: grid;
  gap: var(--ys-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.order__item {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr) auto;
  gap: var(--ys-space-3);
  align-items: center;
  cursor: pointer;
}

.order__item img {
  width: 48px;
  height: 48px;
  border-radius: var(--ys-radius-sm);
  object-fit: cover;
  background: var(--color-bg-surface-muted);
}

.order__item-name {
  font-size: var(--ys-font-sm);
  font-weight: 600;
}

.order__item-spec,
.order__item-qty {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.order__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-3);
  padding-top: var(--ys-space-3);
  border-top: 1px solid var(--color-border);
}

.order__amount strong {
  color: var(--color-primary);
  font-size: var(--ys-font-md);
}

.order__actions {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
}

@media (max-width: 720px) {
  .order__foot {
    flex-direction: column;
    align-items: stretch;
  }

  .order__actions {
    justify-content: flex-end;
  }
}
</style>
