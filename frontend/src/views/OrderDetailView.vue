<script setup lang="ts">
import { getOrders, getLogistics } from '@/api/order'
import type { Logistics, Order } from '@/types/models'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const orders = ref<Order[]>([])
const logisticsMap = ref<Record<number, Logistics | undefined>>({})
const loadingIds = ref<number[]>([])
const loading = ref(true)

async function loadOrders() {
  loading.value = true
  try {
    orders.value = await getOrders()
  } finally {
    loading.value = false
  }
}

async function toggleLogistics(orderId: number) {
  if (logisticsMap.value[orderId]) {
    logisticsMap.value[orderId] = undefined
    return
  }
  loadingIds.value = [...loadingIds.value, orderId]
  try {
    logisticsMap.value[orderId] = await getLogistics(orderId)
  } finally {
    loadingIds.value = loadingIds.value.filter(id => id !== orderId)
  }
}

function goToPayment(order: Order) {
  router.push({ path: '/payment', query: { orderId: order.id, orderNo: order.orderNo, amount: order.totalAmount } })
}

onMounted(loadOrders)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Order Management</p>
        <h1>我的订单</h1>
      </div>
      <div class="header-actions">
        <el-button plain @click="router.push('/shop')">返回商城</el-button>
      </div>
    </header>

    <section v-if="loading" class="order-list">
      <el-skeleton v-for="i in 3" :key="i" :rows="4" animated />
    </section>

    <section v-else-if="orders.length" class="order-list">
      <article v-for="order in orders" :key="order.id" class="order-card">
        <div class="order-head">
          <div>
            <strong>{{ order.orderNo }}</strong>
            <p>{{ order.createdAt }}</p>
          </div>
          <div class="order-head-right">
            <el-tag :type="order.status === 'DELIVERING' ? 'success' : 'warning'" round>
              {{ order.status === 'DELIVERING' ? '配送中' : order.status }}
            </el-tag>
            <span class="order-amount">¥{{ order.totalAmount }}</span>
          </div>
        </div>

        <el-table :data="order.items" stripe style="width: 100%">
          <el-table-column prop="productName" label="商品" />
          <el-table-column prop="unitPrice" label="单价" width="100" />
          <el-table-column prop="quantity" label="数量" width="80" />
          <el-table-column prop="subtotal" label="小计" width="100" />
        </el-table>

        <div class="order-actions">
          <el-button v-if="!logisticsMap[order.id]" :loading="loadingIds.includes(order.id)" link type="primary" @click="toggleLogistics(order.id)">
            {{ logisticsMap[order.id] ? '收起物流' : '查看物流' }}
          </el-button>
          <el-button v-else link type="default" @click="toggleLogistics(order.id)">收起物流</el-button>
          <el-button v-if="order.status !== 'PAID'" type="primary" @click="goToPayment(order)">去支付</el-button>
        </div>

        <div v-if="logisticsMap[order.id]" class="logistics-box">
          <h4>{{ logisticsMap[order.id]?.carrier }} · {{ logisticsMap[order.id]?.trackingNo }}</h4>
          <el-timeline>
            <el-timeline-item
              v-for="step in logisticsMap[order.id]?.steps || []"
              :key="step.time"
              :timestamp="step.time"
            >
              <strong>{{ step.status }}</strong>
              <p>{{ step.detail }}</p>
            </el-timeline-item>
          </el-timeline>
        </div>
      </article>
    </section>

    <el-empty v-else description="暂无订单记录" />
  </div>
</template>

<style scoped>
.order-list {
  display: grid;
  gap: 18px;
}

.order-card {
  padding: 22px;
  border-radius: 24px;
  background: rgba(255, 251, 245, 0.9);
  border: 1px solid var(--ys-border);
  display: grid;
  gap: 16px;
}

.order-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.order-head p {
  color: var(--ys-muted);
  margin: 4px 0 0;
}

.order-head-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.order-amount {
  font-size: 18px;
  font-weight: 700;
  color: var(--ys-primary-deep);
}

.order-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.logistics-box {
  padding: 16px;
  border-radius: 16px;
  background: rgba(182, 91, 46, 0.06);
}

.logistics-box h4 {
  margin: 0 0 14px;
}
</style>
