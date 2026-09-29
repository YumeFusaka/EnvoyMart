<script setup lang="ts">
import type { KnowledgeSnippet } from '@/types/models'
import { relevanceText, sourceLabel } from '@/utils/knowledge'

defineProps<{
  items: KnowledgeSnippet[]
  /** 正文里刚刚点过的角标序号，用来把它对应的卡片标出来 */
  activeIndex?: number | null
  /** 消息 id。只用来生成卡片 id —— 点正文角标时要能滚到对应的卡片上 */
  listId: string
}>()

/**
 * 卡片整块就是去原文的链接 —— 不再单放一个「查看原文」按钮。
 * 依据卡片上唯一有价值的动作就是去看原文，多一个按钮只是多一次点击。
 * 用 RouterLink 而不是在 click 里手写 push：中键与右键「新标签页打开」也就跟着能用了。
 */
function toChunk(item: KnowledgeSnippet) {
  return {
    name: 'knowledge-doc',
    params: { docNo: item.docId },
    query: { chunk: item.chunkId },
  }
}
</script>

<template>
  <section class="citations" aria-label="回答依据">
    <h4 class="citations__title"><span aria-hidden="true">§</span> 依据 {{ items.length }} 条</h4>

    <ol class="citations__list">
      <li
        v-for="(item, i) in items"
        :id="`cite-${listId}-${i + 1}`"
        :key="item.chunkId"
        :class="['citation', { 'is-active': activeIndex === i + 1 }]"
      >
        <RouterLink class="citation__head" :to="toChunk(item)">
          <span class="citation__no">{{ i + 1 }}</span>
          <span class="citation__where">
            <!-- 位置串已含《标题》，缺位置时退到标题，再缺才用编号 -->
            <strong>{{ item.position || (item.title ? `《${item.title}》` : item.docId) }}</strong>
            <span class="citation__meta">
              <span v-if="item.source">{{ sourceLabel(item.source) }}</span>
              <span v-if="item.version">{{ item.version }}</span>
              <span>{{ relevanceText(item.score, item.reranked) }}</span>
            </span>
          </span>
        </RouterLink>

        <p class="citation__content">{{ item.content }}</p>

        <div class="citation__actions">
          <RouterLink class="citation__link" :to="toChunk(item)">查看原文 →</RouterLink>
          <span class="citation__docno">{{ item.docId }}</span>
        </div>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.citations {
  margin-top: var(--ys-space-4);
  padding-top: var(--ys-space-4);
  border-top: 1px dashed var(--color-border);
}

.citations__title {
  margin: 0 0 var(--ys-space-3);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  font-weight: 600;
}

.citations__list {
  display: grid;
  gap: var(--ys-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.citation {
  padding: var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
  transition:
    border-color var(--ys-duration-base) var(--ys-ease-out),
    background-color var(--ys-duration-base) var(--ys-ease-out);
}

/* 角标点过来时，卡片要自己亮一下 —— 否则用户不知道哪一张是刚点的那条 */
.citation.is-active {
  border-color: var(--color-primary-border);
  background: var(--color-primary-subtle);
}

.citation__head {
  display: flex;
  align-items: flex-start;
  gap: var(--ys-space-2);
  color: var(--color-text-primary);
  text-decoration: none;
}

.citation__head:hover .citation__where strong {
  color: var(--color-primary);
}

.citation__head:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
  border-radius: var(--ys-radius-sm);
}

.citation__no {
  display: grid;
  place-items: center;
  flex: none;
  width: 20px;
  height: 20px;
  border-radius: var(--ys-radius-sm);
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  font-size: var(--ys-font-xs);
  font-weight: 700;
}

.citation__where {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.citation__where strong {
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-tight);
}

.citation__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.citation__meta span {
  position: relative;
}

/* 分隔点靠伪元素加，不在数据里拼分隔符 —— 少一个字段就多一个悬空的分隔点 */
.citation__meta span + span::before {
  content: '·';
  margin-right: var(--ys-space-2);
}

.citation__content {
  margin: var(--ys-space-2) 0 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
  /* 折叠展示：切片可能有几百字，全展开会把回答挤到看不见 */
  display: -webkit-box;
  -webkit-line-clamp: 4;
  line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
  white-space: pre-wrap;
}

.citation__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-4);
  margin-top: var(--ys-space-2);
}

.citation__docno {
  color: var(--color-text-muted);
  font-family: var(--ys-font-mono);
  font-size: var(--ys-font-xs);
}

.citation__link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--color-primary);
  font-size: var(--ys-font-xs);
  cursor: pointer;
}

.citation__link:hover {
  text-decoration: underline;
}
</style>
