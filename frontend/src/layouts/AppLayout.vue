<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound,
  Discount,
  Fold,
  Goods,
  List,
  Reading,
  RefreshLeft,
  Setting,
  ShoppingCart,
  Star,
  SwitchButton,
  User,
} from '@element-plus/icons-vue'
import SearchBox, { type SearchIntent } from '@/components/shop/SearchBox.vue'
import { useCartStore, useUserStore } from '@/stores'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const cart = useCartStore()

/**
 * 顶栏搜索框里的文字。
 *
 * 只在商城页与 URL 同步：搜索框是全局的，URL 只有商城页读得懂。
 * 无条件跟着 URL 清空的话，用户搜完再点开「我的订单」，回来时输入框已经空了，
 * 想改一个词重搜就得整个重打。
 */
const searchKeyword = ref('')

watch(
  () => route.fullPath,
  () => {
    if (route.path === '/shop') {
      searchKeyword.value = typeof route.query.keyword === 'string' ? route.query.keyword : ''
    }
  },
  { immediate: true },
)

/**
 * 把搜索意图翻译成去处。
 *
 * 这是整条搜索链路上唯一知道「去哪里」的地方：联想组件只描述用户点了什么，
 * 商城页只认 URL 上的筛选条件。三者各自独立，改路由不用动组件。
 */
function onSearch(intent: SearchIntent) {
  if (intent.kind === 'product') {
    router.push(`/products/${intent.id}`)
    return
  }
  if (intent.kind === 'brand') {
    router.push({ path: '/shop', query: { brandId: String(intent.id) } })
    return
  }
  if (intent.kind === 'category') {
    router.push({ path: '/shop', query: { categoryId: String(intent.id) } })
    return
  }
  // 词为空时不带 keyword 参数，URL 上留一个 ?keyword= 会让人以为筛了什么
  router.push(intent.keyword ? { path: '/shop', query: { keyword: intent.keyword } } : { path: '/shop' })
}

/** 窄屏下的抽屉菜单。宽屏时导航直接写在顶栏里，这个抽屉不出现 */
const drawerOpen = ref(false)

const navItems = [
  { to: '/shop', label: '商城', icon: Goods },
  { to: '/orders', label: '我的订单', icon: List },
  { to: '/favorites', label: '我的收藏', icon: Star },
  { to: '/after-sales', label: '退款/售后', icon: RefreshLeft },
  { to: '/coupons', label: '领券中心', icon: Discount },
  { to: '/assistant', label: '智能助手', icon: ChatDotRound },
  // 知识库对外公开（引用要让任何人能自己核对），所以它也是**未登录**时唯一能用的入口
  { to: '/knowledge', label: '知识库', icon: Reading },
]

/**
 * 未登录时只留公开入口，而不是把整条导航渲染成一片点了就跳登录页的死链。
 * 商城的商品浏览与知识库（含原文、图谱、评测报告）都是公开的——网关侧一直是这个口径，
 * 前端守卫跟上之后，顶栏必须能在未登录状态下站得住。
 */
const PUBLIC_ROUTES = ['/shop', '/knowledge']
const publicNavItems = navItems.filter((item) => PUBLIC_ROUTES.includes(item.to))
const visibleNavItems = computed(() => (userStore.token ? navItems : publicNavItems))

const displayName = computed(
  () => userStore.profile?.nickname || userStore.profile?.username || '用户',
)

/**
 * 管理台入口只对管理员展示。
 * <p>
 * 这不是鉴权 —— 真正的拦截在网关与各服务的 `@RequireAdmin`。这里只是不把一条走不通的路
 * 摆给普通用户看。反过来，角色是以**服务端返回的 profile** 为准的，前端不解析 Token、不猜。
 */
const isAdmin = computed(() => userStore.profile?.roleName === 'ADMIN')

function handleLogout() {
  userStore.clearSession()
  ElMessage.success('已退出登录')
  router.push('/login')
}

/** 抽屉里点完导航要关掉它，否则它会盖在刚跳过去的页面上 */
function closeDrawer() {
  drawerOpen.value = false
}

onMounted(() => {
  // 顶栏角标要显示件数，所以进任意页面都拉一次。
  // 拉失败就只是不显示角标 —— 一次购物车查询失败不该让整个页面打不开
  // 未登录不发这一枪：公开页（知识库）上它必然 401，白白在控制台留一条红字
  if (userStore.token) {
    cart.load().catch(() => undefined)
  }
})
</script>

