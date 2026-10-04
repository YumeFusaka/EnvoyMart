<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import AuthShell from '@/components/auth/AuthShell.vue'
import { register } from '@/api/auth'
import { useUserStore } from '@/stores'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
/**
 * 提交失败的原因。拦截器会把失败弹成右上角的 toast，但页面本身没有任何反应：
 * 按钮从 loading 复原、表单还是原来那样，用户分不清「没点上」还是「被拒了」。
 * 密码错误是最常见的失败，更要留在页面上而不是飘一下就走。
 */
const submitError = ref<string | null>(null)

const form = reactive({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
  phone: '',
})

/**
 * 校验规则与后端 `RegisterRequest` 上的注解**刻意保持一致**。
 * 前端这份只管体验（即时反馈、少一次往返），真正的约束在后端 ——
 * 少了后端那份，绕过页面直接调接口就能建出任意弱口令账号。
 */
const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_]{4,20}$/,
      message: '4-20 位字母、数字或下划线',
      trigger: 'blur',
    },
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { max: 32, message: '昵称最长 32 位', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    {
      pattern: /^(?=.*[A-Za-z])(?=.*\d)\S{8,32}$/,
      message: '8-32 位，且同时包含字母和数字',
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
  phone: [
    // 选填字段：留空要放过，所以不能只写 pattern
    { pattern: /^$|^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  submitError.value = null
  try {
    // 注册接口直接签发 token —— 注册完还要再登一次是多余的往返
    const data = await register({
      username: form.username,
      nickname: form.nickname,
      password: form.password,
      phone: form.phone,
    })
    userStore.setToken(data.token)
    userStore.setProfile(data.user)
    ElMessage.success('注册成功，已自动登录')
    router.push('/shop')
  } catch (e) {
    submitError.value = e instanceof Error ? e.message : '注册失败'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell
    eyebrow="Join us"
    title="注册"
    foot-text="已有账号？"
    foot-link-text="去登录"
    foot-link-to="/login"
  >
    <!-- 失败留在页面上，不只靠右上角那一下。包一层 div 而不是给 el-alert 加 class：
         Element Plus 的 el-alert 会渲染自己的根类并丢掉透传的自定义 class，
         直接写 class 定位不到，间距也无处可加 -->
    <div v-if="submitError" class="submit-error">
      <el-alert type="error" :closable="false" show-icon :title="submitError" />
    </div>

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
          placeholder="4-20 位字母、数字或下划线"
          autocomplete="username"
        />
      </el-form-item>

      <el-form-item label="昵称" prop="nickname">
        <el-input v-model="form.nickname" placeholder="展示给其他人的名字" />
      </el-form-item>

      <el-form-item label="密码" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          placeholder="8-32 位，同时包含字母和数字"
          autocomplete="new-password"
          show-password
        />
      </el-form-item>

      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input
          v-model="form.confirmPassword"
          type="password"
          placeholder="请再次输入密码"
          autocomplete="new-password"
          show-password
          @keyup.enter="handleSubmit"
        />
      </el-form-item>

      <el-form-item label="手机号（选填）" prop="phone">
        <el-input v-model="form.phone" placeholder="用于接收订单通知" maxlength="11" />
      </el-form-item>
    </el-form>

    <el-button
      type="primary"
      size="large"
      class="submit"
      :loading="submitting"
      @click="handleSubmit"
    >
      注册并登录
    </el-button>
  </AuthShell>
</template>

<style scoped>
.submit {
  width: 100%;
}
/* 失败提示与表单之间留一档间距，与 AuthShell 的 gap 一致 */
.submit-error {
  margin-bottom: var(--ys-space-4);
}
</style>
