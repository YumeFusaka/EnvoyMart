import request from '@/utils/axios'
import type { BadCase } from '@/api/ai'
export async function listBadCases(limit = 100) { const response = await request.get('/ai/admin/bad-cases', { params: { limit } }); return (response.data.data ?? []) as BadCase[] }
export async function reviewBadCase(id: string, status: 'REVIEWED' | 'REJECTED') { const response = await request.post(`/ai/admin/bad-cases/${encodeURIComponent(id)}/review`, null, { params: { status } }); return response.data.data as BadCase }
export interface FixtureAnnotation {
  evalKind: string
  expectRefuse: boolean
  mustMention: string[]
  expectedTools: string[]
  annotation?: string
}
export async function addBadCaseTest(id: string, annotation: FixtureAnnotation) { const response = await request.post(`/ai/admin/bad-cases/${encodeURIComponent(id)}/test-case`, annotation); return response.data.data as BadCase }
export async function removeBadCaseTest(id: string) { await request.delete(`/ai/admin/bad-cases/${encodeURIComponent(id)}/test-case`) }
