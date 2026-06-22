import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { UserProfile } from '@/types/models'

// 定义 Store
export const useUserStore = defineStore(
  'user',
  () => {
    const token = ref<string>()
    const profile = ref<UserProfile | null>(null)

    const setToken = (val: string) => {
      token.value = val
    }

    const setProfile = (val: UserProfile | null) => {
      profile.value = val
    }

    const clearToken = () => {
      token.value = undefined
    }

    const clearSession = () => {
      clearToken()
      profile.value = null
    }

    return {
      token,
      profile,
      setToken,
      setProfile,
      clearToken
      ,clearSession
    }
  },
  {
    persist: true
  },
)
