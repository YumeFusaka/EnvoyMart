<script setup lang="ts">
import { getProduct } from '@/api/product'
import { addCartItem } from '@/api/cart'
import { useUserStore } from '@/stores'
import type { Product } from '@/types/models'
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const product = ref<Product | null>(null)
const loading = ref(false)

async function loadProduct() {
  loading.value = true
  try {
    product.value = await getProduct(Number(route.params.id))
  } finally {
    loading.value = false
  }
}

async function handleAddToCart() {
  if (!product.value) return
  await addCartItem({ productId: product.value.id, quantity: 1 })
  ElMessage.success(`已加入购物车`)
}

function goToAssistant() {
  if (!product.value) return
  router.push({ path: '/assistant', query: { q: `推荐类似 ${product.value.name} 的耳机` } })
}

onMounted(loadProduct)
</script>

<template>
  <div v-if="product" class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">{{ product.category }} / {{ product.brand }}</p>
        <h1>{{ product.name }}</h1>
        <p class="subcopy">{{ product.subtitle }}</p>
      </div>
      <div class="header-actions">
        <el-button plain @click="router.push('/shop')">返回商城</el-button>
        <el-button plain @click="goToAssistant">AI 咨询</el-button>
      </div>
    </header>

    <section class="product-detail">
      <div class="product-gallery">
        <img :src="product.image" :alt="product.name" />
      </div>
      <div class="product-info">
        <div class="price-section">
          <strong class="price">¥{{ product.price }}</strong>
          <span class="stock">库存 {{ product.stock }} 件</span>
          <span class="sales">月销 {{ product.monthlySales }}</span>
        </div>

        <div class="tag-list">
          <span v-for="tag in product.tags" :key="tag">{{ tag }}</span>
        </div>

        <p class="sales-copy">{{ product.salesCopy }}</p>
        <p class="description">{{ product.description }}</p>

        <div class="action-bar">
          <el-button size="large" @click="handleAddToCart">加入购物车</el-button>
          <el-button size="large" type="primary" @click="router.push({ path: '/shop', query: { checkout: 'now' } })">立即购买</el-button>
        </div>
      </div>
    </section>
  </div>

  <div v-else-if="loading" class="page" style="text-align: center; padding-top: 80px;">
    <el-skeleton :rows="6" animated />
  </div>
</template>

<style scoped>
.product-detail {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 32px;
  padding: 28px;
  border-radius: 28px;
  background: rgba(255, 251, 245, 0.9);
  border: 1px solid var(--ys-border);
  box-shadow: var(--ys-shadow);
}

.product-gallery img {
  width: 100%;
  border-radius: 20px;
  object-fit: cover;
  aspect-ratio: 1;
}

.product-info {
  display: grid;
  gap: 20px;
  align-content: start;
}

.price-section {
  display: flex;
  gap: 16px;
  align-items: center;
}

.price {
  font-size: 36px;
  color: var(--ys-primary-deep);
}

.stock, .sales {
  color: var(--ys-muted);
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-list span {
  padding: 4px 12px;
  border-radius: 999px;
  background: rgba(31, 122, 107, 0.09);
  color: var(--ys-accent);
  font-size: 12px;
}

.sales-copy {
  font-size: 18px;
  font-weight: 600;
}

.description {
  color: var(--ys-muted);
  line-height: 1.8;
}

.action-bar {
  display: flex;
  gap: 12px;
  padding-top: 12px;
}

@media (max-width: 900px) {
  .product-detail {
    grid-template-columns: 1fr;
  }
}
</style>
