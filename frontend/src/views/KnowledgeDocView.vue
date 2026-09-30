<script setup lang="ts">
import { getDocument } from '@/api/knowledge'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { ChunkRef, DocumentDetail } from '@/types/models'
import { formatDate } from '@/utils/format'
import { scopeLabel, sourceLabel } from '@/utils/knowledge'
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const doc = ref<DocumentDetail | null>(null)
const loading = ref(true)
const error = ref('')

/**
 * 当前高亮的切片。
 * <p>
 * 事实源是 URL 上的 `?chunk=` 而不是本地 state —— 这样「复制链接发给别人」
 * 与「点引用跳过来」打开的是同一段内容，刷新也不会丢高亮。
 */
const activeChunkId = computed(() => (route.query.chunk as string | undefined) ?? '')

const activeChunk = computed<ChunkRef | null>(() => {
  if (!doc.value || !activeChunkId.value) return null
  return doc.value.chunks.find((c) => c.chunkId === activeChunkId.value) ?? null
})

/**
 * 引用的切片在文档里找不到 —— 这是一条**必须让人看见**的失败。
 * <p>
 * 它的成因是检索侧与存储侧的切分参数不一致（或这片的文档被删了），
 * 而那种情况下两边日志都正常，唯一会喊出来的地方就是这里。
 */
const danglingChunk = computed(
  () => Boolean(activeChunkId.value) && !loading.value && !activeChunk.value,
)

/** 正文按高亮区间切成三段。偏移是 UTF-16 码元，正是 JS 字符串的下标 */
const segments = computed(() => {
  const content = doc.value?.content ?? ''
  const chunk = activeChunk.value
  if (!chunk || chunk.charOffset === null || chunk.charOffset === undefined) {
    return null
  }
  const start = clamp(chunk.charOffset, 0, content.length)
  const end = clamp(chunk.charEnd ?? content.length, start, content.length)
  return {
    head: content.slice(0, start),
    mark: content.slice(start, end),
    tail: content.slice(end),
  }
})

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    doc.value = await getDocument(String(route.params.docNo))
  } catch (e) {
    error.value = e instanceof Error ? e.message : '文档加载失败'
  } finally {
    loading.value = false
  }
  await nextTick()
  scrollToMark()
}

/**
 * 滚到高亮处。用 `nearest` 而不是 `start`：
 * 高亮在文档开头时 `start` 会把标题顶出视口，用户失去「这是哪一篇」的上下文。
 */
function scrollToMark() {
  document.querySelector('.doc__mark')?.scrollIntoView({
    block: 'nearest',
    behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
  })
}

function selectChunk(chunk: ChunkRef) {
  // 同一个键再次点击时也要生效，所以带上 replace 也不去重
  router.replace({ query: { ...route.query, chunk: chunk.chunkId } })
}

watch(() => route.params.docNo, load, { immediate: true })
watch(activeChunkId, async () => {
  await nextTick()
  scrollToMark()
})
</script>

<template>
  <div class="doc-page">
    <ErrorState v-if="error" :message="error" :on-retry="load" />

    <!--
      切换文档时也要走这一支：doc 还留着上一篇的内容，直接渲染会让人读到
      标题已换、正文还是旧的错文档。骨架屏把「正在换」说清楚
    -->
    <el-skeleton v-else-if="loading" :rows="8" animated />

    <template v-else-if="doc">
      <header class="doc-header">
        <nav class="doc-breadcrumb" aria-label="面包屑">
          <RouterLink to="/knowledge">知识库</RouterLink>
          <span aria-hidden="true">/</span>
          <span>{{ doc.docNo }}</span>
        </nav>

        <h1>{{ doc.title }}</h1>

        <ul class="doc-meta">
          <li>{{ sourceLabel(doc.source) }}</li>
          <li>{{ scopeLabel(doc.scope) }}</li>
          <li>{{ doc.version }}</li>
          <li>{{ doc.content.length }} 字 · {{ doc.chunks.length }} 片</li>
          <li>更新于 {{ formatDate(doc.updatedAt) }}</li>
          <li v-if="doc.status === 0" class="doc-meta__off">已停用</li>
        </ul>

        <ul v-if="doc.tags" class="doc-tags">
          <li v-for="tag in doc.tags.split(',')" :key="tag">{{ tag }}</li>
        </ul>
      </header>

      <p v-if="danglingChunk" class="doc-alert" role="alert">
        引用的切片 <code>{{ activeChunkId }}</code> 不在这篇文档里。这通常意味着检索侧与存储侧
        用了不同的切分参数，请以时间为准重新发起一次提问。
      </p>

      <div class="doc-body">
        <aside class="doc-toc" aria-label="切片目录">
          <h2>切片索引</h2>
          <ol>
            <li v-for="chunk in doc.chunks" :key="chunk.chunkId">
              <button
                type="button"
                :class="['doc-toc__item', { 'is-active': chunk.chunkId === activeChunkId }]"
                :aria-current="chunk.chunkId === activeChunkId ? 'true' : undefined"
                @click="selectChunk(chunk)"
              >
                <span class="doc-toc__no">{{ chunk.chunkIndex + 1 }}</span>
                <span class="doc-toc__pos">{{ chunk.position }}</span>
              </button>
            </li>
          </ol>
        </aside>

        <article class="doc-content">
          <!--
            三段必须**紧挨着**写在同一行：容器是 `white-space: pre-wrap`，
            模板缩进产生的空白文本节点会被当成正文渲染出来。
            （Vue 的 condense 会去掉含换行的纯空白节点，但那是一条容易被后人
            一次格式化打断的隐式依赖，不如让它不可能发生。）
          -->
          <template v-if="segments"
            ><span>{{ segments.head }}</span
            ><mark class="doc__mark">{{ segments.mark }}</mark
            ><span>{{ segments.tail }}</span></template
          >
          <template v-else>{{ doc.content }}</template>
        </article>
      </div>
    </template>
  </div>
