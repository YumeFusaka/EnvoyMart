<script setup lang="ts">
/**
 * 无导航布局 —— 登录、注册这类页面用。
 *
 * 不套主布局的理由：顶栏里全是需要登录才能用的入口（订单、个人中心、账号菜单），
 * 在登录页上展示它们只会给出走不通的路径。这里只呈现品牌与表单本身。
 */
</script>

<template>
  <div class="blank-layout">
    <div class="blank-layout__panel">
      <RouterLink to="/login" class="blank-brand">
        <span class="blank-brand__mark" aria-hidden="true">EM</span>
        <span class="blank-brand__text">EnvoyMart</span>
      </RouterLink>
      <RouterView />
    </div>
  </div>
</template>

<style scoped>
.blank-layout {
  display: grid;
  place-items: center;
  min-height: 100vh;
  padding: var(--ys-space-6);
}

.blank-layout__panel {
  display: grid;
  gap: var(--ys-space-6);
  width: 100%;
  max-width: 420px;
  padding: var(--ys-space-10) var(--ys-space-8);
  background: var(--color-bg-surface);
  border: var(--card-border);
  border-radius: var(--card-radius);
  box-shadow: var(--ys-shadow-modal);
}

.blank-brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--ys-space-2);
  font-weight: 700;
  font-size: var(--ys-font-lg);
}

.blank-brand__mark {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: var(--ys-radius-sm);
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  font-size: var(--ys-font-xs);
  letter-spacing: 0.04em;
}

@media (max-width: 480px) {
  .blank-layout__panel {
    padding: var(--ys-space-6) var(--ys-space-4);
  }
}
</style>
