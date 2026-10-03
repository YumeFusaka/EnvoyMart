<script setup lang="ts">
import { listDocuments } from '@/api/knowledge'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { DocumentSummary } from '@/types/models'
import { formatDate } from '@/utils/format'
import { SCOPE_OPTIONS, scopeLabel, sourceLabel } from '@/utils/knowledge'
import { onMounted, ref } from 'vue'

const documents = ref<DocumentSummary[]>([])
const loading = ref(true)
const failed = ref(false)
const scope = ref('')
const keyword = ref('')

/**
 * 停用文档不给看。
 * <p>
 * 停用只表示「不再被检索召回」，历史引用仍能反查到它（这点由服务端的默认查询保证）。
 * 但把它摆在公开的知识库列表里会误导人 —— 用户会以为自己看到的是平台当前的规则。
 */
const STATUS_ENABLED = 1

async function load() {
  loading.value = true
  failed.value = false
  try {
    documents.value = await listDocuments({
      scope: scope.value || undefined,
      keyword: keyword.value.trim() || undefined,
      status: STATUS_ENABLED,
    })
  } catch {
    failed.value = true
    documents.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="kb-page">
    <header class="kb-header">
      <p class="eyebrow">Knowledge Base</p>
      <h1>平台知识库</h1>
      <p class="subcopy">
        智能助手回答里的每一条依据都出自这里。全库公开可读，不需要账号。
      </p>

      <!--
        图谱、评测与文档是同一个知识库的三种读法：文档按「一篇」看，图谱按「一条关系」看，
        评测按「多少分」看。所以入口都在这里而不是塞进主导航 —— 它们是知识库内部的视图，
        不是一个新板块
      -->
      <div class="kb-entries">
        <RouterLink to="/knowledge/graph" class="kb-entry">
          <span class="kb-entry__title">成分与相互作用图谱</span>
          <span class="kb-entry__desc">
            换个读法：按「成分 → 营养素 → 药物」看这些文档之间的关系，每条线都点得回原文。
          </span>
          <span class="kb-entry__arrow" aria-hidden="true">→</span>
        </RouterLink>

        <RouterLink to="/knowledge/eval" class="kb-entry kb-entry--accent">
          <span class="kb-entry__title">检索质量评测</span>
          <span class="kb-entry__desc">
            第三种读法：120 条标注查询按三档难度算出的命中率与排序指标，对照随机基线；
            失败样本逐条列出。
          </span>
          <span class="kb-entry__arrow" aria-hidden="true">→</span>
        </RouterLink>

        <RouterLink to="/knowledge/eval/answer" class="kb-entry">
          <span class="kb-entry__title">回答质量评测</span>
          <span class="kb-entry__desc">
            检索的另一半：24 条标注问题上的幻觉率、引用准确率、拒答准确率与多跳命中率；
            管理员可触发真实重跑对照。
          </span>
          <span class="kb-entry__arrow" aria-hidden="true">→</span>
        </RouterLink>
      </div>
    </header>

    <section class="kb-filters" aria-label="筛选">
      <el-input
        v-model="keyword"
        placeholder="按标题或标签搜索"
        clearable
        class="kb-filters__search"
        @keyup.enter="load"
        @clear="load"
      />
      <el-radio-group v-model="scope" @change="load">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button v-for="option in SCOPE_OPTIONS" :key="option.value" :value="option.value">
          {{ option.label }}
        </el-radio-button>
      </el-radio-group>
      <el-button type="primary" plain @click="load">搜索</el-button>
    </section>

    <ErrorState v-if="failed" message="知识库加载失败，请重试" :on-retry="load" />

    <el-skeleton v-else-if="loading" :rows="6" animated />

    <el-empty v-else-if="!documents.length" description="没有符合条件的文档" />

    <section v-else class="kb-grid">
      <RouterLink
        v-for="item in documents"
        :key="item.docNo"
        class="kb-card"
        :to="{ name: 'knowledge-doc', params: { docNo: item.docNo } }"
      >
        <div class="kb-card__head">
          <span class="kb-card__no">{{ item.docNo }}</span>
          <span class="kb-card__source">{{ sourceLabel(item.source) }}</span>
        </div>

        <h2>{{ item.title }}</h2>

        <p class="kb-card__meta">
          <span>{{ scopeLabel(item.scope) }}</span>
          <span>{{ item.version }}</span>
          <span>{{ item.chunkCount }} 片</span>
          <span>{{ item.contentLength }} 字</span>
        </p>

        <ul v-if="item.tags" class="kb-card__tags">
          <li v-for="tag in item.tags.split(',')" :key="tag">{{ tag }}</li>
        </ul>

        <p class="kb-card__updated">更新于 {{ formatDate(item.updatedAt) }}</p>
      </RouterLink>
    </section>
  </div>
</template>

<style scoped>
.kb-page {
  max-width: var(--layout-content-max);
  margin: 0 auto;
  padding: var(--ys-space-8);
  display: grid;
  gap: var(--ys-space-6);
}

/* 入口条。做成一整条可点的横条而不是一个按钮：
   它要说明「这是什么」，光写「查看图谱」四个字没人点得明白。
   两种色调（主色 / 强调色）让两条入口同屏时能被一眼分开 */
.kb-entries {
  display: grid;
  gap: var(--ys-space-3);
  margin-top: var(--ys-space-5);
}

.kb-entry {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--ys-space-1) var(--ys-space-4);
  padding: var(--ys-space-4) var(--ys-space-5);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--ys-radius-md);
  background: linear-gradient(90deg, var(--color-primary-subtle), var(--color-bg-surface) 72%);
  color: var(--color-text-primary);
  transition:
    border-color var(--ys-duration-fast) var(--ys-ease-out),
    transform var(--ys-duration-fast) var(--ys-ease-out);
}

