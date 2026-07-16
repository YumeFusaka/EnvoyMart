<script setup lang="ts">
import type { Product } from '@/types/models'

defineProps<{
  product: Product
}>()

const emit = defineEmits<{
  add: [product: Product]
  click: []
}>()

function handleAdd(event: MouseEvent) {
  event.stopPropagation()
  emit('add', product)
}
</script>

<template>
  <article class="product-card" @click="$emit('click')">
    <img :src="product.image" :alt="product.name" class="product-image" />
    <div class="product-body">
      <div class="product-meta">
        <span>{{ product.category }}</span>
        <span>{{ product.brand }}</span>
      </div>
      <h3>{{ product.name }}</h3>
      <p class="subtitle">{{ product.subtitle }}</p>
      <div class="tag-list">
        <span v-for="tag in product.tags" :key="tag">{{ tag }}</span>
      </div>
      <p class="sales-copy">{{ product.salesCopy }}</p>
      <div class="product-footer">
        <div>
          <strong>¥{{ product.price }}</strong>
          <small>库存 {{ product.stock }}</small>
        </div>
        <el-button type="primary" @click="emit('add', product)">加入购物车</el-button>
      </div>
    </div>
  </article>
</template>

<style scoped>
.product-card {
  overflow: hidden;
  border-radius: 22px;
  background: rgba(255, 252, 247, 0.95);
  border: 1px solid rgba(84, 55, 23, 0.08);
  box-shadow: 0 16px 40px rgba(65, 39, 16, 0.08);
}

.product-image {
  width: 100%;
  height: 220px;
  object-fit: cover;
}

.product-body {
  padding: 18px;
}

.product-meta {
  display: flex;
  justify-content: space-between;
  color: var(--ys-muted);
  font-size: 12px;
}

h3 {
  margin: 12px 0 8px;
  font-size: 20px;
}

.subtitle,
.sales-copy {
  margin: 0;
  color: var(--ys-muted);
  line-height: 1.7;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 14px 0;
}

.tag-list span {
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(31, 122, 107, 0.09);
  color: var(--ys-accent);
  font-size: 12px;
}

.product-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-top: 18px;
}

strong {
  display: block;
  font-size: 24px;
}

small {
  color: var(--ys-muted);
}
</style>
