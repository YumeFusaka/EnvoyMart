<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Collection,
  ChatLineSquare,
  Expand,
  Files,
  Fold,
  Goods,
  List,
  Odometer,
  RefreshLeft,
  Service,
  Setting,
  Shop,
  SwitchButton,
  User,
} from '@element-plus/icons-vue'
import { useAdminStore, useUserStore } from '@/stores'

/**
 * 管理台外壳。
 * <p>
 * 与商城主站**共用一套 token**，但读起来是两个界面：侧栏用深色反底
 * （`--color-bg-inverse`），主站是暖白卡片流。这不是为了「看起来不一样」——
 * 管理台是长时间、高频、以表格为主的工作界面，与商城的浏览型界面本来就该有不同的
 * 视觉密度；同屏出现时也必须一眼分得清自己在哪一侧，否则客服会把后台当成用户页操作。
 *
 * 导航分组顺序跟着**一条订单从生到死**走：商品 → 交易 → 售后 → 评价，最后是人的一侧
 * （用户、工单）。找入口时脑子里想的是「这单卡在哪一步」，而不是「这功能属于哪个服务」。
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const adminStore = useAdminStore()

/** 折叠只影响侧栏宽度，不影响任何导航行为 */
const collapsed = ref(false)
const drawerOpen = ref(false)

interface NavItem {
  to: string
  label: string
  icon: unknown
  /** 待办徽标取哪个计数；null 表示这个入口没有欠账概念 */
  badge?: 'pendingShipment' | 'pendingAfterSale' | 'pendingTicket'
}

const navGroups: { title: string; items: NavItem[] }[] = [
  {
    title: '工作台',
    items: [{ to: '/admin', label: '概览', icon: Odometer }],
  },
  {
    title: '商品',
    items: [
      { to: '/admin/products', label: '商品管理', icon: Goods },
      { to: '/admin/catalog', label: '类目与品牌', icon: Files },
    ],
  },
  {
    title: '交易',
    items: [
      { to: '/admin/orders', label: '订单管理', icon: List, badge: 'pendingShipment' },
      {
        to: '/admin/after-sales',
        label: '售后工作台',
        icon: RefreshLeft,
        badge: 'pendingAfterSale',
      },
    ],
  },
  {
    title: '运营',
    items: [
      { to: '/admin/reviews', label: '评价管理', icon: ChatLineSquare },
      { to: '/admin/knowledge', label: '知识库', icon: Collection },
      { to: '/admin/users', label: '用户管理', icon: User },
      { to: '/admin/tickets', label: '客服工单', icon: Service, badge: 'pendingTicket' },
    ],
  },
]

const displayName = computed(
  () => userStore.profile?.nickname || userStore.profile?.username || '管理员',
)

/**
 * 当前页标题。取 `meta.title` 而不是在布局里再维护一份路由表 ——
 * 两份表迟早会对不上，而 `document.title` 用的正是同一个字段。
 */
const pageTitle = computed(() => (route.meta.title as string | undefined) ?? '管理台')

/**
 * 命中判定要**精确**：`/admin/products/12` 应当点亮「商品管理」，
 * 但 `/admin` 是其它所有路径的前缀 —— 直接用 startsWith 会让概览在每一页都亮着。
 */
function isActive(to: string) {
  return to === '/admin' ? route.path === '/admin' : route.path.startsWith(to)
}

function badgeOf(item: NavItem): number {
  if (!item.badge) {
    return 0
  }
  return adminStore[item.badge] ?? 0
}

function go(to: string) {
  drawerOpen.value = false
  router.push(to)
}

function handleLogout() {
  userStore.clearSession()
  adminStore.reset()
  ElMessage.success('已退出登录')
  router.push('/login')
}

onMounted(() => {
  adminStore.refresh()
})
</script>

