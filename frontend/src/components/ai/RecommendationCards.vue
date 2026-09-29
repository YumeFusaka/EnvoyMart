<script setup lang="ts">
import { formatPriceRange } from '@/api/product'
import type { ProductSummary } from '@/types/models'

/**
 * 与商品列表页同一个类型（`contract.ProductSummary`）。
 * <p>
 * 这里曾经声明成另一套字段（`image` / `salesCopy` / `price`），而后端发的是
 * `mainImage` / `minPrice` / `maxPrice` / `sales` —— 两边没有一个字段对得上，
 * 于是卡片永远渲染成空的。同一个仓库里 `ProductCard.vue` 早就用对了类型，
 * 是「另写一份 DTO」这个做法让它们各错各的。
 */
defineProps<{
  products: ProductSummary[]
}>()

const emit = defineEmits<{
  open: [product: ProductSummary]
}>()
</script>

<template>
  <section class="recommendations" aria-label="推荐商品">
    <h4 class="recommendations__title">推荐商品</h4>

    <div class="recommendation-grid">
      <article
        v-for="product in products"
        :key="product.id"
        class="recommendation-card"
        role="link"
        tabindex="0"
        :aria-label="`查看 ${product.name}`"
        @click="emit('open', product)"
        @keyup.enter="emit('open', product)"
      >
        <div class="recommendation-card__media">
          <img
            v-if="product.mainImage"
            :src="product.mainImage"
            :alt="product.name"
            loading="lazy"
          />
          <span v-else class="recommendation-card__placeholder" aria-hidden="true">无图</span>
        </div>

        <div class="recommendation-card__body">
          <strong class="recommendation-card__name">{{ product.name }}</strong>
          <p v-if="product.brandName" class="recommendation-card__brand">{{ product.brandName }}</p>
          <div class="recommendation-card__foot">
            <span class="recommendation-card__price">
              {{ formatPriceRange(product.minPrice, product.maxPrice) }}
            </span>
            <span class="recommendation-card__sales">已售 {{ product.sales }}</span>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.recommendations {
  margin-top: var(--ys-space-4);
  padding-top: var(--ys-space-4);
  border-top: 1px dashed var(--color-border);
}

.recommendations__title {
  margin: 0 0 var(--ys-space-3);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  font-weight: 600;
}

.recommendation-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--ys-space-3);
}

.recommendation-card {
  display: grid;
  grid-template-columns: 64px 1fr;
  gap: var(--ys-space-3);
  padding: var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
  cursor: pointer;
  transition:
    border-color var(--ys-duration-fast) var(--ys-ease-out),
    transform var(--ys-duration-fast) var(--ys-ease-out);
}

.recommendation-card:hover {
  transform: translateY(-2px);
  border-color: var(--color-primary-border);
}

.recommendation-card:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.recommendation-card__media {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  overflow: hidden;
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-sunken);
}

.recommendation-card__media img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.recommendation-card__placeholder {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.recommendation-card__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.recommendation-card__name {
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-tight);
  /* 两行封顶：商品名长度差异很大，不限高会让卡片高度参差 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.recommendation-card__brand {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.recommendation-card__foot {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ys-space-2);
  margin-top: auto;
}

.recommendation-card__price {
  color: var(--color-primary);
  font-weight: 700;
}

.recommendation-card__sales {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}
</style>
