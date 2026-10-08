import request from '@/utils/axios'
import type { BadCase } from '@/api/ai'
export async function listBadCases(limit = 100) { const response = await request.get('/ai/admin/bad-cases', { params: { limit } }); return (response.data.data ?? []) as BadCase[] }
export async function reviewBadCase(id: string, status: 'REVIEWED' | 'REJECTED') { const response = await request.post(`/ai/admin/bad-cases/${encodeURIComponent(id)}/review`, null, { params: { status } }); return response.data.data as BadCase }
export async function addBadCaseTest(id: string) { const response = await request.post(`/ai/admin/bad-cases/${encodeURIComponent(id)}/test-case`); return response.data.data as BadCase }