<template>
  <div class="admin-layout" :class="{ 'is-collapsed': collapsed }">
    <!-- 桌面侧栏。窄屏隐藏，由抽屉顶上 -->
    <aside class="admin-aside" aria-label="管理台导航">
      <div class="admin-brand">
        <span class="admin-brand__mark" aria-hidden="true">EM</span>
        <span v-show="!collapsed" class="admin-brand__text">
          <strong>EnvoyMart</strong>
          <small>运营管理台</small>
        </span>
      </div>

      <nav class="admin-nav">
        <template v-for="group in navGroups" :key="group.title">
          <p v-show="!collapsed" class="admin-nav__group">{{ group.title }}</p>
          <button
            v-for="item in group.items"
            :key="item.to"
            type="button"
            class="admin-nav__link"
            :class="{ 'is-active': isActive(item.to) }"
            :title="collapsed ? item.label : undefined"
            :aria-current="isActive(item.to) ? 'page' : undefined"
            @click="go(item.to)"
          >
            <el-icon :size="17"><component :is="item.icon" /></el-icon>
            <span v-show="!collapsed" class="admin-nav__label">{{ item.label }}</span>
            <el-badge
              v-if="badgeOf(item) > 0"
              :value="badgeOf(item)"
              :max="99"
              class="admin-nav__badge"
              :class="{ 'is-dot-only': collapsed }"
            />
          </button>
        </template>
      </nav>

      <div class="admin-aside__foot">
        <button type="button" class="admin-nav__link" title="返回商城" @click="go('/shop')">
          <el-icon :size="17"><Shop /></el-icon>
          <span v-show="!collapsed" class="admin-nav__label">返回商城</span>
        </button>
        <button
          type="button"
          class="admin-nav__link admin-nav__link--quiet"
          :aria-label="collapsed ? '展开侧栏' : '收起侧栏'"
          @click="collapsed = !collapsed"
        >
          <el-icon :size="17"><component :is="collapsed ? Expand : Fold" /></el-icon>
          <span v-show="!collapsed" class="admin-nav__label">收起侧栏</span>
        </button>
      </div>
    </aside>

    <div class="admin-body">
      <header class="admin-header">
        <button
          type="button"
          class="admin-header__menu"
          aria-label="打开导航菜单"
          @click="drawerOpen = true"
        >
          <el-icon :size="18"><Fold /></el-icon>
        </button>

        <h1 class="admin-header__title">{{ pageTitle }}</h1>

        <div class="admin-header__actions">
          <button
            type="button"
            class="admin-header__action"
            :disabled="adminStore.loading"
            @click="adminStore.refresh()"
          >
            {{ adminStore.loading ? '刷新中…' : '刷新待办' }}
          </button>

          <el-dropdown trigger="click">
            <button class="admin-operator" type="button" aria-label="账号菜单">
              <el-avatar :size="26" :src="userStore.profile?.avatar ?? undefined">
                {{ displayName.slice(0, 1) }}
              </el-avatar>
              <span class="admin-operator__name">{{ displayName }}</span>
              <el-tag size="small" type="warning" effect="plain">管理员</el-tag>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="router.push('/profile')">
                  <el-icon><Setting /></el-icon>
                  个人中心
                </el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="admin-main">
        <RouterView />
      </main>
    </div>

    <el-drawer v-model="drawerOpen" direction="ltr" size="240px" title="管理台导航">
      <nav class="admin-nav admin-nav--drawer">
        <template v-for="group in navGroups" :key="group.title">
          <p class="admin-nav__group">{{ group.title }}</p>
          <button
            v-for="item in group.items"
            :key="item.to"
            type="button"
            class="admin-nav__link"
            :class="{ 'is-active': isActive(item.to) }"
            @click="go(item.to)"
          >
            <el-icon :size="17"><component :is="item.icon" /></el-icon>
            <span class="admin-nav__label">{{ item.label }}</span>
            <el-badge
              v-if="badgeOf(item) > 0"
              :value="badgeOf(item)"
              :max="99"
              class="admin-nav__badge"
            />
          </button>
        </template>
        <p class="admin-nav__group">返回</p>
        <button type="button" class="admin-nav__link" @click="go('/shop')">
          <el-icon :size="17"><Shop /></el-icon>
          <span class="admin-nav__label">返回商城</span>
        </button>
      </nav>
    </el-drawer>
  </div>
</template>

<style scoped>
.admin-layout {
  display: flex;
  min-height: 100vh;
  background: var(--color-bg-page);
}

/* ==================== 侧栏 ==================== */

.admin-aside {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  width: 224px;
  height: 100vh;
  flex: none;
  padding: var(--ys-space-4) var(--ys-space-3);
  background: var(--color-bg-inverse);
  transition: width var(--ys-duration-base) var(--ys-ease-out);
}

.admin-layout.is-collapsed .admin-aside {
  width: 72px;
}

.admin-brand {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  padding: var(--ys-space-2) var(--ys-space-2) var(--ys-space-5);
}

.admin-brand__mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: none;
  border-radius: var(--ys-radius-sm);
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  font-size: var(--ys-font-xs);
  font-weight: 700;
  letter-spacing: 0.04em;
}

.admin-brand__text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  color: var(--color-text-inverse);
  overflow: hidden;
  white-space: nowrap;
}

.admin-brand__text small {
  color: rgba(255, 255, 255, 0.55);
  font-size: var(--ys-font-xs);
}

