import request from '@/utils/axios'
import type { ChatResponse } from '@/types/models'

export async function chat(payload: { sessionId: string; message: string; contextOrderId?: number }) {
  const response = await request.post('/ai/chat', payload)
  return response.data.data as ChatResponse
}
