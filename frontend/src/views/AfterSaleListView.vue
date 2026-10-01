<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelAfterSale, getAfterSale, listAfterSales, shipBackAfterSale } from '@/api/afterSale'
import { formatPrice } from '@/api/product'
import { formatDate } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import CreateTicketDialog from '@/components/ticket/CreateTicketDialog.vue'
import type { AfterSale, AfterSaleDetail } from '@/types/models'

const router = useRouter()

const records = ref<AfterSale[]>([])
const loading = ref(false)
const failed = ref(false)

/**
 * 「联系客服」的目标。对话框只有一份，开在哪条记录上由它决定 ——
 * 每条记录各挂一个对话框，展开的那条会把别的都渲染一遍。
 *
 * 带上这一单的 id/no 而不是留空：用户是在**看着某条售后**时说这句话的，
 * 不带订单的工单会让客服先去问"哪一笔"，多一个来回，还可能问错单
 */
const ticketTarget = ref<AfterSale | null>(null)
const ticketOpen = ref(false)

function openTicket(record: AfterSale) {
  ticketTarget.value = record
  ticketOpen.value = true
}

/** 终态：不会再变，不提供撤销入口 */
const TERMINAL = ['FINISHED', 'REJECTED', 'CANCELLED']

/** 需要用户寄回实物的类型：审核通过后必须登记退回物流 */
const NEEDS_RETURN = ['RETURN_REFUND', 'EXCHANGE']

const STATUS_TEXT: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已同意',
  REJECTED: '已驳回',
  RETURNING: '退货中',
  REFUNDING: '退款中',
  FINISHED: '已完成',
  CANCELLED: '已撤销',
}

const activeCount = computed(() => records.value.filter((r) => !TERMINAL.includes(r.status)).length)

/** 展开的行 + 已拉取过的流水缓存：同一条反复展开不再打接口 */
const expandedId = ref<number | null>(null)
const details = reactive<Record<number, AfterSaleDetail | undefined>>({})

/** 拉取某一行的流水；失败就折叠回去，别让骨架屏永远转下去 */
async function fetchDetail(id: number) {
  try {
    details[id] = await getAfterSale(id)
  } catch {
    if (expandedId.value === id) {
      expandedId.value = null
    }
  }
}

async function toggleTimeline(record: AfterSale) {
  if (expandedId.value === record.id) {
    expandedId.value = null
    return
  }
  expandedId.value = record.id
  if (!details[record.id]) {
    await fetchDetail(record.id)
  }
}

