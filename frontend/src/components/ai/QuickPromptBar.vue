<script setup lang="ts">
import type { Component } from 'vue'

/**
 * 空态示例问。做成带分类的卡片而不是一排胶囊按钮：
 * 首屏是唯一一次向用户交代「这个助手会什么」的机会，
 * 分类标签（挑商品/问活动/查物流/问知识）先给能力地图，问题文本再给一次可照抄的例子。
 */
defineProps<{
  prompts: { icon: Component; label: string; text: string }[]
}>()

const emit = defineEmits<{
  select: [prompt: string]
}>()
</script>

<template>
  <div class="quick-prompts">
    <button
      v-for="prompt in prompts"
      :key="prompt.text"
      type="button"
      class="quick-prompt"
      @click="emit('select', prompt.text)"
    >
      <span class="quick-prompt__head">
        <span class="quick-prompt__icon" aria-hidden="true">
          <component :is="prompt.icon" />
        </span>
        <span class="quick-prompt__label">{{ prompt.label }}</span>
      </span>
      <span class="quick-prompt__text">{{ prompt.text }}</span>
    </button>
  </div>
</template>

<style scoped>
.quick-prompts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ys-space-3);
  width: 100%;
}

.quick-prompt {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-2);
  padding: var(--ys-space-3) var(--ys-space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface);
  color: var(--color-text-primary);
  font: inherit;
  text-align: start;
  cursor: pointer;
  transition:
    border-color var(--ys-duration-fast) var(--ys-ease-out),
    box-shadow var(--ys-duration-fast) var(--ys-ease-out),
    transform var(--ys-duration-fast) var(--ys-ease-out);
}

.quick-prompt:hover {
  border-color: var(--color-border-focus);
  box-shadow: var(--ys-shadow-raised);
  transform: translateY(-1px);
}

.quick-prompt:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.quick-prompt__head {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
}

.quick-prompt__icon {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: var(--ys-radius-sm);
  background: var(--color-primary-subtle);
  color: var(--color-primary-strong);
  font-size: 13px;
}

.quick-prompt__label {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.quick-prompt__text {
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
}

@media (max-width: 640px) {
  .quick-prompts {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (prefers-reduced-motion: reduce) {
  .quick-prompt {
    transition: none;
  }

  .quick-prompt:hover {
    transform: none;
  }
}
</style>
