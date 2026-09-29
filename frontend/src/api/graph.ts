import request from '@/utils/axios'
import type { GraphEdge, GraphNode, InteractionReport } from '@/types/models'

/**
 * 知识图谱只读接口。与分析层同一条规矩：**公开可读、不需要登录**。
 *
 * 每一条边都带着知识库文档里的原文引文，公开它和公开那些文档是同一件事；
 * 反过来，「登录了才给你看依据」与溯源的目的正好相反。
 */
export async function entityNeighborhood(name: string, depth = 2) {
  const response = await request.get('/knowledge/graph/entity', { params: { name, depth } })
  return response.data.data as GraphEdge[]
}

export async function searchEntities(keyword: string, limit = 20) {
  const response = await request.get('/knowledge/graph/search', { params: { keyword, limit } })
  return response.data.data as GraphNode[]
}

/**
 * 「这几样能不能一起吃」。
 *
 * <b>返回体里的 `available: false` 必须被显示成「没查成」而不是「没冲突」</b> ——
 * 空列表与「无风险」在界面上长得一模一样，而在这个场景里它们是相反的两句话。
 * 后端为此专门没有走「不可用就报错」那条路，就是为了让这里能如实区分。
 */
export async function checkInteractions(items: string[]) {
  const response = await request.get('/knowledge/graph/interactions', {
    params: { items: items.join(',') },
  })
  return response.data.data as InteractionReport
}
