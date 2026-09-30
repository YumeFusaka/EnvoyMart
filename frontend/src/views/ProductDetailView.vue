<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { formatPrice, getProductDetail } from '@/api/product'
import ReviewSection from '@/components/review/ReviewSection.vue'
import FavoriteButton from '@/components/shop/FavoriteButton.vue'
import { useCartStore } from '@/stores'
import { ensureLogin } from '@/utils/login'
import type { ProductDetail, SkuView } from '@/types/models'

// 加购必须走 store，不能直接调 API：顶栏角标读的是 store 里的状态，
// 绕过它加购会让角标纹丝不动 —— 用户看到「已加入购物车」却没有任何数量反馈，
// 会怀疑是不是没加上
const cart = useCartStore()

const route = useRoute()
const router = useRouter()

const detail = ref<ProductDetail | null>(null)
const loading = ref(false)
const adding = ref(false)
const quantity = ref(1)
const activeImage = ref(0)

/** specId → specValueId。某一项不在里面就表示还没选 */
const selected = ref<Record<number, number>>({})

const spuId = computed(() => Number(route.params.id))

/**
 * 当前选中的规格组合对应的 SKU。
 * <p>
 * 规格必须**全部选完**才算确定：少选一项时可能有多个 SKU 都满足已知条件，
 * 这时给出价格是猜的。没选完就只显示价格区间。
 */
const currentSku = computed<SkuView | null>(() => {
  const product = detail.value
  if (!product || product.skus.length === 0) {
    return null
  }
  if (product.specs.length === 0) {
    // 无规格商品：整件商品就是一个 SKU
    return product.skus[0] ?? null
  }
  const chosen = Object.values(selected.value)
  if (chosen.length !== product.specs.length) {
    return null
  }
  return product.skus.find((sku) => chosen.every((id) => sku.specValueIds.includes(id))) ?? null
})

const gallery = computed(() => {
  const product = detail.value
  if (!product) {
    return []
  }
  const images = product.images.length
    ? product.images
    : product.mainImage
      ? [product.mainImage]
      : []
  return images
})

const displayPrice = computed(() => {
  const product = detail.value
  if (!product) {
    return '—'
  }
  if (currentSku.value) {
    return formatPrice(currentSku.value.price)
  }
  const prices = product.skus.map((sku) => sku.price)
  if (prices.length === 0) {
    return '—'
  }
  const min = Math.min(...prices)
  const max = Math.max(...prices)
  return min === max ? formatPrice(min) : `${formatPrice(min)} ~ ${formatPrice(max)}`
})

const displayStock = computed(() => currentSku.value?.stock ?? null)

/**
 * 某个规格值在当前选择下是否可选。
 * <p>
 * 判据是「固定其它已选项，是否还存在同时满足这些条件且**有货**的 SKU」。
 * 不可选的值置灰，用户就不会点完才发现「该组合缺货」——
 * 那是把选择的成本转嫁给了用户。
 */
function isValueAvailable(specId: number, valueId: number): boolean {
  const product = detail.value
  if (!product) {
    return false
  }
  const otherChosen = Object.entries(selected.value)
    .filter(([sid]) => Number(sid) !== specId)
    .map(([, vid]) => vid)

  return product.skus.some(
    (sku) =>
      sku.stock > 0 &&
      sku.specValueIds.includes(valueId) &&
      otherChosen.every((id) => sku.specValueIds.includes(id)),
  )
}

function pickSpecValue(specId: number, valueId: number) {
  if (selected.value[specId] === valueId) {
    // 再点一次已选中的值 = 取消，方便用户改主意
    delete selected.value[specId]
    return
  }
  selected.value[specId] = valueId
  quantity.value = 1
}

async function load() {
  loading.value = true
  selected.value = {}
  activeImage.value = 0
  quantity.value = 1
  try {
    detail.value = await getProductDetail(spuId.value)
  } finally {
    loading.value = false
  }
}

async function handleAddToCart() {
  // 未登录先引导登录，别让用户选完规格才被 401 顶回来
  if (!ensureLogin('登录后即可加入购物车')) {
    return
  }
  const sku = currentSku.value
  if (!sku) {
    ElMessage.warning('请先选择商品规格')
    return
  }
  if (sku.stock <= 0) {
    ElMessage.warning('该规格暂时缺货')
    return
  }
  adding.value = true
  try {
    await cart.add(sku.id, quantity.value)
    ElMessage.success('已加入购物车')
  } finally {
    adding.value = false
  }
}

