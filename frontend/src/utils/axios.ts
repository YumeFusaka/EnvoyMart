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
    return Promise.reject(res.data)
  },
  (err) => {
    ElMessage({ message: err.response?.data?.data || err.response?.data?.msg || '服务异常', type: 'error' })
    console.log(err)
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
