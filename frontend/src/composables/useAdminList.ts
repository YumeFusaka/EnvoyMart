import { computed, ref, type Ref } from 'vue'
import type { PageResult } from '@/types/models'

/**
 * 管理端列表页的公共状态机。
 * <p>
 * 八个列表页（商品、订单、售后、评价、用户、工单…）的形状是同一套：
 * 一组筛选条件 + 一页记录 + 总数 + 加载态 + 失败态 + 翻页。
 * 各写一遍的结果不是「八份代码」，而是**八个略微不同的 bug**：
 * 这里翻页忘了重置页码、那里筛选没回到第一页、另一个地方失败态和空态分不开。
 *
 * 关键约定：**对外页码是 0 基**（与后端 `zeroBasedPage()` / `mpCurrent()` 对齐），
 * 而 Element Plus 的分页组件是 1 基 —— 转换只在这一个文件里做，
 * 页面里不要出现 `+1` / `-1`，那正是最容易两头都加一次的地方。
 */

export interface AdminListQuery {
  page?: number
  size?: number
}

export interface UseAdminListOptions {
  size?: number
  /** 挂载即加载。设为 false 时由页面自己调 `load()` */
  immediate?: boolean
}

export function useAdminList<T, Q extends AdminListQuery>(
  fetcher: (query: Q) => Promise<PageResult<T>>,
  initialQuery: Q,
  options: UseAdminListOptions = {},
) {
  const size = options.size ?? 20

  const query = ref({
    ...initialQuery,
    page: 0,
    size,
  }) as Ref<Q & { page: number; size: number }>

  const records = ref([]) as Ref<T[]>
  const total = ref(0)
  const loading = ref(false)
  /** 失败原因。与「确实是空的」必须分开：前者要给重试，后者不该有 */
  const error = ref<string | null>(null)

  /** 请求序号。慢的那一次回来时若已经不是最后一次，结果直接丢掉 */
  let latest = 0

  async function load(page?: number) {
    if (typeof page === 'number') {
      query.value.page = Math.max(0, page)
    }
    const ticket = ++latest
    loading.value = true
    try {
      const result = await fetcher(query.value as Q)
      if (ticket !== latest) {
        return
      }
      records.value = result.records ?? []
      total.value = result.total ?? 0
      error.value = null
    } catch (e) {
      if (ticket !== latest) {
        return
      }
      // 拦截器已经弹过一次提示，这里只留页面内的重试入口
      error.value = e instanceof Error ? e.message : '加载失败'
      records.value = []
      total.value = 0
    } finally {
      if (ticket === latest) {
        loading.value = false
      }
    }
  }

  /**
   * 筛选条件变了就回到第一页。
   * <p>
   * 留在原页码是错的：第 3 页是「上一组条件」的第 3 页，
   * 换条件后它很可能越界，表现为一张空表配一个不为零的总数。
   */
  function search() {
    return load(0)
  }

  /** 当前页（1 基），直接喂给 el-pagination */
  const currentPage = computed(() => query.value.page + 1)

  function changePage(oneBased: number) {
    return load(oneBased - 1)
  }

  function changeSize(newSize: number) {
    query.value.size = newSize
    return load(0)
  }

  /**
   * 清空筛选。前提是页面把**所有**筛选项都在 `initialQuery` 里声明了 ——
   * 没声明的字段不在这份初值里，也就清不掉。
   */
  function resetFilters() {
    Object.assign(query.value, initialQuery, { page: 0, size: query.value.size })
    return load(0)
  }

  if (options.immediate !== false) {
    void load(0)
  }

  return {
    query,
    records,
    total,
    loading,
    error,
    load,
    search,
    resetFilters,
    currentPage,
    changePage,
    changeSize,
  }
}