// 直接改地址栏 id 时也要重新拉数据（路由复用同一个组件，不会自动重建）
watch(spuId, load)

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page detail">
    <template v-if="detail">
      <nav class="crumb">
        <el-button link @click="router.back()">← 返回</el-button>
        <span class="crumb__path">
          {{ detail.categoryName || '未分类' }}
          <template v-if="detail.brandName"> / {{ detail.brandName }}</template>
        </span>
      </nav>

      <div class="detail__top">
        <div class="detail__gallery surface">
          <img
            v-if="gallery.length"
            :src="gallery[activeImage]"
            :alt="detail.name"
            class="detail__hero"
          />
          <div v-else class="detail__hero detail__hero--empty">暂无图片</div>

          <ul v-if="gallery.length > 1" class="thumbs">
            <li v-for="(img, index) in gallery" :key="img">
              <button
                type="button"
                class="thumbs__item"
                :class="{ 'is-active': index === activeImage }"
                @click="activeImage = index"
              >
                <img :src="img" alt="" />
              </button>
            </li>
          </ul>
        </div>

        <div class="detail__info surface">
          <h1 class="detail__name">{{ detail.name }}</h1>
          <p v-if="detail.subtitle" class="subcopy">{{ detail.subtitle }}</p>

          <ul v-if="detail.tags.length" class="detail__tags">
            <li v-for="tag in detail.tags" :key="tag">{{ tag }}</li>
          </ul>

          <div class="price-box">
            <span class="price-box__value">{{ displayPrice }}</span>
            <span v-if="displayStock !== null" class="price-box__stock">
              {{ displayStock > 0 ? `库存 ${displayStock} 件` : '暂时缺货' }}
            </span>
          </div>

          <div v-for="spec in detail.specs" :key="spec.specId" class="spec">
            <p class="spec__name">{{ spec.name }}</p>
            <div class="spec__values">
              <button
                v-for="value in spec.values"
                :key="value.id"
                type="button"
                class="spec__value"
                :class="{
                  'is-active': selected[spec.specId] === value.id,
                  'is-disabled': !isValueAvailable(spec.specId, value.id),
                }"
                :disabled="!isValueAvailable(spec.specId, value.id)"
                @click="pickSpecValue(spec.specId, value.id)"
              >
                {{ value.value }}
              </button>
            </div>
          </div>

          <div class="buy">
            <el-input-number v-model="quantity" :min="1" :max="Math.max(displayStock ?? 1, 1)" />
            <el-button
              type="primary"
              size="large"
              :loading="adding"
              :disabled="!!detail.specs.length && !currentSku"
              @click="handleAddToCart"
            >
              加入购物车
            </el-button>

            <FavoriteButton :spu-id="detail.id" :label="detail.name" show-text />
          </div>

          <p class="detail__meta">
            已售 {{ detail.sales }}
            <template v-if="detail.reviewCount">
              · {{ detail.reviewCount }} 条评价
              <template v-if="detail.ratingAvg">（{{ detail.ratingAvg.toFixed(1) }} 分）</template>
            </template>
          </p>
        </div>
      </div>

      <section v-if="detail.attributes.length" class="surface">
        <h2 class="section-title">商品参数</h2>
        <el-descriptions :column="2" border>
          <el-descriptions-item
            v-for="attr in detail.attributes"
            :key="attr.attributeId"
            :label="attr.name"
          >
            {{ attr.value }}{{ attr.unit ? ` ${attr.unit}` : '' }}
          </el-descriptions-item>
        </el-descriptions>
      </section>

      <!--
        图谱入口。放在商品参数之后、评价之前 —— 它是「这个商品本身是什么」的一部分，
        而不是社区内容。措辞刻意留了后路：图谱里没有收录本商品时页面会如实说明，
        所以这里不能承诺「一定能查到什么」，否则点进去看到的是一句否定回答
      -->
      <section class="surface knowledge-entry">
        <div>
          <h2 class="section-title">成分与相互作用</h2>
          <p class="knowledge-entry__desc">
            这件商品含什么成分、提供哪些营养素、与常见药物是否有相互作用，都已整理进关系图谱。
            图上每条连线都能点回说明书原文，可以自己核对。
          </p>
        </div>
        <RouterLink
          class="knowledge-entry__link"
          :to="{ path: '/knowledge/graph', query: { root: `SPU${detail.id}` } }"
        >
          查看关系图谱
          <span aria-hidden="true">→</span>
        </RouterLink>
      </section>

      <ReviewSection :spu-id="detail.id" />

      <section v-if="detail.detailHtml" class="surface">
        <h2 class="section-title">商品详情</h2>
        <!-- 详情正文来自后台维护的商品描述。当前没有开放给外部录入的入口，
             且管理端尚未落地，因此这里直接渲染；管理端上线前必须补上服务端净化 -->
        <div class="detail__html" v-html="detail.detailHtml"></div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.crumb {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
}

