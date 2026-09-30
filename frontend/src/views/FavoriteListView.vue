<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listFavorites, removeFavorite } from '@/api/favorite'
import { formatPriceRange } from '@/api/product'
import { formatDate } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import ShopProductCard from '@/components/shop/ProductCard.vue'
import { useFavoriteStore } from '@/stores'
import type { FavoriteItem } from '@/types/models'

const router = useRouter()
const favorite = useFavoriteStore()

const items = ref<FavoriteItem[]>([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const failed = ref(false)

/** 批量管理模式。开启后卡片换成紧凑列表 —— 卡片上叠复选框会和「已下架」角标抢同一块位置 */
const managing = ref(false)
const selected = ref(new Set<number>())
const removing = ref(false)

const SIZE = 12

const selectedCount = computed(() => selected.value.size)
const allSelected = computed(
  () => items.value.length > 0 && selected.value.size === items.value.length,
)

async function load() {
  loading.value = true
  failed.value = false
  try {
    const result = await listFavorites(page.value, SIZE)
    items.value = result.records
    total.value = result.total
    // 新的一页到了，之前的选择就没有意义了：留着它，用户按「取消收藏」删掉的是看不到的东西
    selected.value = new Set()
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

/** 本页空了但总数还没到 0，说明删除把后面的顶上来了，退回上一页而不是把用户扔在空白页 */
function backIfPageEmptied() {
  if (!items.value.length && total.value > 0 && page.value > 0) {
    page.value -= 1
    load()
  }
}

/**
 * 卡片上的心形被点掉了：立刻把这条从列表里移除，并重算总数。
 * <p>
 * 不等重新请求：心形已经灰了，商品还杵在原地，用户会以为刚才那下没生效而再点一次。
 */
function onCardFavoriteChange(spuId: number, favorited: boolean) {
  if (favorited) {
    return
  }
  items.value = items.value.filter((item) => item.spuId !== spuId)
  total.value = Math.max(0, total.value - 1)
  selected.value.delete(spuId)
  backIfPageEmptied()
}

function startManaging() {
  managing.value = true
  selected.value = new Set()
}

function cancelManaging() {
  managing.value = false
  selected.value = new Set()
}

function toggleSelect(spuId: number) {
  if (selected.value.has(spuId)) {
    selected.value.delete(spuId)
  } else {
    selected.value.add(spuId)
  }
}

function toggleSelectAll() {
  selected.value = allSelected.value ? new Set() : new Set(items.value.map((i) => i.spuId))
}

async function removeSelected() {
  const ids = [...selected.value]
  if (!ids.length) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `将从收藏夹移除 ${ids.length} 件商品。商品本身不受影响，之后还可以重新收藏。`,
      '取消收藏',
      { confirmButtonText: '取消收藏', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    // 用户点了「再想想」。ElMessageBox 用 reject 表达取消，这不是错误
    return
  }

  removing.value = true
  try {
    // 后端没有批量接口，一件一个请求。并发发出去：串行时 12 件的等待是 12 个往返，
    // 而它们彼此独立、谁先谁后都一样
    await Promise.all(ids.map((id) => removeFavorite(id)))
    ids.forEach((id) => favorite.setFavorited(id, false))
    total.value = Math.max(0, total.value - ids.length)
    items.value = items.value.filter((item) => !selected.value.has(item.spuId))
    selected.value = new Set()
    ElMessage.success(`已取消收藏 ${ids.length} 件商品`)
    if (items.value.length) {
      backIfPageEmptied()
    } else {
      // 本页被清空且已是第一页：重新拉一次，把后面几条补上来
      load()
    }
    if (!total.value) {
      managing.value = false
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '取消失败，请稍后再试')
    // 部分成功、部分失败时列表已经不准确了，重新拉一次以服务端为准
    await load()
  } finally {
    removing.value = false
  }
}

function onPageChange(next: number) {
  page.value = next - 1
  load()
}

onMounted(load)
</script>

<template>
  <div class="page favorites">
    <header class="page-header">
      <div>
        <p class="eyebrow">My favorites</p>
        <h1>我的收藏</h1>
        <p class="favorites__hint">共 {{ total }} 件商品，按收藏时间倒序</p>
      </div>
      <div v-if="total > 0" class="favorites__actions">
        <el-button v-if="!managing" @click="startManaging">批量管理</el-button>
        <el-button v-else @click="cancelManaging">退出管理</el-button>
      </div>
    </header>

    <ErrorState v-if="failed" :on-retry="load" />

    <template v-else>
      <div v-loading="loading" class="favorites__body">
        <el-empty v-if="!items.length && !loading" description="收藏夹还是空的">
          <el-button type="primary" @click="router.push('/shop')">去逛逛</el-button>
        </el-empty>

        <div v-else-if="!managing" class="favorites__grid">
          <div v-for="item in items" :key="item.spuId" class="fav-cell">
            <ShopProductCard
              v-if="item.product"
              :product="item.product"
              :unavailable="!item.available"
              @favorite-change="onCardFavoriteChange(item.spuId, $event)"
            />
            <!-- 商品被物理删除：收藏记录还在，商品数据没了。给一个能取消收藏的占位，而不是渲染半张空卡 -->
            <div v-else class="fav-tombstone">
              <p class="fav-tombstone__text">该商品已被删除</p>
              <el-button link type="primary" @click="onCardFavoriteChange(item.spuId, false)"
                >从收藏夹移除</el-button
              >
            </div>
            <p class="fav-cell__time">{{ formatDate(item.favoritedAt) }} 收藏</p>
          </div>
        </div>

        <ul v-else class="fav-manage">
          <li v-for="item in items" :key="item.spuId" class="fav-row">
            <el-checkbox
              :model-value="selected.has(item.spuId)"
              :aria-label="`选择 ${item.product?.name ?? '已删除的商品'}`"
              @change="toggleSelect(item.spuId)"
            />
            <img
              v-if="item.product?.mainImage"
              class="fav-row__thumb"
              :src="item.product.mainImage"
              :alt="item.product.name"
              loading="lazy"
            />
            <div v-else class="fav-row__thumb fav-row__thumb--empty" aria-hidden="true" />

            <div class="fav-row__main">
              <p class="fav-row__name">
                {{ item.product?.name ?? '该商品已被删除' }}
                <el-tag v-if="!item.available" size="small" type="info" effect="plain"
                  >已下架</el-tag
                >
              </p>
              <p class="fav-row__meta">
                {{ formatDate(item.favoritedAt) }} 收藏
                <template v-if="item.product">
                  · {{ formatPriceRange(item.product.minPrice, item.product.maxPrice) }}
                </template>
              </p>
            </div>
          </li>
        </ul>
      </div>

      <el-pagination
        v-if="total > SIZE"
        class="favorites__pager"
        layout="prev, pager, next, total"
        background
        :total="total"
        :page-size="SIZE"
        :current-page="page + 1"
        @current-change="onPageChange"
      />
    </template>

    <!-- 批量操作条：吸在页面底部，滚动时不用回头找 -->
    <div v-if="managing" class="fav-bar" role="region" aria-label="批量操作">
      <el-checkbox
        :model-value="allSelected"
        :indeterminate="selectedCount > 0 && !allSelected"
        @change="toggleSelectAll"
      >
        全选本页
      </el-checkbox>
      <span class="fav-bar__count">已选 {{ selectedCount }} 件</span>
      <el-button
        type="primary"
        :loading="removing"
        :disabled="!selectedCount"
        @click="removeSelected"
      >
        取消收藏
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.favorites__hint {
  margin-top: var(--ys-space-2);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.favorites__actions {
  display: flex;
  gap: var(--ys-space-2);
}

.favorites__body {
  min-height: 240px;
}

.favorites__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--ys-space-4);
}

.fav-cell {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-2);
  /* 同一行的卡片高度由网格拉平，但卡片自己不撑满，于是价格换行的那一张会把
     「收藏于…」顶下去，一行里的时间戳参差不齐。让卡片吃掉多余的高度，
     时间戳就落在同一条线上 */
  height: 100%;
}

.fav-cell > .product-card {
  flex: 1;
}

.fav-cell__time {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.fav-tombstone {
  display: grid;
  place-content: center;
  justify-items: center;
  gap: var(--ys-space-2);
  /* 与商品卡同高：占位件混在网格里时，行高不该因为它塌下去 */
  aspect-ratio: 1 / 1.35;
  padding: var(--ys-space-4);
  border: 1px dashed var(--color-border);
  border-radius: var(--card-radius);
  background: var(--color-bg-surface-muted);
  text-align: center;
}

.fav-tombstone__text {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.fav-manage {
  display: grid;
  gap: var(--ys-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.fav-row {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  padding: var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface);
}

.fav-row__thumb {
  width: 56px;
  height: 56px;
  flex: none;
  object-fit: cover;
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface-muted);
}

.fav-row__main {
  min-width: 0;
}

.fav-row__name {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  font-weight: 600;
}

.fav-row__meta {
  margin-top: 2px;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.favorites__pager {
  justify-content: center;
}

.fav-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: var(--ys-space-4);
  padding: var(--ys-space-3) var(--ys-space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface);
  box-shadow: var(--ys-shadow-dropdown);
}

.fav-bar__count {
  margin-right: auto;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

@media (max-width: 720px) {
  .favorites__grid {
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  }
}
</style>
