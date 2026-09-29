<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { formatPrice } from '@/api/product'
import ErrorState from '@/components/ui/ErrorState.vue'
import { useCartStore } from '@/stores'

const router = useRouter()
const cart = useCartStore()

const failed = ref(false)
/** 正在提交数量的条目：只禁用它自己，不禁用整页 */
const updatingId = ref<number | null>(null)

/**
 * 数量的本地副本。
 * <p>
 * `el-input-number` 用 `:model-value` 时，点击 + 会先把内部值复位到未变化的 prop，
 * 等接口回来才跳到新值 —— 表现为「3 → 3 → 4」。用本地副本先乐观更新，
 * 接口回来再对齐服务端的权威值。
 */
const quantities = reactive<Record<number, number>>({})

function quantityOf(id: number, fallback: number) {
  return quantities[id] ?? fallback
}

const selectableItems = computed(() => cart.items.filter((item) => item.available))

const allSelected = computed(
  () => selectableItems.value.length > 0 && selectableItems.value.every((item) => item.selected),
)

const someSelected = computed(
  () => selectableItems.value.some((item) => item.selected) && !allSelected.value,
)

const selectedCount = computed(() => cart.selectedItems.reduce((sum, i) => sum + i.quantity, 0))

async function reload() {
  failed.value = false
  try {
    await cart.load()
  } catch {
    failed.value = true
  }
}

async function changeQuantity(id: number, quantity: number) {
  updatingId.value = id
  quantities[id] = quantity
  try {
    await cart.updateQuantity(id, quantity)
  } catch {
    // 失败时把本地值退回服务端的权威值，否则界面会停在一个不存在的数量上
    delete quantities[id]
  } finally {
    updatingId.value = null
  }
}

async function toggleSelected(id: number, selected: boolean) {
  await cart.toggleSelected(id, selected)
}

async function toggleAll(selected: boolean) {
  await cart.toggleAll(selected)
}

async function removeItem(id: number, name: string) {
  try {
    await ElMessageBox.confirm(`确定从购物车移除「${name}」吗？`, '移除商品', {
      type: 'warning',
      confirmButtonText: '移除',
      cancelButtonText: '取消',
    })
  } catch {
    // 用户取消。ElMessageBox 用 reject 表达取消，不是错误
    return
  }
  await cart.remove(id)
  ElMessage.success('已移除')
}

function goCheckout() {
  if (cart.selectedItems.length === 0) {
    ElMessage.warning('请先勾选要结算的商品')
    return
  }
  router.push('/checkout')
}

onMounted(reload)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Cart</p>
        <h1>购物车</h1>
      </div>
      <p v-if="cart.items.length" class="subcopy">共 {{ cart.items.length }} 种商品</p>
    </header>

    <ErrorState v-if="failed" message="购物车加载失败，请重试" :on-retry="reload" />

    <el-empty v-else-if="!cart.loading && cart.items.length === 0" description="购物车还是空的">
      <el-button type="primary" @click="router.push('/shop')">去逛逛</el-button>
    </el-empty>

    <template v-else>
      <section v-loading="cart.loading" class="surface cart">
        <div class="cart__head">
          <el-checkbox
            :model-value="allSelected"
            :indeterminate="someSelected"
            :disabled="selectableItems.length === 0"
            @change="(v: any) => toggleAll(!!v)"
          >
            全选
          </el-checkbox>
          <span class="cart__col">单价</span>
          <span class="cart__col">数量</span>
          <span class="cart__col">小计</span>
          <span class="cart__col">操作</span>
        </div>

        <ul class="cart__list">
          <li
            v-for="item in cart.items"
            :key="item.id"
            class="cart__item"
            :class="{ 'is-invalid': !item.available }"
          >
            <div class="cart__check">
              <!-- 失效商品**也可以取消勾选**：让它保持勾选又点不动，
                   用户就永远清不掉这一条，而后端结算时仍会去扣它的库存 -->
              <el-checkbox
                :model-value="item.selected"
                @change="(v: any) => toggleSelected(item.id, !!v)"
              />
            </div>

            <img
              v-if="item.image"
              :src="item.image"
              :alt="item.name"
              class="cart__image"
              @click="router.push(`/products/${item.spuId}`)"
            />
            <div v-else class="cart__image cart__image--empty" aria-hidden="true"></div>

            <div class="cart__info">
              <button
                type="button"
                class="cart__name"
                @click="router.push(`/products/${item.spuId}`)"
              >
                {{ item.name }}
              </button>
              <p v-if="item.specText" class="cart__spec">{{ item.specText }}</p>
              <!-- 失效原因要说清楚：商品直接消失或只显示「失效」，用户不知道发生了什么 -->
              <p v-if="!item.available" class="cart__invalid">
                该商品已下架或暂时缺货，结算前请先移除
              </p>
              <p v-else-if="item.stock < item.quantity" class="cart__warn">
                库存仅剩 {{ item.stock }} 件，请调整数量
              </p>
            </div>

            <span class="cart__col cart__price">{{ formatPrice(item.price) }}</span>

            <div class="cart__col cart__qty">
              <el-input-number
                :model-value="quantityOf(item.id, item.quantity)"
                :min="1"
                :max="Math.max(item.stock, 1)"
                :disabled="!item.available || updatingId === item.id"
                size="small"
                @change="(v: any) => changeQuantity(item.id, Number(v))"
              />
            </div>

            <span class="cart__col cart__sum">{{ formatPrice(item.subtotal) }}</span>

            <div class="cart__col cart__op">
              <el-button link type="danger" @click="removeItem(item.id, item.name)">移除</el-button>
            </div>
          </li>
        </ul>
      </section>

      <footer class="surface cart__bar">
        <div class="cart__bar-info">
          <span>已选 <strong>{{ selectedCount }}</strong> 件</span>
          <span class="cart__bar-total">
            合计：<strong>{{ formatPrice(cart.selectedAmount) }}</strong>
          </span>
        </div>
        <el-button
          type="primary"
          size="large"
          :disabled="cart.selectedItems.length === 0"
          @click="goCheckout"
        >
          去结算
        </el-button>
      </footer>
    </template>
  </div>
