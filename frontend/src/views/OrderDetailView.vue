<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { LocationInformation } from '@element-plus/icons-vue'
import { cancelOrder, confirmReceipt, formatAddress, getLogistics, getOrder } from '@/api/order'
import { formatPrice } from '@/api/product'
import { listMyReviews } from '@/api/review'
import ErrorState from '@/components/ui/ErrorState.vue'
import AfterSaleDialog from '@/components/order/AfterSaleDialog.vue'
import ReviewDialog from '@/components/order/ReviewDialog.vue'
import CreateTicketDialog from '@/components/ticket/CreateTicketDialog.vue'
import type { Logistics, Order, OrderItem } from '@/types/models'

const route = useRoute()
const router = useRouter()

const order = ref<Order | null>(null)
const logistics = ref<Logistics | null>(null)
const loading = ref(false)
const failed = ref(false)
const receiving = ref(false)

/** 售后与评价都针对**订单行**，所以要记住当前操作的是哪一行 */
const activeItem = ref<OrderItem | null>(null)
const afterSaleOpen = ref(false)
const reviewOpen = ref(false)
/** 「联系客服」入口。发起时带上这一单，客服打开工单就能看到订单号 */
const ticketOpen = ref(false)

/**
 * 这一单里已经评过的订单行 → 打的分。
 *
 * 按订单行而不是整单记：一单三件商品，用户可能只评了其中一件，
 * 按整单标「已评价」会让另外两件再也点不开评价入口。
 */
const reviewedRatings = ref(new Map<number, number>())

async function loadReviewed() {
  try {
    const result = await listMyReviews({ orderId: orderId.value, size: 50 })
    reviewedRatings.value = new Map(result.records.map((r) => [r.orderItemId, r.rating]))
  } catch {
    // 拉不到就当作「都没评过」：最坏的结果是用户点进评价框、
    // 提交时被服务端的唯一约束挡住，而不是整页打不开
    reviewedRatings.value = new Map()
  }
}

/** 已收货之后才能申请售后或评价 */
const afterSaleEligible = computed(
  () => order.value?.status === 'RECEIVED' || order.value?.status === 'COMPLETED',
)

function openAfterSale(item: OrderItem) {
  activeItem.value = item
  afterSaleOpen.value = true
}

function openReview(item: OrderItem) {
  activeItem.value = item
  reviewOpen.value = true
}

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
    // closedAt 有两个来源：超时/主动取消关闭，或退款完成；对退款订单说「关闭」是误导
    { label: o.status === 'REFUNDED' ? '退款完成' : '订单关闭', time: o.closedAt },
  ]
  return nodes.filter((node) => !!node.time)
})

const payable = computed(
  () =>
    order.value?.status === 'CREATED' &&
    (!order.value.expireAt || new Date(order.value.expireAt) > new Date()),
)

/**
 * 物流轨迹**倒序**，最新一条在最上面。
 *
 * 后端按时间正序返回（那是"轨迹"这件事本来的样子，模型读它也按时间读），
 * 但页面是给人看的：用户点进来问的是「我的包裹现在到哪了」，
 * 正序意味着每次都要先划过三天前的"已揽收"才看得到今天那一条。
 * 顺序是**展示**问题，所以在这一层翻，不动接口。
 */
const traceSteps = computed(() => [...(logistics.value?.steps ?? [])].reverse())

/** 刷新物流：包裹在路上时用户会反复看这一块，不能逼他刷整页 */
const refreshingTrace = ref(false)
async function refreshTrace() {
  refreshingTrace.value = true
  try {
    logistics.value = await getLogistics(orderId.value)
    ElMessage.success('物流信息已更新')
  } catch {
    // 拉取失败保留原来那份轨迹：把它清空等于「刚才看过的信息凭空消失了」，
    // 而用户只会以为是自己点错了
    ElMessage.warning('物流信息暂时取不到，请稍后再试')
  } finally {
    refreshingTrace.value = false
  }
}

