<script setup lang="ts">
/**
 * 售后工作台。
 *
 * 这一页的核心是**审核一个二值裁决**：同意还是驳回。围绕它有两件事必须让运营看得见：
 *
 * 1. 同意之前先看得到「最多能退多少」（`maxRefundable`）。退多了钱就真出去了，
 *    而这个数字由后端按订单实付与已退金额算出来，前端不自己算一份。
 * 2. 驳回必须填原因。用户在商城上看到的只有一句「已驳回」，没有原因就是他来找客服的开场白，
 *    而客服手里并没有当时那个判断依据。
 *
 * 另外两个动作（确认收到退货、重试退款）平时是灰的 —— 它们各自只在一种状态下才有意义，
 * 让它们在其它状态下可点，等于把「点了没反应」做成常态。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import {
  auditAfterSale,
  confirmReceived,
  getAfterSaleDetail,
  listAfterSales,
  retryRefund,
} from '@/api/admin/afterSale'
import { formatPrice } from '@/api/product'
import { useAdminList, useResponsiveColumns } from '@/composables/useAdminList'
import { formatDateTime } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { AdminAfterSaleDetail, AdminAfterSaleQuery, StatusLogView } from '@/types/admin'
import type { AfterSale } from '@/types/models'

const {
  query,
  records,
  total,
  loading,
  error,
  search,
  resetFilters,
  currentPage,
  changePage,
  changeSize,
  load,
} = useAdminList<AfterSale, AdminAfterSaleQuery>(
  listAfterSales,
  {
    keyword: '',
    userId: '',
    status: undefined,
    type: undefined,
    appliedFrom: undefined,
    appliedTo: undefined,
    page: 0,
    size: 20,
  },
  { immediate: false },
)


/** 表格容器：列宽按它实测的宽度算，窄屏时等比例收缩 */
const tableRef = ref<HTMLElement | null>(null)
const { widths: colW } = useResponsiveColumns(
  [
    { width: 200, min: 190 },
    { width: 100, min: 90 },
    { width: 160, min: 150 },
    { width: 120, min: 110 },
    { width: 100, min: 90 },
    { width: 150, min: 130 },
    { width: 90, min: 80 },
  ],
  tableRef,
)

const appliedRange = ref<[string, string] | null>(null)

onMounted(() => load(0))

async function onSearch() {
  query.value.appliedFrom = appliedRange.value?.[0]
  query.value.appliedTo = appliedRange.value?.[1]
  await search()
}

async function onReset() {
  appliedRange.value = null
  await resetFilters()
}

// ==================== 详情 ====================

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<AdminAfterSaleDetail | null>(null)
const active = ref<AfterSale | null>(null)

async function openDetail(row: AfterSale) {
  active.value = row
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getAfterSaleDetail(row.id)
    active.value = detail.value.afterSale
  } catch {
    // 拦截器已提示；抽屉里保留重试入口
  } finally {
    detailLoading.value = false
  }
}

async function reloadDetail() {
  if (!active.value) {
    return
  }
  detailLoading.value = true
  try {
    detail.value = await getAfterSaleDetail(active.value.id)
    active.value = detail.value.afterSale
  } catch {
    // 同上
  } finally {
    detailLoading.value = false
  }
}

// ==================== 动作 ====================

const working = ref(false)

