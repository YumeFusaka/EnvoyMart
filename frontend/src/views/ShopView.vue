<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Close, Search } from '@element-plus/icons-vue'
import { getBrands, getCategoryTree, listProducts, searchProducts } from '@/api/product'
import ErrorState from '@/components/ui/ErrorState.vue'
import ShopProductCard from '@/components/shop/ProductCard.vue'
import type { BrandView, CategoryNode, ProductSummary } from '@/types/models'

const route = useRoute()
const router = useRouter()

const PAGE_SIZE = 12
const SORTS = ['relevance', 'sales', 'price_asc', 'price_desc', 'newest']

const categories = ref<CategoryNode[]>([])
const brands = ref<BrandView[]>([])
const products = ref<ProductSummary[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')
/** 请求序号：连点筛选时，先发的慢响应不许覆盖后发的 */
let loadSeq = 0

const sortOptions = [
  { value: 'relevance', label: '综合排序' },
  { value: 'sales', label: '销量优先' },
  { value: 'price_asc', label: '价格从低到高' },
  { value: 'price_desc', label: '价格从高到低' },
  { value: 'newest', label: '最新上架' },
]

/**
 * URL 是筛选条件的**唯一事实来源**，这一层只负责把它读成好用的形状。
 *
 * 不另开一份本地状态：两份状态并存时，「刷新后筛选丢了」「后退回不到上一个筛选」
 * 「链接发给别人打开是另一批商品」这三件事会各自需要一个同步补丁，而补丁总有漏的。
 * 所有改动都走 {@link pushQuery} 写回 URL，再由 URL 驱动重新查询，链路只有一条。
 *
 * 单位约定：URL 上是**元**（人看的、可分享的），接口是**分**（库里存的）。
 * 换算是这一层的事，两个方向的入口各只有一个。
 */
const filters = computed(() => {
  const raw = (key: string) => {
    const value = route.query[key]
    return typeof value === 'string' ? value : undefined
  }
  const num = (key: string) => {
    const value = Number(raw(key))
    return Number.isFinite(value) && value > 0 ? value : undefined
  }
  const yuan = (key: string) => {
    const value = Number(raw(key))
    return Number.isFinite(value) && value > 0 ? Math.round(value * 100) : undefined
  }
  const sort = raw('sort')
  return {
    keyword: raw('keyword')?.trim() || undefined,
    categoryId: num('categoryId'),
    brandId: num('brandId'),
    minPrice: yuan('minPrice'),
    maxPrice: yuan('maxPrice'),
    sort: sort && SORTS.includes(sort) ? sort : 'relevance',
    /** URL 上的页码从 1 开始（人看的东西按人的习惯写），接口那边再减 1 */
    page: Math.max(1, Math.trunc(Number(raw('page')) || 1)),
  }
})

type Filters = typeof filters.value

/** 把「分」写回 URL 上的「元」，整数不拖小数点：?minPrice=100 比 ?minPrice=100.00 顺眼 */
function toYuan(cents: number | undefined) {
  if (!cents) {
    return undefined
  }
  return String(cents / 100)
}

/**
 * 写回 URL。默认回到第一页 —— 换了筛选条件还停在第 3 页，
 * 结果只剩 1 页时用户看到的是一片空白。
 */
function pushQuery(patch: Partial<Filters>, options: { keepPage?: boolean } = {}) {
  const next = { ...filters.value, ...patch }
  const query: Record<string, string> = {}
  if (next.keyword) query.keyword = next.keyword
  if (next.categoryId) query.categoryId = String(next.categoryId)
  if (next.brandId) query.brandId = String(next.brandId)
  if (next.minPrice) query.minPrice = toYuan(next.minPrice)!
  if (next.maxPrice) query.maxPrice = toYuan(next.maxPrice)!
  // 默认排序与第一页都不写进 URL：链接短一点，也让「有没有筛过」一眼可辨
  if (next.sort !== 'relevance') query.sort = next.sort
  const page = options.keepPage ? next.page : 1
  if (page > 1) query.page = String(page)
  // push 而不是 replace：浏览器后退要能退回上一个筛选，这是电商列表页的默认预期
  router.push({ path: '/shop', query })
}

async function load() {
  const seq = ++loadSeq
  const current = filters.value
  loading.value = true
  loadError.value = ''
  try {
    // 有关键词走 ES 全文检索，没有就走库内查询。
    // 分工：全文检索能命中详情与参数里的词（「胶囊」也能搜到），
    // 库内查询能走索引做结构化筛选，翻页到深页也不会撞 ES 的 max_result_window
    const fetcher = current.keyword ? searchProducts : listProducts
    const result = await fetcher({
      keyword: current.keyword,
      categoryId: current.categoryId,
      brandId: current.brandId,
      minPrice: current.minPrice,
      maxPrice: current.maxPrice,
      sort: current.sort,
      page: current.page - 1,
      size: PAGE_SIZE,
    })
    if (seq !== loadSeq) {
      return
    }
    products.value = result.records
    total.value = result.total
  } catch (error) {
    if (seq !== loadSeq) {
      return
    }
    products.value = []
    total.value = 0
    loadError.value = error instanceof Error ? error.message : '加载失败'
  } finally {
    if (seq === loadSeq) {
      loading.value = false
    }
  }
}

/** 再点一次已选中的项 = 取消筛选 */
function pickCategory(id: number) {
  pushQuery({ categoryId: filters.value.categoryId === id ? undefined : id })
}

function pickBrand(id: number) {
  pushQuery({ brandId: filters.value.brandId === id ? undefined : id })
}

function onPageChange(page: number) {
  pushQuery({ page }, { keepPage: true })
}

// ==================== 价格区间 ====================

const priceDraft = reactive({ min: '', max: '' })

// 草稿跟着 URL 走：后退、或者从别人发来的链接进来，输入框里得是**当前生效**的价钱，
// 否则用户会以为筛选没生效，再点一次「确定」才发现自己把价格重置了
watch(
  () => [filters.value.minPrice, filters.value.maxPrice] as const,
  ([min, max]) => {
    priceDraft.min = min ? String(min / 100) : ''
    priceDraft.max = max ? String(max / 100) : ''
  },
  { immediate: true },
)

function applyPrice() {
  const parse = (value: string) => {
    const number = Number(value.trim())
    return Number.isFinite(number) && number > 0 ? Math.round(number * 100) : undefined
  }
  let min = parse(priceDraft.min)
  let max = parse(priceDraft.max)
  // 填反了就换过来，而不是报错：用户的意思很清楚，只是两个框填颠倒了
  if (min && max && min > max) {
    ;[min, max] = [max, min]
  }
  if (min === filters.value.minPrice && max === filters.value.maxPrice) {
    return
  }
  pushQuery({ minPrice: min, maxPrice: max })
}

const priceLabel = computed(() => {
  const { minPrice, maxPrice } = filters.value
  const yuan = (cents: number) => `¥${cents / 100}`
  if (minPrice && maxPrice) {
    return `${yuan(minPrice)} - ${yuan(maxPrice)}`
  }
  if (minPrice) {
    return `${yuan(minPrice)} 以上`
  }
  if (maxPrice) {
    return `${yuan(maxPrice)} 以下`
  }
  return ''
})

// ==================== 已选条件 ====================

function findCategoryName(id: number): string | undefined {
  for (const top of categories.value) {
    if (top.id === id) {
      return top.name
    }
    const sub = top.children.find((child) => child.id === id)
    if (sub) {
      return sub.name
    }
  }
  return undefined
}

/**
 * 已选条件条。
 *
 * 每个 chip 都是 URL 上真实存在的筛选项，不是本地状态的回显——所以它天然和链接一致，
 * 分享出去对方看到的条件条和自己这边一模一样。
 */
const chips = computed(() => {
  const list: { key: string; label: string; clear: () => void }[] = []
  const current = filters.value
  if (current.keyword) {
    list.push({
      key: 'keyword',
      label: `“${current.keyword}”`,
      clear: () => pushQuery({ keyword: undefined }),
    })
  }
  // 兜底文案不要再带「已选」：模板那侧已经统一加了这个前缀，
  // 两边都写会拼出「已选已选类目」（类目被删或 URL 手改后就会看到）
  if (current.categoryId) {
    list.push({
      key: 'categoryId',
      label: findCategoryName(current.categoryId) ?? '未知类目',
      clear: () => pushQuery({ categoryId: undefined }),
    })
  }
  if (current.brandId) {
    list.push({
      key: 'brandId',
      label: brands.value.find((brand) => brand.id === current.brandId)?.name ?? '未知品牌',
      clear: () => pushQuery({ brandId: undefined }),
    })
  }
  if (priceLabel.value) {
    list.push({
      key: 'price',
      label: priceLabel.value,
      clear: () => pushQuery({ minPrice: undefined, maxPrice: undefined }),
    })
  }
  return list
})

function clearAll() {
  // 排序也一并回到默认：用户点「清空筛选」时想要的是「从头来过」
  pushQuery({
    keyword: undefined,
    categoryId: undefined,
    brandId: undefined,
    minPrice: undefined,
    maxPrice: undefined,
    sort: 'relevance',
  })
}

async function loadCatalog() {
  // 类目与品牌互不依赖，并行取；串行会白等一个往返
  const [tree, brandList] = await Promise.all([getCategoryTree(), getBrands()])
  categories.value = tree
  brands.value = brandList
}

onMounted(async () => {
  // 基础数据与商品列表并行：类目树是用来画侧栏与条件条的，商品不该等它
  await Promise.all([loadCatalog(), load()])
})

// 前进/后退、外部跳转（顶栏搜索、联想里点品牌）都会只改 URL 不改本地状态，
// 所以查询由 URL 驱动，而不是由那几个点击处理函数各自触发
watch(() => route.fullPath, load)
</script>

<template>
  <div class="page shop">
    <aside class="shop__side">
      <section class="surface">
        <h2 class="side-title">类目</h2>
        <ul class="cat-list">
          <li v-for="top in categories" :key="top.id">
            <button
              type="button"
              class="cat-list__item cat-list__item--top"
              :class="{ 'is-active': filters.categoryId === top.id }"
              @click="pickCategory(top.id)"
            >
              {{ top.name }}
            </button>
            <ul v-if="top.children.length" class="cat-list__sub">
              <li v-for="sub in top.children" :key="sub.id">
                <button
                  type="button"
                  class="cat-list__item"
                  :class="{ 'is-active': filters.categoryId === sub.id }"
                  @click="pickCategory(sub.id)"
                >
                  {{ sub.name }}
                </button>
              </li>
            </ul>
          </li>
        </ul>
      </section>

      <section class="surface">
        <h2 class="side-title">品牌</h2>
        <div class="brand-list">
          <button
            v-for="brand in brands"
            :key="brand.id"
            type="button"
            class="brand-list__item"
            :class="{ 'is-active': filters.brandId === brand.id }"
            @click="pickBrand(brand.id)"
          >
            {{ brand.name }}
          </button>
        </div>
      </section>
    </aside>

    <section class="shop__main">
      <div class="surface shop__toolbar">
        <div class="shop__price">
          <span class="shop__price-label">价格</span>
          <!-- 占位符不能当可访问名：触发输入后它就消失，读屏器此时念不出这个框是什么 -->
          <el-input
            v-model="priceDraft.min"
            class="shop__price-input"
            placeholder="最低"
            inputmode="decimal"
            aria-label="价格下限"
            @keyup.enter="applyPrice"
          >
            <template #prefix>¥</template>
          </el-input>
          <span class="shop__price-dash" aria-hidden="true">—</span>
          <el-input
            v-model="priceDraft.max"
            class="shop__price-input"
            placeholder="最高"
            inputmode="decimal"
            aria-label="价格上限"
            @keyup.enter="applyPrice"
          >
            <template #prefix>¥</template>
          </el-input>
          <el-button @click="applyPrice">确定</el-button>
        </div>

        <!-- 单向绑定 + change：filters 是从 URL 算出来的，直接 v-model 会去写一个只读的 computed。
             排序同样写回 URL，所以「按价格排序后把链接发给别人」打开就是排好的 -->
        <el-select
          :model-value="filters.sort"
          size="default"
          class="shop__sort"
          aria-label="排序方式"
          @change="(value: string) => pushQuery({ sort: value })"
        >
          <el-option
            v-for="opt in sortOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
      </div>

      <div v-if="chips.length" class="shop__chips">
        <span class="shop__chips-label">已选</span>
        <button
          v-for="chip in chips"
          :key="chip.key"
          type="button"
          class="shop__chip"
          :aria-label="`取消筛选：${chip.label}`"
          @click="chip.clear()"
        >
          <span>{{ chip.label }}</span>
          <el-icon :size="12"><Close /></el-icon>
        </button>
        <button v-if="chips.length > 1" type="button" class="shop__chip-clear" @click="clearAll">
          清空全部
        </button>
      </div>

      <div v-loading="loading" class="shop__results">
        <ErrorState v-if="loadError" :message="loadError" :on-retry="load" />
        <div v-else-if="products.length" class="shop__grid">
          <ShopProductCard v-for="item in products" :key="item.id" :product="item" />
        </div>
        <div v-else-if="!loading" class="shop__empty">
          <el-empty description="没有找到符合条件的商品" />
          <el-button v-if="chips.length" plain @click="clearAll">清空筛选条件</el-button>
        </div>
      </div>

      <el-pagination
        v-if="total > PAGE_SIZE"
        class="shop__pager"
        layout="prev, pager, next, total"
        background
        :total="total"
        :page-size="PAGE_SIZE"
        :current-page="filters.page"
        @current-change="onPageChange"
      />

      <!-- 无结果时给一条退路：筛选条件越严越容易空手而归，而用户往往只是不知道哪一项卡住了 -->
      <p v-if="!loading && !loadError && !products.length && !chips.length" class="shop__hint">
        <el-icon><Search /></el-icon>
        换个关键词试试，或者从顶栏搜索框里挑一个联想词
      </p>
    </section>
  </div>
</template>

<style scoped>
.shop {
  grid-template-columns: 240px minmax(0, 1fr);
  align-items: start;
}

.shop__side {
  display: grid;
  gap: var(--ys-space-4);
  position: sticky;
  top: calc(var(--layout-header-height) + var(--ys-space-4));
}

.side-title {
  margin-bottom: var(--ys-space-3);
  font-size: var(--ys-font-base);
  font-weight: 600;
}

.cat-list,
.cat-list__sub {
  display: grid;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.cat-list__sub {
  margin: 2px 0 var(--ys-space-2) var(--ys-space-3);
}

.cat-list__item,
.brand-list__item {
  width: 100%;
  padding: 6px 10px;
  border: 0;
  border-radius: var(--ys-radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  text-align: left;
  cursor: pointer;
  transition:
    background-color var(--ys-duration-fast) var(--ys-ease-out),
    color var(--ys-duration-fast) var(--ys-ease-out);
}

.cat-list__item--top {
  color: var(--color-text-primary);
  font-weight: 600;
  font-size: var(--ys-font-base);
}

.cat-list__item:hover,
.brand-list__item:hover {
  background: var(--color-primary-subtle);
  color: var(--color-primary-strong);
}

.cat-list__item.is-active,
.brand-list__item.is-active {
  background: var(--color-primary-subtle);
  color: var(--color-primary-strong);
  font-weight: 600;
}

.brand-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
}

.brand-list__item {
  width: auto;
  padding: 4px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
}

.shop__main {
  display: grid;
  gap: var(--ys-space-4);
}

.shop__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ys-space-3);
}

