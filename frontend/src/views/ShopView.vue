<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getBrands, getCategoryTree, listProducts, searchProducts } from '@/api/product'
import ShopProductCard from '@/components/shop/ProductCard.vue'
import type { BrandView, CategoryNode, ProductSummary } from '@/types/models'

const router = useRouter()

const categories = ref<CategoryNode[]>([])
const brands = ref<BrandView[]>([])
const products = ref<ProductSummary[]>([])
const total = ref(0)
const loading = ref(false)

const query = reactive({
  keyword: '',
  categoryId: undefined as number | undefined,
  brandId: undefined as number | undefined,
  sort: 'sales',
  page: 0,
  size: 12,
})

const sortOptions = [
  { value: 'sales', label: '销量优先' },
  { value: 'price_asc', label: '价格从低到高' },
  { value: 'price_desc', label: '价格从高到低' },
  { value: 'newest', label: '最新上架' },
]

async function load() {
  loading.value = true
  try {
    const keyword = query.keyword.trim()
    // 有关键词走 ES 全文检索，没有就走库内查询。
    // 分工：全文检索能命中详情与参数里的词（「胶囊」也能搜到），
    // 库内查询能走索引做结构化筛选，翻页到深页也不会撞 ES 的 max_result_window
    const fetcher = keyword ? searchProducts : listProducts
    const result = await fetcher({
      keyword: keyword || undefined,
      categoryId: query.categoryId,
      brandId: query.brandId,
      sort: query.sort,
      page: query.page,
      size: query.size,
    })
    products.value = result.records
    total.value = result.total
  } finally {
    loading.value = false
  }
}

/** 换筛选条件必须回到第一页：留在第 3 页而结果只剩 1 页，用户会看到一片空白 */
function reload() {
  query.page = 0
  load()
}

/** 再点一次已选中的项 = 取消筛选 */
function pickCategory(id?: number) {
  query.categoryId = query.categoryId === id ? undefined : id
  reload()
}

function pickBrand(id?: number) {
  query.brandId = query.brandId === id ? undefined : id
  reload()
}

/** el-pagination 的页码从 1 开始，后端接口从 0 开始 */
function onPageChange(page: number) {
  query.page = page - 1
  load()
}

function openDetail(id: number) {
  router.push(`/products/${id}`)
}

onMounted(async () => {
  // 类目与品牌互不依赖，并行取；串行会白等一个往返
  const [tree, brandList] = await Promise.all([getCategoryTree(), getBrands()])
  categories.value = tree
  brands.value = brandList
  await load()
})
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
              :class="{ 'is-active': query.categoryId === top.id }"
              @click="pickCategory(top.id)"
            >
              {{ top.name }}
            </button>
            <ul v-if="top.children.length" class="cat-list__sub">
              <li v-for="sub in top.children" :key="sub.id">
                <button
                  type="button"
                  class="cat-list__item"
                  :class="{ 'is-active': query.categoryId === sub.id }"
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
            :class="{ 'is-active': query.brandId === brand.id }"
            @click="pickBrand(brand.id)"
          >
            {{ brand.name }}
          </button>
        </div>
      </section>
    </aside>

    <section class="shop__main">
      <div class="surface shop__toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="搜索商品、成分、参数"
          clearable
          size="large"
          class="shop__search"
          @keyup.enter="reload"
          @clear="reload"
        >
          <template #append>
            <el-button @click="reload">搜索</el-button>
          </template>
        </el-input>

        <el-select v-model="query.sort" size="large" class="shop__sort" @change="reload">
          <el-option
            v-for="opt in sortOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
      </div>

      <div v-loading="loading" class="shop__results">
        <div v-if="products.length" class="shop__grid">
          <ShopProductCard
            v-for="item in products"
            :key="item.id"
            :product="item"
            @open="openDetail(item.id)"
          />
        </div>
        <el-empty v-else-if="!loading" description="没有找到符合条件的商品" />
      </div>

      <el-pagination
        v-if="total > query.size"
        class="shop__pager"
        layout="prev, pager, next, total"
        background
        :total="total"
        :page-size="query.size"
        :current-page="query.page + 1"
        @current-change="onPageChange"
      />
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
  color: var(--color-primary);
}

.cat-list__item.is-active,
.brand-list__item.is-active {
  background: var(--color-primary-subtle);
  color: var(--color-primary);
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
  gap: var(--ys-space-3);
}

.shop__search {
  flex: 1;
}

.shop__sort {
  width: 160px;
}

.shop__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--ys-space-4);
}

.shop__results {
  min-height: 200px;
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

  .shop__toolbar {
    flex-direction: column;
  }

  .shop__sort {
    width: 100%;
  }
}
</style>