.admin-nav {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
}

.admin-nav__group {
  margin: var(--ys-space-4) 0 var(--ys-space-1);
  padding-inline-start: var(--ys-space-3);
  color: rgba(255, 255, 255, 0.42);
  font-size: var(--ys-font-xs);
  letter-spacing: 0.08em;
}

.admin-nav__group:first-child {
  margin-top: 0;
}

.admin-nav__link {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  width: 100%;
  min-height: 38px;
  padding: 0 var(--ys-space-3);
  border: 0;
  border-radius: var(--ys-radius-sm);
  background: transparent;
  color: rgba(255, 255, 255, 0.72);
  font-size: var(--ys-font-base);
  text-align: start;
  cursor: pointer;
  transition:
    background-color var(--ys-duration-fast) var(--ys-ease-out),
    color var(--ys-duration-fast) var(--ys-ease-out);
}

.admin-nav__link:hover {
  background: rgba(255, 255, 255, 0.08);
  color: var(--color-text-inverse);
}

.admin-nav__link.is-active {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  font-weight: 600;
}

/* 焦点态不能被深色底吞掉：默认 outline 在深底上几乎看不见 */
.admin-nav__link:focus-visible {
  outline: 2px solid var(--ys-terracotta-300);
  outline-offset: 2px;
}

.admin-nav__label {
  flex: 1;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.admin-nav__badge {
  flex: none;
}

.admin-nav__badge :deep(.el-badge__content) {
  position: static;
  transform: none;
  border: 0;
}

/* 折叠时徽标收成一枚圆点：宽度只剩 72px，数字会把它挤到图标上 */
.admin-nav__badge.is-dot-only :deep(.el-badge__content) {
  width: 8px;
  height: 8px;
  padding: 0;
  border-radius: var(--ys-radius-full);
  font-size: 0;
}

.admin-aside__foot {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: var(--ys-space-4);
  padding-top: var(--ys-space-4);
  border-top: 1px solid rgba(255, 255, 255, 0.12);
}

.admin-nav__link--quiet {
  color: rgba(255, 255, 255, 0.5);
}

/* ==================== 主区 ==================== */

.admin-body {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
}

.admin-header {
  position: sticky;
  top: 0;
  z-index: var(--ys-z-dropdown);
  display: flex;
  align-items: center;
  gap: var(--ys-space-4);
  height: var(--layout-header-height);
  padding: 0 var(--ys-space-6);
  background: color-mix(in srgb, var(--color-bg-surface) 88%, transparent);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--color-border);
}

.admin-header__title {
  flex: 1;
  margin: 0;
  font-size: var(--ys-font-md);
  font-weight: 600;
}

.admin-header__actions {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
}

.admin-header__action {
  min-height: 32px;
  padding: 0 var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  cursor: pointer;
  transition: border-color var(--ys-duration-fast) var(--ys-ease-out);
}

.admin-header__action:hover:not(:disabled) {
  border-color: var(--color-primary-border);
  color: var(--color-primary-strong);
}

.admin-header__action:disabled {
  cursor: progress;
  opacity: 0.6;
}

.admin-operator {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  min-height: 36px;
  padding: 3px 10px 3px 3px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-surface);
  color: var(--color-text-primary);
  cursor: pointer;
}

.admin-operator:hover {
  border-color: var(--color-primary-border);
}

.admin-operator__name {
  font-size: var(--ys-font-sm);
}

.admin-header__menu {
  display: none;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface);
  cursor: pointer;
}

.admin-main {
  flex: 1;
  min-width: 0;
  padding: var(--ys-space-6);
}

.admin-nav--drawer {
  flex: none;
}

.admin-nav--drawer .admin-nav__link {
  color: var(--color-text-secondary);
}

.admin-nav--drawer .admin-nav__link:hover {
  background: var(--color-primary-subtle);
  color: var(--color-primary-strong);
}

.admin-nav--drawer .admin-nav__link.is-active {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
}

/* 深色抽屉上不能沿用 --color-text-muted：它按浅底上的对比度定的（warm-500），
   在 #2f2418 这种深底上会掉到 3:1 以下。用与同级导航一致的白色透明度 */
.admin-nav--drawer .admin-nav__group {
  color: rgba(255, 255, 255, 0.55);
}

@media (max-width: 1024px) {
  .admin-aside {
    display: none;
  }

  .admin-header__menu {
    display: flex;
  }

  .admin-header {
    padding: 0 var(--ys-space-4);
  }

  .admin-main {
    padding: var(--ys-space-4);
  }

  .admin-operator__name {
    display: none;
  }
}
</style>
