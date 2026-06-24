import request from '@/utils/axios'
import type { Product } from '@/types/models'

export async function getProducts(params?: { keyword?: string; category?: string }) {
  const response = await request.get('/products', { params })
  return response.data.data as Product[]
}

export async function getProduct(id: number) {
  const response = await request.get(`/products/${id}`)
  return response.data.data as Product
}