<template>
  <div class="app-layout">
    <header class="app-header">
      <div class="app-header__bar">
        <div class="app-header__inner">
          <RouterLink to="/shop" class="app-brand">
            <span class="app-brand__mark" aria-hidden="true">EM</span>
            <span class="app-brand__text">EnvoyMart</span>
          </RouterLink>

          <SearchBox v-model="searchKeyword" @search="onSearch" />

          <div class="app-header__actions">
            <RouterLink v-if="userStore.token" to="/cart" class="app-cart" aria-label="购物车">
              <el-badge :value="cart.totalQuantity" :hidden="cart.totalQuantity === 0" :max="99">
                <el-icon :size="18"><ShoppingCart /></el-icon>
              </el-badge>
            </RouterLink>

            <el-dropdown v-if="userStore.token" trigger="click">
              <button class="app-user" type="button" aria-label="账号菜单">
                <el-avatar :size="26" :src="userStore.profile?.avatar ?? undefined">
                  {{ displayName.slice(0, 1) }}
                </el-avatar>
                <span class="app-user__name">{{ displayName }}</span>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="router.push('/profile')">个人中心</el-dropdown-item>
                  <el-dropdown-item v-if="isAdmin" @click="router.push('/admin')"
                    >管理台</el-dropdown-item
                  >
                  <el-dropdown-item divided @click="handleLogout">
                    <el-icon><SwitchButton /></el-icon>
                    退出登录
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>

            <!-- 未登录时给的是登录入口而不是一个点不开的头像 -->
            <RouterLink v-else class="app-login" :to="{ name: 'login' }">登录</RouterLink>

            <button
              class="app-menu-toggle"
              type="button"
              aria-label="打开导航菜单"
              @click="drawerOpen = true"
            >
              <el-icon :size="18"><Fold /></el-icon>
            </button>
          </div>
        </div>
      </div>

      <div class="app-header__sub">
        <nav class="app-header__inner app-nav" aria-label="主导航">
          <RouterLink
            v-for="item in visibleNavItems"
            :key="item.to"
            :to="item.to"
            class="app-nav__link"
            :class="{ 'is-active': route.path.startsWith(item.to) }"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </RouterLink>
        </nav>
      </div>
    </header>

    <main class="app-main">
      <RouterView />
    </main>

    <el-drawer v-model="drawerOpen" direction="rtl" size="260px" title="导航">
      <nav class="app-nav app-nav--drawer" aria-label="主导航">
        <RouterLink
          v-for="item in visibleNavItems"
          :key="item.to"
          :to="item.to"
          class="app-nav__link"
          @click="closeDrawer"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </RouterLink>
        <RouterLink v-if="userStore.token" to="/profile" class="app-nav__link" @click="closeDrawer">
          <el-icon><User /></el-icon>
          <span>个人中心</span>
        </RouterLink>
        <RouterLink v-if="isAdmin" to="/admin" class="app-nav__link" @click="closeDrawer">
          <el-icon><Setting /></el-icon>
          <span>管理台</span>
        </RouterLink>
        <RouterLink v-else to="/login" class="app-nav__link" @click="closeDrawer">
          <el-icon><User /></el-icon>
          <span>登录 / 注册</span>
        </RouterLink>
      </nav>
    </el-drawer>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  /*
   * 顶栏两行：上排「标志 + 搜索 + 账号」，下排导航。
   * 覆盖在这一层而不是改 tokens.css：管理台用的是另一套布局，顶栏仍是单行 60px，
   * 全局改掉会让它的内容区短掉 44px。自定义属性会往下继承，所以页面里那些
   * 「吸顶偏移 = 顶栏高度」的算式（商城侧栏、知识库目录、对话页）自动跟着对。
   */
  --header-bar-height: 60px;
  --header-sub-height: 44px;
  /* 加上下边框那条分隔线：这个变量的用途是「页面内容从顶栏下面开始」的偏移量，
     少算 1px 会让吸顶的侧栏被顶栏压住一条头发丝宽的边 */
  --header-divider: 1px;
  --layout-header-height: calc(
    var(--header-bar-height) + var(--header-sub-height) + var(--header-divider)
  );
}