.crumb__path {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.knowledge-entry {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-4);
}

.knowledge-entry__desc {
  max-width: 62ch;
  margin-top: var(--ys-space-2);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
}

.knowledge-entry__link {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-2);
  padding: var(--ys-space-2) var(--ys-space-4);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-primary-subtle);
  color: var(--color-primary);
  font-size: var(--ys-font-sm);
  font-weight: 500;
  transition: border-color var(--ys-duration-fast) var(--ys-ease-out);
}

.knowledge-entry__link:hover {
  border-color: var(--color-primary);
}

.knowledge-entry__link:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.detail__top {
  display: grid;
  grid-template-columns: minmax(0, 420px) minmax(0, 1fr);
  gap: var(--ys-space-6);
  align-items: start;
}

.detail__hero {
  width: 100%;
  aspect-ratio: 1 / 1;
  border-radius: var(--ys-radius-md);
  object-fit: cover;
  background: var(--color-bg-surface-muted);
}

.detail__hero--empty {
  display: grid;
  place-items: center;
  color: var(--color-text-muted);
}

.thumbs {
  display: flex;
  gap: var(--ys-space-2);
  margin: var(--ys-space-3) 0 0;
  padding: 0;
  list-style: none;
}

.thumbs__item {
  width: 56px;
  height: 56px;
  padding: 2px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-sm);
  background: transparent;
  cursor: pointer;
}

.thumbs__item.is-active {
  border-color: var(--color-primary);
}

.thumbs__item img {
  width: 100%;
  height: 100%;
  border-radius: 4px;
  object-fit: cover;
}

.detail__info {
  display: grid;
  gap: var(--ys-space-4);
}

.detail__name {
  font-size: var(--ys-font-xl);
}

.detail__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.detail__tags li {
  padding: 2px 8px;
  border: 1px solid var(--color-primary-border);
  border-radius: var(--ys-radius-sm);
  color: var(--color-primary);
  font-size: var(--ys-font-xs);
}

.price-box {
  display: flex;
  align-items: baseline;
  gap: var(--ys-space-3);
  padding: var(--ys-space-4);
  border-radius: var(--ys-radius-md);
  background: var(--color-primary-subtle);
}

.price-box__value {
  color: var(--color-primary);
  font-size: var(--ys-font-2xl);
  font-weight: 700;
}

.price-box__stock {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.spec__name {
  margin-bottom: var(--ys-space-2);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.spec__values {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
}

.spec__value {
  padding: 6px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface);
  color: var(--color-text-primary);
  font-size: var(--ys-font-sm);
  cursor: pointer;
  transition:
    border-color var(--ys-duration-fast) var(--ys-ease-out),
    color var(--ys-duration-fast) var(--ys-ease-out);
}

.spec__value:hover:not(.is-disabled) {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.spec__value.is-active {
  border-color: var(--color-primary);
  background: var(--color-primary-subtle);
  color: var(--color-primary);
  font-weight: 600;
}

.spec__value.is-disabled {
  color: var(--color-text-muted);
  cursor: not-allowed;
  /* 加删除线而不是只置灰：置灰在两套色温接近时几乎看不出来 */
  text-decoration: line-through;
}

.buy {
  display: flex;
  gap: var(--ys-space-3);
  align-items: center;
}

.buy .el-button {
  flex: 1;
}

.detail__meta {
  color: var(--color-text-muted);
  font-size: var(--ys-font-sm);
}

.section-title {
  margin-bottom: var(--ys-space-4);
  font-size: var(--ys-font-md);
}

.detail__html :deep(img) {
  max-width: 100%;
}

@media (max-width: 900px) {
  .detail__top {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
