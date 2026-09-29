<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listMyCoupons } from '@/api/coupon'
import { formatPrice } from '@/api/product'
import { formatDate } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { UserCoupon } from '@/types/models'

const router = useRouter()

const coupons = ref<UserCoupon[]>([])
const loading = ref(false)
const failed = ref(false)
const activeTab = ref<'UNUSED' | 'USED' | 'EXPIRED'>('UNUSED')

const TABS = [
  { value: 'UNUSED', label: '未使用' },
  { value: 'USED', label: '已使用' },
  { value: 'EXPIRED', label: '已过期' },
] as const

const filtered = computed(() => coupons.value.filter((item) => item.status === activeTab.value))

function countOf(status: string) {
  return coupons.value.filter((item) => item.status === status).length
}

/** 券面主数字 */
function faceValue(coupon: UserCoupon): string {
  if (coupon.type === 'DISCOUNT' && coupon.ruleText) {
    const match = coupon.ruleText.match(/(\d+(?:\.\d+)?)\s*折/)
    return match ? `${match[1]}折` : '折扣'
  }
  return formatPrice(coupon.amount ?? 0)
}

/** 距过期还有几天。只对未使用的券显示 */
function expireHint(coupon: UserCoupon): string {
  if (coupon.status !== 'UNUSED') {
    return ''
  }
  const left = new Date(coupon.expireAt).getTime() - Date.now()
  if (left <= 0) {
    return '已过期'
  }
  const days = Math.floor(left / 86_400_000)
  return days > 0 ? `${days} 天后过期` : '今天到期'
}

async function load() {
  loading.value = true
  failed.value = false
  try {
    // 不传 orderAmount：这里是券包，不是结算页，不需要算可用性
    coupons.value = await listMyCoupons()
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">My coupons</p>
        <h1>我的优惠券</h1>
      </div>
      <el-button link type="primary" @click="router.push('/coupons')">去领券中心</el-button>
    </header>

    <nav class="tabs" aria-label="券状态筛选">
      <button
        v-for="tab in TABS"
        :key="tab.value"
        type="button"
        class="tabs__item"
        :class="{ 'is-active': activeTab === tab.value }"
        @click="activeTab = tab.value"
      >
        {{ tab.label }}
        <span v-if="countOf(tab.value) > 0" class="tabs__count">{{ countOf(tab.value) }}</span>
      </button>
    </nav>

    <ErrorState v-if="failed" message="优惠券加载失败" :on-retry="load" />

    <el-empty
      v-else-if="!loading && filtered.length === 0"
      :description="activeTab === 'UNUSED' ? '还没有可用的优惠券' : '这里空空如也'"
    >
      <el-button v-if="activeTab === 'UNUSED'" type="primary" @click="router.push('/coupons')">
        去领券
      </el-button>
    </el-empty>

    <div v-loading="loading" class="grid">
      <article
        v-for="coupon in filtered"
        :key="coupon.id"
        class="coupon"
        :class="{ 'is-dim': coupon.status !== 'UNUSED' }"
      >
        <div class="coupon__left">
          <span class="coupon__value">{{ faceValue(coupon) }}</span>
          <span class="coupon__threshold">
            {{ (coupon.threshold ?? 0) > 0 ? `满 ${formatPrice(coupon.threshold!)} 可用` : '无门槛' }}
          </span>
        </div>
        <div class="coupon__right">
          <p class="coupon__name">{{ coupon.name }}</p>
          <p v-if="coupon.ruleText" class="coupon__rule">{{ coupon.ruleText }}</p>
          <p class="coupon__meta">
            <template v-if="coupon.status === 'USED'">已用于订单 {{ coupon.orderNo }}</template>
            <template v-else>{{ expireHint(coupon) }}</template>
          </p>
          <p class="coupon__meta">有效期至 {{ formatDate(coupon.expireAt) }}</p>
        </div>
        <span class="coupon__badge">{{ coupon.statusText }}</span>
      </article>
    </div>
  </div>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: var(--ys-space-2);
}

.tabs__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-surface);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  cursor: pointer;
}

.tabs__item.is-active {
  border-color: var(--color-primary);
  background: var(--color-primary-subtle);
  color: var(--color-primary);
  font-weight: 600;
}

.tabs__count {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--ys-space-4);
  min-height: 160px;
}

.coupon {
  position: relative;
  display: grid;
  grid-template-columns: 104px minmax(0, 1fr);
  gap: var(--ys-space-4);
  align-items: center;
  padding: var(--ys-space-4);
  background: var(--color-bg-surface);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--card-radius);
  box-shadow: var(--card-shadow);
}

/* 已使用/已过期的券压暗，但**保留可读**：用户仍然要能看清它是什么券 */
.coupon.is-dim {
  border-color: var(--color-border);
  filter: grayscale(1);
  opacity: 0.72;
}

.coupon__left {
  display: grid;
  justify-items: center;
  gap: 2px;
  padding-right: var(--ys-space-4);
  border-right: 1px dashed var(--color-primary-border);
}

.coupon__value {
  color: var(--color-primary);
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

.coupon__badge {
  position: absolute;
  top: var(--ys-space-3);
  right: var(--ys-space-3);
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}
</style>
