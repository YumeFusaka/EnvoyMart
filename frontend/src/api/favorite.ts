import request from '@/utils/axios'
import type { FavoriteItem, PageResult } from '@/types/models'

/**
 * 收藏。后端是幂等的，重复收藏也算成功，所以调用方不需要先查状态再决定调哪个接口。
 */
export async function addFavorite(spuId: number) {
  await request.post(`/favorites/${spuId}`)
}

/** 取消收藏。同样幂等：没收藏过也返回成功 */
export async function removeFavorite(spuId: number) {
  await request.delete(`/favorites/${spuId}`)
}

export async function listFavorites(page = 0, size = 20) {
  const response = await request.get('/favorites', { params: { page, size } })
  return response.data.data as PageResult<FavoriteItem>
}

/**
 * 批量核对是否已收藏，返回入参的子集。
 *
 * id 列表手动拼成逗号串而不是交给 axios 序列化数组：axios 默认发的是 `spuIds[]=1&spuIds[]=2`，
 * Spring 收到的是名为 `spuIds[]` 的参数，绑定不到 `List<Long> spuIds`——表现是这个接口
 * 永远返回 400/500，而请求看起来完全正常。
 */
export async function checkFavorites(spuIds: number[]) {
  const response = await request.get('/favorites/check', {
    params: { spuIds: spuIds.join(',') },
  })
  return response.data.data as number[]
}