.shop__price {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
}

.shop__price-label {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.shop__price-input {
  width: 104px;
}

.shop__price-dash {
  color: var(--color-text-muted);
}

.shop__sort {
  width: 160px;
  margin-inline-start: auto;
}

.shop__chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ys-space-2);
}

.shop__chips-label {
  color: var(--color-text-muted);
  font-size: var(--ys-font-sm);
}

.shop__chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px 3px 10px;
  border: 1px solid var(--color-primary-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-primary-subtle);
  color: var(--color-primary-strong);
  font-size: var(--ys-font-sm);
  cursor: pointer;
  transition: background-color var(--ys-duration-fast) var(--ys-ease-out);
}

.shop__chip:hover {
  background: var(--color-bg-surface);
}

.shop__chip-clear {
  border: 0;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  cursor: pointer;
  text-decoration: underline;
}

.shop__chip-clear:hover {
  color: var(--color-primary-strong);
}

.shop__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--ys-space-4);
}

.shop__results {
  min-height: 200px;
}

.shop__empty {
  display: grid;
  justify-items: center;
  gap: var(--ys-space-3);
}

.shop__hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--ys-space-2);
  color: var(--color-text-muted);
  font-size: var(--ys-font-sm);
}

.shop__pager {
  justify-content: center;
}

@media (max-width: 960px) {
  .shop {
    grid-template-columns: minmax(0, 1fr);
  }

  .shop__side {
    position: static;
    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  }

  .shop__price {
    flex-wrap: wrap;
  }

  .shop__price-input {
    flex: 1;
    width: auto;
    min-width: 96px;
  }

  .shop__sort {
    width: 100%;
    margin-inline-start: 0;
  }
}
</style>
