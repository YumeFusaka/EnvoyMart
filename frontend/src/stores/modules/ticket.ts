import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { getTicketSummary } from '@/api/ticket'
import { subscribeTicketAwaiting } from '@/api/ticketStream'
import { useUserStore } from './user'

/**
 * C 端工单的「待我回应」计数，供顶栏与个人中心显示角标。
 *
 * 与管理台的欠账计数同构：这不是缓存，是一个**待办数字**。
 *
 * 只存一个数而不是整个 `TicketSummary`：列表页要的四个状态计数它自己拉一份，
 * 两处共用一份缓存的结果是「列表页刚翻页、角标跟着变」这种没人看得懂的联动。
 *
 * <b>为什么从「定时轮询」改成了 SSE</b>：原先这里写的是「不做定时轮询，全靠显式
 * refresh」，问题是**客服回话这件事发生在别人的进程里** —— 用户不刷新就永远不知道
 * 客服已经回了。而轮询的代价又不小：一个空转的定时器会让每个开着页面的用户
 * 每分钟都打一次接口，且数字会在用户读页面时自己跳。
 * 现在改成由服务端在写路径上推送（`GET /tickets/stream`），用户不必刷新，
 * 而连接只在**工单列表 / 详情页挂载期间**存在，离开即断开 —— 不占着长连接。
 */
export const useTicketStore = defineStore('ticket', () => {
  const userStore = useUserStore()

  /**
   * 等你回应的条数。`null` = 还没拿到（不显示角标），与 0 是两件事：
   * 0 是「确实没有」，null 是「不知道」，界面上前者该安静、后者不该假装安静。
   */
  const awaitingMe = ref<number | null>(null)

  /** 当前这条 SSE 订阅的取消控制器；null = 没有在订阅 */
  let abortController: AbortController | null = null

  async function refresh() {
    try {
      awaitingMe.value = (await getTicketSummary()).awaitingMe
    } catch {
      // 拉失败只是不显示角标 —— 一次工单计数查询失败不该在顶栏上留一个错误提示
      awaitingMe.value = null
    }
  }

  /**
   * 订阅服务端推送。幂等：重复调用只会保持一条连接。
   * <p>
   * 返回一个停止函数，调用方（组件）在 onUnmounted 里执行它 —— 不用
   * `store.$dispose`，因为这份订阅的生命周期属于「哪个页面开着」，不属于 store。
   */
  function subscribe(): () => void {
    if (abortController) return stop

    const controller = new AbortController()
    abortController = controller
    run(controller)
    return stop
  }

  /** 连接正常结束（服务端 30 分钟寿命到点）后自动重订，直到被显式 stop */
  async function run(controller: AbortController) {
    try {
      await subscribeTicketAwaiting((payload) => {
        awaitingMe.value = payload.awaitingMe
      }, controller.signal)
      // 正常返回 = 服务端主动收尾（寿命到点）。仍在订阅状态就重连一次：
      // 这不是错误恢复，是长连接协议里正常的一环
      if (!controller.signal.aborted) {
        run(controller)
      }
    } catch {
      // 断线（含 abort）不重试：abort 是组件卸载，断线则交给用户的下一次操作。
      // 无脑重连会让「后端挂了」变成前端持续打接口，而用户看到的角标还是不更新
    }
  }

  function stop() {
    abortController?.abort()
    abortController = null
  }

  function reset() {
    awaitingMe.value = null
    // 换账号必须断开旧连接：否则上一个人的工单推送会继续更新这个人的角标
    stop()
  }

  // 换账号就整体作废：`u1001` 的待办数挂到 `u1002` 的顶栏上，是那种"看着不像 bug"的错
  watch(() => userStore.token, reset)

  return { awaitingMe, refresh, subscribe, reset }
})
