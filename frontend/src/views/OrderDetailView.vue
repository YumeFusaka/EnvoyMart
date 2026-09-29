<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelOrder, confirmReceipt, formatAddress, getLogistics, getOrder } from '@/api/order'
import { formatPrice } from '@/api/product'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { Logistics, Order } from '@/types/models'

const route = useRoute()
const router = useRouter()

const order = ref<Order | null>(null)
const logistics = ref<Logistics | null>(null)
const loading = ref(false)
const failed = ref(false)
const receiving = ref(false)

const orderId = computed(() => {
  const raw = Number(route.params.id)
  // 手改地址栏或旧书签会传进非数字，那样会去请求 /orders/NaN
  return Number.isFinite(raw) && raw > 0 ? raw : Number.NaN
})

/** 订单时间轴。只展示实际发生过的节点，未发生的不占位 */
const timeline = computed(() => {
  const o = order.value
  if (!o) {
    return []
  }
  const nodes = [
    { label: '提交订单', time: o.createdAt },
    { label: '支付成功', time: o.paidAt },
    { label: '商家发货', time: o.shippedAt },
    { label: '确认收货', time: o.receivedAt },
    { label: '订单关闭', time: o.closedAt },
  ]
  return nodes.filter((node) => !!node.time)
})

const payable = computed(
  () =>
    order.value?.status === 'CREATED' &&
    (!order.value.expireAt || new Date(order.value.expireAt) > new Date()),
)

const cancellable = computed(() => order.value?.status === 'CREATED')

