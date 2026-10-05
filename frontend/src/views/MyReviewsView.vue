<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listMyReviews, ratingText } from '@/api/review'
import { formatPriceRange } from '@/api/product'
import { formatDate } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { MyReview } from '@/types/models'

const router = useRouter()

const items = ref<MyReview[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const failed = ref(false)

const SIZE = 10

/**
 * 状态标签。
 *
 * 被隐藏的评价**照样列出来**，而不是从「我的评价」里抹掉：抹掉的话用户只会以为
 * 自己没发表成功，然后再发一遍——而这一次会被「同一订单行只能评价一次」挡住，
 * 于是他得到的是第二个看不懂的错误。
 */
const STATUS_TEXT: Record<string, string> = {
  PUBLISHED: '已发布',
  PENDING: '审核中',
  HIDDEN: '已隐藏',
}

async function load() {
  loading.value = true
  failed.value = false
  try {
    const result = await listMyReviews({ page: page.value - 1, size: SIZE })
    items.value = result.records
    total.value = result.total
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

function onPageChange(next: number) {
  page.value = next
  load()
}

onMounted(load)
</script>

<template>
  <div class="page my-reviews">
    <header class="page-header">
      <div>
        <p class="eyebrow">My reviews</p>
        <h1>我的评价</h1>
        <p class="my-reviews__hint">共 {{ total }} 条，含审核中与已隐藏的</p>
      </div>
      <el-button @click="router.push('/orders')">去评价未评的订单</el-button>
    </header>

    <ErrorState v-if="failed" :on-retry="load" />

    <template v-else>
      <div v-loading="loading" class="my-reviews__body">
        <el-empty v-if="!items.length && !loading" description="还没有发表过评价">
          <el-button type="primary" @click="router.push('/orders')">查看我的订单</el-button>
        </el-empty>

        <ul v-else class="list">
          <li v-for="item in items" :key="item.id" class="item">
            <!-- 商品区：点得进详情。被删掉的商品没有详情可去，只留文字 -->
            <RouterLink
              v-if="item.product"
              class="item__product"
              :to="`/products/${item.spuId}`"
            >
              <img
                v-if="item.product.mainImage"
                class="item__thumb"
                :src="item.product.mainImage"
                :alt="item.product.name"
                loading="lazy"
              />
              <span v-else class="item__thumb item__thumb--empty" aria-hidden="true"></span>
              <span class="item__product-main">
                <span class="item__name">{{ item.product.name }}</span>
                <span class="item__price">
                  {{ formatPriceRange(item.product.minPrice, item.product.maxPrice) }}
                </span>
              </span>
            </RouterLink>
            <div v-else class="item__product item__product--gone">
              <span class="item__thumb item__thumb--empty" aria-hidden="true"></span>
              <span class="item__product-main">
                <span class="item__name">该商品已被删除</span>
              </span>
            </div>

            <!-- 评价区 -->
            <div class="item__review">
              <header class="item__head">
                <el-rate :model-value="item.rating" disabled size="small" />
                <span class="item__rating-text">{{ ratingText(item.rating) }}</span>
                <el-tag
                  v-if="item.status !== 'PUBLISHED'"
                  size="small"
                  :type="item.status === 'HIDDEN' ? 'info' : 'warning'"
                  effect="plain"
                >
                  {{ STATUS_TEXT[item.status] ?? item.status }}
                </el-tag>
                <span v-if="item.anonymous" class="item__anon">匿名</span>
                <time class="item__time">{{ formatDate(item.createdAt) }}</time>
              </header>

              <p v-if="item.content" class="item__content">{{ item.content }}</p>
              <p v-else class="item__content item__content--empty">（只打了分，没有写内容）</p>

              <div v-if="item.images.length" class="item__images">
                <el-image
                  v-for="url in item.images"
                  :key="url"
                  :src="url"
                  :preview-src-list="item.images"
                  fit="cover"
                  class="item__image"
                />
              </div>

              <!-- 隐藏原因只给作者看。只告诉结果不告诉原因，用户能做的推断只有「平台在捂嘴」 -->
              <p v-if="item.status === 'HIDDEN'" class="item__notice item__notice--hidden">
                这条评价已被隐藏{{ item.hiddenReason ? `：${item.hiddenReason}` : '' }}
              </p>
              <p v-else-if="item.status === 'PENDING'" class="item__notice">
                这条评价正在审核，审核通过后会展示在商品页
              </p>

              <p v-if="item.replyContent" class="item__reply">
                <strong>商家回复：</strong>{{ item.replyContent }}
              </p>
            </div>

            <footer class="item__foot">
              <span class="item__useful">有用 {{ item.usefulCount }}</span>
              <RouterLink class="item__link" :to="`/orders/${item.orderId}`">查看订单</RouterLink>
            </footer>
          </li>
        </ul>
      </div>

      <el-pagination
        v-if="total > SIZE"
        class="my-reviews__pager"
        layout="prev, pager, next, total"
        background
        :total="total"
        :page-size="SIZE"
        :current-page="page"
        @current-change="onPageChange"
      />
    </template>
  </div>
</template>

<style scoped>
.my-reviews__hint {
  margin-top: var(--ys-space-2);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.my-reviews__body {
  min-height: 240px;
}

.list {
  display: grid;
  gap: var(--ys-space-4);
  margin: 0;
  padding: 0;
  list-style: none;
}

.item {
  display: grid;
  gap: var(--ys-space-3);
  padding: var(--ys-space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface);
}

.item__product {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  padding-bottom: var(--ys-space-3);
  border-bottom: 1px solid var(--color-border);
  color: inherit;
  text-decoration: none;
}

a.item__product:hover .item__name {
  color: var(--color-primary-strong);
}

.item__thumb {
  width: 56px;
  height: 56px;
  flex: none;
  object-fit: cover;
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface-muted);
}

.item__thumb--empty {
  display: block;
}

.item__product-main {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.item__name {
  font-weight: 600;
  transition: color var(--ys-duration-fast) var(--ys-ease-out);
}

.item__price {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.item__review {
  display: grid;
  gap: var(--ys-space-2);
}

.item__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ys-space-2);
}

.item__rating-text {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.item__anon {
  padding: 1px 6px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-sm);
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.item__time {
  margin-inline-start: auto;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.item__content {
  line-height: var(--ys-leading-base);
}

.item__content--empty {
  color: var(--color-text-muted);
}

.item__images {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
}

.item__image {
  width: 80px;
  height: 80px;
  border-radius: var(--ys-radius-sm);
}

.item__notice {
  padding: var(--ys-space-2) var(--ys-space-3);
  border-radius: var(--ys-radius-sm);
  background: var(--color-warning-subtle);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.item__notice--hidden {
  background: var(--color-bg-surface-muted);
}

.item__reply {
  padding: var(--ys-space-2) var(--ys-space-3);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface-muted);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.item__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: var(--ys-space-2);
  border-top: 1px solid var(--color-border);
  font-size: var(--ys-font-xs);
}

.item__useful {
  color: var(--color-text-muted);
}

.item__link {
  color: var(--color-primary-strong);
  text-decoration: none;
}

.item__link:hover {
  text-decoration: underline;
}

.my-reviews__pager {
  justify-content: center;
  margin-top: var(--ys-space-4);
}
</style>
