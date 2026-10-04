<script setup lang="ts">
/**
 * 订单管理。
 *
 * 列表与详情分开取：列表项是**运营视图**（一条订单一行，带商品件数与首个商品名），
 * 详情才带完整订单、状态流水与物流轨迹。把详情塞进列表会让一页 20 条订单
 * 拖着 20 份商品明细和流水，而运营在列表上根本不看那些。
 *
 * 发货是这个页面唯一的写动作。运单号必填而且全局唯一 —— 一个不填运单号的「已发货」，
 * 用户点进去看到的是一条查不到任何东西的假轨迹。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { addOrderTrace, getOrderDetail, listOrders, remarkOrder, shipOrder } from '@/api/admin/order'
import { formatPrice } from '@/api/product'
import { useAdminList, useResponsiveColumns } from '@/composables/useAdminList'
import { formatDate, formatDateTime } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import type {
  AdminOrderDetail,
  AdminOrderQuery,
  AdminOrderSummary,
  AdminShipRequest,
  AdminTraceRequest,
} from '@/types/admin'

const route = useRoute()

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
} = useAdminList<AdminOrderSummary, AdminOrderQuery>(
  listOrders,
  {
    keyword: '',
    userId: '',
    status: undefined,
    createdFrom: undefined,
    createdTo: undefined,
    page: 0,
    size: 20,
  },
  { immediate: false },
)


/** 表格容器：列宽按它实测的宽度算，窄屏时等比例收缩 */
const tableRef = ref<HTMLElement | null>(null)
const { widths: colW } = useResponsiveColumns(
  [
    { width: 220, min: 220 },
    { width: 110, min: 100 },
    { width: 170, min: 140 },
    { width: 120, min: 110 },
    { width: 100, min: 90 },
    { width: 150, min: 130 },
    { width: 150, min: 130 },
    { width: 90, min: 80 },
  ],
  tableRef,
)

/** 日期区间在页面上是一个控件、在协议里是两个字段，转换只在这一处 */
const createdRange = ref<[string, string] | null>(null)

onMounted(async () => {
  const status = route.query.status
  if (typeof status === 'string' && status) {
    query.value.status = status
  }
  await load(0)
})

async function onSearch() {
  query.value.createdFrom = createdRange.value?.[0]
  query.value.createdTo = createdRange.value?.[1]
  await search()
}

async function onReset() {
  createdRange.value = null
  await resetFilters()
}

// ==================== 详情 ====================

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<AdminOrderDetail | null>(null)
const activeOrderId = ref<number | null>(null)

async function openDetail(row: AdminOrderSummary) {
  activeOrderId.value = row.id
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getOrderDetail(row.id)
  } catch {
    // 拦截器已提示；抽屉里显示空态并保留重试入口
  } finally {
    detailLoading.value = false
  }
}

async function reloadDetail() {
  if (activeOrderId.value === null) {
    return
  }
  detailLoading.value = true
  try {
    detail.value = await getOrderDetail(activeOrderId.value)
  } catch {
    // 同上
  } finally {
    detailLoading.value = false
  }
}

// ==================== 发货 ====================

const shipDialog = reactive({ visible: false, saving: false })
const shipForm = ref<AdminShipRequest>(emptyShip())

function emptyShip(): AdminShipRequest {
  return { carrierCode: '', carrierName: '', trackingNo: '' }
}

const carriers = [
  { code: 'SF', name: '顺丰速运' },
  { code: 'JD', name: '京东物流' },
  { code: 'YTO', name: '圆通速递' },
  { code: 'ZTO', name: '中通快递' },
  { code: 'STO', name: '申通快递' },
  { code: 'YUNDA', name: '韵达速递' },
  { code: 'EMS', name: '中国邮政 EMS' },
]

function openShip() {
  shipForm.value = emptyShip()
  shipDialog.visible = true
}

/** 选承运商时把编码与名称一起填上：只填一个的请求会被后端直接拒掉 */
function pickCarrier(code: string) {
  const hit = carriers.find((c) => c.code === code)
  shipForm.value.carrierCode = code
  shipForm.value.carrierName = hit?.name ?? ''
}

