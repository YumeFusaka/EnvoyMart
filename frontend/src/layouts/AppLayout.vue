<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound,
  Fold,
  Goods,
  List,
  RefreshLeft,
  ShoppingCart,
  SwitchButton,
  User,
} from '@element-plus/icons-vue'
import { useCartStore, useUserStore } from '@/stores'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const cart = useCartStore()

/** 窄屏下的抽屉菜单。宽屏时导航直接写在顶栏里，这个抽屉不出现 */
const drawerOpen = ref(false)

const navItems = [
  { to: '/shop', label: '商城', icon: Goods },
  { to: '/orders', label: '我的订单', icon: List },
  { to: '/after-sales', label: '退款/售后', icon: RefreshLeft },
  { to: '/assistant', label: '智能助手', icon: ChatDotRound },
]

const displayName = computed(
  () => userStore.profile?.nickname || userStore.profile?.username || '用户',
)

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
  cart.load().catch(() => undefined)
})
</script>

<template>
  <div class="app-layout">
    <header class="app-header">
      <div class="app-header__inner">
        <RouterLink to="/shop" class="app-brand">
          <span class="app-brand__mark" aria-hidden="true">EM</span>
          <span class="app-brand__text">EnvoyMart</span>
        </RouterLink>

        <nav class="app-nav" aria-label="主导航">
          <RouterLink
            v-for="item in navItems"
            :key="item.to"
            :to="item.to"
            class="app-nav__link"
            :class="{ 'is-active': route.path.startsWith(item.to) }"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </RouterLink>
        </nav>

        <div class="app-header__actions">
          <RouterLink to="/cart" class="app-cart" aria-label="购物车">
            <el-badge :value="cart.totalQuantity" :hidden="cart.totalQuantity === 0" :max="99">
              <el-icon :size="18"><ShoppingCart /></el-icon>
            </el-badge>
          </RouterLink>

          <el-dropdown trigger="click">
            <button class="app-user" type="button" aria-label="账号菜单">
              <el-avatar :size="26" :src="userStore.profile?.avatar ?? undefined">
                {{ displayName.slice(0, 1) }}
              </el-avatar>
              <span class="app-user__name">{{ displayName }}</span>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="router.push('/profile')">个人中心</el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

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
    </header>

    <main class="app-main">
      <RouterView />
    </main>

    <el-drawer v-model="drawerOpen" direction="rtl" size="260px" title="导航">
      <nav class="app-nav app-nav--drawer" aria-label="主导航">
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="app-nav__link"
          @click="closeDrawer"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </RouterLink>
        <RouterLink to="/profile" class="app-nav__link" @click="closeDrawer">
          <el-icon><User /></el-icon>
          <span>个人中心</span>
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

.app-header__inner {
  display: flex;
  align-items: center;
  gap: var(--ys-space-6);
  height: var(--layout-header-height);
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

.app-nav {
  display: flex;
  flex: 1;
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
  align-items: center;
  gap: var(--ys-space-3);
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

@media (max-width: 860px) {
  .app-nav {
    display: none;
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
</style>
