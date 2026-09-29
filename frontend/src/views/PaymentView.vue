<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { formatPrice } from '@/api/product'
import { getOrder } from '@/api/order'
import { createPayment, mockPay, PAY_CHANNELS } from '@/api/payment'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { Order } from '@/types/models'

const route = useRoute()
const router = useRouter()

const order = ref<Order | null>(null)
const channel = ref<string>('MOCK')
const paying = ref(false)
const countdown = ref('')

/**
 * 当前时间。
 * <p>
 * `expired` 必须依赖一个**响应式**的时间源：直接写 `Date.now()` 的话它只依赖
 * `order`，订单加载完就再也不重算 —— 倒计时文字已经显示「已超时」，
 * 支付按钮却仍然可点，点下去才被后端拒绝。
 */
const now = ref(Date.now())

const orderId = computed(() => Number(route.query.orderId))

const expired = computed(() => {
  if (!order.value?.expireAt) {
    return false
  }
  return new Date(order.value.expireAt).getTime() <= now.value
})

let timer: ReturnType<typeof setInterval> | undefined

/** 倒计时。支付截止时间来自订单，前端只负责显示 */
function tick() {
  now.value = Date.now()
  const expireAt = order.value?.expireAt
  if (!expireAt) {
    countdown.value = ''
    return
  }
  const left = new Date(expireAt).getTime() - now.value
  if (left <= 0) {
    countdown.value = '已超时'
    return
  }
  const minutes = Math.floor(left / 60000)
  const seconds = Math.floor((left % 60000) / 1000)
  countdown.value = `${minutes} 分 ${String(seconds).padStart(2, '0')} 秒`
}

/**
 * 等订单真的变成已支付再跳转。
 * <p>
 * 支付成功的 MQ 事件由 order-service 异步消费，`mockPay` 返回时订单状态还没变。
 * 直接跳过去会看到详情页仍显示「待付款」并提供「去支付」，更糟的是「取消订单」
 * 也还亮着 —— 而在那个窗口里取消，后端会收到「已取消订单的支付成功事件」，
 * 那正是它刻意防的路径。
 */
async function waitForPaid(id: number) {
  for (let i = 0; i < 20; i++) {
    const latest = await getOrder(id).catch(() => null)
    if (latest && latest.status !== 'CREATED') {
      return
    }
    await new Promise((resolve) => setTimeout(resolve, 300))
  }
  // 等不到也放行：支付本身已经成功，跳过去看到「待付款」只是慢了一拍，
  // 而卡在这里会让用户以为支付失败
}

async function pay() {
  if (!order.value) {
    return
  }
  if (expired.value) {
    ElMessage.warning('订单已超时，请重新下单')
    return
  }
  paying.value = true
  try {
    // 建支付单（已存在会复用），再让后端扮演渠道发一次回调
    await createPayment({ orderId: order.value.id, channel: channel.value })
    await mockPay(order.value.id)
    await waitForPaid(order.value.id)
    ElMessage.success('支付成功')
    router.push(`/orders/${order.value.id}`)
  } finally {
    paying.value = false
  }
}

const loadFailed = ref(false)

async function load() {
  loadFailed.value = false
  try {
    order.value = await getOrder(orderId.value)
    tick()
  } catch {
    loadFailed.value = true
  }
}

onMounted(async () => {
  if (!orderId.value || Number.isNaN(orderId.value)) {
    ElMessage.error('缺少订单信息')
    router.replace('/orders')
    return
  }
  await load()
  timer = setInterval(tick, 1000)
})

