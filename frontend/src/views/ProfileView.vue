<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createAddress,
  deleteAddress,
  listAddresses,
  setDefaultAddress,
  updateAddress,
  type AddressPayload,
} from '@/api/address'
import { useTicketStore, useUserStore } from '@/stores'
import type { UserAddress } from '@/types/models'

const userStore = useUserStore()
const ticketStore = useTicketStore()
const profile = computed(() => userStore.profile)

const addresses = ref<UserAddress[]>([])
const loading = ref(false)

const dialogOpen = ref(false)
const saving = ref(false)
/** null 表示新增；有值表示正在编辑哪一条 */
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

interface AddressForm extends Omit<AddressPayload, 'tag' | 'isDefault'> {
  tag: string
  isDefault: boolean
}

function emptyForm(): AddressForm {
  return {
    receiverName: '',
    receiverPhone: '',
    province: '',
    city: '',
    district: '',
    detail: '',
    tag: '',
    isDefault: false,
  }
}

const form = reactive<AddressForm>(emptyForm())

const rules: FormRules = {
  receiverName: [{ required: true, message: '请输入收货人姓名', trigger: 'blur' }],
  receiverPhone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  province: [{ required: true, message: '请输入省份', trigger: 'blur' }],
  city: [{ required: true, message: '请输入城市', trigger: 'blur' }],
  district: [{ required: true, message: '请输入区县', trigger: 'blur' }],
  detail: [{ required: true, message: '请输入详细地址', trigger: 'blur' }],
}

async function loadAddresses() {
  loading.value = true
  try {
    addresses.value = await listAddresses()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, emptyForm())
  formRef.value?.clearValidate()
  dialogOpen.value = true
}

function openEdit(item: UserAddress) {
  editingId.value = item.id
  Object.assign(form, {
    receiverName: item.receiverName,
    receiverPhone: item.receiverPhone,
    province: item.province,
    city: item.city,
    district: item.district,
    detail: item.detail,
    tag: item.tag ?? '',
    isDefault: item.isDefault === 1,
  })
  formRef.value?.clearValidate()
  dialogOpen.value = true
}

async function handleSave() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    const payload: AddressPayload = { ...form, tag: form.tag || undefined }
    if (editingId.value === null) {
      await createAddress(payload)
      ElMessage.success('地址已添加')
    } else {
      await updateAddress(editingId.value, payload)
      ElMessage.success('地址已更新')
    }
    dialogOpen.value = false
    await loadAddresses()
  } finally {
    saving.value = false
  }
}

async function handleSetDefault(item: UserAddress) {
  await setDefaultAddress(item.id)
  ElMessage.success('已设为默认地址')
  await loadAddresses()
}