/** 底部操作条提示：告诉用户「现在能做什么、为什么」 */
const barHint = computed(() => {
  const status = order.value?.status
  if (status === 'CREATED') {
    return '订单尚未支付，可直接取消；取消后库存立即回补'
  }
  if (status === 'PAID') {
    return '订单已支付，可取消并全额退款；已发货的订单请走售后'
  }
  if (status === 'REFUNDING') {
    return '退款处理中，如长时间未完成可点击「重试退款」'
  }
  if (status === 'SHIPPED') {
    return '已发货，确认收货后可申请售后与评价'
  }
  return ''
})

/**
 * 可取消：待付款与待发货（已支付的取消会同步全额退款）。
 * 退款中也可点：后端对 REFUNDING 的取消就是退款重试入口，幂等键保证不会退第二次。
 */
const cancellable = computed(() => {
  const status = order.value?.status
  return status === 'CREATED' || status === 'PAID' || status === 'REFUNDING'
})

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
    await loadReviewed()
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
  const retry = o.status === 'REFUNDING'
  const paid = o.status === 'PAID'
  const confirmText = retry
    ? '订单退款还未完成，将重新发起全额退款。确定重试吗？'
    : paid
      ? '订单已支付，取消后将发起全额退款，款项原路退回。确定取消吗？'
      : '取消后库存会立即回补，确定取消吗？'
  const title = retry ? '重试退款' : paid ? '取消并退款' : '取消订单'
  try {
    await ElMessageBox.confirm(confirmText, title, {
      type: 'warning',
      confirmButtonText: title,
      cancelButtonText: '再想想',
    })
  } catch {
    return
  }
  const updated = await cancelOrder(o.id)
  if (retry) {
    ElMessage.success(updated.status === 'REFUNDED' ? '退款已完成，款项原路退回' : '退款受理中，请稍后查看')
  } else {
    ElMessage.success(paid ? '订单已取消，退款将原路退回' : '订单已取消')
  }
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

      <section v-if="traceSteps.length" class="surface">
        <h2 class="section-title">
          物流轨迹
          <span class="section-hint">承运商：{{ logistics?.carrier }} · 运单号 {{ logistics?.trackingNo }}</span>
          <el-button
            class="trace-refresh"
            link
            type="primary"
            :loading="refreshingTrace"
            @click="refreshTrace"
            >刷新</el-button
          >
        </h2>
        <ol class="trace">
          <li
            v-for="(step, index) in traceSteps"
            :key="`${step.time}-${index}`"
            class="trace__item"
            :class="{ 'is-latest': index === 0 }"
          >
            <span class="trace__dot" aria-hidden="true"></span>
            <div class="trace__body">
              <p class="trace__head">
                <!-- 用户看得懂的是那句说明，"PICKED_UP" 是给机器认的编码 -->
                <strong>{{ step.detail }}</strong>
                <time class="trace__time">{{ step.time?.replace('T', ' ') }}</time>
              </p>
              <p v-if="step.location" class="trace__where">
                <el-icon><LocationInformation /></el-icon>
                {{ step.location }}
              </p>
            </div>
          </li>
        </ol>
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

            <!-- 售后与评价的入口挂在**订单行**上，不是整单：真实场景里用户常常
                 只退其中一件 -->
            <div v-if="afterSaleEligible" class="goods__actions">
              <el-button link size="small" @click="openAfterSale(item)">申请售后</el-button>
              <!--
                评过的行给状态而不是继续摆一个点进去必然报错的「评价」按钮：
                用户看不出哪里不一样，只会在提交时撞上「该商品已经评价过了」。
              -->
              <span v-if="reviewedRatings.has(item.id)" class="goods__reviewed">
                <el-rate :model-value="reviewedRatings.get(item.id)" disabled size="small" />
                已评价
              </span>
              <el-button v-else link size="small" type="primary" @click="openReview(item)"
                >评价</el-button
              >
            </div>
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
        <span class="bar__hint">{{ barHint }}</span>
        <div class="bar__actions">
          <!-- 售后（退款诉求，要审批动钱）之外的疑问走工单。摆在最左边：
               它是这一排里唯一「不改变订单状态」的动作，不该和取消/收货混在一起找 -->
          <el-button @click="ticketOpen = true">联系客服</el-button>
          <el-button v-if="cancellable" @click="handleCancel">
            {{ order.status === 'REFUNDING' ? '重试退款' : order.status === 'PAID' ? '取消并退款' : '取消订单' }}
          </el-button>
          <el-button v-if="order.status === 'SHIPPED'" type="primary" :loading="receiving" @click="handleReceive">
            确认收货
          </el-button>
          <el-button v-if="payable" type="primary" @click="router.push(`/payment?orderId=${order.id}`)">
            去支付
          </el-button>
        </div>
      </footer>

      <AfterSaleDialog
        v-model="afterSaleOpen"
        :order-id="order.id"
        :item="activeItem"
        @applied="load"
      />
      <ReviewDialog v-model="reviewOpen" :order-id="order.id" :item="activeItem" @submitted="load" />
      <CreateTicketDialog
        v-model="ticketOpen"
        :order-id="order.id"
        :order-no="order.orderNo"
        @created="(id: number) => router.push(`/tickets/${id}`)"
      />
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

