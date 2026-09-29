<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="not-found">
    <p class="eyebrow">404</p>
    <h1>页面不存在</h1>
    <p class="subcopy">你访问的地址可能已被移除，或者链接写错了。</p>
    <div class="not-found__actions">
      <el-button type="primary" @click="router.push('/shop')">回到商城</el-button>
      <el-button @click="router.back()">返回上一页</el-button>
    </div>
  </div>
</template>

<style scoped>
.not-found {
  display: grid;
  place-content: center;
  justify-items: center;
  gap: var(--ys-space-3);
  min-height: 100vh;
  padding: var(--ys-space-6);
  text-align: center;
}

.not-found h1 {
  font-size: var(--ys-font-2xl);
}

.not-found__actions {
  display: flex;
  gap: var(--ys-space-2);
  margin-top: var(--ys-space-2);
}
</style>
