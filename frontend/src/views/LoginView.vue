<script setup lang="ts">
import { login } from '@/api/auth'
import { useUserStore } from '@/stores'
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)

const form = reactive({
  username: 'alice',
  password: '123456'
})

async function onSubmit() {
  loading.value = true
  try {
    const data = await login(form)
    userStore.setToken(data.token)
    userStore.setProfile(data.user)
    ElMessage.success('登录成功，正在进入 EnvoyMart')
    await router.push('/shop')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-copy">
        <p class="eyebrow">EnvoyMart Agent Platform</p>
        <h1>智能零售 MVP 演示台</h1>
        <p class="description">
          集成商品浏览、购物车、订单流转、AI 导购与售后问答。当前默认接入演示账号与本地知识库能力。
        </p>
        <ul class="highlights">
          <li>登录后即可体验商品推荐与订单工具查询</li>
          <li>AI 助手支持商品、活动、物流、售后场景</li>
          <li>电商主链路与 AI 能力共用一套前后端演示界面</li>
        </ul>
      </div>

      <el-form class="login-form" label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" size="large" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" size="large" show-password />
        </el-form-item>
        <el-button :loading="loading" class="submit-button" size="large" type="primary" @click="onSubmit">
          进入平台
        </el-button>
        <p class="hint">演示账号默认已填充：`alice / 123456`</p>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 32px;
}

.login-card {
  width: min(1080px, 100%);
  display: grid;
  grid-template-columns: 1.2fr 0.9fr;
  gap: 28px;
  padding: 32px;
  border-radius: 28px;
  background: rgba(255, 250, 242, 0.88);
  border: 1px solid var(--ys-border);
  box-shadow: var(--ys-shadow);
  backdrop-filter: blur(20px);
}

.eyebrow {
  margin: 0 0 12px;
  color: var(--ys-primary);
  letter-spacing: 0.14em;
  text-transform: uppercase;
  font-size: 12px;
  font-weight: 700;
}

h1 {
  margin: 0 0 12px;
  font-size: clamp(34px, 5vw, 54px);
  line-height: 1.04;
}

.description {
  margin: 0;
  max-width: 560px;
  color: var(--ys-muted);
  font-size: 16px;
  line-height: 1.8;
}

.highlights {
  margin: 28px 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 12px;
}

.highlights li {
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(182, 91, 46, 0.08);
  color: var(--ys-text);
}

.login-form {
  padding: 24px;
  border-radius: 22px;
  background: #fffdf8;
  border: 1px solid rgba(84, 55, 23, 0.08);
}

.submit-button {
  width: 100%;
  margin-top: 10px;
  background: linear-gradient(135deg, var(--ys-primary), var(--ys-primary-deep));
  border: none;
}

.hint {
  margin: 14px 0 0;
  color: var(--ys-muted);
  font-size: 13px;
}

@media (max-width: 900px) {
  .login-card {
    grid-template-columns: 1fr;
  }
}
</style>
