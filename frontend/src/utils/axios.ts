import { useUserStore } from '@/stores'
import axios from 'axios'
import router from '@/router'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types/models'

const baseURL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

const instance = axios.create({
  baseURL,
  timeout: 100000
})

instance.interceptors.request.use(
  (config) => {
    const userStore = useUserStore()
    if (userStore.token) {
      config.headers.Authorization = 'Bearer ' + userStore.token
    }
    // 请求标识：让人能把这个操作在前端、网关与各服务的日志里串成一条线；
    // 服务端（ai-service）还拿它做请求级幂等 —— 同一个标识重复到达时只执行一次。
    //
    // 挂在 config 上而不是每次都生成：重试（axios 的 config 会被复用）必须带同一个标识，
    // 否则「重试」在服务端看来就是一次全新的提问，幂等无从谈起。
    const cfg = config as typeof config & { __requestId?: string }
    if (!cfg.__requestId) {
      cfg.__requestId =
        typeof crypto !== 'undefined' && crypto.randomUUID
          ? crypto.randomUUID().replace(/-/g, '').slice(0, 16)
          : Math.random().toString(16).slice(2, 18)
    }
    config.headers['X-Request-Id'] = cfg.__requestId
    return config
  },
  (err) => Promise.reject(err)
)


instance.interceptors.response.use(
  (res) => {
    if (res.data.code === 200) {
      return res
    }
    ElMessage({ message: res.data.msg || '服务异常', type: 'error' })
    // 抛 Error 而不是那个响应体对象：页面里写的是 `e instanceof Error ? e.message : '加载失败'`，
    // 抛普通对象会让它们**全部**退化成「加载失败」，后端那句「商品不存在或已下架」就只剩
    // 右上角一闪而过的提示，页面里留给用户重试的地方反而什么都不说。
    // 收口在这里改一处，六个页面同时正确
    const error = new Error(res.data.msg || '服务异常') as Error & { code?: number }
    error.code = res.data.code
    // 标记「这条错误已经给过用户提示」：全局兜底据此去重，
    // 否则同一个失败会先弹一次这里、再由 unhandledrejection 弹第二次
    ;(error as Error & { __notified?: boolean }).__notified = true
    return Promise.reject(error)
  },
  (err) => {
    ElMessage({ message: err.response?.data?.data || err.response?.data?.msg || '服务异常', type: 'error' })
    if (err.response?.status === 401) {
      const userStore = useUserStore()
      userStore.clearSession()
      router.push('/login')
    }
    // 同上：网络/HTTP 错误也标记为已提示
    ;(err as { __notified?: boolean }).__notified = true
    return Promise.reject(err)
  }
)

export default instance
export { baseURL }
export type { ApiResponse }
