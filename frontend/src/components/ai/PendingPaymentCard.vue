<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { formatPrice } from '@/api/product'
import type { PendingPayment } from '@/types/models'

const props = defineProps<{
  payments: PendingPayment[]
  active: boolean
}>()

const router = useRouter()
const payments = computed(() => Array.isArray(props.payments)
  ? props.payments.filter(Boolean).map((payment) => ({
    ...payment,
    items: Array.isArray(payment.items) ? payment.items.filter(Boolean) : [],
  })) : [])
const now = ref(Date.now())
const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
})
let expiryTimer: ReturnType<typeof setTimeout> | undefined

function expireTime(payment: PendingPayment): number | undefined {
  const timestamp = payment.expireAt ? Date.parse(payment.expireAt) : NaN
  return Number.isFinite(timestamp) ? timestamp : undefined
}

function expired(payment: PendingPayment): boolean {
  const timestamp = expireTime(payment)
  return timestamp !== undefined && timestamp <= now.value
}

function expiryLabel(payment: PendingPayment): string {
  const timestamp = expireTime(payment)
  return timestamp === undefined ? '' : dateFormatter.format(timestamp)
}

function scheduleExpiry(): void {
  clearTimeout(expiryTimer)
  now.value = Date.now()
  const next = Math.min(...payments.value
    .map(expireTime)
    .filter((timestamp): timestamp is number => timestamp !== undefined && timestamp > now.value))
  if (Number.isFinite(next)) {
    expiryTimer = setTimeout(scheduleExpiry, Math.min(next - now.value + 1, 2147483647))
  }
}

watch(() => payments.value.map((payment) => payment.expireAt), scheduleExpiry, { immediate: true })
onUnmounted(() => clearTimeout(expiryTimer))

function amount(value: unknown): string {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
    ? formatPrice(value) : '未提供'
}

function quantity(value: unknown): string {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
    ? String(value) : '未提供'
}

function text(value: unknown): string {
  const result = typeof value === 'string' ? value.trim() : ''
  return result === '[object Object]' ? '' : result
}

function specification(value: unknown): string {
  if (value === null || value === undefined || (typeof value === 'string' && !value.trim())) {
    return '单一规格'
  }
  return text(value).replace(/^规格[:：]\s*/, '') || '规格信息未提供'
}

function hasOrderId(payment: PendingPayment): boolean {
  return Number.isSafeInteger(payment.orderId) && payment.orderId > 0
}

function pay(payment: PendingPayment): void {
  now.value = Date.now()
  if (!props.active || expired(payment) || !hasOrderId(payment)) {
    return
  }
  router.push({ name: 'payment', query: { orderId: String(payment.orderId) } })
}
</script>

<template>
  <section class="payment" :class="{ 'payment--stale': !active }" aria-label="订单支付信息">
    <p class="payment__title">{{ active ? '请核对订单后支付' : '订单支付记录' }}</p>

    <ul v-if="payments.length" class="payment__list">
      <li v-for="payment in payments" :key="payment.orderId" class="payment__order">
        <p class="payment__no">单号 {{ text(payment.orderNo) || '未提供' }}</p>
        <ul v-if="payment.items.length" class="payment__items">
          <li v-for="(item, index) in payment.items" :key="item.id ?? index" class="payment__item">
            <p class="payment__product">{{ text(item.spuName) || '商品名称未提供' }}</p>
            <p class="payment__spec">规格：{{ specification(item.skuSpecText) }}</p>
            <dl class="payment__item-amounts">
              <div><dt>数量</dt><dd>{{ quantity(item.quantity) }}</dd></div>
              <div><dt>单价</dt><dd>{{ amount(item.unitPrice) }}</dd></div>
              <div><dt>小计</dt><dd>{{ amount(item.subtotal) }}</dd></div>
            </dl>
          </li>
        </ul>
        <p v-else class="payment__hint">此记录未保存商品明细，可查看订单详情核对。</p>
        <div class="payment__summary">
          <span>订单应付总额</span>
          <strong class="payment__amount">{{ amount(payment.payAmount) }}</strong>
        </div>
        <p v-if="expiryLabel(payment)" class="payment__hint">
          支付截止：{{ expiryLabel(payment) }}
          <span v-if="expired(payment)">。截止时间已过，请查看订单当前状态。</span>
        </p>
        <div class="payment__buttons">
          <RouterLink
            v-if="hasOrderId(payment)"
            class="payment__detail"
            :to="{ name: 'order-detail', params: { id: String(payment.orderId) } }"
            :aria-label="`查看订单 ${text(payment.orderNo)} 的详情`"
          >查看订单详情</RouterLink>
          <el-button
            type="primary"
            size="small"
            :disabled="!active || expired(payment) || !hasOrderId(payment)"
            :aria-label="`去支付订单 ${text(payment.orderNo)}`"
            @click="pay(payment)"
          >{{ expired(payment) ? '支付截止已过' : '去支付' }}</el-button>
        </div>
      </li>
    </ul>
    <p v-else class="payment__hint">暂无订单信息。</p>

    <p v-if="!active" class="payment__stale-note">
      这是历史支付记录，订单可能已支付或关闭。请查看订单详情确认当前状态；此卡片不能重复发起支付。
    </p>
  </section>