async function load() {
  if (Number.isNaN(orderId.value)) {
    ElMessage.error('订单不存在')
    router.replace('/orders')
    return
  }
  loading.value = true
  failed.value = false
  try {
    order.value = await getOrder(orderId.value)
    // 物流只有已发货之后才有意义；未发货时后端返回空轨迹
    if (order.value.shippedAt) {
      logistics.value = await getLogistics(orderId.value).catch(() => null)
    } else {
      logistics.value = null
    }
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

/** 确认收货。它是售后与评价的前置条件，所以在详情页给一个明确入口 */
async function handleReceive() {
  if (!order.value) {
    return
  }
  try {
    await ElMessageBox.confirm('确认已经收到货吗？确认后可以申请售后与评价。', '确认收货', {
      type: 'info',
      confirmButtonText: '确认收货',
      cancelButtonText: '还没收到',
    })
  } catch {
    return
  }
  receiving.value = true
  try {
    await confirmReceipt(order.value.id)
    ElMessage.success('已确认收货')
    await load()
  } finally {
    receiving.value = false
  }
}

async function handleCancel() {
  const o = order.value
  if (!o) {
    return
  }
  try {
    await ElMessageBox.confirm('取消后库存会立即回补，确定取消吗？', '取消订单', {
      type: 'warning',
      confirmButtonText: '确认取消',
      cancelButtonText: '再想想',
    })
  } catch {
    return
  }
  await cancelOrder(o.id)
  ElMessage.success('订单已取消')
  await load()
}

watch(orderId, load)
onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page">
    <ErrorState v-if="failed" message="订单加载失败" :on-retry="load" />

    <template v-else-if="order">
      <nav class="crumb">
        <el-button link @click="router.push('/orders')">← 返回订单列表</el-button>
      </nav>

      <section class="surface head-card">
        <div>
          <p class="eyebrow">Order</p>
          <h1 class="head-card__no">{{ order.orderNo }}</h1>
        </div>
        <el-tag size="large" effect="light">{{ order.statusText }}</el-tag>
      </section>

      <section v-if="timeline.length" class="surface">
        <h2 class="section-title">订单进度</h2>
        <el-timeline>
          <el-timeline-item
            v-for="node in timeline"
            :key="node.label"
            :timestamp="node.time?.replace('T', ' ')"
            placement="top"
          >
            {{ node.label }}
          </el-timeline-item>
        </el-timeline>
      </section>

      <section v-if="logistics?.steps?.length" class="surface">
        <h2 class="section-title">
          物流轨迹
          <span class="section-hint">承运商：{{ logistics.carrier }} · 运单号 {{ logistics.trackingNo }}</span>
        </h2>
        <el-timeline>
          <el-timeline-item
            v-for="(step, index) in logistics.steps"
            :key="index"
            :timestamp="step.time?.replace('T', ' ')"
            placement="top"
          >
            <strong>{{ step.status }}</strong>
            <p class="trace-detail">{{ step.detail }}</p>
          </el-timeline-item>
        </el-timeline>
      </section>

      <section class="surface">
        <h2 class="section-title">收货信息</h2>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="收货人">
            {{ order.receiverName }} {{ order.receiverPhone }}
          </el-descriptions-item>
          <el-descriptions-item label="地址">{{ formatAddress(order) }}</el-descriptions-item>
          <el-descriptions-item v-if="order.remark" label="备注">{{ order.remark }}</el-descriptions-item>
          <el-descriptions-item v-if="order.cancelReason" label="关闭原因">
            {{ order.cancelReason }}
          </el-descriptions-item>
        </el-descriptions>
      </section>

      <section class="surface">
        <h2 class="section-title">商品清单</h2>
        <ul class="goods">
          <li v-for="item in order.items" :key="item.id" class="goods__item">
            <img v-if="item.skuImage" :src="item.skuImage" :alt="item.spuName" />
            <div class="goods__info">
              <button type="button" class="goods__name" @click="router.push(`/products/${item.spuId}`)">
                {{ item.spuName }}
              </button>
              <p v-if="item.skuSpecText" class="goods__spec">{{ item.skuSpecText }}</p>
            </div>
            <span class="goods__price">{{ formatPrice(item.unitPrice) }} × {{ item.quantity }}</span>
            <span class="goods__sum">{{ formatPrice(item.subtotal) }}</span>
          </li>
        </ul>

        <dl class="summary">
          <div><dt>商品金额</dt><dd>{{ formatPrice(order.totalAmount) }}</dd></div>
          <div>
            <dt>运费</dt>
            <dd>{{ order.freightAmount === 0 ? '包邮' : formatPrice(order.freightAmount) }}</dd>
          </div>
          <div v-if="order.discountAmount > 0">
            <dt>优惠</dt><dd>-{{ formatPrice(order.discountAmount) }}</dd>
          </div>
          <div class="summary__total"><dt>实付</dt><dd>{{ formatPrice(order.payAmount) }}</dd></div>
        </dl>
      </section>

      <footer class="surface bar">
        <span class="bar__hint">
          {{
            cancellable
              ? '订单尚未支付，可直接取消；取消后库存立即回补'
              : order.status === 'PAID'
                ? '订单已支付，申请退款请联系客服'
                : ''
          }}
        </span>
        <div class="bar__actions">
          <el-button v-if="cancellable" @click="handleCancel">取消订单</el-button>
          <el-button v-if="order.status === 'SHIPPED'" type="primary" :loading="receiving" @click="handleReceive">
            确认收货
          </el-button>
          <el-button v-if="payable" type="primary" @click="router.push(`/payment?orderId=${order.id}`)">
            去支付
          </el-button>
        </div>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.crumb {
  display: flex;
}

.head-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-4);
}

.head-card__no {
  font-size: var(--ys-font-lg);
  font-variant-numeric: tabular-nums;
}

.section-title {
  margin-bottom: var(--ys-space-4);
  font-size: var(--ys-font-md);
}

.section-hint {
  margin-left: var(--ys-space-3);
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  font-weight: 400;
}

.trace-detail {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.goods {
  display: grid;
  gap: var(--ys-space-3);
  margin: 0 0 var(--ys-space-4);
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
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-text-primary);
  font-weight: 600;
  text-align: left;
  cursor: pointer;
}

.goods__name:hover {
  color: var(--color-primary);
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

.summary {
  display: flex;
  justify-content: flex-end;
  gap: var(--ys-space-6);
  margin: 0;
  padding-top: var(--ys-space-3);
  border-top: 1px solid var(--color-border);
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
  font-size: var(--ys-font-md);
  font-weight: 700;
}

.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-4);
}

.bar__hint {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.bar__actions {
  display: flex;
  gap: var(--ys-space-2);
}

@media (max-width: 720px) {
  .bar,
  .head-card {
    flex-direction: column;
    align-items: stretch;
  }

  .summary {
    flex-direction: column;
    align-items: flex-end;
    gap: var(--ys-space-1);
  }

  .goods__item {
    grid-template-columns: 56px minmax(0, 1fr);
  }
}
</style>
