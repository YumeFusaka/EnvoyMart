import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '@/stores'
import pinia from '@/stores'

declare module 'vue-router' {
  interface RouteMeta {
    /** 无需登录即可访问。默认所有页面都需要登录 */
    public?: boolean
    /** 页面标题，用于 document.title */
    title?: string
  }
}

/**
 * 路由分两组，用不同布局包裹：
 *   - 业务页面 → AppLayout（顶栏导航）；它们的共同前提是「已登录」
 *   - 登录/注册 → BlankLayout（只有品牌与表单）
 *
 * 为什么不用一个布局加条件渲染：顶栏里全是需要登录才能用的入口，
 * 在登录页上渲染它，等于给用户摆一排走不通的路。
 */
const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: '/',
      component: () => import('@/layouts/AppLayout.vue'),
      children: [
        { path: '', redirect: '/shop' },
        {
          path: 'shop',
          name: 'shop',
          component: () => import('@/views/ShopView.vue'),
          meta: { title: '商城' },
        },
        {
          path: 'products/:id',
          name: 'product-detail',
          component: () => import('@/views/ProductDetailView.vue'),
          meta: { title: '商品详情' },
        },
        {
          path: 'cart',
          name: 'cart',
          component: () => import('@/views/CartView.vue'),
          meta: { title: '购物车' },
        },
        {
          path: 'checkout',
          name: 'checkout',
          component: () => import('@/views/CheckoutView.vue'),
          meta: { title: '确认订单' },
        },
        {
          path: 'orders',
          name: 'orders',
          component: () => import('@/views/OrderListView.vue'),
          meta: { title: '我的订单' },
        },
        {
          path: 'orders/:id',
          name: 'order-detail',
          component: () => import('@/views/OrderDetailView.vue'),
          meta: { title: '订单详情' },
        },
        {
          path: 'coupons',
          name: 'coupons',
          component: () => import('@/views/CouponCenterView.vue'),
          meta: { title: '领券中心' },
        },
        {
          path: 'coupons/mine',
          name: 'my-coupons',
          component: () => import('@/views/MyCouponView.vue'),
          meta: { title: '我的优惠券' },
        },
        {
          path: 'after-sales',
          name: 'after-sales',
          component: () => import('@/views/AfterSaleListView.vue'),
          meta: { title: '退款/售后' },
        },
        {
          path: 'payment',
          name: 'payment',
          component: () => import('@/views/PaymentView.vue'),
          meta: { title: '收银台' },
        },
        {
          path: 'assistant',
          name: 'assistant',
          component: () => import('@/views/AiAssistantView.vue'),
          meta: { title: '智能助手' },
        },
        {
          path: 'profile',
          name: 'profile',
          component: () => import('@/views/ProfileView.vue'),
          meta: { title: '个人中心' },
        },
      ],
    },
    {
      path: '/',
      component: () => import('@/layouts/BlankLayout.vue'),
      children: [
        {
          path: 'login',
          name: 'login',
          component: () => import('@/views/LoginView.vue'),
          meta: { public: true, title: '登录' },
        },
        {
          path: 'register',
          name: 'register',
          component: () => import('@/views/RegisterView.vue'),
          meta: { public: true, title: '注册' },
        },
      ],
    },
    {
      // 通配必须放最后：它匹配一切，放在前面会把所有路由都吃掉
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
      meta: { public: true, title: '页面不存在' },
    },
  ],
})

router.beforeEach((to) => {
  const userStore = useUserStore(pinia)

  if (to.meta.public) {
    // 已登录还去看登录页，多半是点了浏览器后退 —— 送回商城而不是让他重登一次
    if (userStore.token && (to.name === 'login' || to.name === 'register')) {
      return { path: '/shop' }
    }
    return true
  }

  if (!userStore.token) {
    // 带上原目标地址：登完回到被拦下的那一页，而不是一律丢回商城
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · EnvoyMart` : 'EnvoyMart'
})

export default router
