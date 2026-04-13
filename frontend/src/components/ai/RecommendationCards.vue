<script setup lang="ts">
import type { Product } from '@/types/models'

defineProps<{
  products: Product[]
}>()

const emit = defineEmits<{
  open: [product: Product]
}>()
</script>

<template>
  <div class="recommendation-grid">
    <article v-for="product in products" :key="product.id" class="recommendation-card" @click="emit('open', product)">
      <img :src="product.image" :alt="product.name" />
      <div>
        <strong>{{ product.name }}</strong>
        <p>{{ product.salesCopy }}</p>
        <span>¥{{ product.price }}</span>
      </div>
    </article>
  </div>
</template>

<style scoped>
.recommendation-grid {
  display: grid;
  gap: 12px;
}

.recommendation-card {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 12px;
  padding: 12px;
  border-radius: 16px;
  background: rgba(31, 122, 107, 0.08);
  cursor: pointer;
}

.recommendation-card img {
  width: 72px;
  height: 72px;
  object-fit: cover;
  border-radius: 12px;
}

.recommendation-card p {
  margin: 6px 0;
  color: var(--ys-muted);
  font-size: 13px;
}

.recommendation-card span {
  color: var(--ys-primary-deep);
  font-weight: 700;
}
</style>
