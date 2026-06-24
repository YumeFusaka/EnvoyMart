import request from '@/utils/axios'
import type { Logistics, Order } from '@/types/models'

export async function checkout(payload: {
  recipientName: string
  recipientPhone: string
  address: string
}) {
  const response = await request.post('/orders/checkout', payload)
  return response.data.data as Order
}

export async function getOrders() {
  const response = await request.get('/orders')
  return response.data.data as Order[]
}

export async function getLogistics(orderId: number) {
  const response = await request.get(`/orders/${orderId}/logistics`)
  return response.data.data as Logistics
}
