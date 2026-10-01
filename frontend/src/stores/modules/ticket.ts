import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { getTicketSummary } from '@/api/ticket'
import { useUserStore } from './user'

/**
 * C 端工单的「待我回应」计数，供顶栏与个人中心显示角标。
 *
 * 与管理台的欠账计数同构：这不是缓存，是一个**待办数字**，
 * 按需求驱动（布局挂载拉一次、建单/回复/关闭后显式刷新一次），不做定时轮询 ——
 * 轮询会让用户正在读的那一页数字自己跳。
 *
 * 只存一个数而不是整个 `TicketSummary`：列表页要的四个状态计数它自己拉一份，
 * 两处共用一份缓存的结果是「列表页刚翻页、角标跟着变」这种没人看得懂的联动。
 */
export const useTicketStore = defineStore('ticket', () => {
  const userStore = useUserStore()

  /**
   * 等你回应的条数。`null` = 还没拿到（不显示角标），与 0 是两件事：
   * 0 是「确实没有」，null 是「不知道」，界面上前者该安静、后者不该假装安静。
   */
  const awaitingMe = ref<number | null>(null)

  async function refresh() {
    try {
      awaitingMe.value = (await getTicketSummary()).awaitingMe
    } catch {
      // 拉失败只是不显示角标 —— 一次工单计数查询失败不该在顶栏上留一个错误提示
      awaitingMe.value = null
    }
  }

  function reset() {
    awaitingMe.value = null
  }

  // 换账号就整体作废：`u1001` 的待办数挂到 `u1002` 的顶栏上，是那种"看着不像 bug"的错
  watch(() => userStore.token, reset)

  return { awaitingMe, refresh, reset }
})
