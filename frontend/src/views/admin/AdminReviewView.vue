<script setup lang="ts">
/**
 * 评价管理。
 *
 * 与商城侧的评价有个关键差别：这里的**匿名评价同样带 userId**。
 * 匿名是对外脱敏，不是对平台隐身 —— 否则「这条差评是谁写的」在管理台上就断了线，
 * 而这恰恰是运营最需要知道的一件事（同一个人反复给同一个商品一星，是个信号）。
 *
 * 隐藏必须填原因，恢复不填：隐藏是行使权力，要有依据；恢复是撤回自己的处置，
 * 没有需要留痕的新决定。这条不对称是刻意的，前端不把它做对称。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search, Star, StarFilled } from '@element-plus/icons-vue'
import { changeReviewStatus, getReviewDetail, listReviews, replyReview } from '@/api/admin/review'
import { useAdminList } from '@/composables/useAdminList'
import { formatDateTime } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { AdminReviewDetail, AdminReviewQuery, AdminReviewSummary } from '@/types/admin'

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
} = useAdminList<AdminReviewSummary, AdminReviewQuery>(
  listReviews,
  {
    // 「重置」只会把 initialQuery 里有过的字段清掉（见 useAdminList#resetFilters），
    // 所以**界面上能填的每一项都得在这里出现一次**。少写一项的表现是：
    // 点重置，列表看着回到了初始状态，其实那次筛选还在生效，而且界面上没有任何地方
    // 还显示着它 —— 只能靠「条数怎么不对」倒推
    spuId: undefined,
    orderId: undefined,
    keyword: '',
    userId: '',
    status: undefined,
    rating: undefined,
    hasReply: undefined,
    createdFrom: undefined,
    createdTo: undefined,
    page: 0,
    size: 20,
  },
  { immediate: false },
)

const createdRange = ref<[string, string] | null>(null)

/** 三态：有回复 / 没回复 / 都要。用字符串让「都要」有位置 */
const replyFilter = ref<'all' | 'replied' | 'unreplied'>('all')

function syncReplyFilter() {
  if (replyFilter.value === 'all') {
    query.value.hasReply = undefined
  } else {
    query.value.hasReply = replyFilter.value === 'replied'
  }
}

onMounted(async () => {
  syncReplyFilter()
  await load(0)
})

/**
 * `el-input` 清空后给的是空串。空串发给后端，Long 类型的 `spuId` 绑定失败会变成一次 400，
 * 而用户只是把输入框清了一下 —— 这类「看起来什么都没做」的失败最难排查，在这里统一收口。
 * <p>
 * 只收整数：输入框是 `type="number"`，浏览器允许敲出 `1.5`（数字键盘上的小数点），
 * 而 `spuId` 是 `Long`。放行小数等于把 400 换个地方发生，不如按「没填」处理。
 */
function blankToUndefined(value: number | string | undefined): number | undefined {
  if (value === undefined || value === null || value === '') {
    return undefined
  }
  const parsed = Number(value)
  return Number.isInteger(parsed) ? parsed : undefined
}

async function onSearch() {
  query.value.spuId = blankToUndefined(query.value.spuId)
  query.value.orderId = blankToUndefined(query.value.orderId)
  query.value.createdFrom = createdRange.value?.[0]
  query.value.createdTo = createdRange.value?.[1]
  syncReplyFilter()
  await search()
}

async function onReset() {
  createdRange.value = null
  replyFilter.value = 'all'
  await resetFilters()
}

// ==================== 详情 ====================

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<AdminReviewDetail | null>(null)
const active = ref<AdminReviewSummary | null>(null)
const working = ref(false)

async function openDetail(row: AdminReviewSummary) {
  active.value = row
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getReviewDetail(row.id)
    active.value = detail.value.review
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
    detail.value = await getReviewDetail(active.value.id)
    active.value = detail.value.review
  } catch {
    // 同上
  } finally {
    detailLoading.value = false
  }
}

// ==================== 动作 ====================

