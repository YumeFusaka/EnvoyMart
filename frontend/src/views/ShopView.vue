<script setup lang="ts">
import { addCartItem, getCartItems, updateCartItem } from '@/api/cart'
import { getLogistics, getOrders } from '@/api/order'
import { getProducts } from '@/api/product'
import CartDrawer from '@/components/shop/CartDrawer.vue'
import OrderPanel from '@/components/shop/OrderPanel.vue'
import ProductCard from '@/components/shop/ProductCard.vue'
import { checkout } from '@/api/order'
import { useUserStore } from '@/stores'
import type { CartItem, Logistics, Order, Product } from '@/types/models'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const checkoutLoading = ref(false)
const cartVisible = ref(false)
const products = ref<Product[]>([])
const cartItems = ref<CartItem[]>([])
const orders = ref<Order[]>([])
const logisticsMap = ref<Record<number, Logistics | undefined>>({})
const logisticsLoadingIds = ref<number[]>([])

const filters = reactive({
  keyword: String(route.query.keyword || ''),
  category: ''
})

const categories = computed(() => Array.from(new Set(products.value.map((item) => item.category))))

async function loadProducts() {
  loading.value = true
  try {
    products.value = await getProducts({
      keyword: filters.keyword || undefined,
      category: filters.category || undefined
    })
  } finally {
    loading.value = false
  }
}

async function loadCart() {
  cartItems.value = await getCartItems()
}

async function loadOrders() {
  orders.value = await getOrders()
}

async function initialize() {
  await Promise.all([loadProducts(), loadCart(), loadOrders()])
}

async function handleAddToCart(product: Product) {
  await addCartItem({ productId: product.id, quantity: 1 })
  ElMessage.success(`已将 ${product.name} 加入购物车`)
  cartVisible.value = true
  await loadCart()
}

async function handleUpdateQuantity(payload: { id: number; quantity: number }) {
  await updateCartItem(payload.id, { quantity: payload.quantity })
  await loadCart()
}

async function handleCheckout(payload: { recipientName: string; recipientPhone: string; address: string }) {
  checkoutLoading.value = true
  try {
    const order = await checkout(payload)
    ElMessage.success(`订单 ${order.orderNo} 创建成功`)
    cartVisible.value = false
    await Promise.all([loadCart(), loadOrders()])
  } finally {
    checkoutLoading.value = false
  }
}

async function handleLoadLogistics(orderId: number) {
  logisticsLoadingIds.value = [...logisticsLoadingIds.value, orderId]
  try {
    logisticsMap.value[orderId] = await getLogistics(orderId)
  } finally {
    logisticsLoadingIds.value = logisticsLoadingIds.value.filter((id) => id !== orderId)
  }
}

function logout() {
  userStore.clearSession()
  router.push('/login')
}

watch(
  () => route.query.keyword,
  async (keyword) => {
    filters.keyword = String(keyword || '')
    await loadProducts()
  }
)

onMounted(initialize)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Retail Command Desk</p>
        <h1>EnvoyMart 智能零售工作台</h1>
        <p class="subcopy">欢迎回来，{{ userStore.profile?.nickname || userStore.profile?.username }}</p>
      </div>
      <div class="header-actions">
        <el-button plain @click="router.push('/assistant')">进入 AI 助手</el-button>
        <el-button type="primary" @click="cartVisible = true">购物车 {{ cartItems.length }}</el-button>
        <el-button text @click="logout">退出</el-button>
      </div>
    </header>

    <section class="hero-strip">
      <div>
        <strong>商品、订单、AI 联动演示</strong>
        <p>当前已打通商品浏览、购物车、订单查询与 AI 智能客服问答。</p>
      </div>
      <el-button type="success" @click="router.push('/assistant')">试试导购提问</el-button>
    </section>

    <section class="filter-bar">
      <el-input v-model="filters.keyword" clearable placeholder="搜索商品、品牌、标签" @change="loadProducts" />
      <el-select v-model="filters.category" clearable placeholder="分类筛选" @change="loadProducts">
        <el-option v-for="category in categories" :key="category" :label="category" :value="category" />
      </el-select>
    </section>

    <section class="content-grid">
      <div class="product-grid">
        <ProductCard v-for="product in products" :key="product.id" :product="product" @add="handleAddToCart" />
      </div>

      <OrderPanel
        :orders="orders"
        :logistics-map="logisticsMap"
        :loading-ids="logisticsLoadingIds"
        @logistics="handleLoadLogistics"
      />
    </section>

    <CartDrawer
      v-model="cartVisible"
      :items="cartItems"
      :loading="checkoutLoading"
      @update-quantity="handleUpdateQuantity"
      @checkout="handleCheckout"
    />
  </div>
</template>

<style scoped>
.page {
  padding: 28px;
  display: grid;
  gap: 22px;
}

.page-header,
.hero-strip,
.filter-bar {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  align-items: center;
}

.page-header h1 {
  margin: 4px 0;
  font-size: clamp(28px, 4vw, 42px);
}

.eyebrow {
  margin: 0;
  color: var(--ys-primary);
  font-size: 12px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  font-weight: 700;
}

.subcopy {
  margin: 0;
  color: var(--ys-muted);
}

.hero-strip {
  padding: 20px 22px;
  border-radius: 24px;
  background: linear-gradient(135deg, rgba(182, 91, 46, 0.13), rgba(31, 122, 107, 0.12));
  border: 1px solid rgba(84, 55, 23, 0.08);
}

.hero-strip p {
  margin: 6px 0 0;
  color: var(--ys-muted);
}

.filter-bar {
  padding: 18px;
  border-radius: 20px;
  background: rgba(255, 251, 245, 0.9);
  border: 1px solid rgba(84, 55, 23, 0.08);
}

.filter-bar > * {
  flex: 1;
}

.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) 360px;
  gap: 22px;
  align-items: start;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 18px;
}

@media (max-width: 1100px) {
  .content-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .page-header,
  .hero-strip,
  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
