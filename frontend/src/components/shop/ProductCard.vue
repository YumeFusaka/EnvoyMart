<script setup lang="ts">
import { formatPriceRange } from '@/api/product'
import type { ProductSummary } from '@/types/models'

defineProps<{ product: ProductSummary }>()

/**
 * 卡片整体是一个点击目标，跳详情。
 * <p>
 * 列表页**不放「加入购物车」**：价格与库存都挂在 SKU 上，从列表加购等于
 * 替用户随便挑一个规格。真实电商也是「进详情、选规格、再加购」。
 * <p>
 * 这也顺手消掉了原先那个缺陷：卡片上那个加购按钮的 `@emit('add')` 绕过了
 * 专门做 stopPropagation 的处理函数，点一次会**同时加购并跳走**。
 */
const emit = defineEmits<{ (e: 'open'): void }>()
</script>

<template>
  <article
    class="product-card"
    role="link"
    tabindex="0"
    :aria-label="`查看 ${product.name}`"
    @click="emit('open')"
    @keyup.enter="emit('open')"
  >
    <div class="product-card__media">
      <img
        v-if="product.mainImage"
        :src="product.mainImage"
        :alt="product.name"
        loading="lazy"
      />
      <div v-else class="product-card__placeholder" aria-hidden="true">暂无图片</div>
      <span v-if="product.totalStock === 0" class="product-card__badge">缺货</span>
    </div>

    <div class="product-card__body">
      <p v-if="product.brandName" class="product-card__brand">{{ product.brandName }}</p>
      <h3 class="product-card__name">{{ product.name }}</h3>
      <p v-if="product.subtitle" class="product-card__subtitle">{{ product.subtitle }}</p>

      <ul v-if="product.tags.length" class="product-card__tags">
        <li v-for="tag in product.tags" :key="tag">{{ tag }}</li>
      </ul>

      <div class="product-card__foot">
        <span class="product-card__price">
          {{ formatPriceRange(product.minPrice, product.maxPrice) }}
        </span>
        <span class="product-card__sales">已售 {{ product.sales }}</span>
      </div>
    </div>
  </article>
</template>

<style scoped>
.product-card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--card-radius);
  cursor: pointer;
  transition:
    transform var(--ys-duration-base) var(--ys-ease-out),
    box-shadow var(--ys-duration-base) var(--ys-ease-out),
    border-color var(--ys-duration-base) var(--ys-ease-out);
}

.product-card:hover {
  transform: translateY(-2px);
  border-color: var(--color-primary-border);
  box-shadow: var(--ys-shadow-dropdown);
}

.product-card__media {
  position: relative;
  aspect-ratio: 1 / 1;
  background: var(--color-bg-surface-muted);
}

.product-card__media img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.product-card__placeholder {
  display: grid;
  place-items: center;
  height: 100%;
  color: var(--color-text-muted);
  font-size: var(--ys-font-sm);
}

.product-card__badge {
  position: absolute;
  top: var(--ys-space-2);
  left: var(--ys-space-2);
  padding: 2px 8px;
  border-radius: var(--ys-radius-full);
  background: var(--color-text-secondary);
  color: var(--color-text-inverse);
  font-size: var(--ys-font-xs);
}

.product-card__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: var(--ys-space-1);
  padding: var(--ys-space-3) var(--ys-space-4) var(--ys-space-4);
}

.product-card__brand {
  color: var(--color-primary);
  font-size: var(--ys-font-xs);
  font-weight: 600;
}

.product-card__name {
  font-size: var(--ys-font-base);
  font-weight: 600;
  line-height: var(--ys-leading-tight);
  /* 两行封顶：标题长度差异很大，不限高会让卡片高度参差 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.product-card__subtitle {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  display: -webkit-box;
  -webkit-line-clamp: 1;
  line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.product-card__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin: var(--ys-space-1) 0 0;
  padding: 0;
  list-style: none;
}

.product-card__tags li {
  padding: 1px 6px;
  border: 1px solid var(--color-primary-border);
  border-radius: var(--ys-radius-sm);
  color: var(--color-primary);
  font-size: var(--ys-font-xs);
}

.product-card__foot {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ys-space-2);
  margin-top: auto;
  padding-top: var(--ys-space-2);
}

.product-card__price {
  color: var(--color-primary);
  font-size: var(--ys-font-md);
  font-weight: 700;
}

.product-card__sales {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}
</style>
