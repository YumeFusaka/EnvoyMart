<script setup lang="ts">
import { renderMarkdown } from '@/utils/markdown'
import { computed } from 'vue'

const props = defineProps<{
  content: string
  /** 本次回答带回了多少条依据。编号超出这个范围的 `[n]` 按原文渲染 */
  citationCount?: number
  /** 正在流式生成。末尾渲染闪烁光标，让"还在写"这件事可见 */
  streaming?: boolean
  /**
   * 纯文本渲染（用户消息）。用户打的 `# 1.` 是一句话，不是标题和列表——
   * 按 Markdown 渲染等于替用户改写他的话；保留换行、不做任何解析才如实。
   */
  plain?: boolean
}>()

const emit = defineEmits<{ cite: [index: number] }>()

const html = computed(() => renderMarkdown(props.content, props.citationCount ?? 0))

/**
 * 点击委托：角标与代码块复制按钮都在 v-html 出来的 DOM 里，
 * 逐个绑事件要在每次流式重渲染后重做一遍；委托挂在容器上，只需要挂一次。
 */
async function handleClick(event: MouseEvent) {
  const target = event.target as HTMLElement

  const cite = target.closest<HTMLElement>('[data-cite]')
  if (cite?.dataset.cite) {
    emit('cite', Number(cite.dataset.cite))
    return
  }

  const copy = target.closest<HTMLElement>('[data-code-copy]')
  if (copy) {
    await copyCode(copy)
  }
}

/**
 * 代码块复制。"已复制"直接改按钮文字，不走响应式状态 ——
 * 按钮本身在 v-html 的 DOM 里随每次 delta 重建，响应式状态反而记不住它；
 * 这段文字本来就是一次性的瞬时反馈，重渲染把它还原成"复制"正是想要的行为。
 */
async function copyCode(button: HTMLElement) {
  const code = button.parentElement?.querySelector('pre')?.textContent
  if (!code) return
  try {
    await navigator.clipboard.writeText(code)
    button.textContent = '已复制'
    setTimeout(() => {
      button.textContent = '复制'
    }, 1500)
  } catch {
    // 剪贴板不可用（权限被拒/非安全上下文）时保持原文案，用户可手动选中复制
  }
}
</script>

<template>
  <!-- 用户消息：插值输出即天然转义，白空格由 CSS 保留（pre-wrap） -->
  <div v-if="plain" class="message-content is-plain">{{ content }}</div>
  <!--
    v-html 的内容已经过 markdown-it（html:false）+ DOMPurify 两道处理，
    见 utils/markdown.ts —— 模型输出是不可信输入，这里不是直通。
  -->
  <div
    v-else
    class="message-content"
    :class="{ 'is-streaming': streaming }"
    v-html="html"
    @click="handleClick"
  />
</template>

<style scoped>
/*
 * 通用排版。上一版是 pre-wrap 的纯文本，现在的输入是渲染后的块级元素，
 * 行高、段距、列表缩进都得由这里接管 —— 浏览器默认样式在 14px 的对话气泡里
 * 会显得又挤又乱。
 */
.message-content {
  margin: var(--ys-space-2) 0 0;
  /* 用户可能把不含空格的长串（订单号、URL、UUID）贴进来，模型也可能回抄长 token，
     或者渲染出长表格。不折的话它会撑破气泡并把整个容器推宽 */
  overflow-wrap: anywhere;
  line-height: var(--ys-leading-loose);
  color: var(--color-text-primary);
}

.message-content :deep(> :first-child) {
  margin-block-start: 0;
}

/* 用户消息按纯文本渲染：换行是他敲的换行，原样保留 */
.message-content.is-plain {
  white-space: pre-wrap;
}

.message-content :deep(> :last-child) {
  margin-block-end: 0;
}

/* 标题收着做：回答里的 h1 是"这段的标题"，不是页面标题，
   按页面级的 40px 渲染会把每条回答都变成一根柱子 */
.message-content :deep(h1),
.message-content :deep(h2),
.message-content :deep(h3),
.message-content :deep(h4) {
  margin: var(--ys-space-4) 0 var(--ys-space-2);
  font-size: var(--ys-font-md);
  font-weight: 700;
  line-height: var(--ys-leading-tight);
}

.message-content :deep(h1:first-child),
.message-content :deep(h2:first-child),
.message-content :deep(h3:first-child) {
  margin-block-start: 0;
}

.message-content :deep(p) {
  margin: var(--ys-space-2) 0;
}

.message-content :deep(ul),
.message-content :deep(ol) {
  margin: var(--ys-space-2) 0;
  padding-inline-start: 1.5em;
}

.message-content :deep(li) {
  margin: var(--ys-space-1) 0;
}

.message-content :deep(li > ul),
.message-content :deep(li > ol) {
  margin-block: 0;
}

.message-content :deep(strong) {
  font-weight: 700;
}

