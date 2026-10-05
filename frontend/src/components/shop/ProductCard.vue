<script setup lang="ts">
import { StarFilled } from '@element-plus/icons-vue'
import { formatPriceRange } from '@/api/product'
import FavoriteButton from '@/components/shop/FavoriteButton.vue'
import type { ProductSummary } from '@/types/models'

/** 心形按钮的状态变化透传出去，收藏夹页据此移除条目。卡片本身不关心这件事 */
const emit = defineEmits<{ (e: 'favorite-change', favorited: boolean): void }>()

withDefaults(
  defineProps<{
    product: ProductSummary
    /**
     * 商品已下架（收藏夹里会用到）。
     * <p>
     * 下架商品**照样渲染**，只是标出来、不给链接：详情接口对下架商品返回「不存在」，
     * 留一个点进去就报错的链接，比不给链接更糟。也从列表里抹掉更糟——用户会以为收藏丢了。
     */
    unavailable?: boolean
  }>(),
  { unavailable: false },
)

/**
 * 点击区由标题链接的 `::after` 撑满整张卡（"stretched link"），而不是给卡片挂 click 事件。
 * <p>
 * 这样整卡可点、键盘可聚焦、浏览器能预览链接地址，都不需要手写 `role`/`tabindex`/回车监听；
 * 心形按钮只要抬到覆盖层之上，就天然不会连带触发跳转——原先那种「按钮上补一个
 * stopPropagation」的写法，漏一次就会同时收藏并跳走。
 * <p>
 * 列表页**不放「加入购物车」**：价格与库存都挂在 SKU 上，从列表加购等于替用户随便挑一个规格。
 */
</script>

<template>
  <article class="product-card" :class="{ 'is-unavailable': unavailable }">
    <div class="product-card__media">
      <img v-if="product.mainImage" :src="product.mainImage" :alt="product.name" loading="lazy" />
      <div v-else class="product-card__placeholder" aria-hidden="true">暂无图片</div>

      <span v-if="unavailable" class="product-card__badge product-card__badge--off">已下架</span>
      <span v-else-if="product.totalStock === 0" class="product-card__badge">缺货</span>

      <FavoriteButton
        class="product-card__fav"
        :spu-id="product.id"
        :label="product.name"
        small
        @change="emit('favorite-change', $event)"
      />
    </div>

    <div class="product-card__body">
      <p v-if="product.brandName" class="product-card__brand">{{ product.brandName }}</p>
      <h3 class="product-card__name">
        <RouterLink v-if="!unavailable" class="product-card__link" :to="`/products/${product.id}`">
          {{ product.name }}
        </RouterLink>
        <template v-else>{{ product.name }}</template>
      </h3>
      <p v-if="product.subtitle" class="product-card__subtitle">{{ product.subtitle }}</p>

      <!--
        评分与销量是电商卡片上的一等信息，缺一个都会让人觉得「这个平台是不是没人买」。
        没人评价过时整行不出现，而不是显示 0.0 分 —— 那会被读成「评价极差」。
      -->
      <p v-if="product.reviewCount" class="product-card__rating">
        <el-icon class="product-card__rating-star"><StarFilled /></el-icon>
        <span class="product-card__rating-value">{{ (product.ratingAvg ?? 0).toFixed(1) }}</span>
        <span class="product-card__rating-count">{{ product.reviewCount }} 条评价</span>
      </p>

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
  position: relative;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--card-radius);
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

/* 下架商品不跟着「浮起来」：它没有可去的页面，浮起+阴影是在暗示可点 */
.product-card.is-unavailable:hover {
  transform: none;
  border-color: var(--color-border);
  box-shadow: var(--ys-shadow-none);
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

.product-card.is-unavailable .product-card__media img {
  /* 下架商品褪色，与角标一起把「不能买」这件事说两遍。
     不用 opacity 0.4 那种程度：商品图仍是识别它是什么的主要线索 */
  filter: grayscale(0.7);
  opacity: 0.75;
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

.product-card__badge--off {
  background: var(--color-text-muted);
}

.product-card__fav {
  position: absolute;
  top: var(--ys-space-2);
  right: var(--ys-space-2);
  /* 抬到 stretched link 的覆盖层之上，否则点心形会顺带跳详情 */
  z-index: var(--ys-z-raised);
}

.product-card__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: var(--ys-space-1);
  padding: var(--ys-space-3) var(--ys-space-4) var(--ys-space-4);
}

.product-card__brand {
  color: var(--color-primary-strong);
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

.product-card__link {
  color: inherit;
  text-decoration: none;
}

.product-card__link:focus-visible {
  outline: none;
  border-radius: var(--ys-radius-sm);
  box-shadow: var(--focus-ring);
}

/* 撑满整卡的可点区。放在链接上而不是卡片的 click 上：语义、键盘、右键菜单全部免费 */
.product-card__link::after {
  content: '';
  position: absolute;
  inset: 0;
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

.product-card__rating {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: var(--ys-space-1);
  font-size: var(--ys-font-xs);
  line-height: 1.4;
}

.product-card__rating-star {
  color: var(--color-warning-strong);
}

.product-card__rating-value {
  color: var(--color-text-secondary);
  font-weight: 600;
  /* 数字等宽：评分位数不同时，「条评价」不会跟着左右晃 */
  font-variant-numeric: tabular-nums;
}

.product-card__rating-count {
  color: var(--color-text-muted);
}

.product-card__tags li {
  padding: 1px 6px;
  border: 1px solid var(--color-primary-border);
  border-radius: var(--ys-radius-sm);
  color: var(--color-primary-strong);
  font-size: var(--ys-font-xs);
}

.product-card__foot {
  display: flex;
  /* 价格区间长（"¥268.00 ~ ¥498.00"）时让它自己占一行，而不是把「已售」挤到价格中间去。
     nowrap + wrap 的组合：放得下就并排，放不下就换行，不需要知道卡片有多宽 */
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 2px var(--ys-space-2);
  margin-top: auto;
  padding-top: var(--ys-space-2);
}

.product-card__price {
  color: var(--color-primary-strong);
  font-size: var(--ys-font-md);
  font-weight: 700;
  white-space: nowrap;
}

.product-card__sales {
  /* 换行后独占一行时靠右，与并排时的位置一致 */
  margin-inline-start: auto;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  white-space: nowrap;
}
</style>
