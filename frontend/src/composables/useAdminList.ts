import { computed, onBeforeUnmount, onMounted, ref, type Ref } from 'vue'
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

/**
 * 单列收缩下限。低于它列头文字会折成竖排，比窄更糟。
 * 70 是「两个汉字 + 内边距」的宽度，四个字以上的列头会折行——可以接受。
 */
const minColumnWidth = 70

/**
 * 管理台表格的响应式列宽。
 * <p>
 * <b>它解决的问题是「不是没滚动条，而是没有滚动条」</b>：Element Plus 在「各列
 * width/min-width 声明值之和 > 容器宽」时，走的是**把弹性列砍回 min-width** 这条分支，
 * 而不是让表格横向滚动（\`table-layout.mjs\` 的 scrollX=false 分支）。一旦走进这条分支，
 * 带 \`fixed="right"\` 操作列的表格会变成最右侧那一列被固定列盖住——<b>滚动也看不到</b>，
 * 因为内部 wrapper 的 overflow 仍是 hidden。
 * <p>
 * 所以唯一的出路是**让声明值之和装得下容器**：窄屏时按比例收缩，而不是等它溢出。
 * 收缩有下限（\`min\`），保证文字还能读——比「整列藏着看不见」要好。
 * <p>
 * 比例按「实际容器宽 / 声明值之和」算，因此窗口被拖着缩放时也是连续的，不依赖断点跳变。
 *
 * @param columns 各列的声明宽度与最小宽度（顺序要与模板里一致）
 * @param containerRef 表格容器的引用；宽度的变化源
 */
export function useResponsiveColumns(
  columns: Array<{ width: number; min: number }>,
  containerRef: Ref<HTMLElement | null>,
) {
  const declaredSum = columns.reduce((sum, column) => sum + column.width, 0)

  /** 可用宽度。表格有自己的内边距，容器宽度要扣掉，否则算出来永远差一点 */
  const available = ref(0)

  const widths = computed(() => {
    // 还没量到（首帧）时先用声明值：SSR 与首屏不会有跳变
    if (!available.value) {
      return columns.map((column) => column.width)
    }
    if (available.value >= declaredSum) {
      return columns.map((column) => column.width)
    }
    // 不够宽：所有列**等比例**收缩，而不是把其余列钉死在 min。
    // 钉死 min 是不够的——各列 min 之和本身就可能超过容器（本项目的表在 1040 宽下表宽 728，
    // 而七列 min 之和是 870），那时「收缩」根本没生效，症状原样保留。
    // 比例收缩的代价是每列都会窄一点，但那是「都还能读」，而不是「有一列永久看不见」。
    // 等比例收缩 + 「触底的水位」修正：直接 clamp 会让被下限托住的列吃掉
    // 其他列让出来的份额，总和仍然超容器（实测 744 > 728，白收缩一场）。
    // 所以收缩要迭代：锁定触底的列，把剩余的可用宽度在其余列之间再分一次，
    // 直到总和装得下或所有列都触底
    let locked = columns.map(() => false)
    let remaining = available.value
    let flexibleSum = declaredSum
    for (let round = 0; round < columns.length; round++) {
      const scale = remaining / flexibleSum
      let changed = false
      for (let i = 0; i < columns.length; i++) {
        const column = columns[i]
        if (!column || locked[i]) {
          continue
        }
        if (column.width * scale <= minColumnWidth) {
          locked[i] = true
          remaining -= minColumnWidth
          flexibleSum -= column.width
          changed = true
        }
      }
      if (!changed || flexibleSum <= 0) {
        break
      }
    }
    const finalScale = flexibleSum > 0 ? remaining / flexibleSum : 0
    return columns.map((column, i) =>
      locked[i] ? minColumnWidth : Math.max(minColumnWidth, Math.floor(column.width * finalScale)),
    )
  })

  function measure() {
    const el = containerRef.value
    if (!el) {
      return
    }
    // 量的是 .el-table 本身而不是外层容器：容器带左右 padding（.admin-table 的
    // `padding: 0 var(--ys-space-5)`），用容器宽度会高估约 40px——
    // 高估的后果和没收缩一样，列宽之和仍然超表宽
    const table = el.querySelector<HTMLElement>('.el-table')
    available.value = (table ?? el).clientWidth
  }

  let observer: ResizeObserver | undefined
  onMounted(() => {
    measure()
    if (typeof ResizeObserver !== 'undefined' && containerRef.value) {
      // 监听容器而不是 window：Tauri 桌面窗口可以被任意拖拽缩放，
      // 而且容器宽度还受侧边栏折叠影响——window 的 resize 覆盖不到后者
      observer = new ResizeObserver(measure)
      observer.observe(containerRef.value)
    }
  })
  onBeforeUnmount(() => observer?.disconnect())

  return { widths, measure }
}