async function submitShip() {
  if (activeOrderId.value === null) {
    return
  }
  const form = shipForm.value
  if (!form.carrierCode.trim() || !form.carrierName.trim()) {
    ElMessage.warning('请选择承运商')
    return
  }
  if (!form.trackingNo.trim()) {
    ElMessage.warning(
      '运单号不能为空 —— 没有运单号的「已发货」在用户那边是一条查不到任何东西的假轨迹',
    )
    return
  }
  shipDialog.saving = true
  try {
    await shipOrder(activeOrderId.value, {
      carrierCode: form.carrierCode.trim(),
      carrierName: form.carrierName.trim(),
      trackingNo: form.trackingNo.trim(),
    })
    ElMessage.success('已发货')
    shipDialog.visible = false
    await reloadDetail()
    await load()
  } catch {
    // 运单号重复之类的拒绝原因由后端给出，拦截器已展示
  } finally {
    shipDialog.saving = false
  }
}

// ==================== 补录物流节点 ====================

const traceDialog = reactive({ visible: false, saving: false })
const traceForm = ref<AdminTraceRequest>(emptyTrace())

function emptyTrace(): AdminTraceRequest {
  return { status: 'IN_TRANSIT', description: '', location: '', happenAt: null }
}

/**
 * 物流节点字典。
 *
 * 与承运商那份一样写在前端：取值非法时后端会 400 并把合法取值一并回过来，
 * 而"加一个节点"是物流侧的事，不该为它发一次前端版本。这里的 label 只影响
 * 下拉框里显示什么，不影响落库的编码。
 */
const deliveryStatuses = [
  { code: 'CREATED', label: '电子面单已生成' },
  { code: 'PICKED_UP', label: '已揽收' },
  { code: 'IN_TRANSIT', label: '运输中' },
  { code: 'DELIVERING', label: '派送中' },
  { code: 'SIGNED', label: '已签收' },
]

function openTrace() {
  traceForm.value = emptyTrace()
  traceDialog.visible = true
}

async function submitTrace() {
  if (activeOrderId.value === null) {
    return
  }
  const form = traceForm.value
  traceDialog.saving = true
  try {
    const updated = await addOrderTrace(activeOrderId.value, {
      status: form.status,
      // 空串不发给后端：那边空串会走默认文案，但先清掉能少一次无意义的传输
      description: form.description?.trim() || undefined,
      location: form.location?.trim() || undefined,
      happenAt: form.happenAt ?? null,
    })
    // 用接口回来的整条轨迹就地更新，不重拉详情：补录的价值之一就是
    // "看看这一步落在时间轴的哪个位置"，多一次往返就多一次看不到的机会
    if (detail.value) {
      detail.value.delivery = updated
    }
    ElMessage.success('物流节点已补录')
    traceDialog.visible = false
  } catch {
    // 取值非法等拒绝原因由后端给出（含全部合法取值），拦截器已展示
  } finally {
    traceDialog.saving = false
  }
}

/** 轨迹是给人读时间顺序的，列表里倒序看更顺 —— 最新的一条总在最上面 */
const traceSteps = computed(() => [...(detail.value?.delivery?.steps ?? [])].reverse())

// ==================== 备注 ====================

const remarkDialog = reactive({ visible: false, saving: false })
const remarkText = ref('')

function openRemark() {
  remarkText.value = detail.value?.adminRemark ?? ''
  remarkDialog.visible = true
}

async function submitRemark() {
  if (activeOrderId.value === null) {
    return
  }
  remarkDialog.saving = true
  try {
    await remarkOrder(activeOrderId.value, remarkText.value.trim())
    ElMessage.success('备注已保存')
    remarkDialog.visible = false
    await reloadDetail()
    await load()
  } catch {
    // 同发货
  } finally {
    remarkDialog.saving = false
  }
}

// ==================== 展示 ====================

const statusOptions = [
  { label: '待支付', value: 'CREATED' },
  { label: '已支付', value: 'PAID' },
  { label: '已发货', value: 'SHIPPED' },
  { label: '已收货', value: 'RECEIVED' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '已取消', value: 'CANCELLED' },
  { label: '已关闭', value: 'CLOSED' },
  { label: '退款中', value: 'REFUNDING' },
  { label: '已退款', value: 'REFUNDED' },
]

/** 只有已支付能发货。其余状态让后端去拒也行，但先把按钮藏掉更省一次往返 */
const canShip = computed(() => detail.value?.order.status === 'PAID')

