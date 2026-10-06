<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { formatPrice } from '@/api/product'
import type { PendingPayment } from '@/types/models'

const props = defineProps<{
  payments: PendingPayment[]
  /**
   * 是否可操作。只有最新一条消息上的卡片可点——与高危确认卡同一取舍：
   * 过期的支付卡点进去可能付的是一笔早就关掉的订单。
   */
  active: boolean
}>()

const router = useRouter()

const primary = computed(() => props.payments[0])

function pay(): void {
  if (!primary.value) {
    return
  }
  router.push({ name: 'payment', query: { orderId: String(primary.value.orderId) } })
}
</script>

<template>
  <section class="payment" :class="{ 'payment--stale': !active }" aria-label="待支付订单">
    <p class="payment__title">这笔订单还需要支付</p>

    <ul class="payment__list">
      <li v-for="p in payments" :key="p.orderNo">
        <span class="payment__no">单号 {{ p.orderNo }}</span>
        <span class="payment__amount">{{ formatPrice(p.payAmount) }}</span>
      </li>
    </ul>

    <p v-if="payments.length > 1" class="payment__hint">
      有多笔订单待支付，这里先带你处理第一笔；其余的在「我的订单」里都能付。
    </p>

    <p v-if="!active" class="payment__stale-note">
      这张卡片已经过期：对话往下走了，这笔订单可能已经被支付或关闭。请对最新一条回复操作。
    </p>

    <div v-else class="payment__buttons">
      <el-button type="primary" size="small" @click="pay">去支付</el-button>
    </div>
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

.payment__list li {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ys-space-3);
  padding: var(--ys-space-2) var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface);
}

.payment--stale .payment__list li {
  color: var(--color-text-secondary);
}

.payment__no {
  font-family: var(--ys-font-mono);
  font-size: var(--ys-font-xs);
  color: var(--color-text-secondary);
  word-break: break-all;
}

.payment__amount {
  flex: none;
  font-size: var(--ys-font-sm);
  font-weight: 600;
  color: var(--color-text-primary);
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
  gap: var(--ys-space-2);
  margin-top: var(--ys-space-4);
}

@media (prefers-reduced-motion: no-preference) {
  .payment {
    animation: payment-in 240ms ease-out;
  }

  @keyframes payment-in {
    from {
      opacity: 0;
      transform: translateY(-4px);
    }
  }
}
</style>
