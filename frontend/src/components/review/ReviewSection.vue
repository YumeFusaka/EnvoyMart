<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getReviewStatistics, listReviews, markReviewUseful, ratingText } from '@/api/review'
import { formatDate } from '@/utils/format'
import type { Review, ReviewStatistics } from '@/types/models'

const props = defineProps<{ spuId: number }>()

const reviews = ref<Review[]>([])
const statistics = ref<ReviewStatistics | null>(null)
const loading = ref(false)
const filter = ref<'ALL' | 'IMAGE'>('ALL')
/** 已点过「有用」的条目，防止同一会话里重复点 */
const usefulClicked = ref<Set<number>>(new Set())

const filtered = computed(() =>
  filter.value === 'IMAGE' ? reviews.value.filter((item) => item.images.length > 0) : reviews.value,
)

/** 星级分布柱状图的宽度百分比 */
function bucketPercent(count: number): string {
  const total = statistics.value?.total ?? 0
  if (total === 0) {
    return '0%'
  }
  return `${Math.round((count / total) * 100)}%`
}

async function load() {
  loading.value = true
  try {
    const [list, stats] = await Promise.all([
      listReviews(props.spuId),
      getReviewStatistics(props.spuId),
    ])
    reviews.value = list
    statistics.value = stats
  } finally {
    loading.value = false
  }
}

async function handleUseful(review: Review) {
  if (usefulClicked.value.has(review.id)) {
    return
  }
  await markReviewUseful(review.id)
  usefulClicked.value.add(review.id)
  review.usefulCount += 1
  ElMessage.success('感谢反馈')
}

// 商品切换时要重新拉 —— 详情页是同一个组件复用的，不重建
watch(() => props.spuId, load)
onMounted(load)
</script>

<template>
  <section v-loading="loading" class="surface">
    <h2 class="section-title">商品评价</h2>

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

    <div class="filters">
      <button
        type="button"
        class="filters__item"
        :class="{ 'is-active': filter === 'ALL' }"
        @click="filter = 'ALL'"
      >
        全部 {{ reviews.length }}
      </button>
      <button
        type="button"
        class="filters__item"
        :class="{ 'is-active': filter === 'IMAGE' }"
        @click="filter = 'IMAGE'"
      >
        有图 {{ statistics?.withImage ?? 0 }}
      </button>
    </div>

    <el-empty v-if="filtered.length === 0" :description="filter === 'IMAGE' ? '还没有带图的评价' : '还没有评价'" />

    <ul v-else class="list">
      <li v-for="review in filtered" :key="review.id" class="review">
        <header class="review__head">
          <el-avatar :size="32">{{ review.anonymous ? '匿' : '用' }}</el-avatar>
          <div class="review__meta">
            <span class="review__user">{{ review.anonymous ? '匿名用户' : review.userId }}</span>
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
          <el-button
            link
            size="small"
            :disabled="usefulClicked.has(review.id)"
            @click="handleUseful(review)"
          >
            有用（{{ review.usefulCount }}）
          </el-button>
          <span class="review__rating-text">{{ ratingText(review.rating) }}</span>
        </footer>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.section-title {
  margin-bottom: var(--ys-space-4);
  font-size: var(--ys-font-md);
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
  color: var(--color-primary);
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
  gap: var(--ys-space-2);
  margin-bottom: var(--ys-space-4);
}

.filters__item {
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-surface);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  cursor: pointer;
}

.filters__item.is-active {
  border-color: var(--color-primary);
  background: var(--color-primary-subtle);
  color: var(--color-primary);
  font-weight: 600;
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

.review__rating-text {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

@media (max-width: 720px) {
  .summary {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
