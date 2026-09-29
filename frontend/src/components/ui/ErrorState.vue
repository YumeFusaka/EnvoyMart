<script setup lang="ts">
/**
 * 统一的加载失败态。
 * <p>
 * 存在的原因：此前所有 `await` 都没有 catch，也没有 error 分支 ——
 * 接口一失败，订单详情与收银台就是整页空白，购物车显示「还是空的」，
 * 订单列表显示「没有相关订单」。**最后这条尤其糟**：后端特意抛错避免
 * 「用户以为购物车被清空」，前端却把它原样制造了出来。
 * <p>
 * 关键是把「加载失败」与「确实是空的」区分开 —— 空态可以没有重试按钮，
 * 失败态必须有。
 */
defineProps<{
  /** 失败原因。拦截器已经弹过一次提示，这里只做页面内的停留态 */
  message?: string
  /** 重试回调。不传则不显示重试按钮 */
  onRetry?: () => void
}>()
</script>

<template>
  <div class="error-state" role="alert">
    <p class="error-state__title">加载失败</p>
    <p class="error-state__message">{{ message || '请检查网络后重试' }}</p>
    <el-button v-if="onRetry" type="primary" plain @click="onRetry">重新加载</el-button>
  </div>
</template>

<style scoped>
.error-state {
  display: grid;
  justify-items: center;
  gap: var(--ys-space-3);
  padding: var(--ys-space-10) var(--ys-space-4);
  text-align: center;
}

.error-state__title {
  font-size: var(--ys-font-md);
  font-weight: 600;
  color: var(--color-text-primary);
}

.error-state__message {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}
</style>
