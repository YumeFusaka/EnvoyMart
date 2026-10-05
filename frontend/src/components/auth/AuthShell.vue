<script setup lang="ts">
/**
 * 认证页外壳（登录 / 注册）。
 *
 * 抽出来是为了让「标题区 + 表单区 + 底部切换链接」这套结构只有一份样式。
 * 登录与注册页此前的差异只有字段和提交逻辑，把壳子复制两遍，
 * 迟早会出现「登录页改了间距、注册页没改」这种不一致。
 */
defineProps<{
  eyebrow: string
  title: string
  description?: string
  footText: string
  footLinkText: string
  footLinkTo: string
}>()
</script>

<template>
  <div class="auth-shell">
    <header class="auth-shell__head">
      <p class="eyebrow">{{ eyebrow }}</p>
      <h1>{{ title }}</h1>
      <p v-if="description" class="subcopy">{{ description }}</p>
    </header>

    <slot />

    <p class="auth-shell__foot">
      {{ footText }}
      <RouterLink :to="footLinkTo" class="auth-shell__link">{{ footLinkText }}</RouterLink>
    </p>
  </div>
</template>

<style scoped>
.auth-shell {
  display: grid;
  gap: var(--ys-space-5);
}

.auth-shell__head {
  display: grid;
  gap: var(--ys-space-1);
  text-align: center;
}

.auth-shell__head h1 {
  font-size: var(--ys-font-xl);
}

.auth-shell__foot {
  text-align: center;
  font-size: var(--ys-font-sm);
  color: var(--color-text-secondary);
}

.auth-shell__link {
  color: var(--color-primary-strong);
  font-weight: 600;
}

.auth-shell__link:hover {
  color: var(--color-primary-strong);
}
</style>
