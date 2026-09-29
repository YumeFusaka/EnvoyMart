<script setup lang="ts">
import { splitCitations } from '@/utils/knowledge'
import { computed } from 'vue'

const props = defineProps<{
  content: string
  /** 本次回答带回了多少条依据。编号超出这个范围的 `[n]` 按原文渲染 */
  citationCount?: number
}>()

const emit = defineEmits<{ cite: [index: number] }>()

const segments = computed(() => splitCitations(props.content, props.citationCount ?? 0))
</script>

<template>
  <p class="message-content">
    <template v-for="(segment, i) in segments" :key="i">
      <button
        v-if="segment.type === 'cite'"
        type="button"
        class="message-content__cite"
        :aria-label="`查看第 ${segment.index} 条依据`"
        @click="emit('cite', segment.index)"
      >
        {{ segment.index }}
      </button>
      <template v-else>{{ segment.text }}</template>
    </template>
  </p>
</template>

<style scoped>
.message-content {
  margin: 10px 0 0;
  /* 模型输出里的换行是排版的一部分，不能塌成空格 */
  white-space: pre-wrap;
  line-height: var(--ys-leading-base);
}

/*
 * 角标上标化用 `vertical-align: super` 而不是 `position: relative; top: -0.4em`：
 * 后者不影响行高，在行首会顶破容器的上边距。
 */
.message-content__cite {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  margin: 0 2px;
  border: 1px solid var(--color-primary-border);
  border-radius: var(--ys-radius-sm);
  background: var(--color-primary-subtle);
  color: var(--color-primary);
  font-size: var(--ys-font-xs);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  vertical-align: super;
  cursor: pointer;
  transition:
    background-color var(--ys-duration-fast) var(--ys-ease-out),
    color var(--ys-duration-fast) var(--ys-ease-out);
}

.message-content__cite:hover {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
}

.message-content__cite:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
</style>
