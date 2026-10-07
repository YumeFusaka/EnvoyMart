<script setup lang="ts">
/**
 * 用户管理。
 *
 * 这一页的两类改动后果完全不同，所以做得不对称：
 *
 * - **禁用**要有原因，且原因、操作人、时间三列一并落库。被禁用的人手里的旧 Token 会立刻失效
 *   （网关鉴权时读 Redis 认证态），所以这**不是**一个「下次登录才生效」的软操作。
 * - **改角色**会写进 Redis 认证态，网关下一次鉴权就生效，不必等旧 Token 过期 ——
 *   所以改完不需要提示对方重新登录，界面上也不该那么说。
 *
 * 幂等性也在这里露出来：重复禁用不会覆盖第一次的留痕，
 * 否则「这个人当初为什么被禁」会被最后一次误操作抹掉。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { changeUserRole, changeUserStatus, getUserDetail, listUsers } from '@/api/admin/user'
import { useAdminList, useResponsiveColumns } from '@/composables/useAdminList'
import { formatDateTime } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { AdminUserDetail, AdminUserQuery, AdminUserSummary } from '@/types/admin'

const {
  query,
  records,
  total,
  loading,
  error,
  search,
  resetFilters,
  currentPage,
  changePage,
  changeSize,
  load,
} = useAdminList<AdminUserSummary, AdminUserQuery>(
  listUsers,
  {
    keyword: '',
    role: undefined,
    status: undefined,
    page: 0,
    size: 20,
  },
  { immediate: false },
)


/** 表格容器：列宽按它实测的宽度算，窄屏时等比例收缩 */
const tableRef = ref<HTMLElement | null>(null)
const { widths: colW } = useResponsiveColumns(
  [
    { width: 220, min: 200 },
    { width: 170, min: 150 },
    { width: 110, min: 100 },
    { width: 160, min: 140 },
    { width: 150, min: 130 },
    { width: 90, min: 80 },
  ],
  tableRef,
)

onMounted(() => load(0))

async function onReset() {
  await resetFilters()
}

// ==================== 详情 ====================

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<AdminUserDetail | null>(null)
const active = ref<AdminUserSummary | null>(null)
const working = ref(false)

async function openDetail(row: AdminUserSummary) {
  active.value = row
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getUserDetail(row.id)
    active.value = detail.value.user
  } catch {
    // 拦截器已提示；抽屉里保留重试入口
  } finally {
    detailLoading.value = false
  }
}

async function reloadDetail() {
  if (!active.value) {
    return
  }
  detailLoading.value = true
  try {
    detail.value = await getUserDetail(active.value.id)
    active.value = detail.value.user
  } catch {
    // 同上
  } finally {
    detailLoading.value = false
  }
}

// ==================== 动作 ====================

async function disable() {
  const user = active.value
  if (!user) {
    return
  }
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      '禁用会立刻生效：这个人手里的 Token 会在下一次请求时被网关拒掉，不是等它过期。' +
        '原因会落库，作为这条处置的依据。',
      `禁用「${user.username}」`,
      {
        inputPlaceholder: '如：批量刷单 / 恶意退款',
        inputValidator: (value) => (value && value.trim() ? true : '必须填禁用原因'),
        confirmButtonText: '禁用',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
    reason = result.value.trim()
  } catch {
    return
  }
  working.value = true
  try {
    await changeUserStatus(user.id, 0, reason)
    ElMessage.success('已禁用')
    await Promise.all([reloadDetail(), load()])
  } catch {
    // 拒绝原因由后端给出（例如不能禁用自己），拦截器已展示
  } finally {
    working.value = false
  }
}

async function enable() {
  const user = active.value
  if (!user) {
    return
  }
  working.value = true
  try {
    await changeUserStatus(user.id, 1)
    ElMessage.success('已启用')
    await Promise.all([reloadDetail(), load()])
  } catch {
    // 同上
  } finally {
    working.value = false
  }
}