async function hide() {
  const item = active.value
  if (!item) {
    return
  }
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      '隐藏原因会写进这条评价的处置记录里。恢复时它会被一并清空 —— ' +
        '「已隐藏」与「有一条隐藏原因」必须是同一件事的两个说法。',
      '隐藏评价',
      {
        inputPlaceholder: '如：含广告信息 / 与商品无关',
        inputValidator: (value) => (value && value.trim() ? true : '必须填隐藏原因'),
        confirmButtonText: '隐藏',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
    reason = result.value.trim()
  } catch {
    return
  }
  working.value = true
  try {
    await changeReviewStatus(item.id, 'HIDDEN', reason)
    ElMessage.success('已隐藏')
    await Promise.all([reloadDetail(), load()])
  } catch {
    // 拒绝原因由后端给出，拦截器已展示
  } finally {
    working.value = false
  }
}

async function publish() {
  const item = active.value
  if (!item) {
    return
  }
  working.value = true
  try {
    await changeReviewStatus(item.id, 'PUBLISHED')
    ElMessage.success('已发布')
    await Promise.all([reloadDetail(), load()])
  } catch {
    // 同上
  } finally {
    working.value = false
  }
}

const replyDialog = ref({ visible: false, saving: false, text: '' })

function openReply() {
  replyDialog.value.text = active.value?.replyContent ?? ''
  replyDialog.value.visible = true
}

