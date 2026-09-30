import { ElMessage } from 'element-plus'
import router from '@/router'
import { useUserStore } from '@/stores'

/**
 * 已登录返回 true；未登录则引导登录并返回 false，调用方据此提前返回。
 *
 * 用 `if (!ensureLogin('…')) return` 而不是各写一遍「判断 token → 提示 → 跳转」：
 * 商品浏览是公开的，加购、收藏这些「属于某个人」的动作才要求身份，
 * 这样的入口只会越来越多，而漏掉一次就是一个 401。
 *
 * 跳登录时带上当前地址：登完回到被拦下的那一步，而不是一律丢回商城——
 * 用户是在商品详情页点收藏被拦的，登录后应该回到那件商品，不是从头再找一遍。
 */
export function ensureLogin(message = '登录后即可继续') {
  if (useUserStore().token) {
    return true
  }
  ElMessage.info(message)
  router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  return false
}
