import request from '@/utils/axios'
import type {
  BrandView,
  CategoryNode,
  PageResult,
  ProductDetail,
  ProductSummary,
  SuggestItem,
} from '@/types/models'

export interface ProductQueryParams {
  keyword?: string
  /** 传一级或二级类目时，后端会自动展开整棵子树 */
  categoryId?: number
  brandId?: number
  /** 单位「分」，与后端一致 */
  minPrice?: number
  maxPrice?: number
  /** sales / price_asc / price_desc / newest，其它值后端回落到 sales */
  sort?: string
  page?: number
  size?: number
}

/**
 * 按条件浏览：走库内查询，适合「点类目、翻页」这类结构化浏览。
 * 带关键词的搜索走 {@link searchProducts}（ES）。
 */
export async function listProducts(params: ProductQueryParams = {}) {
  const response = await request.get('/products', { params })
  return response.data.data as PageResult<ProductSummary>
}

/** 全文检索。索引是 SPU 粒度，返回的商品价格是一个区间 */
export async function searchProducts(params: ProductQueryParams = {}) {
  const response = await request.get('/products/search', { params })
  return response.data.data as PageResult<ProductSummary>
}

export async function getProductDetail(id: number) {
  const response = await request.get(`/products/${id}`)
  return response.data.data as ProductDetail
}

/**
 * 搜索联想。输入两个字就该出候选，所以后端走的是 ES 的前缀查询。
 *
 * 返回项带 `type`：同一个词可能既是商品名又是品牌名（「肌」既是「肌力方」品牌，
 * 也出现在「乳清蛋白粉」这类商品里），界面据此显示不同的去向前缀。
 */
export async function suggestProducts(q: string, limit = 8) {
  const response = await request.get('/products/suggest', { params: { q, limit } })
  return response.data.data as SuggestItem[]
}

/**
 * 热门搜索词。来自真实搜索行为的排行，冷启动时后端回落种子词，
 * 所以这里不会拿到空数组。
 */
export async function getHotKeywords(limit = 8) {
  const response = await request.get('/products/hot-keywords', { params: { limit } })
  return response.data.data as string[]
}

export async function getCategoryTree() {
  const response = await request.get('/categories/tree')
  return response.data.data as CategoryNode[]
}

export async function getBrands() {
  const response = await request.get('/brands')
  return response.data.data as BrandView[]
}

/** 金额按「分」存。展示前统一走这里，避免各页面各写一遍除法 */
export function formatPrice(cents: number | null | undefined): string {
  if (cents === null || cents === undefined) {
    return '—'
  }
  return `¥${(cents / 100).toFixed(2)}`
}

/** 价格区间。两端相同时只显示一个价格，而不是「¥59.00 ~ ¥59.00」 */
export function formatPriceRange(min: number | null, max: number | null): string {
  if (min === null || min === undefined) {
    return '—'
  }
  if (max === null || max === undefined || min === max) {
    return formatPrice(min)
  }
  return `${formatPrice(min)} ~ ${formatPrice(max)}`
}
