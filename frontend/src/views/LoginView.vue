<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import AuthShell from '@/components/auth/AuthShell.vue'
import { login } from '@/api/auth'
import { useUserStore } from '@/stores'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 演示账号提示只在开发构建里出现。生产包带上它，等于把半个账号公开 */
const isDev = import.meta.env.DEV

const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive({
  username: '',
  password: '',
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleSubmit() {
  if (!formRef.value) return
  // validate() 校验不过会 reject；字段级错误已经由表单自己标红，这里只需知道「没过」
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const data = await login({ username: form.username, password: form.password })
    userStore.setToken(data.token)
    userStore.setProfile(data.user)
    ElMessage.success('登录成功')

    // 回跳到被守卫拦下的那一页；没有就回商城
    const redirect = route.query.redirect
    router.push(typeof redirect === 'string' && redirect ? redirect : '/shop')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell
    eyebrow="Welcome back"
    title="登录"
    foot-text="还没有账号？"
    foot-link-text="立即注册"
    foot-link-to="/register"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      @submit.prevent="handleSubmit"
    >
      <el-form-item label="用户名" prop="username">
        <el-input
          v-model="form.username"
          placeholder="请输入用户名"
          autocomplete="username"
          size="large"
        />
      </el-form-item>

      <el-form-item label="密码" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          placeholder="请输入密码"
          autocomplete="current-password"
          show-password
          size="large"
          @keyup.enter="handleSubmit"
        />
      </el-form-item>
    </el-form>

    <el-button
      type="primary"
      size="large"
      class="submit"
      :loading="submitting"
      @click="handleSubmit"
    >
      登录
    </el-button>

    <!-- 演示账号只在开发构建里提示。生产包带上它，等于把半个账号公开 -->
    <p v-if="isDev" class="demo-hint">演示账号：alice / 123456</p>
  </AuthShell>
</template>

<style scoped>
.submit {
  width: 100%;
}

.demo-hint {
  margin-top: calc(-1 * var(--ys-space-3));
  text-align: center;
  font-size: var(--ys-font-xs);
  color: var(--color-text-muted);
}
</style>