async function toggleRole() {
  const user = active.value
  if (!user) {
    return
  }
  const next = user.roleName === 'ADMIN' ? 'USER' : 'ADMIN'
  const tip =
    next === 'ADMIN'
      ? '提权为管理员：对方立刻获得全部管理接口的访问权，不必重新登录。'
      : '撤权为普通用户：对方立刻失去管理接口的访问权，手里的旧 Token 也一样。'
  try {
    await ElMessageBox.confirm(tip, next === 'ADMIN' ? '提权为管理员' : '撤权为普通用户', {
      type: next === 'ADMIN' ? 'warning' : 'info',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  working.value = true
  try {
    await changeUserRole(user.id, next)
    ElMessage.success(next === 'ADMIN' ? '已提权' : '已撤权')
    await Promise.all([reloadDetail(), load()])
  } catch {
    // 同上
  } finally {
    working.value = false
  }
}

// ==================== 展示 ====================

const roleOptions = [
  { label: '普通用户', value: 'USER' },
  { label: '管理员', value: 'ADMIN' },
]

const statusOptions = [
  { label: '正常', value: 1 },
  { label: '已禁用', value: 0 },
]

const isAdmin = computed(() => active.value?.roleName === 'ADMIN')
const isDisabled = computed(() => active.value?.status === 0)
</script>

<template>
  <div class="admin-panel">
    <div class="admin-filters">
      <div class="admin-filters__item">
        <label class="admin-filters__label" for="user-keyword">关键词</label>
        <el-input
          id="user-keyword"
          v-model="query.keyword"
          placeholder="用户名 / 昵称 / 手机号 / 邮箱"
          clearable
          @keyup.enter="search"
        />
      </div>

      <div class="admin-filters__item">
        <label class="admin-filters__label" for="user-role">角色</label>
        <el-select id="user-role" v-model="query.role" clearable placeholder="全部角色">
          <el-option v-for="r in roleOptions" :key="r.value" :label="r.label" :value="r.value" />
        </el-select>
      </div>

      <div class="admin-filters__item">
        <label class="admin-filters__label" for="user-status">状态</label>
        <el-select id="user-status" v-model="query.status" clearable placeholder="全部状态">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </div>

      <div class="admin-filters__actions">
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="onReset">重置</el-button>
      </div>
    </div>

    <div class="admin-toolbar">
      <h2 class="admin-toolbar__title">用户</h2>
      <span class="admin-toolbar__count">共 {{ total }} 人</span>
    </div>

    <ErrorState v-if="error" :message="error" :on-retry="() => load()" />

    <template v-else>
      <div ref="tableRef" class="admin-table">
        <el-table v-loading="loading" :data="records" style="width: 100%">
          <el-table-column label="用户" :width="colW[0]">
            <template #default="{ row }">
              <div class="user-cell">
                <el-avatar class="user-cell__avatar" :src="row.avatar ?? undefined" :size="40">
                  {{ (row.nickname || row.username).slice(0, 1) }}
                </el-avatar>
                <div class="admin-stack">
                  <span class="admin-cell--strong">{{ row.nickname || row.username }}</span>
                  <span class="admin-cell--tiny user-cell__id">{{ row.username }} · {{ row.id }}</span>
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="联系方式" :width="colW[1]">
            <template #default="{ row }">
              <div class="admin-stack">
                <span class="admin-cell--tiny">{{ row.phone ?? '—' }}</span>
                <span class="admin-cell--tiny">{{ row.email ?? '—' }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="角色" :width="colW[2]">
            <template #default="{ row }">
              <el-tag
                :type="row.roleName === 'ADMIN' ? 'warning' : 'info'"
                effect="plain"
                size="small"
              >
                {{ row.roleName === 'ADMIN' ? '管理员' : '普通用户' }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="状态" :width="colW[3]">
            <template #default="{ row }">
              <div class="admin-stack">
                <el-tag :type="row.status === 1 ? 'success' : 'danger'" effect="plain" size="small">
                  {{ row.status === 1 ? '正常' : '已禁用' }}
                </el-tag>
                <span
                  v-if="row.status === 0 && row.disabledReason"
                  class="admin-cell--tiny admin-cell--ellipsis"
                >
                  {{ row.disabledReason }}
                </span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="注册时间" :width="colW[4]">
            <template #default="{ row }">
              <span class="admin-cell--tiny">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>

          <el-table-column   fixed="right" label="操作" :width="colW[5]">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">管理</el-button>
            </template>
          </el-table-column>

          <template #empty>
            <p class="admin-empty">没有符合条件的用户</p>
          </template>
        </el-table>
      </div>

      <div class="admin-pager">
        <el-pagination
          :current-page="currentPage"
          :page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="changePage"
          @size-change="changeSize"
        />
      </div>
    </template>

    <el-drawer v-model="detailVisible" title="用户管理" size="560px">
      <el-skeleton v-if="detailLoading" :rows="6" animated />

      <ErrorState v-else-if="!detail" message="用户详情没能加载出来" :on-retry="reloadDetail" />

      <div v-else class="admin-detail">
        <h3 class="admin-section__title">
          {{ detail.user.nickname || detail.user.username }}
          <el-tag :type="isAdmin ? 'warning' : 'info'" effect="plain" size="small">
            {{ isAdmin ? '管理员' : '普通用户' }}
          </el-tag>
          <el-tag v-if="isDisabled" type="danger" effect="plain" size="small">已禁用</el-tag>
        </h3>

        <div class="user-actions">
          <el-button v-if="!isDisabled" type="danger" plain :loading="working" @click="disable"
            >禁用</el-button
          >
          <el-button v-else type="primary" :loading="working" @click="enable">启用</el-button>
          <el-button :loading="working" @click="toggleRole">
            {{ isAdmin ? '撤权为普通用户' : '提权为管理员' }}
          </el-button>
        </div>

        <div class="admin-kv">
          <span class="admin-kv__key">用户 ID</span>
          <span class="admin-kv__value">{{ detail.user.id }}</span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">用户名</span>
          <span class="admin-kv__value">{{ detail.user.username }}</span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">昵称</span>
          <span class="admin-kv__value">{{ detail.user.nickname ?? '—' }}</span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">手机号</span>
          <span class="admin-kv__value">{{ detail.user.phone ?? '—' }}</span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">邮箱</span>
          <span class="admin-kv__value">{{ detail.user.email ?? '—' }}</span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">收货地址</span>
          <span class="admin-kv__value">{{ detail.addressCount }} 条</span>
        </div>
        <div class="admin-kv">
          <span class="admin-kv__key">注册时间</span>
          <span class="admin-kv__value">{{ formatDateTime(detail.user.createdAt) }}</span>
        </div>

        <template v-if="isDisabled">
          <h3 class="admin-section__title">处置记录</h3>
          <div class="admin-kv">
            <span class="admin-kv__key">禁用原因</span>
            <span class="admin-kv__value">{{ detail.user.disabledReason ?? '—' }}</span>
          </div>
          <div class="admin-kv">
            <span class="admin-kv__key">操作人</span>
            <span class="admin-kv__value">{{ detail.user.disabledBy ?? '—' }}</span>
          </div>
          <div class="admin-kv">
            <span class="admin-kv__key">操作时间</span>
            <span class="admin-kv__value">{{ formatDateTime(detail.user.disabledAt) }}</span>
          </div>
          <p class="admin-dialog__hint">
            重复点禁用不会覆盖上面这条记录 —— 否则「当初为什么被禁」会被最后一次误操作抹掉。
          </p>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.user-cell {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  min-width: 0;
}

/* 账号 ID 是 32 位十六进制串，中间没有空格可折 —— 让它自由换行会折成三行，
   把整行行高从 56 撑到 111。截断成一行，完整值在详情抽屉里 */
.user-cell__id {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.user-cell__avatar {
  flex: none;
  background: var(--color-bg-sunken);
  /* muted 档叠在 sunken 底上只有 4.06:1，头像缩写也是要读的文字，升到 secondary */
  color: var(--color-text-secondary);
}

.user-actions {
  display: flex;
  gap: var(--ys-space-2);
  margin-bottom: var(--ys-space-3);
}
</style>
