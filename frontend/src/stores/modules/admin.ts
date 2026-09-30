import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listOrders } from '@/api/admin/order'
import { listAfterSales } from '@/api/admin/afterSale'
import { listTickets } from '@/api/admin/ticket'

/**
 * 管理台的「欠账」计数。
 * <p>
 * 这不是一个缓存，是一组**待办数字**，所以按需求驱动：布局挂载时拉一次、
 * 处理完一单后显式刷新一次，不做定时轮询 —— 轮询会让「客服正在看的那一页」
 * 数字自己跳，而他刚处理掉的那条已经不在列表里了。
 *
 * 每个数字都是一次「取第一页、只用 total」的查询，`size: 1` 就够了：
 * 要的是条数不是记录，多取 19 条是白花的带宽。
 * 三个查询并发发，其中一个失败只把它自己置空（显示为无徽标），
 * 不让一个域的故障把整个侧边栏的徽标全抹掉。
 */
export const useAdminStore = defineStore('admin', () => {
  /** 待发货：已付款但未发货 */
  const pendingShipment = ref<number | null>(null)
  /** 待审核售后 */
  const pendingAfterSale = ref<number | null>(null)
  /** 待客服回复的工单（球权在用户侧且未关闭） */
  const pendingTicket = ref<number | null>(null)

  const loading = ref(false)

  async function refresh() {
    loading.value = true
    const results = await Promise.allSettled([
      listOrders({ status: 'PAID', page: 0, size: 1 }).then(
        (r) => (pendingShipment.value = r.total),
      ),
      listAfterSales({ status: 'APPLIED', page: 0, size: 1 }).then(
        (r) => (pendingAfterSale.value = r.total),
      ),
      listTickets({ awaitingAdmin: true, page: 0, size: 1 }).then(
        (r) => (pendingTicket.value = r.total),
      ),
    ])
    // 失败的域置空而不是保留旧值：旧值会让「已经处理完了」和「查不到」看起来一样
    const targets = [pendingShipment, pendingAfterSale, pendingTicket]
    results.forEach((result, index) => {
      if (result.status === 'rejected') {
        targets[index]!.value = null
      }
    })
    loading.value = false
  }

  function reset() {
    pendingShipment.value = null
    pendingAfterSale.value = null
    pendingTicket.value = null
  }

  return { pendingShipment, pendingAfterSale, pendingTicket, loading, refresh, reset }
})
