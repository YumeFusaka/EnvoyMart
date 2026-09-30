import request from '@/utils/axios'
import type { DocumentDetail, DocumentSummary } from '@/types/models'

/**
 * 知识库只读接口。两个都**不需要登录** —— 这是刻意的：
 * 回答里给的依据要能让任何人自己核对，「登录了才给你看依据」与溯源的目的正好相反。
 * 网关的 PUBLIC_RULES 已放行这两条路径。
 */
export async function listDocuments(params: { scope?: string; keyword?: string; status?: number }) {
  const response = await request.get('/knowledge/documents', { params })
  return response.data.data as DocumentSummary[]
}

/** 文档详情（含全文与切片索引），「点引用跳原文」的目的地 */
export async function getDocument(docNo: string) {
  const response = await request.get(`/knowledge/documents/${docNo}`)
  return response.data.data as DocumentDetail
}
