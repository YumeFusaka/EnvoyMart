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
    return Promise.reject(error)
  },
  (err) => {
    ElMessage({ message: err.response?.data?.data || err.response?.data?.msg || '服务异常', type: 'error' })
    if (err.response?.status === 401) {
      const userStore = useUserStore()
      userStore.clearSession()
      router.push('/login')
    }
    return Promise.reject(err)
  }
)

export default instance
export { baseURL }
export type { ApiResponse }
