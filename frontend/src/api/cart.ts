import request from '@/utils/axios'
import type { CartItem } from '@/types/models'

export async function getCartItems() {
  const response = await request.get('/cart')
  return response.data.data as CartItem[]
}

export async function addCartItem(payload: { productId: number; quantity: number }) {
  const response = await request.post('/cart/items', payload)
  return response.data.data as CartItem
}

export async function updateCartItem(id: number, payload: { quantity: number }) {
  const response = await request.put(`/cart/items/${id}`, payload)
  return response.data.data as CartItem
}
