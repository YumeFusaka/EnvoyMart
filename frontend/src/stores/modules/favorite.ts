import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { addFavorite, checkFavorites, removeFavorite } from '@/api/favorite'
import { useUserStore } from './user'

/**
 * 收藏状态的会话内缓存。
 *
 * **为什么需要它**：心形按钮出现在商品列表的每一张卡上，而「这个商品我收藏过没有」只有
 * 服务端知道。没有这层缓存，要么每个页面各自记一份（点收藏后不刷新，另一处的心形还是灰的），
 * 要么每张卡各发一次请求。
 *
 * 两件事在这里一次做完：
 * - **合并请求**：同一轮里到达的 id 攒成一个微任务再一起问后端，一页 12 张卡是一个请求
 *   而不是 12 个——这也是后端 `/favorites/check` 做成批量的理由；
 * - **记住已经问过的 id**：翻回上一页、切类目再切回来，不会把同样的问题再问一遍。
 *
 * 不持久化：它是**服务端数据的影子**，存进 localStorage 只会在换账号、或另一台设备取消收藏后
 * 给出一个陈旧的心形。token 一变（登录、登出、401 被清）整个缓存作废，见下面的 watch。
 */
export const useFavoriteStore = defineStore('favorite', () => {
  const ids = ref(new Set<number>())
  /** 已经问过后端的 id。没有它就没法区分「没收藏」和「还没问过」 */
  const known = ref(new Set<number>())

  let pending: number[] = []
  let scheduled = false

  function isFavorited(spuId: number) {
    return ids.value.has(spuId)
  }

  /**
   * 确保这批商品的收藏状态是已知的。可以随手调：已经问过的不再问，同一轮的多次调用合并成一次请求。
   * 失败只是这批 id 保持未知（按钮维持灰的），不会抛给调用方——一次核对失败不该让页面报错。
   */
  function ensure(spuIds: number[]) {
    const missing = spuIds.filter((id) => !known.value.has(id) && !pending.includes(id))
    if (!missing.length) return
    pending.push(...missing)
    if (scheduled) return
    scheduled = true
    // 微任务而不是定时器：同一轮渲染里所有卡片都已经把 id 交上来了，且不引入额外延迟
    queueMicrotask(flush)
  }

  async function flush() {
    const batch = pending
    pending = []
    scheduled = false
    if (!batch.length) return
    try {
      const favorited = await checkFavorites(batch)
      batch.forEach((id) => known.value.add(id))
      favorited.forEach((id) => ids.value.add(id))
    } catch {
      // 保持未知：下一次 ensure 还会再问。写进 known 的话，这一次失败会变成这个会话里的永久结论
    }
  }

  /**
   * 收藏 / 取消收藏。乐观更新：心形先变，请求失败再回滚。
   *
   * 不乐观的话，点一下要等一个往返才变色，用户会以为没点上而再点一次——那一下正好是取消。
   * 失败时抛出去，由调用方提示并回滚后的状态即为真。
   */
  async function toggle(spuId: number) {
    const wasFavorited = ids.value.has(spuId)
    setFavorited(spuId, !wasFavorited)
    try {
      await (wasFavorited ? removeFavorite(spuId) : addFavorite(spuId))
    } catch (error) {
      setFavorited(spuId, wasFavorited)
      throw error
    }
  }

  /**
   * 本地状态推平（回滚也走这里），顺带把 id 记成「已知」——这次操作本身就是一次权威答复。
   * <p>
   * 也供收藏夹的批量取消使用：那些请求是调用方自己发的，发完得把结果同步进来，
   * 否则心形还会停在「已收藏」上，直到用户刷新页面。
   */
  function setFavorited(spuId: number, favorited: boolean) {
    known.value.add(spuId)
    if (favorited) {
      ids.value.add(spuId)
    } else {
      ids.value.delete(spuId)
    }
  }

  const userStore = useUserStore()
  watch(
    () => userStore.token,
    () => {
      ids.value = new Set()
      known.value = new Set()
      pending = []
    },
  )

  return { ids, isFavorited, ensure, toggle, setFavorited }
})
