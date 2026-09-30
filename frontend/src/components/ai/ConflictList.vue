<script setup lang="ts">
import type { KnowledgeConflict, KnowledgeSnippet } from '@/types/models'
import { computed } from 'vue'

const props = defineProps<{
  conflicts: KnowledgeConflict[]
  /** 把 refs 里的编号翻译成原文锚点用的那张表，就是回答里 `[n]` 背后同一份数据 */
  knowledge?: KnowledgeSnippet[]
}>()

/**
 * 只给能落到原文的编号生成链接。
 *
 * 编号在知识列表里找不到对应项时（模型写错了、或那一轮压根没这条证据）就不给链接——
 * 跳错地方比不跳更坏：用户点过去、看到一段不相干的原文，会以为自己已经核对过了。
 */
function citations(conflict: KnowledgeConflict) {
  return conflict.refs.flatMap((ref) => {
    const item = props.knowledge?.[ref - 1]
    if (!item) {
      return []
    }
    return [
      {
        ref,
        to: {
          name: 'knowledge-doc',
          params: { docNo: item.docId },
          query: { chunk: item.chunkId },
        },
      },
    ]
  })
}

/** 在 computed 里一次算完，模板里两次调用（判空 + 遍历）不会各算一遍 */
const rows = computed(() =>
  props.conflicts.map((conflict) => ({
    detail: conflict.detail,
    links: citations(conflict),
  })),
)
</script>

<template>
  <section class="conflicts" aria-label="证据冲突">
    <h4 class="conflicts__title">
      <span class="conflicts__mark" aria-hidden="true">!</span>
      证据存在冲突 {{ rows.length }} 处
    </h4>
    <p class="conflicts__note">
      平台知识库中的资料对同一件事说法不一致。<strong>以下内容未经取舍</strong>，请结合商品页标注或人工客服确认后再使用。
    </p>

    <ul class="conflicts__list">
      <li v-for="(row, i) in rows" :key="i" class="conflict">
        <p class="conflict__detail">{{ row.detail }}</p>
        <div v-if="row.links.length" class="conflict__refs">
          <RouterLink v-for="link in row.links" :key="link.ref" class="conflict__ref" :to="link.to">
            核对第 {{ link.ref }} 条 →
          </RouterLink>
        </div>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.conflicts {
  margin-top: var(--ys-space-4);
  padding: var(--ys-space-3) var(--ys-space-4);
  border: 1px solid var(--color-danger);
  border-radius: var(--ys-radius-md);
  background: var(--color-danger-subtle);
}

.conflicts__title {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  margin: 0 0 var(--ys-space-2);
  color: var(--color-danger);
  font-size: var(--ys-font-sm);
  font-weight: 600;
}

.conflicts__mark {
  display: grid;
  place-items: center;
  flex: none;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--color-danger);
  color: var(--color-text-on-primary);
  font-size: 11px;
  font-weight: 700;
}

.conflicts__note {
  margin: 0 0 var(--ys-space-3);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
}

.conflicts__note strong {
  color: var(--color-text-primary);
  font-weight: 600;
}

.conflicts__list {
  display: grid;
  gap: var(--ys-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

/* 冲突正文用正常底色：警示信息在外框上已经给足了，内层再染一层红反而看不清字 */
.conflict {
  padding: var(--ys-space-3);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface);
}

.conflict__detail {
  margin: 0;
  color: var(--color-text-primary);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
}

.conflict__refs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-3);
  margin-top: var(--ys-space-2);
}

.conflict__ref {
  color: var(--color-danger);
  font-size: var(--ys-font-xs);
  font-weight: 600;
}

.conflict__ref:hover {
  text-decoration: underline;
}

.conflict__ref:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
  border-radius: var(--ys-radius-sm);
}
</style>