async function approve() {
  const item = active.value
  if (!item) {
    return
  }
  const max = item.maxRefundable
  try {
    await ElMessageBox.confirm(
      `同意后将按 ${formatPrice(item.refundAmount)} 发起退款` +
        (max !== null ? `（本单最多可退 ${formatPrice(max)}）` : '') +
        '。需要寄回的类型会先进入「待寄回」，收到货后才打款。',
      '同意售后',
      { type: 'warning', confirmButtonText: '同意', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  working.value = true
  try {
    await auditAfterSale(item.id, true)
    ElMessage.success('已同意')
    await Promise.all([reloadDetail(), load()])
  } catch {
    // 超出可退金额、状态已变之类的拒绝由后端给出，拦截器已展示
  } finally {
    working.value = false
  }
}

async function reject() {
  const item = active.value
  if (!item) {
    return
  }
  let remark = ''
  try {
    const result = await ElMessageBox.prompt(
      '驳回原因会随售后单一起回给用户。用户在商城上只看到「已驳回」时，' +
        '他会带着这个问题来找客服，而客服手里并没有当时的判断依据。',
      '驳回售后',
      {
        inputPlaceholder: '如：商品已拆封使用，不符合退货条件',
        inputValidator: (value) => (value && value.trim() ? true : '必须填驳回原因'),
        confirmButtonText: '驳回',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
    remark = result.value.trim()
  } catch {
    return
  }
  working.value = true
  try {
    await auditAfterSale(item.id, false, remark)
    ElMessage.success('已驳回')
    await Promise.all([reloadDetail(), load()])
  } catch {
    // 同同意
  } finally {
    working.value = false
  }
}

async function markReceived() {
  const item = active.value
  if (!item) {
    return
  }
  try {
    await ElMessageBox.confirm(
      '确认已经收到用户寄回的商品？确认后将进入退款打款。',
      '确认收到退货',
      {
        type: 'info',
        confirmButtonText: '确认收到',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }
  working.value = true
  try {
    await confirmReceived(item.id)
    ElMessage.success('已确认收到')
    await Promise.all([reloadDetail(), load()])
  } catch {
    // 同同意
  } finally {
    working.value = false
  }
}

async function retry() {
  const item = active.value
  if (!item) {
    return
  }
  working.value = true
  try {
    await retryRefund(item.id)
    ElMessage.success('已重新发起退款')
    await Promise.all([reloadDetail(), load()])
  } catch {
    // 支付服务仍不可用之类的失败原因由后端给出
  } finally {
    working.value = false
  }
}

// ==================== 展示 ====================

const statusOptions = [
  { label: '待审核', value: 'APPLIED' },
  { label: '已通过', value: 'APPROVED' },
  { label: '待寄回', value: 'RETURNING' },
  { label: '已收货', value: 'RECEIVED' },
  { label: '退款中', value: 'REFUNDING' },
  { label: '已完成', value: 'FINISHED' },
  { label: '已驳回', value: 'REJECTED' },
  { label: '已取消', value: 'CANCELLED' },
]

const typeOptions = [
  { label: '仅退款', value: 'REFUND_ONLY' },
  { label: '退货退款', value: 'RETURN_REFUND' },
  { label: '换货', value: 'EXCHANGE' },
]

/** 状态的中文说明由服务端给出（`statusText`），这里只决定它的颜色 */
function statusTagType(status: string): 'success' | 'warning' | 'info' | 'danger' | 'primary' {
  switch (status) {
    case 'APPLIED':
      return 'warning'
    case 'APPROVED':
    case 'RETURNING':
    case 'RECEIVED':
      return 'primary'
    case 'FINISHED':
      return 'success'
    case 'REJECTED':
    case 'CANCELLED':
      return 'info'
    default:
      return 'info'
  }
}

/** 三个动作各自只在一种状态下有意义 —— 其余状态让按钮可点，等于把「点了没反应」做成常态 */
const canAudit = computed(() => active.value?.status === 'APPLIED')
const canReceive = computed(() => active.value?.status === 'RETURNING')
const canRetryRefund = computed(
  () => active.value?.status === 'REFUNDING' || active.value?.status === 'RECEIVED',
)

function operatorText(log: StatusLogView): string {
  if (log.operatorType === 'SYSTEM') {
    return '系统'
  }
  const who = log.operatorType === 'ADMIN' ? '客服' : '用户'
  return log.operatorId ? `${who} ${log.operatorId}` : who
}
</script>

<template>
  <div class="admin-panel">
    <div class="admin-filters">
      <div class="admin-filters__item admin-filters__item--wide">
        <label class="admin-filters__label" for="as-keyword">关键词</label>
        <el-input
          id="as-keyword"
          v-model="query.keyword"
          placeholder="售后单号 / 订单号 / 商品名"
          clearable
          @keyup.enter="onSearch"
        />
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="as-user">用户 ID</label>
        <el-input
          id="as-user"
          v-model="query.userId"
          placeholder="如 u1001"
          clearable
          @keyup.enter="onSearch"
        />
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="as-status">状态</label>
        <el-select id="as-status" v-model="query.status" clearable placeholder="全部状态">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="as-type">类型</label>
        <el-select id="as-type" v-model="query.type" clearable placeholder="全部类型">
          <el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </div>

      <div class="admin-filters__item admin-filters__item--wide">
        <label class="admin-filters__label" for="as-range">申请时间</label>
        <el-date-picker
          id="as-range"
          v-model="appliedRange"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          start-placeholder="起"
          end-placeholder="止"
          style="width: 100%"
        />
      </div>

      <div class="admin-filters__actions">
        <el-button type="primary" :icon="Search" @click="onSearch">查询</el-button>
        <el-button :icon="Refresh" @click="onReset">重置</el-button>
      </div>
    </div>

    <div class="admin-toolbar">
      <h2 class="admin-toolbar__title">售后</h2>
      <span class="admin-toolbar__count">共 {{ total }} 单</span>
    </div>

    <ErrorState v-if="error" :message="error" :on-retry="() => load()" />

    <template v-else>
      <div ref="tableRef" class="admin-table">
        <el-table v-loading="loading" :data="records" style="width: 100%">
          <el-table-column label="售后单" :width="colW[0]">
            <template #default="{ row }">
              <div class="admin-stack">
                <span class="admin-cell--strong">{{ row.afterSaleNo }}</span>
                <span class="admin-cell--tiny">订单 {{ row.orderNo }}</span>
                <span class="admin-cell--muted admin-cell--ellipsis">{{ row.spuName ?? '—' }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="类型" :width="colW[1]">
            <template #default="{ row }">
              <el-tag effect="plain" size="small">{{ row.typeText }}</el-tag>
            </template>
          </el-table-column>

          <el-table-column label="原因" :width="colW[2]">
            <template #default="{ row }">
              <div class="admin-stack">
                <span class="admin-cell--ellipsis">{{ row.reason }}</span>
                <span v-if="row.description" class="admin-cell--muted admin-cell--ellipsis">
                  {{ row.description }}
                </span>
              </div>
            </template>
          </el-table-column>

          <el-table-column   align="right" label="退款金额" :width="colW[3]">
            <template #default="{ row }">
              <div class="admin-stack admin-stack--end">
                <span class="admin-cell--num">{{ formatPrice(row.refundAmount) }}</span>
                <span v-if="row.maxRefundable !== null" class="admin-cell--tiny">
                  最多 {{ formatPrice(row.maxRefundable) }}
                </span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="状态" :width="colW[4]">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" effect="plain" size="small">{{
                row.statusText
              }}</el-tag>
            </template>
          </el-table-column>

          <el-table-column label="申请时间" :width="colW[5]">
            <template #default="{ row }">
              <span class="admin-cell--tiny">{{ formatDateTime(row.appliedAt) }}</span>
            </template>
          </el-table-column>

          <el-table-column   fixed="right" label="操作" :width="colW[6]">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">处理</el-button>
            </template>
          </el-table-column>

          <template #empty>
            <p class="admin-empty">没有符合条件的售后单</p>
          </template>
        </el-table>
      </div>

      <div class="admin-pager">
        <el-pagination
          :current-page="currentPage"
          :page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="changePage"
          @size-change="changeSize"
        />
      </div>
    </template>

    <el-drawer v-model="detailVisible" title="售后处理" size="620px">
      <el-skeleton v-if="detailLoading" :rows="8" animated />

      <ErrorState v-else-if="!detail" message="售后详情没能加载出来" :on-retry="reloadDetail" />

      <div v-else class="admin-detail">
        <h3 class="admin-section__title">
          {{ detail.afterSale.afterSaleNo }}
          <el-tag :type="statusTagType(detail.afterSale.status)" effect="plain" size="small">
            {{ detail.afterSale.statusText }}
          </el-tag>
        </h3>

        <div class="audit-actions">
          <template v-if="canAudit">
            <el-button type="primary" :loading="working" @click="approve">同意</el-button>
            <el-button type="danger" plain :loading="working" @click="reject">驳回</el-button>
          </template>
          <el-button v-if="canReceive" type="primary" :loading="working" @click="markReceived">
            确认收到退货
          </el-button>
          <el-button v-if="canRetryRefund" :loading="working" @click="retry">重试退款</el-button>
        </div>
        <p v-if="!canAudit && !canReceive" class="admin-dialog__hint">
          这条售后当前没有待处理的动作。状态只能沿售后的状态机往前走，不能跳步或回退。
        </p>

        <h3 class="admin-section__title">申请内容</h3>
        <div class="admin-kv">
          <span class="admin-kv__key">关联订单</span>
          <span class="admin-kv__value">{{ detail.afterSale.orderNo }}</span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">商品</span>
          <span class="admin-kv__value">
            {{ detail.afterSale.spuName ?? '—' }}
            <template v-if="detail.afterSale.skuSpecText">
              · {{ detail.afterSale.skuSpecText }}</template
            >
          </span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">类型</span>
          <span class="admin-kv__value">{{ detail.afterSale.typeText }}</span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">退款金额</span>
          <span class="admin-kv__value amount">{{
            formatPrice(detail.afterSale.refundAmount)
          }}</span>
        </div>
        <div v-if="detail.afterSale.maxRefundable !== null" class="admin-kv">
          <span class="admin-kv__key">最多可退</span>
          <span class="admin-kv__value amount">{{
            formatPrice(detail.afterSale.maxRefundable)
          }}</span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">原因</span>
          <span class="admin-kv__value">{{ detail.afterSale.reason }}</span>
        </div>
        <div v-if="detail.afterSale.description" class="admin-kv">
          <span class="admin-kv__key">补充说明</span>
          <span class="admin-kv__value">{{ detail.afterSale.description }}</span>
        </div>
        <div v-if="detail.afterSale.docRef" class="admin-kv">
          <span class="admin-kv__key">依据条款</span>
          <span class="admin-kv__value">{{ detail.afterSale.docRef }}</span>
        </div>
        <div v-if="detail.afterSale.auditRemark" class="admin-kv">
          <span class="admin-kv__key">审核意见</span>
          <span class="admin-kv__value">{{ detail.afterSale.auditRemark }}</span>
        </div>

        <template v-if="detail.afterSale.images.length > 0">
          <h3 class="admin-section__title">凭证</h3>
          <div class="proof">
            <el-image
              v-for="(image, index) in detail.afterSale.images"
              :key="index"
              class="proof__img"
              :src="image"
              :preview-src-list="detail.afterSale.images"
              :initial-index="index"
              fit="cover"
              preview-teleported
            />
          </div>
        </template>

        <h3 class="admin-section__title">状态流水</h3>
        <p v-if="detail.logs.length === 0" class="admin-empty">还没有状态变更记录</p>
        <div v-else class="admin-timeline">
          <div v-for="(log, index) in detail.logs" :key="index" class="admin-timeline__item">
            <div class="admin-timeline__head">
              <span>
                <template v-if="log.fromStatus">{{ log.fromStatus }} → </template>{{ log.toStatus }}
              </span>
              <span class="admin-timeline__time">{{ formatDateTime(log.createdAt) }}</span>
            </div>
            <p class="admin-timeline__body">
              {{ operatorText(log) }}
              <template v-if="log.remark"> · {{ log.remark }}</template>
            </p>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.admin-stack--end {
  align-items: flex-end;
}

.audit-actions {
  display: flex;
  gap: var(--ys-space-2);
  margin-bottom: var(--ys-space-3);
}

.amount {
  color: var(--color-accent-strong);
  font-weight: 600;
}

.proof {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
}

.proof__img {
  width: 96px;
  height: 96px;
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-sunken);
  overflow: hidden;
  cursor: zoom-in;
}
</style>
