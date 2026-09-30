import request from '@/utils/axios'
import type { PageResult } from '@/types/models'
import type { AdminUserDetail, AdminUserQuery, AdminUserSummary } from '@/types/admin'

export async function listUsers(query: AdminUserQuery) {
  const response = await request.get('/auth/admin/users', { params: query })
  return response.data.data as PageResult<AdminUserSummary>
}

export async function getUserDetail(id: string) {
  const response = await request.get(`/auth/admin/users/${id}`)
  return response.data.data as AdminUserDetail
}

/**
 * 禁用 / 启用。禁用必填原因，且原因、操作人、时间三列一并落库。
 * 重复禁用是**幂等**的：第二次不会再覆盖第一次的留痕 ——
 * 否则「这个人当初为什么被禁」会被最后一次误操作抹掉。
 */
export async function changeUserStatus(id: string, status: number, reason?: string) {
  const response = await request.put(`/auth/admin/users/${id}/status`, { status, reason })
  return response.data.data as AdminUserSummary
}

/**
 * 调整角色。改动会写进 Redis 认证态，网关下次鉴权即生效，
 * 不必等旧 Token 过期 —— 所以前端改完不需要提示用户重新登录。
 */
export async function changeUserRole(id: string, role: string) {
  const response = await request.put(`/auth/admin/users/${id}/role`, { role })
  return response.data.data as AdminUserSummary
}
