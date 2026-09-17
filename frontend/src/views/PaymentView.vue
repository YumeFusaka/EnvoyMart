<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { createPayment } from '@/api/payment'

const route = useRoute()
const router = useRouter()
const paying = ref(false)
const done = ref(false)

/**
 * URL 里的订单号与金额只用作**进入页面时的占位**。
 * 真正生效的是支付单创建后服务端返回的那份——后端以订单服务为准，
 * 不再采信请求体里的金额，所以这里的显示也必须跟着服务端走，
 * 否则页面写着 0.01 元、实际建出来的是 79 元，用户不知道该信哪个。
 */
const orderInfo = {
  orderId: Number(route.query.orderId),
  orderNo: String(route.query.orderNo || ''),
  amount: Number(route.query.amount || 0),
}

const confirmed = ref<{ orderNo: string; amount: number } | null>(null)
const displayNo = computed(() => confirmed.value?.orderNo ?? orderInfo.orderNo)
const displayAmount = computed(() => confirmed.value?.amount ?? orderInfo.amount)

const methods = ['微信支付', '支付宝', '银联云闪付']
const selected = ref('微信支付')

/**
 * 支付动作。
 *
 * **真实调后端建支付单**（订单归属由网关从 JWT 注入，前端不传 userId），
 * 但不接真实渠道：支付单停在 PENDING，等渠道回调推进。
 * 页面上如实说明"演示环境不产生真实扣款"，不谎报支付成功——
 * 之前这里是个纯 setTimeout 的假成功，点了不看后端是发现不了的。
 *
 * 失败提示交给 axios 拦截器统一弹（它会带上后端返回的 msg），
 * 这里再弹一次会出现两条互相矛盾的提示。
 */
async function handlePay() {
  paying.value = true
  try {
    const res = await createPayment({ orderId: orderInfo.orderId })
    confirmed.value = { orderNo: res.data.data.orderNo, amount: res.data.data.amount }
    done.value = true
    ElMessage.success(`订单 ${displayNo.value} 支付单已创建（演示环境，不产生真实扣款）`)
  } finally {
    paying.value = false
  }
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
          <strong>{{ displayNo }}</strong>
        </div>
        <div class="summary-row total">
          <span>应付金额</span>
          <strong>¥{{ displayAmount.toFixed(2) }}</strong>
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
          {{ paying ? '支付处理中...' : `确认支付 ¥${displayAmount.toFixed(2)}` }}
        </el-button>
      </div>

      <div v-else class="payment-success">
        <el-result
          icon="success"
          title="支付单已创建"
          :sub-title="`订单 ${displayNo} 已生成支付单，等待渠道回调确认（演示环境，不产生真实扣款）`"
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