</template>

<style scoped>
/*
 * 网格列：勾选 / 图 / 商品信息 / 单价 / 数量 / 小计 / 操作。
 * 表头与行**共用同一个模板**，用 CSS 变量定义一次 ——
 * 之前两处各写一遍、列数还对不上，结果「单价」标在了图片列上。
 */
.cart {
  --cart-columns: 40px 72px minmax(0, 1fr) 100px 140px 100px 70px;
}

.cart__head,
.cart__item {
  display: grid;
  grid-template-columns: var(--cart-columns);
  gap: var(--ys-space-3);
  align-items: center;
}

.cart__head {
  padding-bottom: var(--ys-space-3);
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

/* 表头第一列是复选框，与数据行的勾选列对齐 */
.cart__head > .el-checkbox {
  grid-column: 1 / 3;
}

.cart__head .cart__col {
  text-align: center;
}

.cart__list {
  display: grid;
  gap: var(--ys-space-2);
  margin: 0;
  padding: var(--ys-space-3) 0 0;
  list-style: none;
}

.cart__item {
  padding: var(--ys-space-3) 0;
  border-bottom: 1px solid var(--color-border);
}

.cart__item:last-child {
  border-bottom: 0;
}

.cart__item.is-invalid {
  opacity: 0.66;
}

.cart__check {
  display: flex;
  justify-content: center;
}

.cart__image {
  width: 72px;
  height: 72px;
  border-radius: var(--ys-radius-sm);
  object-fit: cover;
  cursor: pointer;
  background: var(--color-bg-surface-muted);
}

.cart__image--empty {
  display: block;
}

.cart__info {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.cart__name {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-text-primary);
  font-size: var(--ys-font-base);
  font-weight: 600;
  text-align: left;
  cursor: pointer;
}

.cart__name:hover {
  color: var(--color-primary);
}

.cart__spec {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.cart__invalid {
  color: var(--color-danger);
  font-size: var(--ys-font-xs);
}

.cart__warn {
  color: var(--color-warning);
  font-size: var(--ys-font-xs);
}

.cart__price,
.cart__qty,
.cart__sum,
.cart__op {
  text-align: center;
}

.cart__sum {
  color: var(--color-primary);
  font-weight: 600;
}

.cart__bar {
  position: sticky;
  bottom: var(--ys-space-4);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-4);
}

.cart__bar-info {
  display: flex;
  align-items: baseline;
  gap: var(--ys-space-5);
  color: var(--color-text-secondary);
}

.cart__bar-total strong {
  color: var(--color-primary);
  font-size: var(--ys-font-xl);
}

/*
 * 窄屏：换成两列布局，商品信息占满右侧。
 * 不用 grid-template-areas —— 那要求每个子元素显式声明 grid-area，
 * 之前写了区域名却没配 grid-area，声明的区域完全不起作用、元素按顺序乱排。
 */
@media (max-width: 900px) {
  .cart__head {
    display: none;
  }

  .cart__item {
    grid-template-columns: 32px 72px minmax(0, 1fr);
    grid-template-rows: auto auto auto auto;
    row-gap: var(--ys-space-2);
  }

  .cart__check {
    grid-row: 1 / 5;
  }

  .cart__image {
    grid-row: 1 / 5;
    align-self: start;
  }

  .cart__info {
    grid-column: 3;
  }

  .cart__price,
  .cart__qty,
  .cart__sum,
  .cart__op {
    grid-column: 3;
    text-align: left;
  }

  .cart__bar {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
