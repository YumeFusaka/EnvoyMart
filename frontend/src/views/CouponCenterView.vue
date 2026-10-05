<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listAvailableCoupons, receiveCoupon } from '@/api/coupon'
import { formatPrice } from '@/api/product'
import { formatDate } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { Coupon } from '@/types/models'

const router = useRouter()

const coupons = ref<Coupon[]>([])
const loading = ref(false)
const failed = ref(false)
/** 正在领取的那张，避免连点重复提交 */
const receivingId = ref<number | null>(null)

/** 券面上的主数字：满减显示金额，折扣显示折扣率 */
function faceValue(coupon: Coupon): string {
  if (coupon.type === 'DISCOUNT' && coupon.discount) {
    const zhe = (coupon.discount * 10).toFixed(1).replace(/\.0$/, '')
    return `${zhe}折`
  }
  return formatPrice(coupon.amount ?? 0)
}

async function load() {
  loading.value = true
  failed.value = false
  try {
    coupons.value = await listAvailableCoupons()
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

async function handleReceive(coupon: Coupon) {
  receivingId.value = coupon.id
  try {
    await receiveCoupon(coupon.id)
    ElMessage.success('领取成功，可在「我的优惠券」查看')
    await load()
  } finally {
    receivingId.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Coupons</p>
        <h1>领券中心</h1>
      </div>
      <el-button link type="primary" @click="router.push('/coupons/mine')">我的优惠券</el-button>
    </header>

    <ErrorState v-if="failed" message="优惠券加载失败" :on-retry="load" />

    <el-empty v-else-if="!loading && coupons.length === 0" description="暂无可领取的优惠券" />

    <div v-loading="loading" class="grid">
      <article v-for="coupon in coupons" :key="coupon.id" class="coupon" :class="{ 'is-sold-out': coupon.remainingCount === 0 }">
        <div class="coupon__left">
          <span class="coupon__value">{{ faceValue(coupon) }}</span>
          <span class="coupon__threshold">
            {{ coupon.threshold > 0 ? `满 ${formatPrice(coupon.threshold)} 可用` : '无门槛' }}
          </span>
        </div>

        <div class="coupon__right">
          <p class="coupon__name">{{ coupon.name }}</p>
          <p class="coupon__rule">{{ coupon.ruleText }}</p>
          <p class="coupon__meta">
            剩余 {{ coupon.remainingCount }} / {{ coupon.totalCount }}
            <template v-if="coupon.validTo"> · 至 {{ formatDate(coupon.validTo) }}</template>
          </p>
        </div>

        <div class="coupon__action">
          <el-button
            v-if="coupon.received"
            disabled
            round
          >
            已领取
          </el-button>
          <el-button
            v-else-if="coupon.remainingCount === 0"
            disabled
            round
          >
            已领完
          </el-button>
          <el-button
            v-else
            type="primary"
            round
            :loading="receivingId === coupon.id"
            @click="handleReceive(coupon)"
          >
            立即领取
          </el-button>
        </div>
      </article>
    </div>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--ys-space-4);
  min-height: 160px;
}

/* 券的形态：左边一竖条是面额，右边是说明，右侧是操作 */
.coupon {
  display: grid;
  grid-template-columns: 108px minmax(0, 1fr) auto;
  gap: var(--ys-space-4);
  align-items: center;
  padding: var(--ys-space-4);
  background: var(--color-bg-surface);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--card-radius);
  box-shadow: var(--card-shadow);
}

.coupon.is-sold-out {
  border-color: var(--color-border);
  opacity: 0.7;
}

.coupon__left {
  display: grid;
  justify-items: center;
  gap: 2px;
  padding-right: var(--ys-space-4);
  border-right: 1px dashed var(--color-primary-border);
}

.coupon__value {
  color: var(--color-primary-strong);
  font-size: var(--ys-font-2xl);
  font-weight: 700;
  line-height: 1.1;
}

.coupon__threshold {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.coupon__right {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.coupon__name {
  font-weight: 600;
}

.coupon__rule,
.coupon__meta {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.coupon__action {
  flex-shrink: 0;
}

@media (max-width: 720px) {
  .coupon {
    grid-template-columns: 88px minmax(0, 1fr);
  }

  .coupon__action {
    grid-column: 1 / -1;
  }
}
</style>
