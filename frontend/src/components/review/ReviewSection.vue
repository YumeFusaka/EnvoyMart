<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getReviewStatistics, listReviews, markReviewUseful, ratingText } from '@/api/review'
import { formatDate } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import { ensureLogin } from '@/utils/login'
import type { Review, ReviewStatistics } from '@/types/models'

const props = defineProps<{ spuId: number }>()

const PAGE_SIZE = 10

const reviews = ref<Review[]>([])
const total = ref(0)
const statistics = ref<ReviewStatistics | null>(null)
const loading = ref(false)
const failed = ref(false)

/** 当前页，1 起（Element 分页器与后端零基页码的换算只在这一处做） */
const page = ref(1)
/** 星级筛选，null = 不筛 */
const rating = ref<number | null>(null)
const hasImage = ref(false)

/** 已点过「有用」的条目，防止同一会话里重复点（服务端也拦，这里只是不让他白点一次） */
const usefulClicked = ref<Set<number>>(new Set())

const hasFilter = computed(() => rating.value !== null || hasImage.value)

/**
 * 星级筛选项。
 *
 * 沿用「全部 / 有图 / 5星…1星」这一排，是电商评价区通行的样子：
 * 用户来这里最想做的两件事就是「看看差评怎么说」和「看看实物图」。
 */
const ratingOptions = [5, 4, 3, 2, 1]

/** 星级分布柱状图的宽度百分比 */
function bucketPercent(count: number): string {
  const sum = statistics.value?.total ?? 0
  if (sum === 0) {
    return '0%'
  }
  return `${Math.round((count / sum) * 100)}%`
}

/** 切换筛选一律回到第一页：停在第 3 页去筛「1 星」多半是空的，用户会以为没有差评 */
function applyFilter(next: { rating?: number | null; hasImage?: boolean }) {
  if (next.rating !== undefined) {
    rating.value = next.rating
  }
  if (next.hasImage !== undefined) {
    hasImage.value = next.hasImage
  }
  page.value = 1
  loadList()
}

async function loadList() {
  loading.value = true
  failed.value = false
  try {
    const result = await listReviews(props.spuId, {
      rating: rating.value ?? undefined,
      hasImage: hasImage.value || undefined,
      page: page.value - 1,
      size: PAGE_SIZE,
    })
    reviews.value = result.records
    total.value = result.total
  } catch {
    // 拉不到与「没有评价」必须分开：静默成空列表，用户读到的是一句
    //「这件商品还没人评价过」——那是界面在替服务端下一个它不知道的结论
    failed.value = true
  } finally {
    loading.value = false
  }
}

/** 统计与筛选、翻页无关，只在商品变化时拉一次 */
async function loadStatistics() {
  try {
    statistics.value = await getReviewStatistics(props.spuId)
  } catch {
    // 统计拉不到不阻塞列表：评价正文才是主体，星级分布是补充
    statistics.value = null
  }
}

function reload() {
  loadStatistics()
  loadList()
}

async function handleUseful(review: Review) {
  if (usefulClicked.value.has(review.id)) {
    return
  }
  // 商品详情对游客开放，而「有用」是一票一次的用户行为，未登录先引导登录
  if (!ensureLogin('登录后即可标记有用')) {
    return
  }
  try {
    await markReviewUseful(review.id)
    // 换一个新的 Set 而不是原地 add：Set 在 ref 里是同一个引用，
    // 原地改不会触发视图更新，按钮看起来没反应
    usefulClicked.value = new Set(usefulClicked.value).add(review.id)
    review.usefulCount += 1
    ElMessage.success('感谢反馈')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败，请稍后再试')
  }
}

// 商品切换时要重新拉 —— 详情页是同一个组件复用的，不重建
watch(
  () => props.spuId,
  () => {
    page.value = 1
    rating.value = null
    hasImage.value = false
    reload()
  },
)
onMounted(reload)
</script>

