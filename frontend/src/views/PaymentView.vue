<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const paying = ref(false)
const done = ref(false)

const orderInfo = {
  orderId: Number(route.query.orderId),
  orderNo: String(route.query.orderNo || ''),
  amount: Number(route.query.amount || 0),
}

const methods = ['微信支付', '支付宝', '银联云闪付']
const selected = ref('微信支付')

async function handlePay() {
  paying.value = true
  // 模拟支付流程
  setTimeout(() => {
    paying.value = false
    done.value = true
    ElMessage.success(`订单 ${orderInfo.orderNo} 支付成功`)
  }, 1500)
}
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Payment Gateway</p>
        <h1>订单支付</h1>
      </div>
      <div class="header-actions">
        <el-button plain @click="router.push('/shop')">返回商城</el-button>
      </div>
    </header>

    <section class="payment-card">
      <div class="order-summary">
        <h3>订单摘要</h3>
        <div class="summary-row">
          <span>订单编号</span>
          <strong>{{ orderInfo.orderNo }}</strong>
        </div>
        <div class="summary-row total">
          <span>应付金额</span>
          <strong>¥{{ orderInfo.amount.toFixed(2) }}</strong>
        </div>
      </div>

      <el-divider />

      <div v-if="!done" class="payment-methods">
        <h3>选择支付方式</h3>
        <el-radio-group v-model="selected">
          <el-radio v-for="m in methods" :key="m" :value="m" class="payment-method">
            <span class="method-name">{{ m }}</span>
          </el-radio>
        </el-radio-group>

        <el-button
          :loading="paying"
          class="pay-button"
          size="large"
          type="primary"
          @click="handlePay"
        >
          {{ paying ? '支付处理中...' : `确认支付 ¥${orderInfo.amount.toFixed(2)}` }}
        </el-button>
      </div>

      <div v-else class="payment-success">
        <el-result
          icon="success"
          title="支付成功"
          :sub-title="`订单 ${orderInfo.orderNo} 已完成支付`"
        >
          <template #extra>
            <el-button type="primary" @click="router.push('/orders')">查看订单</el-button>
            <el-button plain @click="router.push('/shop')">继续购物</el-button>
          </template>
        </el-result>
      </div>
    </section>
  </div>
</template>

<style scoped>
.payment-card {
  max-width: 640px;
  margin: 0 auto;
  padding: 32px;
  border-radius: 28px;
  background: rgba(255, 251, 245, 0.9);
  border: 1px solid var(--ys-border);
  box-shadow: var(--ys-shadow);
}

.order-summary h3,
.payment-methods h3 {
  margin: 0 0 16px;
}

.summary-row {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12px;
  color: var(--ys-muted);
}

.summary-row.total {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--ys-border);
}

.summary-row.total strong {
  font-size: 28px;
  color: var(--ys-primary-deep);
}

.payment-methods {
  display: grid;
  gap: 16px;
}

.payment-method {
  display: flex;
  align-items: center;
  padding: 14px 18px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid var(--ys-border);
  width: 100%;
}

.method-name {
  margin-left: 8px;
  font-size: 16px;
}

.pay-button {
  width: 100%;
  margin-top: 8px;
}

.payment-success {
  text-align: center;
}
</style>
