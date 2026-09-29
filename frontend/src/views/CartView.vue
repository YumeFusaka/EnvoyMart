<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { formatPrice } from '@/api/product'
import { useCartStore } from '@/stores'

const router = useRouter()
const cart = useCartStore()

/** 本地维护「正在改数量」的条目，避免每次点都整页 loading */
const updatingId = ref<number | null>(null)

const allSelected = computed(
  () =>
    cart.items.length > 0 &&
    cart.items.filter((item) => item.available).every((item) => item.selected),
)

const selectedCount = computed(() => cart.selectedItems.reduce((sum, i) => sum + i.quantity, 0))

async function changeQuantity(id: number, quantity: number) {
  updatingId.value = id
  try {
    await cart.updateQuantity(id, quantity)
  } finally {
    updatingId.value = null
  }
}

async function toggleSelected(id: number, selected: boolean) {
  await cart.toggleSelected(id, selected)
}

async function toggleAll(selected: boolean) {
  // 只对在售条目生效：失效商品勾了也结算不了，让它跟着全选变化只会让人困惑
  const targets = cart.items.filter((item) => item.available && item.selected !== selected)
  for (const item of targets) {
    await cart.toggleSelected(item.id, selected)
  }
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

onMounted(cart.load)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Cart</p>
        <h1>购物车</h1>
      </div>
      <p class="subcopy">共 {{ cart.items.length }} 种商品</p>
    </header>

    <el-empty v-if="!cart.loading && cart.items.length === 0" description="购物车还是空的">
      <el-button type="primary" @click="router.push('/shop')">去逛逛</el-button>
    </el-empty>

    <template v-else>
      <section v-loading="cart.loading" class="surface cart">
        <div class="cart__head">
          <el-checkbox
            :model-value="allSelected"
            :indeterminate="!allSelected && cart.selectedItems.length > 0"
            @change="(v: any) => toggleAll(!!v)"
          >
            全选
          </el-checkbox>
          <span class="cart__col cart__col--price">单价</span>
          <span class="cart__col cart__col--qty">数量</span>
          <span class="cart__col cart__col--sum">小计</span>
          <span class="cart__col cart__col--op">操作</span>
        </div>

        <ul class="cart__list">
          <li
            v-for="item in cart.items"
            :key="item.id"
            class="cart__item"
            :class="{ 'is-invalid': !item.available }"
          >
            <el-checkbox
              :model-value="item.selected"
              :disabled="!item.available"
              @change="(v: any) => toggleSelected(item.id, !!v)"
            />

            <img
              v-if="item.image"
              :src="item.image"
              :alt="item.name"
              class="cart__image"
              @click="router.push(`/products/${item.spuId}`)"
            />
            <div v-else class="cart__image cart__image--empty" aria-hidden="true"></div>

            <div class="cart__info">
              <button type="button" class="cart__name" @click="router.push(`/products/${item.spuId}`)">
                {{ item.name }}
              </button>
              <p v-if="item.specText" class="cart__spec">{{ item.specText }}</p>
              <!-- 失效原因要说清楚：商品直接消失或只显示"失效"，用户不知道发生了什么 -->
              <p v-if="!item.available" class="cart__invalid">
                该商品已下架或暂时缺货，无法结算
              </p>
              <p v-else-if="item.stock < item.quantity" class="cart__warn">
                库存仅剩 {{ item.stock }} 件，请调整数量
              </p>
            </div>

            <span class="cart__col cart__col--price">{{ formatPrice(item.price) }}</span>

            <div class="cart__col cart__col--qty">
              <el-input-number
                :model-value="item.quantity"
                :min="1"
                :max="Math.max(item.stock, 1)"
                :disabled="!item.available || updatingId === item.id"
                size="small"
                @change="(v: any) => changeQuantity(item.id, Number(v))"
              />
            </div>

            <span class="cart__col cart__col--sum cart__sum">{{ formatPrice(item.subtotal) }}</span>

            <div class="cart__col cart__col--op">
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
        <el-button type="primary" size="large" :disabled="cart.selectedItems.length === 0" @click="goCheckout">
          去结算
        </el-button>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.cart__head,
.cart__item {
  display: grid;
  grid-template-columns: 32px 72px minmax(0, 1fr) 100px 140px 100px 70px;
  gap: var(--ys-space-3);
  align-items: center;
}

.cart__head {
  padding-bottom: var(--ys-space-3);
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
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

.cart__col--price,
.cart__col--qty,
.cart__col--sum,
.cart__col--op {
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

@media (max-width: 900px) {
  .cart__head {
    display: none;
  }

  .cart__item {
    grid-template-columns: 32px 72px minmax(0, 1fr);
    grid-template-areas:
      'check image info'
      '. . price'
      '. . qty'
      '. . sum';
    row-gap: var(--ys-space-2);
  }

  .cart__bar {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