function statusTagType(status: string): 'success' | 'warning' | 'info' | 'danger' | 'primary' {
  switch (status) {
    case 'PAID':
    case 'RECEIVED':
    case 'COMPLETED':
      return 'success'
    case 'SHIPPED':
      return 'primary'
    case 'CREATED':
      return 'warning'
    case 'CANCELLED':
    case 'CLOSED':
      return 'info'
    case 'REFUNDING':
    case 'REFUNDED':
      return 'danger'
    default:
      return 'info'
  }
}

/** 流水里的操作者：用户、客服、系统。来源不同，可追责性也不同 */
function operatorText(log: AdminOrderDetail['statusLogs'][number]): string {
  if (log.operatorType === 'SYSTEM') {
    return '系统'
  }
  const who = log.operatorType === 'ADMIN' ? '客服' : '用户'
  return log.operatorId ? `${who} ${log.operatorId}` : who
}

const receiverText = computed(() => {
  const order = detail.value?.order
  if (!order) {
    return ''
  }
  return `${order.receiverProvince}${order.receiverCity}${order.receiverDistrict}${order.receiverDetail}`
})

async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success('已复制')
  } catch {
    // 剪贴板权限被拒时不弹成功提示，用户自己选中复制即可
    ElMessage.warning('复制失败，请手动选中')
  }
}
</script>

