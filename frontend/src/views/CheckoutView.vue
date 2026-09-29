<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listAddresses } from '@/api/address'
import { checkout } from '@/api/order'
import { formatPrice } from '@/api/product'
import { useCartStore } from '@/stores'
import type { UserAddress } from '@/types/models'

const router = useRouter()
const cart = useCartStore()

const addresses = ref<UserAddress[]>([])
const selectedAddressId = ref<number | null>(null)
const remark = ref('')
const submitting = ref(false)

const selectedAddress = computed(
  () => addresses.value.find((item) => item.id === selectedAddressId.value) ?? null,
)

/** 运费规则尚未实现，先按包邮处理 —— 与后端 checkout 里的 FREIGHT_FREE 一致 */
const freight = 0
const payAmount = computed(() => cart.selectedAmount + freight)

async function submit() {
  const address = selectedAddress.value
  if (!address) {
    ElMessage.warning('请选择收货地址')
    return
  }

  submitting.value = true
  try {
    // 传展开后的收货信息而不是 addressId：订单要存的是下单那一刻的快照，
    // 地址簿之后被改被删都与它无关
    const order = await checkout({
      receiverName: address.receiverName,
      receiverPhone: address.receiverPhone,
      receiverProvince: address.province,
      receiverCity: address.city,
      receiverDistrict: address.district,
      receiverDetail: address.detail,
      remark: remark.value || undefined,
    })
    // 下单成功后车里的已结算条目已被后端清掉，本地也要同步
    await cart.load()
    ElMessage.success('下单成功，请完成支付')
    router.push(`/payment?orderId=${order.id}`)
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  if (cart.items.length === 0) {
    await cart.load()
  }
  if (cart.selectedItems.length === 0) {
    ElMessage.warning('请先在购物车勾选要结算的商品')
    router.replace('/cart')
    return
  }
  addresses.value = await listAddresses()
  const fallback = addresses.value.find((item) => item.isDefault === 1) ?? addresses.value[0]
  selectedAddressId.value = fallback?.id ?? null
})
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Checkout</p>
        <h1>确认订单</h1>
      </div>
    </header>

    <section class="surface">
      <div class="section-head">
        <h2 class="section-title">收货地址</h2>
        <el-button link type="primary" @click="router.push('/profile')">管理地址</el-button>
      </div>

      <el-empty v-if="addresses.length === 0" description="还没有收货地址">
        <el-button type="primary" @click="router.push('/profile')">去添加</el-button>
      </el-empty>

      <ul v-else class="addr-list">
        <li
          v-for="addr in addresses"
          :key="addr.id"
          class="addr"
          :class="{ 'is-active': addr.id === selectedAddressId }"
          @click="selectedAddressId = addr.id"
        >
          <div class="addr__line">
            <strong>{{ addr.receiverName }}</strong>
            <span>{{ addr.receiverPhone }}</span>
            <el-tag v-if="addr.isDefault === 1" size="small" type="success">默认</el-tag>
            <el-tag v-if="addr.tag" size="small" effect="plain">{{ addr.tag }}</el-tag>
          </div>
          <p class="addr__detail">
            {{ addr.province }} {{ addr.city }} {{ addr.district }} {{ addr.detail }}
          </p>
        </li>
      </ul>
    </section>

    <section class="surface">
      <h2 class="section-title">商品清单</h2>
      <ul class="goods">
        <li v-for="item in cart.selectedItems" :key="item.id" class="goods__item">
          <img v-if="item.image" :src="item.image" :alt="item.name" />
          <div class="goods__info">
            <p class="goods__name">{{ item.name }}</p>
            <p v-if="item.specText" class="goods__spec">{{ item.specText }}</p>
          </div>
          <span class="goods__price">{{ formatPrice(item.price) }} × {{ item.quantity }}</span>
          <span class="goods__sum">{{ formatPrice(item.subtotal) }}</span>
        </li>
      </ul>
    </section>

    <section class="surface">
      <h2 class="section-title">订单备注</h2>
      <el-input
        v-model="remark"
        type="textarea"
        :rows="2"
        maxlength="255"
        show-word-limit
        placeholder="选填，如对配送时间的要求"
      />
    </section>

    <footer class="surface bar">
      <dl class="summary">
        <div><dt>商品金额</dt><dd>{{ formatPrice(cart.selectedAmount) }}</dd></div>
        <div><dt>运费</dt><dd>{{ freight === 0 ? '包邮' : formatPrice(freight) }}</dd></div>
        <div class="summary__total">
          <dt>应付</dt>
          <dd>{{ formatPrice(payAmount) }}</dd>
        </div>
      </dl>
      <el-button
        type="primary"
        size="large"
        :loading="submitting"
        :disabled="!selectedAddress"
        @click="submit"
      >
        提交订单
      </el-button>
    </footer>
  </div>
</template>

<style scoped>
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--ys-space-4);
}

.section-head .section-title,
.surface > .section-title {
  margin-bottom: var(--ys-space-4);
}

.section-head .section-title {
  margin-bottom: 0;
}

.addr-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: var(--ys-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.addr {
  padding: var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
  cursor: pointer;
  transition: border-color var(--ys-duration-fast) var(--ys-ease-out);
}

.addr.is-active {
  border-color: var(--color-primary);
  background: var(--color-primary-subtle);
}

.addr__line {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  flex-wrap: wrap;
  font-size: var(--ys-font-sm);
}

.addr__detail {
  margin-top: 4px;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.goods {
  display: grid;
  gap: var(--ys-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.goods__item {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr) 140px 100px;
  gap: var(--ys-space-3);
  align-items: center;
}

.goods__item img {
  width: 56px;
  height: 56px;
  border-radius: var(--ys-radius-sm);
  object-fit: cover;
  background: var(--color-bg-surface-muted);
}

.goods__name {
  font-weight: 600;
}

.goods__spec,
.goods__price {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.goods__sum {
  color: var(--color-primary);
  font-weight: 600;
  text-align: right;
}

.bar {
  position: sticky;
  bottom: var(--ys-space-4);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-6);
}

.summary {
  display: flex;
  gap: var(--ys-space-6);
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.summary div {
  display: flex;
  gap: var(--ys-space-2);
}

.summary dt,
.summary dd {
  margin: 0;
}

.summary__total dd {
  color: var(--color-primary);
  font-size: var(--ys-font-xl);
  font-weight: 700;
}

@media (max-width: 720px) {
  .bar {
    flex-direction: column;
    align-items: stretch;
  }

  .summary {
    flex-direction: column;
    gap: var(--ys-space-1);
  }

  .goods__item {
    grid-template-columns: 56px minmax(0, 1fr);
  }
}
</style>
