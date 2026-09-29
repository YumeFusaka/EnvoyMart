import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  addCartItem,
  getCartItems,
  removeCartItem,
  setAllCartItemsSelected,
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

  async function load() {
    loading.value = true
    try {
      items.value = await getCartItems()
    } finally {
      loading.value = false
    }
  }

  /**
   * 只替换一条，不整页刷新。
   * <p>
   * 每次操作都全量 {@link load} 会把整个列表闪一次 loading，并且把用户
   * 正在操作的其他条目也重置回服务端的值 —— 连点两下数量时表现为「跳回去」。
   */
  function patchOne(updated: CartItem) {
    const index = items.value.findIndex((item) => item.id === updated.id)
    if (index >= 0) {
      items.value[index] = updated
    }
  }

  async function add(skuId: number, quantity: number) {
    await addCartItem({ skuId, quantity })
    // 加购可能触发「同 SKU 累加」，也可能新增条目 —— 这一处仍然全量拉
    await load()
  }

  async function updateQuantity(id: number, quantity: number) {
    patchOne(await updateCartItem(id, { quantity }))
  }

  async function remove(id: number) {
    await removeCartItem(id)
    items.value = items.value.filter((item) => item.id !== id)
  }

  async function toggleSelected(id: number, selected: boolean) {
    patchOne(await setCartItemSelected(id, selected))
  }

  /** 全选/全不选走一个请求 */
  async function toggleAll(selected: boolean) {
    items.value = await setAllCartItemsSelected(selected)
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
    load,
    add,
    updateQuantity,
    remove,
    toggleSelected,
    toggleAll,
    clear,
  }
})
