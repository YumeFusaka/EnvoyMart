import request from '@/utils/axios'
import type { LoginResponse } from '@/types/models'

export async function login(payload: { username: string; password: string }) {
  const response = await request.post('/auth/login', payload)
  return response.data.data as LoginResponse
}