<template>
  <div class="admin-panel">
    <div class="admin-filters">
      <div class="admin-filters__item admin-filters__item--wide">
        <label class="admin-filters__label" for="order-keyword">关键词</label>
        <el-input
          id="order-keyword"
          v-model="query.keyword"
          placeholder="订单号 / 收货人 / 手机号 / 运单号"
          clearable
          @keyup.enter="onSearch"
        />
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="order-user">用户 ID</label>
        <el-input
          id="order-user"
          v-model="query.userId"
          placeholder="如 u1001"
          clearable
          @keyup.enter="onSearch"
        />
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="order-status">状态</label>
        <el-select id="order-status" v-model="query.status" clearable placeholder="全部状态">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </div>

      <div class="admin-filters__item admin-filters__item--wide">
        <label class="admin-filters__label" for="order-range">下单时间</label>
        <el-date-picker
          id="order-range"
          v-model="createdRange"
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
      <h2 class="admin-toolbar__title">订单</h2>
      <span class="admin-toolbar__count">共 {{ total }} 单</span>
    </div>

    <ErrorState v-if="error" :message="error" :on-retry="() => load()" />

    <template v-else>
      <div ref="tableRef" class="admin-table">
        <el-table v-loading="loading" :data="records" style="width: 100%" @row-click="openDetail">
          <el-table-column label="订单" :width="colW[0]">
            <template #default="{ row }">
              <div class="admin-stack">
                <span class="admin-cell--strong">{{ row.orderNo }}</span>
                <span class="admin-cell--muted admin-cell--ellipsis">
                  {{ row.firstItemName ?? '—' }}
                  <template v-if="row.itemCount > 1">等 {{ row.itemCount }} 种</template>
                </span>
                <span v-if="row.adminRemark" class="admin-cell--tiny"
                  >备注：{{ row.adminRemark }}</span
                >
              </div>
            </template>
          </el-table-column>

          <el-table-column label="用户" :width="colW[1]">
            <template #default="{ row }">
              <span class="admin-cell--tiny">{{ row.userId }}</span>
            </template>
          </el-table-column>

          <el-table-column label="收货人" :width="colW[2]">
            <template #default="{ row }">
              <div class="admin-stack">
                <span>{{ row.receiverName }}</span>
                <span class="admin-cell--tiny">{{ row.receiverPhone }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="金额" :width="colW[3]" align="right">
            <template #default="{ row }">
              <div class="admin-stack admin-stack--end">
                <span class="admin-cell--num">{{ formatPrice(row.payAmount) }}</span>
                <span v-if="row.discountAmount > 0" class="admin-cell--tiny">
                  已减 {{ formatPrice(row.discountAmount) }}
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

          <el-table-column label="物流" :width="colW[5]">
            <template #default="{ row }">
              <div v-if="row.trackingNo" class="admin-stack">
                <span class="admin-cell--tiny">{{ row.carrierName ?? '—' }}</span>
                <span class="admin-cell--tiny">{{ row.trackingNo }}</span>
              </div>
              <span v-else class="admin-cell--muted">—</span>
            </template>
          </el-table-column>

          <el-table-column label="下单时间" :width="colW[6]">
            <template #default="{ row }">
              <span class="admin-cell--tiny">{{ formatDate(row.createdAt) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" :width="colW[7]" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>

          <template #empty>
            <p class="admin-empty">没有符合条件的订单</p>
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

    <el-drawer v-model="detailVisible" title="订单详情" size="640px">
      <el-skeleton v-if="detailLoading" :rows="8" animated />

      <ErrorState v-else-if="!detail" message="订单详情没能加载出来" :on-retry="reloadDetail" />

      <div v-else class="admin-detail">
        <h3 class="admin-section__title">
          {{ detail.order.orderNo }}
          <el-tag :type="statusTagType(detail.order.status)" effect="plain" size="small">
            {{ detail.order.statusText }}
          </el-tag>
        </h3>

        <div class="order-actions">
          <el-button v-if="canShip" type="primary" @click="openShip">发货</el-button>
          <el-button @click="openRemark">编辑备注</el-button>
        </div>
        <p v-if="!canShip && detail.order.status === 'CREATED'" class="admin-dialog__hint">
          订单还没支付，发货按钮要等支付回调把它推到「已支付」才会出现。
        </p>

        <div class="admin-kv">
          <span class="admin-kv__key">收货人</span>
          <span class="admin-kv__value"
            >{{ detail.order.receiverName }} · {{ detail.order.receiverPhone }}</span
          >
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">收货地址</span>
          <span class="admin-kv__value">{{ receiverText }}</span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">订单备注</span>
          <span class="admin-kv__value">{{ detail.order.remark || '—' }}</span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">客服备注</span>
          <span class="admin-kv__value">{{ detail.adminRemark || '—' }}</span>
        </div>
        <div v-if="detail.order.cancelReason" class="admin-kv">
          <span class="admin-kv__key">取消原因</span>
          <span class="admin-kv__value">{{ detail.order.cancelReason }}</span>
        </div>

        <h3 class="admin-section__title">商品</h3>
        <div class="admin-table">
          <el-table :data="detail.order.items" style="width: 100%">
            <el-table-column label="商品" min-width="200">
              <template #default="{ row }">
                <div class="admin-stack">
                  <span class="admin-cell--strong">{{ row.spuName }}</span>
                  <span v-if="row.skuSpecText" class="admin-cell--tiny">{{ row.skuSpecText }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="单价" width="100" align="right">
              <template #default="{ row }">
                <span class="admin-cell--num">{{ formatPrice(row.unitPrice) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="70" align="right">
              <template #default="{ row }">
                <span class="admin-cell--num">×{{ row.quantity }}</span>
              </template>
            </el-table-column>
            <el-table-column label="小计" width="110" align="right">
              <template #default="{ row }">
                <span class="admin-cell--num">{{ formatPrice(row.subtotal) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="order-total">
          <span class="admin-kv__key">商品合计</span>
          <span class="admin-cell--num">{{ formatPrice(detail.order.totalAmount) }}</span>
          <span class="admin-kv__key">运费</span>
          <span class="admin-cell--num">{{ formatPrice(detail.order.freightAmount) }}</span>
          <span class="admin-kv__key">优惠</span>
          <span class="admin-cell--num">-{{ formatPrice(detail.order.discountAmount) }}</span>
          <span class="admin-kv__key">实付</span>
          <span class="admin-cell--num order-total__pay">{{
            formatPrice(detail.order.payAmount)
          }}</span>
        </div>

        <template v-if="detail.delivery">
          <h3 class="admin-section__title">
            物流轨迹
            <el-button class="admin-section__action" link type="primary" @click="openTrace"
              >补录节点</el-button
            >
          </h3>
          <div class="admin-kv">
            <span class="admin-kv__key">承运商</span>
            <span class="admin-kv__value">{{ detail.delivery.carrier }}</span>
          </div>
          <div class="admin-kv">
            <span class="admin-kv__key">运单号</span>
            <span class="admin-kv__value">
              {{ detail.delivery.trackingNo }}
              <el-button link type="primary" @click="copy(detail.delivery!.trackingNo)"
                >复制</el-button
              >
            </span>
          </div>
          <p v-if="traceSteps.length === 0" class="admin-empty">
            还没有轨迹节点。发货会自动写一条「已揽收」。
          </p>
          <div v-else class="admin-timeline">
            <div v-for="(step, index) in traceSteps" :key="`${step.time}-${index}`" class="admin-timeline__item">
              <div class="admin-timeline__head">
                <span :class="{ 'admin-cell--strong': index === 0 }">{{ step.detail }}</span>
                <span class="admin-timeline__time">{{ formatDateTime(step.time) }}</span>
              </div>
              <!-- 编码与地点各占一行：前者是给机器认的取值（补录时要照着填），
                   后者是"货到哪了"的答案，客服核对时两样都要看 -->
              <p class="admin-timeline__body">
                <el-tag size="small" effect="plain">{{ step.status }}</el-tag>
                <template v-if="step.location"> · {{ step.location }}</template>
              </p>
            </div>
          </div>
        </template>

        <h3 class="admin-section__title">状态流水</h3>
        <p v-if="detail.statusLogs.length === 0" class="admin-empty">还没有状态变更记录</p>
        <div v-else class="admin-timeline">
          <div v-for="(log, index) in detail.statusLogs" :key="index" class="admin-timeline__item">
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

    <el-dialog v-model="traceDialog.visible" title="补录物流节点" width="460px">
      <el-form label-width="90px">
        <el-form-item label="节点" required>
          <el-select v-model="traceForm.status" style="width: 100%">
            <el-option
              v-for="s in deliveryStatuses"
              :key="s.code"
              :label="`${s.label}（${s.code}）`"
              :value="s.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="所在地">
          <el-input v-model="traceForm.location" placeholder="如 杭州转运中心" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input
            v-model="traceForm.description"
            type="textarea"
            :rows="2"
            placeholder="留空按节点生成，如「包裹已发往下一站」"
          />
        </el-form-item>
        <el-form-item label="发生时间">
          <el-date-picker
            v-model="traceForm.happenAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="留空即现在"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <p class="admin-dialog__hint">
        补录是事后录入，时间要按包裹实际经过那一站的时刻填，而不是现在 ——
        按录入时间落库会让轨迹的顺序与事实不符，而轨迹的意义就是那个顺序。
        录错了再补一条更正，已录的节点不会被改写。
      </p>
      <template #footer>
        <div class="admin-dialog__foot">
          <el-button @click="traceDialog.visible = false">取消</el-button>
          <el-button type="primary" :loading="traceDialog.saving" @click="submitTrace"
            >确认补录</el-button
          >
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="shipDialog.visible" title="发货" width="460px">
      <el-form label-width="90px">
        <el-form-item label="承运商" required>
          <el-select
            :model-value="shipForm.carrierCode"
            placeholder="选择承运商"
            style="width: 100%"
            @update:model-value="pickCarrier"
          >
            <el-option v-for="c in carriers" :key="c.code" :label="c.name" :value="c.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="运单号" required>
          <el-input v-model="shipForm.trackingNo" placeholder="快递单号" />
        </el-form-item>
      </el-form>
      <p class="admin-dialog__hint">
        运单号全局唯一：重复提交同一个号会被拒绝，那是为了避免一张面单挂在两笔订单上。
      </p>
      <template #footer>
        <div class="admin-dialog__foot">
          <el-button @click="shipDialog.visible = false">取消</el-button>
          <el-button type="primary" :loading="shipDialog.saving" @click="submitShip"
            >确认发货</el-button
          >
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="remarkDialog.visible" title="客服备注" width="460px">
      <el-input
        v-model="remarkText"
        type="textarea"
        :rows="4"
        maxlength="200"
        show-word-limit
        placeholder="只有后台看得见，用户不会看到"
      />
      <template #footer>
        <div class="admin-dialog__foot">
          <el-button @click="remarkDialog.visible = false">取消</el-button>
          <el-button type="primary" :loading="remarkDialog.saving" @click="submitRemark"
            >保存</el-button
          >
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin-stack--end {
  align-items: flex-end;
}

.order-actions {
  display: flex;
  gap: var(--ys-space-2);
  margin-bottom: var(--ys-space-3);
}

.order-total {
  display: grid;
  grid-template-columns: auto 1fr auto 1fr;
  align-items: center;
  gap: var(--ys-space-2) var(--ys-space-3);
  justify-items: end;
  margin-block: var(--ys-space-3);
  padding: var(--ys-space-3);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-sunken);
}

.order-total .admin-kv__key {
  justify-self: start;
}

.order-total__pay {
  color: var(--color-accent);
  font-weight: 700;
}

:deep(.el-table__row) {
  cursor: pointer;
}
</style>