.message-content :deep(a) {
  color: var(--color-primary-strong);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.message-content :deep(a:hover) {
  color: var(--color-primary-strong);
}

.message-content :deep(a:focus-visible) {
  outline: none;
  box-shadow: var(--focus-ring);
  border-radius: var(--ys-radius-sm);
}

.message-content :deep(hr) {
  margin: var(--ys-space-4) 0;
  border: 0;
  border-top: 1px solid var(--color-border);
}

.message-content :deep(blockquote) {
  margin: var(--ys-space-2) 0;
  padding: var(--ys-space-1) var(--ys-space-4);
  border-inline-start: 3px solid var(--color-border-strong);
  color: var(--color-text-secondary);
}

/* ==================== 表格 ==================== */

.message-content :deep(.md-table-scroll) {
  margin: var(--ys-space-3) 0;
  overflow-x: auto;
  overscroll-behavior-inline: contain;
}

.message-content :deep(table) {
  border-collapse: collapse;
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
}

.message-content :deep(th),
.message-content :deep(td) {
  padding: var(--ys-space-2) var(--ys-space-3);
  border: 1px solid var(--color-border);
  text-align: start;
  /* 表头保持单行（列名短、断开反而难认）；数据格允许折行——模型给的说明列
     往往是一整句，nowrap 会让整张表变成一条横向滚动的长带 */
  white-space: normal;
  overflow-wrap: anywhere;
}

.message-content :deep(th) {
  background: var(--color-bg-surface-muted);
  font-weight: 600;
  white-space: nowrap;
}

/* 模型输出里的图片。不约束的话一张原图能把 860px 的正文列直接撑破 */
.message-content :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: var(--ys-radius-sm);
}

/* ==================== 代码 ==================== */

.message-content :deep(code) {
  padding: 1px 5px;
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-sunken);
  font-family: var(--ys-font-mono);
  font-size: var(--ys-font-sm);
}

.message-content :deep(.md-code) {
  position: relative;
  margin: var(--ys-space-3) 0;
}

/* 深底代码块：与正文形成清晰分界，也避免浅底 code 与"引用角标"的浅底混淆 */
.message-content :deep(.md-code pre) {
  margin: 0;
  padding: var(--ys-space-3) var(--ys-space-4);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-inverse);
  color: var(--color-text-inverse);
  overflow-x: auto;
  overscroll-behavior-inline: contain;
}

.message-content :deep(.md-code pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
  white-space: pre;
}

.message-content :deep(.md-code__copy) {
  position: absolute;
  inset-block-start: var(--ys-space-2);
  inset-inline-end: var(--ys-space-2);
  padding: 2px 10px;
  border: 1px solid rgba(255, 255, 255, 0.24);
  border-radius: var(--ys-radius-sm);
  background: rgba(255, 255, 255, 0.08);
  color: var(--color-text-inverse);
  font-size: var(--ys-font-xs);
  cursor: pointer;
  transition: background-color var(--ys-duration-fast) var(--ys-ease-out);
}

.message-content :deep(.md-code__copy:hover) {
  background: rgba(255, 255, 255, 0.18);
}

.message-content :deep(.md-code__copy:focus-visible) {
  outline: none;
  box-shadow: var(--focus-ring);
}

/* ==================== 引用角标 ==================== */

/*
 * 角标上标化用 `vertical-align: super` 而不是 `position: relative; top: -0.4em`：
 * 后者不影响行高，在行首会顶破容器的上边距。
 */
/*
 * 引用角标 [1]。它内联在正文流里，**视觉尺寸必须小于行高** ——
 * 直接撑到 24px 会把每行间距顶开，一段话被拉得稀稀拉拉。
 *
 * 所以视觉保持 18px，靠 ::before 把命中区外扩到 24x24（项目基线要求的最小
 * 可点击区域）。点击目标只有 19x18 时，触控板上要瞄准着点，很容易点空。
 */
.message-content :deep(.message-content__cite) {
  position: relative;
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
  color: var(--color-primary-strong);
  font-size: var(--ys-font-xs);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  vertical-align: super;
  cursor: pointer;
  transition:
    background-color var(--ys-duration-fast) var(--ys-ease-out),
    color var(--ys-duration-fast) var(--ys-ease-out);
}

/* 命中区外扩：18x18 的视觉 + 3px 外扩 = 24x24 的可点击面 */
.message-content :deep(.message-content__cite)::before {
  content: '';
  position: absolute;
  inset: -3px;
}

.message-content :deep(.message-content__cite:hover) {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
}

.message-content :deep(.message-content__cite:focus-visible) {
  outline: none;
  box-shadow: var(--focus-ring);
}

/* ==================== 流式光标 ==================== */

/*
 * 光标挂在"最后一个叶子块元素"的 ::after 上，而不是额外插一个 span：
 * 插 span 的话它会落在所有块元素之后、单独占一行，看起来像光标掉到了下一行。
 *
 * 两个限定缺一不可：:last-child 保证只在末尾元素上出现；
 * :not(:has(> p, > ul, ...)) 排除"里面还包着块级子元素"的容器 ——
 * 松散列表的 li 里裹着 p，两个都是 last-child，不排除的话会叠出两个光标。
 * 内联元素（引用角标按钮、strong）不算块级子元素，不影响。
 */
.message-content.is-streaming
  :deep(:is(p, li, h1, h2, h3, h4, td, blockquote):last-child:not(:has(> p, > ul, > ol, > pre, > blockquote, > table, > div)))::after {
  content: '';
  display: inline-block;
  width: 0.5em;
  height: 1em;
  margin-inline-start: 2px;
  vertical-align: text-bottom;
  background: var(--color-primary);
  animation: caret-blink 1s steps(2, start) infinite;
}

@keyframes caret-blink {
  50% {
    opacity: 0;
  }
}

/* 动效降级是硬要求：开「减少动态效果」时不闪，光标静止显示 */
@media (prefers-reduced-motion: reduce) {
  .message-content.is-streaming
    :deep(:is(p, li, h1, h2, h3, h4, td, blockquote):last-child:not(:has(> p, > ul, > ol, > pre, > blockquote, > table, > div)))::after {
    animation: none;
  }
}
</style>