onUnmounted(() => {
  // 页面离开必须清掉定时器：它每秒触发一次响应式更新，
  // 留着会在后台一直跑，而且引用的组件已经卸载
  if (timer) {
    clearInterval(timer)
  }
})
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Payment</p>
        <h1>收银台</h1>
      </div>
      <p v-if="countdown" class="subcopy">
        支付剩余时间：<strong :class="{ 'is-expired': expired }">{{ countdown }}</strong>
      </p>
    </header>

    <ErrorState v-if="loadFailed" message="订单信息加载失败" :on-retry="load" />

    <template v-else-if="order">
      <section class="surface">
        <h2 class="section-title">订单信息</h2>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="订单号">{{ order.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="订单状态">{{ order.statusText }}</el-descriptions-item>
          <el-descriptions-item label="收货人">
            {{ order.receiverName }} {{ order.receiverPhone }}
          </el-descriptions-item>
          <el-descriptions-item label="收货地址">
            {{ order.receiverProvince }} {{ order.receiverCity }}
            {{ order.receiverDistrict }} {{ order.receiverDetail }}
          </el-descriptions-item>
        </el-descriptions>
      </section>

      <section class="surface">
        <h2 class="section-title">商品清单</h2>
        <ul class="goods">
          <li v-for="item in order.items" :key="item.id" class="goods__item">
            <img v-if="item.skuImage" :src="item.skuImage" :alt="item.spuName" />
            <div class="goods__info">
              <p class="goods__name">{{ item.spuName }}</p>
              <p v-if="item.skuSpecText" class="goods__spec">{{ item.skuSpecText }}</p>
            </div>
            <span class="goods__price">{{ formatPrice(item.unitPrice) }} × {{ item.quantity }}</span>
            <span class="goods__sum">{{ formatPrice(item.subtotal) }}</span>
          </li>
        </ul>
      </section>

      <section class="surface">
        <h2 class="section-title">支付方式</h2>
        <div class="channels">
          <button
            v-for="item in PAY_CHANNELS"
            :key="item.value"
            type="button"
            class="channel"
            :class="{ 'is-active': channel === item.value }"
            @click="channel = item.value"
          >
            <span class="channel__name">{{ item.label }}</span>
            <span class="channel__hint">{{ item.hint }}</span>
          </button>
        </div>
      </section>

      <footer class="surface bar">
        <dl class="summary">
          <div><dt>商品金额</dt><dd>{{ formatPrice(order.totalAmount) }}</dd></div>
          <div>
            <dt>运费</dt>
            <dd>{{ order.freightAmount === 0 ? '包邮' : formatPrice(order.freightAmount) }}</dd>
          </div>
          <div v-if="order.discountAmount > 0">
            <dt>优惠</dt>
            <dd>-{{ formatPrice(order.discountAmount) }}</dd>
          </div>
          <div class="summary__total"><dt>应付</dt><dd>{{ formatPrice(order.payAmount) }}</dd></div>
        </dl>
        <el-button
          type="primary"
          size="large"
          :loading="paying"
          :disabled="expired || order.status !== 'CREATED'"
          @click="pay"
        >
          {{ order.status === 'CREATED' ? (expired ? '订单已超时' : '确认支付') : '该订单无需支付' }}
        </el-button>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.section-title {
  margin-bottom: var(--ys-space-4);
  font-size: var(--ys-font-md);
}

.goods {
  display: grid;
  gap: var(--ys-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.goods__item {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr) 140px 100px;
  gap: var(--ys-space-3);
  align-items: center;
}

.goods__item img {
  width: 56px;
  height: 56px;
  border-radius: var(--ys-radius-sm);
  object-fit: cover;
  background: var(--color-bg-surface-muted);
}

.goods__name {
  font-weight: 600;
}

.goods__spec,
.goods__price {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.goods__sum {
  color: var(--color-primary);
  font-weight: 600;
  text-align: right;
}

.channels {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: var(--ys-space-3);
}

.channel {
  display: grid;
  gap: 2px;
  padding: var(--ys-space-3) var(--ys-space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--ys-duration-fast) var(--ys-ease-out);
}

.channel.is-active {
  border-color: var(--color-primary);
  background: var(--color-primary-subtle);
}

.channel__name {
  font-weight: 600;
}

.channel__hint {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-6);
}

.summary {
  display: flex;
  gap: var(--ys-space-6);
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.summary div {
  display: flex;
  gap: var(--ys-space-2);
}

.summary dt,
.summary dd {
  margin: 0;
}

.summary__total dd {
  color: var(--color-primary);
  font-size: var(--ys-font-xl);
  font-weight: 700;
}

.is-expired {
  color: var(--color-danger);
}

@media (max-width: 720px) {
  .bar {
    flex-direction: column;
    align-items: stretch;
  }

  .summary {
    flex-direction: column;
    gap: var(--ys-space-1);
  }

  .goods__item {
    grid-template-columns: 56px minmax(0, 1fr);
  }
}
</style>
