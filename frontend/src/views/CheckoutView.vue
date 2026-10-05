<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listAddresses } from '@/api/address'
import { checkout, previewOrder } from '@/api/order'
import { formatPrice } from '@/api/product'
import { useCartStore } from '@/stores'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { OrderPreview, UserAddress } from '@/types/models'

const router = useRouter()
const cart = useCartStore()

const addresses = ref<UserAddress[]>([])
const selectedAddressId = ref<number | null>(null)
const remark = ref('')
const submitting = ref(false)

/**
 * 试算结果：金额与每张券的可用性都由服务端算。
 *
 * 商品明细不从这里传 —— 服务端拿购物车里已勾选的条目，与下单时装配的是同一段代码，
 * 所以「这里说能用、提交却被拒」不可能发生。金额同理：折扣只有一个出处，
 * 前端不拿券面文案反推（曾经用正则从「满 100 减 10 元」里解数字，
 * 「8.5 折」这类券的取整方向一旦与后端不同，就差一分钱）。
 */
const preview = ref<OrderPreview | null>(null)
const selectedCouponId = ref<number | null>(null)
/** 切券要重新试算：金额是服务端算的，本地不自己减 */
const previewing = ref(false)

const coupons = computed(() => preview.value?.coupons ?? [])
/** 地址加载完成之前不渲染空态 —— 否则有地址的用户会看到一帧「还没有收货地址」 */
const addressLoading = ref(true)
const addressFailed = ref(false)

const selectedAddress = computed(
  () => addresses.value.find((item) => item.id === selectedAddressId.value) ?? null,
)

const payAmount = computed(() => preview.value?.payAmount ?? cart.selectedAmount)

async function loadPreview() {
  previewing.value = true
  try {
    preview.value = await previewOrder(selectedCouponId.value)
  } catch {
    // 试算失败不该挡住下单 —— 用户按原价结算就是了。
    // 清空而不是留着旧值：旧金额对应的是上一批商品或上一张券，留着比没有更误导
    preview.value = null
  } finally {
    previewing.value = false
  }
}

/**
 * 换券：只改选择，金额等重新试算的结果。
 *
 * 不回滚成「先本地减掉再等服务端」—— 那样页面上会先出现一个可能不对的数字。
 * 试算期间按钮禁用（见模板的 loading），用户点不快也点不乱。
 */
async function selectCoupon(id: number | null) {
  if (selectedCouponId.value === id || previewing.value) {
    return
  }
  selectedCouponId.value = id
  await loadPreview()
}

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
      userCouponId: selectedCouponId.value ?? undefined,
    })
    // 下单成功后车里的已结算条目已被后端清掉，本地也要同步
    await cart.load()
    ElMessage.success('下单成功，请完成支付')
    router.push(`/payment?orderId=${order.id}`)
  } finally {
    submitting.value = false
  }
}

async function loadAddresses() {
  addressLoading.value = true
  addressFailed.value = false
  try {
    addresses.value = await listAddresses()
    const fallback = addresses.value.find((item) => item.isDefault === 1) ?? addresses.value[0]
    selectedAddressId.value = fallback?.id ?? null
  } catch {
    addressFailed.value = true
  } finally {
    addressLoading.value = false
  }
}

onMounted(async () => {
  if (cart.items.length === 0) {
    try {
      await cart.load()
    } catch {
      // 购物车拉不到时不让用户停在一个「选不了商品」的结算页上
      ElMessage.error('购物车加载失败，请重试')
      router.replace('/cart')
      return
    }
  }
  if (cart.selectedItems.length === 0) {
    ElMessage.warning('请先在购物车勾选要结算的商品')
    router.replace('/cart')
    return
  }
  await loadAddresses()
  await loadPreview()
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

      <el-skeleton v-if="addressLoading" :rows="3" animated />

      <!-- 加载失败与「确实没有」必须分开：前者要能重试，后者才是引导去添加 -->
      <ErrorState
        v-else-if="addressFailed"
        message="收货地址加载失败"
        :on-retry="loadAddresses"
      />

      <el-empty v-else-if="addresses.length === 0" description="还没有收货地址">
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

    <section v-if="coupons.length" class="surface">
      <h2 class="section-title">优惠券</h2>
      <!-- 不可用的券也列出来并写明原因：用户会想知道自己那张券为什么没出现在这里。
           理由来自服务端，与提交时被拒的那句话是同一段判定产生的 -->
      <div v-loading="previewing" class="coupons">
        <button
          type="button"
          class="coupon-pick"
          :class="{ 'is-active': selectedCouponId === null }"
          @click="selectCoupon(null)"
        >
          不使用优惠券
        </button>
        <button
          v-for="item in coupons"
          :key="item.id"
          type="button"
          class="coupon-pick"
          :class="{ 'is-active': selectedCouponId === item.id, 'is-disabled': item.usable === false }"
          :disabled="item.usable === false"
          @click="selectCoupon(item.id)"
        >
          <span class="coupon-pick__rule">{{ item.ruleText }}</span>
          <span v-if="item.usable" class="coupon-pick__hint">-{{ formatPrice(item.deductAmount) }}</span>
          <span v-else class="coupon-pick__hint">{{ item.unusableReason }}</span>
        </button>
      </div>
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
      <div class="bar__left">
        <!-- 有失效商品时当场说清：这些行不进结算，提交后它们仍留在购物车 -->
        <p v-if="preview && preview.unavailableCount > 0" class="bar__warn">
          有 {{ preview.unavailableCount }} 件商品已失效，不会进入本单
        </p>
        <dl class="summary">
          <div>
            <dt>商品金额</dt>
            <dd>{{ formatPrice(preview?.totalAmount ?? cart.selectedAmount) }}</dd>
          </div>
          <div>
            <dt>运费</dt>
            <dd>{{ (preview?.freightAmount ?? 0) === 0 ? '包邮' : formatPrice(preview!.freightAmount) }}</dd>
          </div>
          <div v-if="(preview?.discountAmount ?? 0) > 0">
            <dt>优惠券</dt>
            <dd class="summary__discount">-{{ formatPrice(preview!.discountAmount) }}</dd>
          </div>
          <div class="summary__total">
            <dt>应付</dt>
            <dd>{{ formatPrice(payAmount) }}</dd>
          </div>
        </dl>
      </div>
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
  color: var(--color-primary-strong);
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

.bar__left {
  display: grid;
  gap: var(--ys-space-2);
}

.bar__warn {
  color: var(--color-danger-strong);
  font-size: var(--ys-font-xs);
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
  color: var(--color-primary-strong);
  font-size: var(--ys-font-xl);
  font-weight: 700;
}

.summary__discount {
  color: var(--color-danger-strong);
}

.coupons {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
}

.coupon-pick {
  display: grid;
  gap: 2px;
  padding: 6px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface);
  color: var(--color-text-primary);
  font-size: var(--ys-font-sm);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--ys-duration-fast) var(--ys-ease-out);
}

.coupon-pick.is-active {
  border-color: var(--color-primary);
  background: var(--color-primary-subtle);
  color: var(--color-primary-strong);
  font-weight: 600;
}

.coupon-pick.is-disabled {
  color: var(--color-text-muted);
  cursor: not-allowed;
}

.coupon-pick__hint {
  font-size: var(--ys-font-xs);
  font-weight: 400;
  color: var(--color-text-muted);
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
