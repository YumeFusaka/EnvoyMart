<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { formatPriceRange } from '@/api/product'
import { changeSpuStatus, deleteSpu, listSpus } from '@/api/admin/product'
import { listBrands, listCategoryTree } from '@/api/admin/catalog'
import { useAdminList, useResponsiveColumns } from '@/composables/useAdminList'
import { formatDate } from '@/utils/format'
import type { AdminBrand, AdminSpuQuery, AdminSpuSummary } from '@/types/admin'
import type { CategoryNode } from '@/types/models'
import ErrorState from '@/components/ui/ErrorState.vue'

const route = useRoute()
const router = useRouter()

const brands = ref<AdminBrand[]>([])
const categoryTree = ref<CategoryNode[]>([])

/**
 * 初始条件读路由 query：概览页的「订单在途」「待发货」都要能带着筛选跳进来，
 * 跳过来却显示全量列表，等于让用户自己再筛一次刚点过的条件。
 */
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
} = useAdminList<AdminSpuSummary, AdminSpuQuery>(
  listSpus,
  {
    keyword: '',
    categoryId: undefined,
    brandId: undefined,
    status: undefined,
    page: 0,
    size: 20,
  },
  { immediate: false },
)


/** 表格容器：列宽按它实测的宽度算，窄屏时等比例收缩 */
const tableRef = ref<HTMLElement | null>(null)
const { widths: colW } = useResponsiveColumns(
  [
    { width: 260, min: 240 },
    { width: 150, min: 140 },
    { width: 130, min: 120 },
    { width: 110, min: 100 },
    { width: 80, min: 70 },
    { width: 90, min: 80 },
    { width: 150, min: 130 },
    { width: 180, min: 150 },
  ],
  tableRef,
)

onMounted(async () => {
  const status = route.query.status
  if (status !== undefined && status !== '') {
    query.value.status = Number(status)
  }
  const keyword = route.query.keyword
  if (typeof keyword === 'string' && keyword) {
    query.value.keyword = keyword
  }
  await load(0)
  // 类目与品牌是筛选项的来源，拉失败只让下拉变空，不该把列表也一起判死
  await Promise.allSettled([
    listCategoryTree().then((tree) => (categoryTree.value = tree)),
    listBrands().then((list) => (brands.value = list)),
  ])
})

async function toggleStatus(row: AdminSpuSummary) {
  const next = row.status === 1 ? 0 : 1
  try {
    await changeSpuStatus(row.id, next)
    ElMessage.success(next === 1 ? '已上架' : '已下架')
    await load()
  } catch {
    // 拦截器已经提示过原因（例如没填价格不能上架），这里只保证不再往下走
  }
}