.kb-entry:hover {
  border-color: var(--color-primary);
}

.kb-entry:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.kb-entry--accent {
  border-color: var(--color-border);
  background: linear-gradient(90deg, var(--color-accent-subtle), var(--color-bg-surface) 72%);
}

.kb-entry--accent:hover {
  border-color: var(--color-accent);
}

.kb-entry__title {
  font-size: var(--ys-font-md);
  font-weight: 600;
}

.kb-entry__desc {
  grid-column: 1;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
}

.kb-entry__arrow {
  grid-row: 1 / span 2;
  grid-column: 2;
  align-self: center;
  color: var(--color-primary);
  font-size: var(--ys-font-lg);
  transition: transform var(--ys-duration-fast) var(--ys-ease-out);
}

.kb-entry--accent .kb-entry__arrow {
  color: var(--color-accent);
}

.kb-entry:hover .kb-entry__arrow {
  transform: translateX(4px);
}

.kb-header h1 {
  margin: var(--ys-space-1) 0;
  font-size: var(--ys-font-2xl);
}

.eyebrow {
  margin: 0;
  color: var(--color-primary);
  font-size: var(--ys-font-xs);
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.subcopy {
  margin: 0;
  max-width: 760px;
  color: var(--color-text-secondary);
  line-height: var(--ys-leading-loose);
}

.kb-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ys-space-3);
}

.kb-filters__search {
  width: 280px;
}

.kb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: var(--ys-space-4);
}

.kb-card {
  display: grid;
  gap: var(--ys-space-2);
  align-content: start;
  padding: var(--card-padding);
  border: var(--card-border);
  border-radius: var(--card-radius);
  background: var(--color-bg-surface);
  color: var(--color-text-primary);
  text-decoration: none;
  transition:
    transform var(--ys-duration-base) var(--ys-ease-out),
    border-color var(--ys-duration-base) var(--ys-ease-out),
    box-shadow var(--ys-duration-base) var(--ys-ease-out);
}

.kb-card:hover {
  transform: translateY(-2px);
  border-color: var(--color-primary-border);
  box-shadow: var(--ys-shadow-dropdown);
}

.kb-card:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.kb-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-2);
}

.kb-card__no {
  color: var(--color-text-muted);
  font-family: var(--ys-font-mono);
  font-size: var(--ys-font-xs);
}

.kb-card__source {
  padding: 1px 8px;
  border: 1px solid var(--color-accent);
  border-radius: var(--ys-radius-sm);
  color: var(--color-accent);
  font-size: var(--ys-font-xs);
}

.kb-card h2 {
  margin: 0;
  font-size: var(--ys-font-md);
  line-height: var(--ys-leading-tight);
}

.kb-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.kb-card__meta span + span::before {
  content: '·';
  margin-right: var(--ys-space-2);
  color: var(--color-text-muted);
}

.kb-card__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.kb-card__tags li {
  padding: 1px 6px;
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface-muted);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.kb-card__updated {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

@media (max-width: 720px) {
  .kb-page {
    padding: var(--ys-space-4);
  }

  .kb-filters__search {
    width: 100%;
  }
}
</style>