</template>

<style scoped>
/*
  支付卡用主色导轨而不是危险色：它要的是「继续往下走」，不是「停下来看清」。
  与高危确认卡共用同一套结构（导轨 + 列表 + 按钮），让两种卡片一眼能区分轻重。
*/
.payment {
  margin-top: var(--ys-space-3);
  padding: var(--ys-space-4) var(--ys-space-4) var(--ys-space-4) var(--ys-space-5);
  border: 1px solid var(--color-primary-border);
  border-left: 3px solid var(--color-primary);
  border-radius: var(--ys-radius-md);
  background: var(--color-primary-subtle);
}

.payment--stale {
  border-color: var(--color-border);
  border-left-color: var(--color-border-strong);
  background: var(--color-bg-surface-muted);
}

.payment__title {
  margin: 0;
  font-size: var(--ys-font-sm);
  font-weight: 600;
  color: var(--color-text-primary);
}

.payment__list {
  margin: var(--ys-space-3) 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: var(--ys-space-2);
}

.payment__order {
  min-width: 0;
  padding: var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface);
}

.payment--stale .payment__order {
  color: var(--color-text-secondary);
}

.payment__no {
  margin: 0;
  font-family: var(--ys-font-mono);
  font-size: var(--ys-font-xs);
  color: var(--color-text-secondary);
  overflow-wrap: anywhere;
}

.payment__items {
  display: grid;
  gap: var(--ys-space-3);
  margin: var(--ys-space-3) 0;
  padding: 0;
  list-style: none;
}

.payment__item {
  min-width: 0;
}

.payment__item + .payment__item {
  padding-top: var(--ys-space-3);
  border-top: 1px solid var(--color-border);
}

.payment__product {
  margin: 0;
  font-size: var(--ys-font-sm);
  font-weight: 600;
  color: var(--color-text-primary);
  line-height: var(--ys-leading-base);
  overflow-wrap: anywhere;
}

.payment__spec {
  margin: var(--ys-space-1) 0 0;
  font-size: var(--ys-font-xs);
  color: var(--color-text-secondary);
  line-height: var(--ys-leading-base);
  overflow-wrap: anywhere;
}

.payment__item-amounts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2) var(--ys-space-4);
  margin: var(--ys-space-2) 0 0;
  font-size: var(--ys-font-xs);
}

.payment__item-amounts div {
  display: flex;
  gap: var(--ys-space-2);
}

.payment__item-amounts dt {
  color: var(--color-text-secondary);
}

.payment__item-amounts dd {
  margin: 0;
  color: var(--color-text-primary);
  font-variant-numeric: tabular-nums;
}

.payment__summary {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ys-space-2);
  margin-top: var(--ys-space-3);
  padding-top: var(--ys-space-3);
  border-top: 1px solid var(--color-border);
  font-size: var(--ys-font-xs);
  color: var(--color-text-secondary);
}

.payment__amount {
  flex: none;
  font-size: var(--ys-font-sm);
  font-weight: 600;
  color: var(--color-text-primary);
  font-variant-numeric: tabular-nums;
}

.payment__hint,
.payment__stale-note {
  margin: var(--ys-space-3) 0 0;
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
  color: var(--color-text-muted);
}

.payment__buttons {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-2);
  margin-top: var(--ys-space-4);
}

.payment__detail {
  display: inline-flex;
  align-items: center;
  min-height: var(--ys-space-6);
  font-size: var(--ys-font-xs);
  color: var(--color-primary-strong);
  text-underline-offset: var(--ys-space-1);
}

.payment__detail:hover {
  text-decoration: underline;
}

.payment__detail:focus-visible {
  outline: 2px solid var(--color-border-focus);
  outline-offset: 2px;
  border-radius: var(--ys-radius-sm);
}

@media (prefers-reduced-motion: no-preference) {
  .payment {
    animation: payment-in var(--ys-duration-base) var(--ys-ease-out);
  }

  @keyframes payment-in {
    from {
      opacity: 0;
      transform: translateY(-4px);
    }
  }
}
</style>