async function load() {
  loading.value = true
  failed.value = false
  try {
    records.value = await listAfterSales()
    // 状态可能已变化，流水缓存作废；仍展开的那一行要立刻重拉，否则只剩骨架屏在转
    Object.keys(details).forEach((key) => delete details[Number(key)])
    const open = expandedId.value
    if (open !== null) {
      if (records.value.some((r) => r.id === open)) {
        await fetchDetail(open)
      } else {
        expandedId.value = null
      }
    }
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

// ------- 寄回登记 -------
const shipBackVisible = ref(false)
const shipBackTarget = ref<AfterSale | null>(null)
const shipBackForm = reactive({ carrier: '', trackingNo: '' })
const shipBackSubmitting = ref(false)
const CARRIERS = ['顺丰速运', '中通快递', '圆通速递', '韵达快递', '京东物流', '邮政EMS']

function openShipBack(record: AfterSale) {
  shipBackTarget.value = record
  shipBackForm.carrier = record.returnCarrier ?? ''
  shipBackForm.trackingNo = record.returnTrackingNo ?? ''
  shipBackVisible.value = true
}

async function submitShipBack() {
  if (!shipBackTarget.value) {
    return
  }
  if (!shipBackForm.carrier.trim() || !shipBackForm.trackingNo.trim()) {
    ElMessage.warning('请填写快递公司与运单号')
    return
  }
  shipBackSubmitting.value = true
  try {
    await shipBackAfterSale(shipBackTarget.value.id, {
      carrier: shipBackForm.carrier.trim(),
      trackingNo: shipBackForm.trackingNo.trim(),
    })
    ElMessage.success('已登记寄回物流，等待商家收货')
    shipBackVisible.value = false
    await load()
  } finally {
    shipBackSubmitting.value = false
  }
}

function operatorText(type: string) {
  return type === 'USER' ? '用户' : type === 'ADMIN' ? '平台' : '系统'
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

        <!-- 寄回信息：用户最常问「我寄回去的包裹到哪了」，直接摆出来 -->
        <p v-if="record.returnTrackingNo" class="record__remark">
          寄回物流：{{ record.returnCarrier }} {{ record.returnTrackingNo }}
        </p>

        <!-- 政策依据可追溯：规则给结论，知识库给依据 -->
        <p v-if="record.docRef" class="record__doc">依据政策文档 {{ record.docRef }}</p>

        <!-- 进度时间线：从申请到退款，每一步谁做的、什么时候，可追溯 -->
        <div v-if="expandedId === record.id" class="timeline">
          <el-skeleton v-if="!details[record.id]" :rows="2" animated />
          <el-timeline v-else>
            <el-timeline-item
              v-for="(log, index) in details[record.id]!.logs"
              :key="index"
              :timestamp="formatDate(log.createdAt)"
              placement="top"
              :type="index === details[record.id]!.logs.length - 1 ? 'primary' : ''"
              :hollow="index !== details[record.id]!.logs.length - 1"
            >
              <p class="timeline__title">
                {{ log.remark || STATUS_TEXT[log.toStatus] || log.toStatus }}
              </p>
              <p class="timeline__sub">
                <el-tag size="small" effect="plain" type="info">{{ operatorText(log.operatorType) }}</el-tag>
                <span>{{ STATUS_TEXT[log.toStatus] ?? log.toStatus }}</span>
              </p>
            </el-timeline-item>
          </el-timeline>
        </div>

        <footer class="record__foot">
          <el-button link @click="toggleTimeline(record)">
            {{ expandedId === record.id ? '收起进度' : '查看进度' }}
          </el-button>
          <el-button link @click="router.push(`/orders/${record.orderId}`)">查看订单</el-button>
          <el-button link @click="openTicket(record)">联系客服</el-button>
          <el-button
            v-if="record.status === 'APPROVED' && NEEDS_RETURN.includes(record.type)"
            link
            type="primary"
            @click="openShipBack(record)"
          >
            {{ record.returnTrackingNo ? '修改寄回单号' : '填写寄回单号' }}
          </el-button>
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

    <!-- 常驻渲染（不加 v-if）：对话框关闭时要走完自己的退场动画，
         中途被卸载会留下一层没擦掉的遮罩 -->
    <CreateTicketDialog
      v-model="ticketOpen"
      :order-id="ticketTarget?.orderId"
      :order-no="ticketTarget?.orderNo"
      @created="(id: number) => router.push(`/tickets/${id}`)"
    />

    <el-dialog v-model="shipBackVisible" title="登记寄回物流" width="440px">
      <p v-if="shipBackTarget" class="shipback__tip">
        {{ shipBackTarget.afterSaleNo }} · {{ shipBackTarget.spuName }}
      </p>
      <el-form label-position="top">
        <el-form-item label="快递公司" required>
          <el-select
            v-model="shipBackForm.carrier"
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入快递公司"
            class="full"
          >
            <el-option v-for="name in CARRIERS" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
        <el-form-item label="运单号" required>
          <el-input v-model="shipBackForm.trackingNo" maxlength="40" placeholder="快递面单上的运单号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="shipBackVisible = false">取消</el-button>
        <el-button type="primary" :loading="shipBackSubmitting" @click="submitShipBack">提交</el-button>
      </template>
    </el-dialog>
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

.timeline {
  padding: var(--ys-space-4) var(--ys-space-3) 0;
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
}

.timeline__title {
  font-size: var(--ys-font-sm);
}

.timeline__sub {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  margin-top: var(--ys-space-1);
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

.shipback__tip {
  margin-bottom: var(--ys-space-3);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.full {
  width: 100%;
}

@media (max-width: 720px) {
  .record__goods {
    grid-template-columns: 56px minmax(0, 1fr);
  }
}
</style>
