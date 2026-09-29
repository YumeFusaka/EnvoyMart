import request from '@/utils/axios'
import type { LoginResponse } from '@/types/models'

export async function login(payload: { username: string; password: string }) {
  const response = await request.post('/auth/login', payload)
  return response.data.data as LoginResponse
}

export async function register(payload: {
  username: string
  nickname: string
  password: string
  phone?: string
}) {
  const response = await request.post('/auth/register', payload)
  return response.data.data as LoginResponse
}
