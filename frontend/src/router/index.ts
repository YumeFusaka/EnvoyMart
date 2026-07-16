import '@/assets/main.css'
import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '@/stores'
import pinia from '@/stores'

const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/shop'
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: {
        public: true
      }
    },
    {
      path: '/shop',
      name: 'shop',
      component: () => import('@/views/ShopView.vue')
    },
    {
      path: '/assistant',
      name: 'assistant',
      component: () => import('@/views/AiAssistantView.vue')
    },
    {
      path: '/products/:id',
      name: 'product-detail',
      component: () => import('@/views/ProductDetailView.vue')
    },
    {
      path: '/orders',
      name: 'orders',
      component: () => import('@/views/OrderDetailView.vue')
    },
    {
      path: '/payment',
      name: 'payment',
      component: () => import('@/views/PaymentView.vue')
    }
  ],
})

router.beforeEach((to) => {
  const userStore = useUserStore(pinia)
  if (to.meta.public) {
    if (to.path === '/login' && userStore.token) {
      return '/shop'
    }
    return true
  }
  if (!userStore.token) {
    return '/login'
  }
  return true
})

export default router