async function submitReply() {
  const item = active.value
  if (!item) {
    return
  }
  const text = replyDialog.value.text.trim()
  if (!text) {
    // 传空串是「撤回回复」这个动作，不该让它伪装成「保存了一个空回复」
    try {
      await ElMessageBox.confirm('内容为空会撤回已有的商家回复。确定撤回？', '撤回回复', {
        type: 'warning',
        confirmButtonText: '撤回',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  replyDialog.value.saving = true
  try {
    await replyReview(item.id, text)
    ElMessage.success(text ? '已回复' : '已撤回回复')
    replyDialog.value.visible = false
    await Promise.all([reloadDetail(), load()])
  } catch {
    // 同上
  } finally {
    replyDialog.value.saving = false
  }
}

// ==================== 展示 ====================

const statusOptions = [
  { label: '待审核', value: 'PENDING' },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '已隐藏', value: 'HIDDEN' },
]

const ratingOptions = [5, 4, 3, 2, 1].map((value) => ({ label: `${value} 星`, value }))

function statusTagType(status: string): 'success' | 'warning' | 'info' | 'danger' {
  switch (status) {
    case 'PUBLISHED':
      return 'success'
    case 'PENDING':
      return 'warning'
    case 'HIDDEN':
      return 'danger'
    default:
      return 'info'
  }
}

function statusText(status: string): string {
  return statusOptions.find((s) => s.value === status)?.label ?? status
}

const isHidden = computed(() => active.value?.status === 'HIDDEN')

/** 商详页上的商品链接：让运营能顺着评价点回去看它说的到底是哪件商品 */
function spuLabel(review: AdminReviewSummary): string {
  return review.skuId ? `SPU ${review.spuId} / SKU ${review.skuId}` : `SPU ${review.spuId}`
}
</script>

<template>
  <div class="admin-panel">
    <div class="admin-filters">
      <div class="admin-filters__item admin-filters__item--wide">
        <label class="admin-filters__label" for="review-keyword">关键词</label>
        <el-input
          id="review-keyword"
          v-model="query.keyword"
          placeholder="评价内容 / 商品名"
          clearable
          @keyup.enter="onSearch"
        />
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="review-spu">商品 SPU</label>
        <el-input
          id="review-spu"
          v-model.number="query.spuId"
          placeholder="如 7"
          clearable
          @keyup.enter="onSearch"
        />
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="review-order">订单 ID</label>
        <el-input
          id="review-order"
          v-model.number="query.orderId"
          placeholder="如 12"
          clearable
          @keyup.enter="onSearch"
        />
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="review-user">用户 ID</label>
        <el-input
          id="review-user"
          v-model="query.userId"
          placeholder="如 u1001"
          clearable
          @keyup.enter="onSearch"
        />
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="review-rating">评分</label>
        <el-select id="review-rating" v-model="query.rating" clearable placeholder="全部评分">
          <el-option v-for="r in ratingOptions" :key="r.value" :label="r.label" :value="r.value" />
        </el-select>
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="review-status">状态</label>
        <el-select id="review-status" v-model="query.status" clearable placeholder="全部状态">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="review-reply">商家回复</label>
        <el-select id="review-reply" v-model="replyFilter" placeholder="全部">
          <el-option label="全部" value="all" />
          <el-option label="已回复" value="replied" />
          <el-option label="未回复" value="unreplied" />
        </el-select>
      </div>

      <div class="admin-filters__item admin-filters__item--wide">
        <label class="admin-filters__label" for="review-range">评价时间</label>
        <el-date-picker
          id="review-range"
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
      <h2 class="admin-toolbar__title">评价</h2>
      <span class="admin-toolbar__count">共 {{ total }} 条</span>
    </div>

    <ErrorState v-if="error" :message="error" :on-retry="() => load()" />

    <template v-else>
      <div class="admin-table">
        <el-table v-loading="loading" :data="records" style="width: 100%">
          <el-table-column label="评价" min-width="260">
            <template #default="{ row }">
              <div class="admin-stack">
                <span class="review-stars" :aria-label="`${row.rating} 星`">
                  <el-icon v-for="n in 5" :key="n">
                    <StarFilled v-if="n <= row.rating" />
                    <Star v-else />
                  </el-icon>
                </span>
                <span class="admin-cell--ellipsis">{{
                  row.content || '（没有文字，只有评分）'
                }}</span>
                <span class="admin-cell--tiny">
                  {{ spuLabel(row) }} · 订单 {{ row.orderId }}
                  <template v-if="row.imageCount > 0"> · {{ row.imageCount }} 张图</template>
                </span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="用户" width="150">
            <template #default="{ row }">
              <div class="admin-stack">
                <span class="admin-cell--tiny">{{ row.userId }}</span>
                <el-tag v-if="row.anonymous" type="info" effect="plain" size="small"
                  >对外匿名</el-tag
                >
              </div>
            </template>
          </el-table-column>

          <el-table-column label="商家回复" min-width="180">
            <template #default="{ row }">
              <div v-if="row.replyContent" class="admin-stack">
                <span class="admin-cell--ellipsis">{{ row.replyContent }}</span>
                <span class="admin-cell--tiny"
                  >{{ row.replyBy }} · {{ formatDateTime(row.replyAt) }}</span
                >
              </div>
              <span v-else class="admin-cell--muted">未回复</span>
            </template>
          </el-table-column>

          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <div class="admin-stack">
                <el-tag :type="statusTagType(row.status)" effect="plain" size="small">
                  {{ statusText(row.status) }}
                </el-tag>
                <span v-if="row.hiddenReason" class="admin-cell--tiny admin-cell--ellipsis">
                  {{ row.hiddenReason }}
                </span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="有用" width="70" align="right">
            <template #default="{ row }">
              <span class="admin-cell--num">{{ row.usefulCount }}</span>
            </template>
          </el-table-column>

          <el-table-column label="时间" width="150">
            <template #default="{ row }">
              <span class="admin-cell--tiny">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">处理</el-button>
            </template>
          </el-table-column>

          <template #empty>
            <p class="admin-empty">没有符合条件的评价</p>
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

    <el-drawer v-model="detailVisible" title="评价处理" size="600px">
      <el-skeleton v-if="detailLoading" :rows="6" animated />

      <ErrorState v-else-if="!detail" message="评价详情没能加载出来" :on-retry="reloadDetail" />

      <div v-else class="admin-detail">
        <h3 class="admin-section__title">
          评价 #{{ detail.review.id }}
          <el-tag :type="statusTagType(detail.review.status)" effect="plain" size="small">
            {{ statusText(detail.review.status) }}
          </el-tag>
        </h3>

        <div class="review-actions">
          <el-button v-if="!isHidden" type="danger" plain :loading="working" @click="hide"
            >隐藏</el-button
          >
          <el-button v-else type="primary" :loading="working" @click="publish">恢复发布</el-button>
          <el-button :loading="working" @click="openReply">
            {{ detail.review.replyContent ? '修改回复' : '回复评价' }}
          </el-button>
        </div>

        <div class="admin-kv">
          <span class="admin-kv__key">评分</span>
          <span class="admin-kv__value review-stars" :aria-label="`${detail.review.rating} 星`">
            <el-icon v-for="n in 5" :key="n">
              <StarFilled v-if="n <= detail.review.rating" />
              <Star v-else />
            </el-icon>
          </span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">用户</span>
          <span class="admin-kv__value">
            {{ detail.review.userId }}
            <el-tag v-if="detail.review.anonymous" type="info" effect="plain" size="small"
              >对外匿名</el-tag
            >
          </span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">商品</span>
          <span class="admin-kv__value">{{ spuLabel(detail.review) }}</span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">订单</span>
          <span class="admin-kv__value">
            #{{ detail.review.orderId }} · 明细 #{{ detail.review.orderItemId }}
          </span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">有用数</span>
          <span class="admin-kv__value">{{ detail.review.usefulCount }}</span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">评价时间</span>
          <span class="admin-kv__value">{{ formatDateTime(detail.review.createdAt) }}</span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">内容</span>
          <span class="admin-kv__value">{{
            detail.review.content || '（没有文字，只有评分）'
          }}</span>
        </div>

        <template v-if="detail.review.hiddenReason">
          <h3 class="admin-section__title">处置记录</h3>
          <div class="admin-kv">
            <span class="admin-kv__key">隐藏原因</span>
            <span class="admin-kv__value">{{ detail.review.hiddenReason }}</span>
          </div>
          <div class="admin-kv">
            <span class="admin-kv__key">操作人</span>
            <span class="admin-kv__value">{{ detail.review.hiddenBy ?? '—' }}</span>
          </div>
          <div class="admin-kv">
            <span class="admin-kv__key">操作时间</span>
            <span class="admin-kv__value">{{ formatDateTime(detail.review.hiddenAt) }}</span>
          </div>
        </template>

        <template v-if="detail.review.replyContent">
          <h3 class="admin-section__title">商家回复</h3>
          <div class="admin-kv">
            <span class="admin-kv__key">内容</span>
            <span class="admin-kv__value">{{ detail.review.replyContent }}</span>
          </div>
          <div class="admin-kv">
            <span class="admin-kv__key">回复人</span>
            <span class="admin-kv__value"
              >{{ detail.review.replyBy ?? '—' }} ·
              {{ formatDateTime(detail.review.replyAt) }}</span
            >
          </div>
        </template>

        <template v-if="detail.images.length > 0">
          <h3 class="admin-section__title">图片</h3>
          <div class="proof">
            <el-image
              v-for="(image, index) in detail.images"
              :key="index"
              class="proof__img"
              :src="image"
              :preview-src-list="detail.images"
              :initial-index="index"
              fit="cover"
              preview-teleported
            />
          </div>
        </template>
      </div>
    </el-drawer>

    <el-dialog v-model="replyDialog.visible" title="回复评价" width="480px">
      <el-input
        v-model="replyDialog.text"
        type="textarea"
        :rows="5"
        maxlength="500"
        show-word-limit
        placeholder="回复会展示在商详页这条评价下面，以商家身份出现"
      />
      <p class="admin-dialog__hint">清空内容再保存等于撤回回复。</p>
      <template #footer>
        <div class="admin-dialog__foot">
          <el-button @click="replyDialog.visible = false">取消</el-button>
          <el-button type="primary" :loading="replyDialog.saving" @click="submitReply"
            >保存</el-button
          >
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.review-stars {
  display: inline-flex;
  gap: 1px;
  color: var(--ys-amber-500, #d99a2b);
}

.review-actions {
  display: flex;
  gap: var(--ys-space-2);
  margin-bottom: var(--ys-space-3);
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
