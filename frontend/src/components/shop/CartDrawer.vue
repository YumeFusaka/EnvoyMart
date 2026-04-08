<script setup lang="ts">
import type { CartItem } from '@/types/models'
import { computed, reactive } from 'vue'

const props = defineProps<{
  modelValue: boolean
  items: CartItem[]
  loading: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  updateQuantity: [payload: { id: number; quantity: number }]
  checkout: [payload: { recipientName: string; recipientPhone: string; address: string }]
}>()

const form = reactive({
  recipientName: 'Alice',
  recipientPhone: '13800000000',
  address: 'Shanghai Pudong Expo Avenue 1000'
})

const total = computed(() => props.items.reduce((sum, item) => sum + item.subtotal, 0))

function submitCheckout() {
  emit('checkout', { ...form })
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="购物车与结算"
    size="420px"
    @close="emit('update:modelValue', false)"
  >
    <div class="cart-list">
      <div v-for="item in items" :key="item.id" class="cart-item">
        <img :src="item.image" :alt="item.name" />
        <div class="cart-info">
          <strong>{{ item.name }}</strong>
          <span>¥{{ item.price }}</span>
          <el-input-number
            :min="1"
            :model-value="item.quantity"
            size="small"
            @change="(value: number | undefined) => emit('updateQuantity', { id: item.id, quantity: Number(value || 1) })"
          />
        </div>
        <em>¥{{ item.subtotal }}</em>
      </div>
    </div>

    <el-divider />

    <el-form label-position="top">
      <el-form-item label="收货人">
        <el-input v-model="form.recipientName" />
      </el-form-item>
      <el-form-item label="联系电话">
        <el-input v-model="form.recipientPhone" />
      </el-form-item>
      <el-form-item label="收货地址">
        <el-input v-model="form.address" type="textarea" :rows="3" />
      </el-form-item>
    </el-form>

    <div class="checkout-bar">
      <div>
        <small>合计</small>
        <strong>¥{{ total.toFixed(2) }}</strong>
      </div>
      <el-button :disabled="!items.length" :loading="loading" type="primary" @click="submitCheckout">
        提交订单
      </el-button>
    </div>
  </el-drawer>
</template>

<style scoped>
.cart-list {
  display: grid;
  gap: 16px;
}

.cart-item {
  display: grid;
  grid-template-columns: 80px 1fr auto;
  gap: 12px;
  align-items: center;
  padding: 12px;
  border-radius: 16px;
  background: rgba(182, 91, 46, 0.06);
}

.cart-item img {
  width: 80px;
  height: 80px;
  border-radius: 12px;
  object-fit: cover;
}

.cart-info {
  display: grid;
  gap: 6px;
}

.cart-info span,
small,
em {
  color: var(--ys-muted);
}

.checkout-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

strong {
  display: block;
}
</style>