</template>

<style scoped>
.doc-page {
  max-width: var(--layout-content-max);
  margin: 0 auto;
  padding: var(--ys-space-8);
  display: grid;
  gap: var(--ys-space-5);
}

.doc-header h1 {
  margin: var(--ys-space-2) 0;
  font-size: var(--ys-font-2xl);
  line-height: var(--ys-leading-tight);
}

.doc-breadcrumb {
  display: flex;
  gap: var(--ys-space-2);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.doc-breadcrumb a {
  color: var(--color-primary);
}

.doc-meta,
.doc-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
  margin: var(--ys-space-2) 0 0;
  padding: 0;
  list-style: none;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.doc-meta li:not(:last-child)::after {
  content: '·';
  margin-left: var(--ys-space-2);
  color: var(--color-text-muted);
}

.doc-meta__off {
  color: var(--color-danger);
  font-weight: 600;
}

.doc-tags li {
  padding: 2px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-sm);
}

.doc-alert {
  margin: 0;
  padding: var(--ys-space-3) var(--ys-space-4);
  border: 1px solid var(--color-danger);
  border-radius: var(--ys-radius-md);
  background: var(--color-danger-subtle);
  color: var(--color-text-primary);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
}

.doc-body {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: var(--ys-space-5);
  align-items: start;
}

.doc-toc {
  position: sticky;
  /* 顶栏高度 + 一点呼吸，否则目录会贴在导航下面 */
  top: calc(var(--layout-header-height) + var(--ys-space-4));
  max-height: calc(100vh - var(--layout-header-height) - var(--ys-space-8));
  overflow-y: auto;
  padding: var(--ys-space-4);
  border: var(--card-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface);
}

.doc-toc h2 {
  margin: 0 0 var(--ys-space-3);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.doc-toc ol {
  display: grid;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.doc-toc__item {
  display: flex;
  gap: var(--ys-space-2);
  width: 100%;
  padding: 6px 8px;
  border: 0;
  border-radius: var(--ys-radius-sm);
  background: none;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-tight);
  text-align: left;
  cursor: pointer;
}

.doc-toc__item:hover {
  background: var(--color-bg-surface-muted);
}

.doc-toc__item.is-active {
  background: var(--color-primary-subtle);
  color: var(--color-primary);
  font-weight: 600;
}

.doc-toc__item:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.doc-toc__no {
  flex: none;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-muted);
}

.doc-content {
  padding: var(--card-padding);
  border: var(--card-border);
  border-radius: var(--card-radius);
  background: var(--color-bg-surface);
  color: var(--color-text-primary);
  font-size: var(--ys-font-base);
  line-height: var(--ys-leading-loose);
  /* 原文里的换行就是文档结构，不能塌掉 */
  white-space: pre-wrap;
  word-break: break-word;
}

/*
 * 高亮靠内联的 <mark> 而不是加个 class 到某个块上：
 * 引用的范围可能落在段落中间，只有内联标记才切得准。
 */
.doc__mark {
  padding: 2px 0;
  background: var(--color-warning-subtle);
  box-shadow: inset 3px 0 0 var(--color-warning);
  color: inherit;
}

@media (max-width: 960px) {
  .doc-body {
    grid-template-columns: minmax(0, 1fr);
  }

  .doc-toc {
    position: static;
    max-height: 260px;
  }
}
</style>
