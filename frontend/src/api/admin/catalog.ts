import request from '@/utils/axios'
import type { CategoryNode } from '@/types/models'
import type {
  AdminAttribute,
  AdminBrand,
  AttributeUpsertRequest,
  BrandUpsertRequest,
  CategoryUpsertRequest,
} from '@/types/admin'

/**
 * 类目与品牌。两个资源各用完整路径，没有类级前缀 —— 与后端控制器一致。
 * <p>
 * 删除类目会被后端按「下级节点 / 在售商品 / 参数模板引用」三条规则拒绝，
 * 前端不做同样的判断：规则会变，而把规则抄一份到前端只会得到两份不一致的规则。
 */

export async function listCategoryTree() {
  const response = await request.get('/categories/admin/tree')
  return response.data.data as CategoryNode[]
}

export async function createCategory(payload: CategoryUpsertRequest) {
  const response = await request.post('/categories/admin', payload)
  return response.data.data as number
}

export async function updateCategory(id: number, payload: CategoryUpsertRequest) {
  await request.put(`/categories/admin/${id}`, payload)
}

export async function deleteCategory(id: number) {
  await request.delete(`/categories/admin/${id}`)
}

/**
 * 类目的参数模板。路径挂在 `/categories/admin` 下而不是单开 `/attributes/admin`：
 * 参数模板没有「不属于任何类目」的存在形式，独立的顶层资源会凭空多出一种非法状态。
 */
export async function listAttributes(categoryId: number) {
  const response = await request.get(`/categories/admin/${categoryId}/attributes`)
  return response.data.data as AdminAttribute[]
}

export async function createAttribute(categoryId: number, payload: AttributeUpsertRequest) {
  const response = await request.post(`/categories/admin/${categoryId}/attributes`, payload)
  return response.data.data as number
}

export async function updateAttribute(attributeId: number, payload: AttributeUpsertRequest) {
  await request.put(`/categories/admin/attributes/${attributeId}`, payload)
}

export async function deleteAttribute(attributeId: number) {
  await request.delete(`/categories/admin/attributes/${attributeId}`)
}

export async function listBrands() {
  const response = await request.get('/brands/admin')
  return response.data.data as AdminBrand[]
}

export async function createBrand(payload: BrandUpsertRequest) {
  const response = await request.post('/brands/admin', payload)
  return response.data.data as number
}

export async function updateBrand(id: number, payload: BrandUpsertRequest) {
  await request.put(`/brands/admin/${id}`, payload)
}

export async function deleteBrand(id: number) {
  await request.delete(`/brands/admin/${id}`)
}