async function remove(row: AdminSpuSummary) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.name}」？被历史订单引用过的商品会被拒绝删除，此时请改用下架。`,
      '删除商品',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteSpu(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 拒绝原因由后端给出（例如「已被订单引用」），拦截器已展示
  }
}

const statusOptions = [
  { label: '已上架', value: 1 },
  { label: '已下架', value: 0 },
]
</script>

<template>
  <div class="admin-panel">
    <div class="admin-filters">
      <div class="admin-filters__item admin-filters__item--wide">
        <label class="admin-filters__label" for="spu-keyword">关键词</label>
        <el-input
          id="spu-keyword"
          v-model="query.keyword"
          placeholder="商品名 / 副标题 / 编码"
          clearable
          @keyup.enter="search"
        />
      </div>

      <div class="admin-filters__item admin-filters__item--wide">
        <label class="admin-filters__label" for="spu-category">类目</label>
        <el-tree-select
          id="spu-category"
          v-model="query.categoryId"
          :data="categoryTree"
          :props="{ label: 'name', value: 'id', children: 'children' }"
          node-key="id"
          check-strictly
          clearable
          placeholder="全部类目"
          style="width: 100%"
        />
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="spu-brand">品牌</label>
        <el-select id="spu-brand" v-model="query.brandId" clearable placeholder="全部品牌">
          <el-option v-for="b in brands" :key="b.id" :label="b.name" :value="b.id" />
        </el-select>
      </div>

      <div class="admin-filters__item admin-filters__item--narrow">
        <label class="admin-filters__label" for="spu-status">状态</label>
        <el-select id="spu-status" v-model="query.status" clearable placeholder="全部状态">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </div>

      <div class="admin-filters__actions">
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
      </div>
    </div>

    <div class="admin-toolbar">
      <h2 class="admin-toolbar__title">商品</h2>
      <span class="admin-toolbar__count">共 {{ total }} 个 SPU</span>
      <div class="admin-toolbar__actions">
        <el-button type="primary" :icon="Plus" @click="router.push('/admin/products/new')">
          新建商品
        </el-button>
      </div>
    </div>

    <ErrorState v-if="error" :message="error" :on-retry="() => load()" />

    <template v-else>
      <div ref="tableRef" class="admin-table">
        <el-table v-loading="loading" :data="records" row-key="id" style="width: 100%">
          <el-table-column label="商品" :width="colW[0]">
            <template #default="{ row }">
              <div class="spu-cell">
                <el-image class="spu-cell__img" :src="row.mainImage ?? undefined" fit="cover">
                  <template #error>
                    <div class="spu-cell__img spu-cell__img--blank" aria-hidden="true">无图</div>
                  </template>
                </el-image>
                <div class="admin-stack">
                  <span class="admin-cell--strong">{{ row.name }}</span>
                  <span class="admin-cell--tiny">{{ row.spuCode }}</span>
                  <span v-if="row.subtitle" class="admin-cell--muted">{{ row.subtitle }}</span>
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="类目 / 品牌" :width="colW[1]">
            <template #default="{ row }">
              <div class="admin-stack">
                <span>{{ row.categoryName ?? '—' }}</span>
                <span class="admin-cell--muted">{{ row.brandName ?? '无品牌' }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column   align="right" label="价格" :width="colW[2]">
            <template #default="{ row }">
              <span class="admin-cell--num">{{
                formatPriceRange(row.minPrice, row.maxPrice)
              }}</span>
            </template>
          </el-table-column>

          <el-table-column   align="right" label="SKU / 库存" :width="colW[3]">
            <template #default="{ row }">
              <div class="admin-stack">
                <span class="admin-cell--num">{{ row.skuCount }} 个 SKU</span>
                <span
                  class="admin-cell--num"
                  :class="row.totalStock === 0 ? 'stock--out' : 'admin-cell--muted'"
                >
                  库存 {{ row.totalStock }}
                </span>
              </div>
            </template>
          </el-table-column>

          <el-table-column prop="sales"   align="right" label="销量" :width="colW[4]">
            <template #default="{ row }">
              <span class="admin-cell--num">{{ row.sales }}</span>
            </template>
          </el-table-column>

          <el-table-column label="状态" :width="colW[5]">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="plain" size="small">
                {{ row.status === 1 ? '已上架' : '已下架' }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="更新时间" :width="colW[6]">
            <template #default="{ row }">
              <span class="admin-cell--tiny">{{ formatDate(row.updatedAt) }}</span>
            </template>
          </el-table-column>

          <el-table-column   fixed="right" label="操作" :width="colW[7]">
            <template #default="{ row }">
              <div class="admin-table__actions">
                <el-button
                  link
                  type="primary"
                  @click="router.push(`/admin/products/${row.id}/edit`)"
                >
                  编辑
                </el-button>
                <el-button link type="primary" @click="toggleStatus(row)">
                  {{ row.status === 1 ? '下架' : '上架' }}
                </el-button>
                <el-button link type="danger" @click="remove(row)">删除</el-button>
              </div>
            </template>
          </el-table-column>

          <template #empty>
            <p class="admin-empty">没有符合条件的商品</p>
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
  </div>
</template>

<style scoped>
.spu-cell {
  display: flex;
  align-items: flex-start;
  gap: var(--ys-space-3);
}

.spu-cell__img {
  width: 48px;
  height: 48px;
  flex: none;
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-sunken);
  overflow: hidden;
}

.spu-cell__img--blank {
  display: grid;
  place-items: center;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.stock--out {
  color: var(--color-danger-strong);
  font-weight: 600;
}
</style>
