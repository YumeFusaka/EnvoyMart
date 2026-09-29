import request from '@/utils/axios'
import type { CartItem } from '@/types/models'

export async function getCartItems() {
  const response = await request.get('/cart')
  return response.data.data as CartItem[]
}

/**
 * 加购的粒度是 **SKU** 而不是商品：价格与库存都挂在 SKU 上，
 * 用商品粒度加购等于替用户随便挑了一个规格。
 */
export async function addCartItem(payload: { skuId: number; quantity: number }) {
  const response = await request.post('/cart/items', payload)
  return response.data.data as CartItem
}

export async function updateCartItem(id: number, payload: { quantity: number }) {
  const response = await request.put(`/cart/items/${id}`, payload)
  return response.data.data as CartItem
}

export async function removeCartItem(id: number) {
  await request.delete(`/cart/items/${id}`)
}

/** 勾选/取消勾选。结算时只结算勾选的条目 */
export async function setCartItemSelected(id: number, selected: boolean) {
  const response = await request.put(`/cart/items/${id}/selected`, { selected })
  return response.data.data as CartItem
}
