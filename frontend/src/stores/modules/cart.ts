import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  addCartItem,
  getCartItems,
  removeCartItem,
  setCartItemSelected,
  updateCartItem,
} from '@/api/cart'
import type { CartItem } from '@/types/models'

/**
 * 购物车状态。
 * <p>
 * 放在 store 而不是页面里，是因为顶栏的角标、购物车页、结算页都要读它 ——
 * 之前购物车状态压在商城页组件内部，导致订单页必须自己重新拉一遍，
 * 加购之后商城页也并不知道。
 */
export const useCartStore = defineStore('cart', () => {
  const items = ref<CartItem[]>([])
  const loading = ref(false)

  /** 顶栏角标：只算**在售**的件数。失效商品留在车里但计进角标会让人困惑 */
  const totalQuantity = computed(() =>
    items.value.filter((item) => item.available).reduce((sum, item) => sum + item.quantity, 0),
  )

  /** 已勾选且在售的条目 —— 结算的就是这些 */
  const selectedItems = computed(() =>
    items.value.filter((item) => item.selected && item.available),
  )

  /** 已勾选条目的金额合计（分） */
  const selectedAmount = computed(() =>
    selectedItems.value.reduce((sum, item) => sum + item.subtotal, 0),
  )

  const hasUnavailable = computed(() => items.value.some((item) => !item.available))

  async function load() {
    loading.value = true
    try {
      items.value = await getCartItems()
    } finally {
      loading.value = false
    }
  }

  async function add(skuId: number, quantity: number) {
    await addCartItem({ skuId, quantity })
    // 重新拉一次而不是把返回值塞进本地数组：加购可能触发「同 SKU 累加」，
    // 而累加后的小计、库存可用性都要以服务端为准
    await load()
  }

  async function updateQuantity(id: number, quantity: number) {
    await updateCartItem(id, { quantity })
    await load()
  }

  async function remove(id: number) {
    await removeCartItem(id)
    await load()
  }

  async function toggleSelected(id: number, selected: boolean) {
    await setCartItemSelected(id, selected)
    await load()
  }

  function clear() {
    items.value = []
  }

  return {
    items,
    loading,
    totalQuantity,
    selectedItems,
    selectedAmount,
    hasUnavailable,
    load,
    add,
    updateQuantity,
    remove,
    toggleSelected,
    clear,
  }
})
