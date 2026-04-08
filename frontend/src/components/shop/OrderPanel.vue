<script setup lang="ts">
import type { Logistics, Order } from '@/types/models'

defineProps<{
  orders: Order[]
  logisticsMap: Record<number, Logistics | undefined>
  loadingIds: number[]
}>()

const emit = defineEmits<{
  logistics: [orderId: number]
}>()
</script>

<template>
  <section class="order-panel">
    <div class="panel-header">
      <h3>近期订单</h3>
      <span>{{ orders.length }} 单</span>
    </div>

    <div v-if="orders.length" class="order-list">
      <article v-for="order in orders" :key="order.id" class="order-card">
        <div class="order-head">
          <div>
            <strong>{{ order.orderNo }}</strong>
            <p>{{ order.createdAt }}</p>
          </div>
          <el-tag effect="dark" round type="success">{{ order.status }}</el-tag>
        </div>
        <ul>
          <li v-for="item in order.items" :key="item.id">
            <span>{{ item.productName }} × {{ item.quantity }}</span>
            <b>¥{{ item.subtotal }}</b>
          </li>
        </ul>
        <div class="order-actions">
          <span>合计 ¥{{ order.totalAmount }}</span>
          <el-button
            :loading="loadingIds.includes(order.id)"
            link
            type="primary"
            @click="emit('logistics', order.id)"
          >
            查看物流
          </el-button>
        </div>
        <div v-if="logisticsMap[order.id]" class="logistics-box">
          <h4>{{ logisticsMap[order.id]?.carrier }} · {{ logisticsMap[order.id]?.trackingNo }}</h4>
          <div
            v-for="step in logisticsMap[order.id]?.steps || []"
            :key="`${order.id}-${step.time}-${step.status}`"
            class="logistics-step"
          >
            <strong>{{ step.status }}</strong>
            <span>{{ step.detail }}</span>
            <small>{{ step.time }}</small>
          </div>
        </div>
      </article>
    </div>

    <el-empty v-else description="还没有订单，先挑一件喜欢的商品吧" />
  </section>
</template>

<style scoped>
.order-panel {
  padding: 22px;
  border-radius: 24px;
  background: rgba(255, 251, 245, 0.9);
  border: 1px solid rgba(84, 55, 23, 0.08);
}

.panel-header,
.order-head,
.order-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.panel-header h3 {
  margin: 0;
}

.order-list {
  display: grid;
  gap: 16px;
  margin-top: 16px;
}

.order-card {
  padding: 16px;
  border-radius: 18px;
  background: rgba(182, 91, 46, 0.06);
}

.order-head p,
.order-actions span,
.logistics-step span,
.logistics-step small {
  color: var(--ys-muted);
}

ul {
  margin: 14px 0;
  padding-left: 18px;
}

li {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.logistics-box {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed rgba(84, 55, 23, 0.12);
}

.logistics-box h4 {
  margin: 0 0 10px;
}

.logistics-step {
  display: grid;
  gap: 4px;
  margin-bottom: 10px;
}
</style>
