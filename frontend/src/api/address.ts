import request from '@/utils/axios'
import type { UserAddress } from '@/types/models'

export interface AddressPayload {
  receiverName: string
  receiverPhone: string
  province: string
  city: string
  district: string
  detail: string
  tag?: string
  /** 不传表示不改变当前的默认设置 */
  isDefault?: boolean
}

/** 归属校验在后端做：前端传的是路径 id，用户身份由网关放进请求头，不从参数里取 */
export async function listAddresses() {
  const response = await request.get('/auth/addresses')
  return response.data.data as UserAddress[]
}

export async function createAddress(payload: AddressPayload) {
  const response = await request.post('/auth/addresses', payload)
  return response.data.data as UserAddress
}

export async function updateAddress(id: number, payload: AddressPayload) {
  const response = await request.put(`/auth/addresses/${id}`, payload)
  return response.data.data as UserAddress
}

export async function deleteAddress(id: number) {
  await request.delete(`/auth/addresses/${id}`)
}

export async function setDefaultAddress(id: number) {
  await request.put(`/auth/addresses/${id}/default`)
}
