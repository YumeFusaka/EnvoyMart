<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelAfterSale, listAfterSales } from '@/api/afterSale'
import { formatPrice } from '@/api/product'
import { formatDate } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { AfterSale } from '@/types/models'

const router = useRouter()

const records = ref<AfterSale[]>([])
const loading = ref(false)
const failed = ref(false)

/** 终态：不会再变，不提供撤销入口 */
const TERMINAL = ['FINISHED', 'REJECTED', 'CANCELLED']

const activeCount = computed(() => records.value.filter((r) => !TERMINAL.includes(r.status)).length)

async function load() {
  loading.value = true
  failed.value = false
  try {
    records.value = await listAfterSales()
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

async function handleCancel(record: AfterSale) {
  try {
    await ElMessageBox.confirm('撤销后需要重新申请，确定撤销吗？', '撤销售后', {
      type: 'warning',
      confirmButtonText: '确认撤销',
      cancelButtonText: '再想想',
    })
  } catch {
    return
  }
  await cancelAfterSale(record.id)
  ElMessage.success('已撤销')
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">After-sales</p>
        <h1>退款/售后</h1>
      </div>
      <p v-if="activeCount" class="subcopy">{{ activeCount }} 条处理中</p>
    </header>

    <ErrorState v-if="failed" message="售后记录加载失败" :on-retry="load" />

    <el-empty v-else-if="!loading && records.length === 0" description="没有售后记录">
      <el-button type="primary" @click="router.push('/orders')">查看我的订单</el-button>
    </el-empty>

    <div v-loading="loading" class="records">
      <article v-for="record in records" :key="record.id" class="surface record">
        <header class="record__head">
          <div class="record__meta">
            <span class="record__no">{{ record.afterSaleNo }}</span>
            <el-tag size="small" effect="plain">{{ record.typeText }}</el-tag>
            <span class="record__time">{{ formatDate(record.appliedAt) }}</span>
          </div>
          <el-tag :type="TERMINAL.includes(record.status) ? 'info' : 'warning'" effect="light">
            {{ record.statusText }}
          </el-tag>
        </header>

        <div class="record__goods">
          <img v-if="record.skuImage" :src="record.skuImage" :alt="record.spuName ?? ''" />
          <div>
            <p class="record__name">{{ record.spuName }}</p>
            <p v-if="record.skuSpecText" class="record__spec">{{ record.skuSpecText }}</p>
            <p class="record__reason">原因：{{ record.reason }}</p>
          </div>
          <span class="record__amount">退款 {{ formatPrice(record.refundAmount) }}</span>
        </div>

        <p v-if="record.auditRemark" class="record__remark">审核意见：{{ record.auditRemark }}</p>
        <p v-if="record.description" class="record__remark">问题描述：{{ record.description }}</p>

        <!-- 政策依据可追溯：规则给结论，知识库给依据 -->
        <p v-if="record.docRef" class="record__doc">依据政策文档 {{ record.docRef }}</p>

        <footer class="record__foot">
          <el-button link @click="router.push(`/orders/${record.orderId}`)">查看订单</el-button>
          <el-button
            v-if="!TERMINAL.includes(record.status) && record.status !== 'REFUNDING'"
            link
            type="danger"
            @click="handleCancel(record)"
          >
            撤销申请
          </el-button>
        </footer>
      </article>
    </div>
  </div>
</template>

<style scoped>
.records {
  display: grid;
  gap: var(--ys-space-4);
  min-height: 160px;
}

.record {
  display: grid;
  gap: var(--ys-space-3);
}

.record__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-3);
  padding-bottom: var(--ys-space-3);
  border-bottom: 1px solid var(--color-border);
}

.record__meta {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  flex-wrap: wrap;
}

.record__no {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.record__time {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.record__goods {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr) auto;
  gap: var(--ys-space-3);
  align-items: center;
}

.record__goods img {
  width: 56px;
  height: 56px;
  border-radius: var(--ys-radius-sm);
  object-fit: cover;
  background: var(--color-bg-surface-muted);
}

.record__name {
  font-weight: 600;
}

.record__spec,
.record__reason {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.record__amount {
  color: var(--color-primary);
  font-weight: 600;
}

.record__remark,
.record__doc {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.record__doc {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.record__foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--ys-space-2);
  padding-top: var(--ys-space-3);
  border-top: 1px solid var(--color-border);
}

@media (max-width: 720px) {
  .record__goods {
    grid-template-columns: 56px minmax(0, 1fr);
  }
}
</style>