<template>
  <section class="surface">
    <header class="head">
      <h2 class="section-title">商品评价</h2>
      <span v-if="statistics" class="head__total">共 {{ statistics.total }} 条</span>
    </header>

    <div v-if="statistics && statistics.total > 0" class="summary">
      <div class="summary__score">
        <span class="summary__average">{{ statistics.average.toFixed(1) }}</span>
        <el-rate :model-value="statistics.average" disabled allow-half />
        <span class="summary__count">{{ statistics.total }} 条评价</span>
      </div>

      <ul class="summary__bars">
        <li v-for="(count, index) in statistics.distribution.slice().reverse()" :key="index">
          <span class="summary__star">{{ 5 - index }} 星</span>
          <span class="summary__bar">
            <span class="summary__bar-fill" :style="{ width: bucketPercent(count) }"></span>
          </span>
          <span class="summary__num">{{ count }}</span>
        </li>
      </ul>
    </div>

    <div class="filters" role="group" aria-label="评价筛选">
      <button
        type="button"
        class="filters__item"
        :class="{ 'is-active': !hasFilter }"
        @click="applyFilter({ rating: null, hasImage: false })"
      >
        全部 {{ statistics?.total ?? 0 }}
      </button>
      <button
        type="button"
        class="filters__item"
        :class="{ 'is-active': hasImage }"
        @click="applyFilter({ rating: null, hasImage: true })"
      >
        有图 {{ statistics?.withImage ?? 0 }}
      </button>
      <button
        v-for="star in ratingOptions"
        :key="star"
        type="button"
        class="filters__item"
        :class="{ 'is-active': rating === star }"
        @click="applyFilter({ rating: star, hasImage: false })"
      >
        {{ star }} 星
      </button>
    </div>

    <ErrorState v-if="failed" :on-retry="reload" />

    <div v-else v-loading="loading" class="body">
      <el-empty v-if="reviews.length === 0" :description="hasFilter ? '没有符合条件的评价' : '还没有评价'">
        <el-button v-if="hasFilter" link type="primary" @click="applyFilter({ rating: null, hasImage: false })">
          清除筛选
        </el-button>
      </el-empty>

      <ul v-else class="list">
        <li v-for="review in reviews" :key="review.id" class="review">
          <header class="review__head">
            <el-avatar :size="32" class="review__avatar">
              {{ review.anonymous ? '匿' : (review.nickname ?? '用').slice(0, 1) }}
            </el-avatar>
            <div class="review__meta">
              <span class="review__user">{{ review.anonymous ? '匿名用户' : review.nickname }}</span>
              <span class="review__time">{{ formatDate(review.createdAt) }}</span>
            </div>
            <el-rate :model-value="review.rating" disabled size="small" />
          </header>

          <p v-if="review.content" class="review__content">{{ review.content }}</p>

          <div v-if="review.images.length" class="review__images">
            <el-image
              v-for="url in review.images"
              :key="url"
              :src="url"
              :preview-src-list="review.images"
              fit="cover"
              class="review__image"
            />
          </div>

          <p v-if="review.replyContent" class="review__reply">
            <strong>商家回复：</strong>{{ review.replyContent }}
          </p>

          <footer class="review__foot">
            <button
              type="button"
              class="review__useful"
              :class="{ 'is-done': usefulClicked.has(review.id) }"
              :disabled="usefulClicked.has(review.id)"
              @click="handleUseful(review)"
            >
              有用 {{ review.usefulCount }}
            </button>
            <span class="review__rating-text">{{ ratingText(review.rating) }}</span>
          </footer>
        </li>
      </ul>

      <el-pagination
        v-if="total > PAGE_SIZE"
        class="pager"
        layout="prev, pager, next"
        background
        :current-page="page"
        :page-size="PAGE_SIZE"
        :total="total"
        hide-on-single-page
        @current-change="(next: number) => { page = next; loadList() }"
      />
    </div>
  </section>
</template>

<style scoped>
.head {
  display: flex;
  align-items: baseline;
  gap: var(--ys-space-3);
  margin-bottom: var(--ys-space-4);
}

.section-title {
  font-size: var(--ys-font-md);
}

.head__total {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.summary {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  gap: var(--ys-space-6);
  padding-bottom: var(--ys-space-4);
  margin-bottom: var(--ys-space-4);
  border-bottom: 1px solid var(--color-border);
}

.summary__score {
  display: grid;
  justify-items: center;
  gap: var(--ys-space-1);
}

.summary__average {
  color: var(--color-primary-strong);
  font-size: var(--ys-font-3xl);
  font-weight: 700;
  line-height: 1;
}

.summary__count {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.summary__bars {
  display: grid;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.summary__bars li {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) 32px;
  gap: var(--ys-space-2);
  align-items: center;
  font-size: var(--ys-font-xs);
  color: var(--color-text-secondary);
}

.summary__bar {
  height: 6px;
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-sunken);
  overflow: hidden;
}

.summary__bar-fill {
  display: block;
  height: 100%;
  background: var(--color-primary);
}

.summary__num {
  text-align: right;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
  margin-bottom: var(--ys-space-4);
}

.filters__item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-surface);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  cursor: pointer;
  transition:
    border-color var(--ys-duration-fast) var(--ys-ease-out),
    color var(--ys-duration-fast) var(--ys-ease-out);
}

.filters__item:hover {
  border-color: var(--color-primary-border);
  color: var(--color-primary-strong);
}

.filters__item:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.filters__item.is-active {
  border-color: var(--color-primary);
  background: var(--color-primary-subtle);
  color: var(--color-primary-strong);
  font-weight: 600;
}

/* 加载中也要保一个最小高度：否则每次翻页整块塌下去，页面跟着上下跳 */
.body {
  min-height: 120px;
}

.list {
  display: grid;
  gap: var(--ys-space-4);
  margin: 0;
  padding: 0;
  list-style: none;
}

.review {
  display: grid;
  gap: var(--ys-space-2);
  padding-bottom: var(--ys-space-4);
  border-bottom: 1px solid var(--color-border);
}

.review:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}

.review__head {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
}

.review__avatar {
  background: var(--color-primary-subtle);
  color: var(--color-primary-strong);
  font-size: var(--ys-font-sm);
}

.review__meta {
  display: grid;
  flex: 1;
}

.review__user {
  font-size: var(--ys-font-sm);
  font-weight: 600;
}

.review__time {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.review__content {
  line-height: var(--ys-leading-base);
}

.review__images {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
}

.review__image {
  width: 88px;
  height: 88px;
  border-radius: var(--ys-radius-sm);
}

.review__reply {
  padding: var(--ys-space-2) var(--ys-space-3);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface-muted);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.review__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.review__useful {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  /* 点击区下限 24×24（WCAG 2.2 AA）。原先 2px 上下内边距只有 22px 高，
     在触屏与轨迹板上都偏难点中；这里用 min-height 撑到 24 而不是加内边距，
     免得把这一排药丸撑得比旁边的标签高出一截 */
  min-height: 24px;
  padding: 2px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  cursor: pointer;
}

.review__useful:hover:not(:disabled) {
  border-color: var(--color-primary-border);
  color: var(--color-primary-strong);
}

.review__useful:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.review__useful.is-done {
  border-color: var(--color-primary-border);
  background: var(--color-primary-subtle);
  color: var(--color-primary-strong);
  cursor: default;
}

.review__rating-text {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.pager {
  justify-content: center;
  margin-top: var(--ys-space-4);
}

@media (max-width: 720px) {
  .summary {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