async function handleDelete(item: UserAddress) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${item.receiverName} ${item.province}${item.city}${item.district}」这条地址吗？`,
      '删除收货地址',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    // 用户点了取消。ElMessageBox 用 reject 表达取消，不是错误
    return
  }
  await deleteAddress(item.id)
  ElMessage.success('地址已删除')
  await loadAddresses()
}

onMounted(() => {
  loadAddresses()
  // 「我的服务」里的工单角标。拉失败只是不显示数字（store 内部已吞掉异常）
  ticketStore.refresh()
})
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Account</p>
        <h1>个人中心</h1>
      </div>
    </header>

    <section class="surface">
      <h2 class="section-title">账号信息</h2>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="用户名">
          {{ profile?.username ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="昵称">{{ profile?.nickname ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ profile?.phone || '未绑定' }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ profile?.email || '未绑定' }}</el-descriptions-item>
      </el-descriptions>
    </section>

    <section class="surface">
      <h2 class="section-title">我的服务</h2>
      <nav class="links" aria-label="常用入口">
        <RouterLink to="/orders" class="link">我的订单</RouterLink>
        <RouterLink to="/after-sales" class="link">退款/售后</RouterLink>
        <RouterLink to="/reviews" class="link">我的评价</RouterLink>
        <RouterLink to="/tickets" class="link">
          我的工单
          <!-- 只在确实有「等你回应」时才显示数字：一个恒为 0 的红点等于没有红点 -->
          <span v-if="ticketStore.awaitingMe" class="link__badge">
            {{ ticketStore.awaitingMe > 99 ? '99+' : ticketStore.awaitingMe }}
          </span>
        </RouterLink>
        <RouterLink to="/coupons/mine" class="link">我的优惠券</RouterLink>
        <RouterLink to="/favorites" class="link">我的收藏</RouterLink>
      </nav>
    </section>

    <section class="surface">
      <div class="section-head">
        <h2 class="section-title">收货地址</h2>
        <el-button type="primary" @click="openCreate">新增地址</el-button>
      </div>

      <div v-loading="loading" class="address-wrap">
        <el-empty v-if="!loading && addresses.length === 0" description="还没有收货地址" />
        <ul v-else class="address-list">
          <li
            v-for="item in addresses"
            :key="item.id"
            class="address-item"
            :class="{ 'is-default': item.isDefault === 1 }"
          >
            <div class="address-item__main">
              <div class="address-item__line">
                <strong>{{ item.receiverName }}</strong>
                <span class="address-item__phone">{{ item.receiverPhone }}</span>
                <el-tag v-if="item.isDefault === 1" size="small" type="success">默认</el-tag>
                <el-tag v-if="item.tag" size="small" effect="plain">{{ item.tag }}</el-tag>
              </div>
              <p class="address-item__detail">
                {{ item.province }} {{ item.city }} {{ item.district }} {{ item.detail }}
              </p>
            </div>

            <div class="address-item__actions">
              <el-button
                v-if="item.isDefault !== 1"
                link
                type="primary"
                @click="handleSetDefault(item)"
              >
                设为默认
              </el-button>
              <el-button link @click="openEdit(item)">编辑</el-button>
              <el-button link type="danger" @click="handleDelete(item)">删除</el-button>
            </div>
          </li>
        </ul>
      </div>
    </section>

    <el-dialog
      v-model="dialogOpen"
      :title="editingId === null ? '新增收货地址' : '编辑收货地址'"
      width="560px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="form-grid">
          <el-form-item label="收货人" prop="receiverName">
            <el-input v-model="form.receiverName" placeholder="收货人姓名" />
          </el-form-item>
          <el-form-item label="手机号" prop="receiverPhone">
            <el-input v-model="form.receiverPhone" placeholder="11 位手机号" maxlength="11" />
          </el-form-item>
        </div>

        <div class="form-grid form-grid--3">
          <el-form-item label="省份" prop="province">
            <el-input v-model="form.province" placeholder="省 / 直辖市" />
          </el-form-item>
          <el-form-item label="城市" prop="city">
            <el-input v-model="form.city" placeholder="市" />
          </el-form-item>
          <el-form-item label="区县" prop="district">
            <el-input v-model="form.district" placeholder="区 / 县" />
          </el-form-item>
        </div>

        <el-form-item label="详细地址" prop="detail">
          <el-input
            v-model="form.detail"
            type="textarea"
            :rows="2"
            placeholder="街道、门牌号、楼层、房间号"
          />
        </el-form-item>

        <el-form-item label="标签">
          <el-input v-model="form.tag" placeholder="家 / 公司 / 学校（选填）" maxlength="8" />
        </el-form-item>

        <el-checkbox v-model="form.isDefault">设为默认收货地址</el-checkbox>
      </el-form>

      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.section-title {
  font-size: var(--ys-font-md);
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-4);
  margin-bottom: var(--ys-space-4);
}

.section-head .section-title {
  margin-bottom: 0;
}

/* 「我的服务」：自适应列宽，窄窗口下自动从三列掉到两列、一列，不用断点 */
.links {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(132px, 1fr));
  gap: var(--ys-space-2);
}

.link {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  padding: var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  color: var(--color-text-primary);
  font-size: var(--ys-font-sm);
  text-decoration: none;
  transition:
    border-color var(--ys-duration-fast) var(--ys-ease-out),
    background var(--ys-duration-fast) var(--ys-ease-out);
}

.link:hover {
  border-color: var(--color-primary-border);
  background: var(--color-primary-subtle);
}

.link:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.link__badge {
  min-width: 20px;
  margin-inline-start: auto;
  padding: 0 6px;
  border-radius: var(--ys-radius-full);
  background: var(--color-danger);
  color: var(--color-text-inverse);
  font-size: var(--ys-font-xs);
  font-variant-numeric: tabular-nums;
  text-align: center;
  line-height: 18px;
}

.surface > .section-title {
  margin-bottom: var(--ys-space-4);
}

.address-list {
  display: grid;
  gap: var(--ys-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.address-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ys-space-4);
  padding: var(--ys-space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
  transition: border-color var(--ys-duration-fast) var(--ys-ease-out);
}

.address-item.is-default {
  border-color: var(--color-primary-border);
  background: var(--color-primary-subtle);
}

.address-item__line {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  flex-wrap: wrap;
}

.address-item__phone {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.address-item__detail {
  margin-top: var(--ys-space-1);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
}

.address-item__actions {
  display: flex;
  gap: var(--ys-space-1);
  flex-shrink: 0;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 var(--ys-space-4);
}

.form-grid--3 {
  grid-template-columns: repeat(3, 1fr);
}

@media (max-width: 720px) {
  .address-item {
    flex-direction: column;
  }

  .form-grid,
  .form-grid--3 {
    grid-template-columns: 1fr;
  }
}
</style>