.app-header {
  position: sticky;
  top: 0;
  /* 层级用语义 token 而不是裸写数字：裸写迟早会演变成 9999 打 9998 */
  z-index: var(--ys-z-dropdown);
  background: color-mix(in srgb, var(--color-bg-surface) 88%, transparent);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--color-border);
}

.app-header__bar {
  height: var(--header-bar-height);
}

/* 下排比上排矮、底色也压一档：导航是「次级入口」，和搜索抢视觉重量会显得整条顶栏很吵 */
.app-header__sub {
  height: var(--header-sub-height);
  border-top: 1px solid var(--color-border);
  background: color-mix(in srgb, var(--color-bg-surface-muted) 70%, transparent);
}

.app-header__inner {
  display: flex;
  align-items: center;
  gap: var(--ys-space-5);
  height: 100%;
  max-width: var(--layout-content-max);
  margin: 0 auto;
  padding: 0 var(--ys-space-8);
}

.app-brand {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  font-weight: 700;
}

.app-brand__mark {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: var(--ys-radius-sm);
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  font-size: var(--ys-font-xs);
  letter-spacing: 0.04em;
}

.app-brand__text {
  font-size: var(--ys-font-md);
}

/* 下排本身就是容器本体，它复用 .app-header__inner 的版心与内边距 */
.app-nav {
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
}

.app-nav__link {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-radius: var(--ys-radius-sm);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-base);
  transition:
    background-color var(--ys-duration-fast) var(--ys-ease-out),
    color var(--ys-duration-fast) var(--ys-ease-out);
}

.app-nav__link:hover,
.app-nav__link.is-active {
  background: var(--color-primary-subtle);
  color: var(--color-primary);
}

.app-nav__link.is-active {
  font-weight: 600;
}

.app-header__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: var(--ys-space-3);
  /* 搜索框吃掉剩余宽度，账号区永远贴右，不随搜索框长度浮动 */
  margin-inline-start: auto;
}

.app-cart {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-sm);
  color: var(--color-text-primary);
  transition: border-color var(--ys-duration-fast) var(--ys-ease-out);
}

.app-cart:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.app-user {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 10px 3px 3px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-surface);
  color: var(--color-text-primary);
  cursor: pointer;
  transition: border-color var(--ys-duration-fast) var(--ys-ease-out);
}

.app-user:hover {
  border-color: var(--color-primary-border);
}

.app-user__name {
  font-size: var(--ys-font-sm);
}

.app-login {
  padding: 6px 16px;
  border: 1px solid var(--color-primary);
  border-radius: var(--ys-radius-full);
  color: var(--color-primary);
  font-size: var(--ys-font-sm);
  transition:
    background-color var(--ys-duration-fast) var(--ys-ease-out),
    color var(--ys-duration-fast) var(--ys-ease-out);
}

.app-login:hover {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
}

.app-menu-toggle {
  display: none;
  align-items: center;
  justify-content: center;
  /* 最小可点击区域 36px，高于 WCAG 的 24px 下限 */
  width: 36px;
  height: 36px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface);
  color: var(--color-text-primary);
  cursor: pointer;
}

.app-main {
  flex: 1;
}

.app-nav--drawer {
  flex-direction: column;
  align-items: stretch;
}

/* 1080 而不是 960：导航有七个入口，窄一点就挤成两行或溢出。
   挤不下时收进抽屉比硬塞着强 */
@media (max-width: 1080px) {
  /* 导航整排收进抽屉，顶栏回到单行 —— 本排只剩一条空底边会很难看，
     而顶栏少了 44px，页面里那些按顶栏高度算的吸顶偏移必须跟着回退 */
  .app-header__sub {
    display: none;
  }

  .app-layout {
    --layout-header-height: calc(var(--header-bar-height) + var(--header-divider));
  }

  .app-menu-toggle {
    display: flex;
  }

  .app-header__inner {
    gap: var(--ys-space-3);
    padding: 0 var(--ys-space-4);
  }

  .app-user__name {
    display: none;
  }
}

/* 再窄就只剩「标志 + 搜索 + 账号图标」，品牌文字让位给输入框 */
@media (max-width: 720px) {
  .app-brand__text {
    display: none;
  }
}
</style>