/* 刷新放在标题行右端：包裹在路上时用户会反复看这一块 */
.trace-refresh {
  margin-left: var(--ys-space-3);
  font-size: var(--ys-font-xs);
  font-weight: 400;
}

/*
 * 轨迹用竖线 + 圆点自己画，而不是 el-timeline。
 * el-timeline 的节点是"由大到小"的一套固定观感（时间戳在上、节点大小均等），
 * 而物流轨迹要表达的是**一条线上走到哪了**：最新一条必须一眼看出来，
 * 之前的几条要退到背景里去。这个层级差用自定义样式写比覆盖组件样式干净。
 */
.trace {
  display: grid;
  gap: var(--ys-space-4);
  margin: 0;
  padding: 0;
  list-style: none;
}

.trace__item {
  position: relative;
  display: grid;
  grid-template-columns: 14px minmax(0, 1fr);
  gap: var(--ys-space-3);
  padding-inline-start: var(--ys-space-1);
}

/* 竖线画在每一项上、连到下一项：最后一项不画，轨迹才不会拖出一条没有终点的尾巴 */
.trace__item:not(:last-child)::before {
  content: '';
  position: absolute;
  inset-block: 16px calc(-1 * var(--ys-space-4));
  inset-inline-start: 7px;
  width: 2px;
  background: var(--color-border);
}

.trace__dot {
  z-index: 1;
  width: 10px;
  height: 10px;
  margin-block-start: 5px;
  border: 2px solid var(--color-border-strong);
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-surface);
}

.trace__item.is-latest .trace__dot {
  border-color: var(--color-primary);
  background: var(--color-primary);
  box-shadow: 0 0 0 4px var(--color-primary-subtle);
}

.trace__item:not(.is-latest) .trace__head strong {
  color: var(--color-text-secondary);
  font-weight: 400;
}

.trace__head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--ys-space-3);
}

.trace__time {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  font-variant-numeric: tabular-nums;
}

/* 「货到哪了」问的就是地点，所以它不能只作为说明的一部分被折进去 */
.trace__where {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-block-start: 2px;
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

/* 操作按钮另起一行，不挤占商品信息的宽度 */
.goods__actions {
  grid-column: 2 / -1;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--ys-space-2);
}

.goods__reviewed {
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-1);
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
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
  color: var(--color-primary-strong);
}

.goods__spec,
.goods__price {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.goods__sum {
  color: var(--color-primary-strong);
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
  color: var(--color-primary-strong);
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
