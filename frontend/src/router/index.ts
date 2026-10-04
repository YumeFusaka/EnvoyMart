import { createRouter, createWebHashHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores'
import pinia from '@/stores'

declare module 'vue-router' {
  interface RouteMeta {
    /** 无需登录即可访问。默认所有页面都需要登录 */
    public?: boolean
    /** 页面标题，用于 document.title */
    title?: string
    /**
     * 仅管理员可进。**这只是不把走不通的路摆出来**，不是鉴权：
     * 真正的拦截在网关（`/admin` 路径段判据）与下游 `@RequireAdmin` 两层，
     * 前端守卫拦得住的是误点，拦不住改地址栏。
     */
    admin?: boolean
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
          // 逛商品**不需要登录**。这不是顺手放开：网关的白名单里 `GET /products/**`
          // 从一开始就是公开的（还专门配了反向排除挡住 `/products/admin/**` 与内部接口），
          // 被挡住的一直只是前端这一层守卫。一个要登录才能看的商城，
          // 第一眼就不像电商，而且它让「未登录点收藏 → 引导登录」这个流程无从发生。
          // 购物车、订单、收藏这些**属于某个人的东西**仍然要求登录
          path: 'shop',
          name: 'shop',
          component: () => import('@/views/ShopView.vue'),
          meta: { public: true, title: '商城' },
        },
        {
          path: 'products/:id',
          name: 'product-detail',
          component: () => import('@/views/ProductDetailView.vue'),
          meta: { public: true, title: '商品详情' },
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
          path: 'favorites',
          name: 'favorites',
          component: () => import('@/views/FavoriteListView.vue'),
          meta: { title: '我的收藏' },
        },
        {
          // 评价是「我做过的一件事」的留档，所以放在个人侧而不是商品侧：
          // 商品页只回答「这件东西别人怎么说」，这里回答「我说过什么、现在什么状态」
          path: 'reviews',
          name: 'my-reviews',
          component: () => import('@/views/MyReviewsView.vue'),
          meta: { title: '我的评价' },
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
          // 工单与售后是两条不同的路：售后是**对某件商品的退款诉求**（走审批、动钱），
          // 工单是**对平台说的话**（不涉及金额，客服回话）。合并成一条只会让
          // 「我的退款到哪了」和「这个成分孕妇能吃吗」挤进同一个列表
          path: 'tickets',
          name: 'my-tickets',
          component: () => import('@/views/TicketListView.vue'),
          meta: { title: '我的工单' },
        },
        {
          path: 'tickets/:id',
          name: 'ticket-detail',
          component: () => import('@/views/TicketDetailView.vue'),
          meta: { title: '工单详情' },
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
          // 知识库是**公开**的：AI 回答里的引用要能让任何人自己核对，
          // 点开引用却要求先登录，等于把「可追溯」变成了「本店会员可追溯」。
          path: 'knowledge',
          name: 'knowledge',
          component: () => import('@/views/KnowledgeListView.vue'),
          meta: { public: true, title: '知识库' },
        },
        {
          // 图谱同样公开。两点理由，都不是「顺手」：
          // 一是它每条边都带知识库原文引文，公开它与公开那些文档是同一件事；
          // 二是 AI 回答里引用一条图谱依据时，点进去要能看见那句话的来路，
          // 而「登录了才给你看依据」与溯源的目的正好相反
          path: 'knowledge/graph',
          name: 'knowledge-graph',
          component: () => import('@/views/KnowledgeGraphView.vue'),
          meta: { public: true, title: '关系图谱' },
        },
        {
          // 评测报告同上公开：质量数字要让任何人能自己核对。
          // 「重新运行」按钮只对管理员显示，但真正拦得住的是网关 /admin 段
          // 与下游 @RequireAdmin 两层 —— 前端隐藏只拦误点，拦不住改地址栏
          path: 'knowledge/eval',
          name: 'knowledge-eval',
          component: () => import('@/views/KnowledgeEvalView.vue'),
          meta: { public: true, title: '检索评测' },
        },
        {
          // 回答质量评测：与检索评测是两个量（找得对不对 vs 答得站不站得住），
          // 所以是两页而不是一页两段。公开可读的理由相同 —— 质量数字是声明，
          // 谁都能核对才成立；触发真跑要管理员（花模型配额），在 /ai/admin 段下
          path: 'knowledge/eval/answer',
          name: 'knowledge-eval-answer',
          component: () => import('@/views/AnswerQualityView.vue'),
          meta: { public: true, title: '回答质量评测' },
        },
        {
          // 静态段排在动态段前面只是好读，不是必需：vue-router 按具体度打分，
          // `knowledge/graph` 本来就会赢过 `knowledge/:docNo`，不会被当成一个文档编号
          path: 'knowledge/:docNo',
          name: 'knowledge-doc',
          component: () => import('@/views/KnowledgeDocView.vue'),
          meta: { public: true, title: '知识文档' },
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
      // 管理台。与商城挂在同一个域名下、共用 token，但布局与信息密度完全不同，
      // 所以是第三个布局而不是 AppLayout 里加一个条件分支
      path: '/admin',
      component: () => import('@/layouts/AdminLayout.vue'),
      meta: { admin: true },
      children: [
        {
          path: '',
          name: 'admin-dashboard',
          component: () => import('@/views/admin/AdminDashboardView.vue'),
          meta: { admin: true, title: '概览' },
        },
        {
          path: 'products',
          name: 'admin-products',
          component: () => import('@/views/admin/AdminProductListView.vue'),
          meta: { admin: true, title: '商品管理' },
        },
        {
          path: 'products/new',
          name: 'admin-product-create',
          component: () => import('@/views/admin/AdminProductEditView.vue'),
          meta: { admin: true, title: '新建商品' },
        },
        {
          path: 'products/:id/edit',
          name: 'admin-product-edit',
          component: () => import('@/views/admin/AdminProductEditView.vue'),
          meta: { admin: true, title: '编辑商品' },
        },
        {
          path: 'catalog',
          name: 'admin-catalog',
          component: () => import('@/views/admin/AdminCatalogView.vue'),
          meta: { admin: true, title: '类目与品牌' },
        },
        {
          path: 'orders',
          name: 'admin-orders',
          component: () => import('@/views/admin/AdminOrderView.vue'),
          meta: { admin: true, title: '订单管理' },
        },
        {
          path: 'after-sales',
          name: 'admin-after-sales',
          component: () => import('@/views/admin/AdminAfterSaleView.vue'),
          meta: { admin: true, title: '售后工作台' },
        },
        {
          path: 'reviews',
          name: 'admin-reviews',
          component: () => import('@/views/admin/AdminReviewView.vue'),
          meta: { admin: true, title: '评价管理' },
        },
        {
          path: 'users',
          name: 'admin-users',
          component: () => import('@/views/admin/AdminUserView.vue'),
          meta: { admin: true, title: '用户管理' },
        },
        {
          path: 'tickets',
          name: 'admin-tickets',
          component: () => import('@/views/admin/AdminTicketView.vue'),
          meta: { admin: true, title: '客服工单' },
        },
        {
          // 知识库：上传文档 → 自动切分 → 重建索引进向量库与图谱。
          // 放在 admin 段下是因为「谁都能看依据」（/knowledge/documents 公开只读）
          // 与「谁来写依据」是两件事 —— 读的门开着，写必须换一扇门
          path: 'knowledge',
          name: 'admin-knowledge',
          component: () => import('@/views/admin/AdminKnowledgeView.vue'),
          meta: { admin: true, title: '知识库' },
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

  // 管理台的路由名与路径都带 admin 前缀，用 meta 判定而不是 `path.startsWith('/admin')`：
  // 后者会在将来出现 `/administer` 之类的路径时静默放行
  if (to.meta.admin && userStore.profile?.roleName !== 'ADMIN') {
    ElMessage.error('需要管理员权限')
    return { path: '/shop' }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · EnvoyMart` : 'EnvoyMart'
})

export default router
