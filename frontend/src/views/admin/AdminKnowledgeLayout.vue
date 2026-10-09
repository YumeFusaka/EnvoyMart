<script setup lang="ts">
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { computed } from 'vue'

const route = useRoute()
const tabs = [
  { to: '/admin/knowledge', label: '文档管理', caption: '事实源、切片与商品覆盖' },
  { to: '/admin/knowledge/graph-diagnostics', label: '图谱构建诊断', caption: '失败阶段、原因与可重试项' },
  { to: '/admin/knowledge/evaluations', label: '评测运行', caption: '管理员手动触发真实评测' },
]
const active = computed(() => route.path)
</script>

<template>
  <div class="knowledge-shell">
    <header class="knowledge-shell__head">
      <div>
        <p class="eyebrow">Knowledge Operations</p>
        <h1>知识库运营</h1>
        <p>统一管理事实源、图谱构建和需要消耗模型额度的评测任务。</p>
      </div>
    </header>
    <nav class="knowledge-tabs" aria-label="知识库管理子导航">
      <RouterLink v-for="tab in tabs" :key="tab.to" :to="tab.to" class="knowledge-tab" :class="{ active: active === tab.to || (tab.to !== '/admin/knowledge' && active.startsWith(tab.to + '/')) }">
        <strong>{{ tab.label }}</strong><span>{{ tab.caption }}</span>
      </RouterLink>
    </nav>
    <RouterView />
  </div>
</template>

<style scoped>
.knowledge-shell{max-width:var(--layout-content-max);margin:0 auto;padding:var(--ys-space-7);display:grid;gap:var(--ys-space-5)}
.knowledge-shell__head{display:flex;justify-content:space-between;align-items:end}.eyebrow{margin:0;color:var(--color-primary-strong);font-size:var(--ys-font-xs);font-weight:700;letter-spacing:.1em;text-transform:uppercase}.knowledge-shell h1{margin:.25rem 0;font-size:var(--ys-font-2xl)}.knowledge-shell__head p:last-child{margin:0;color:var(--color-text-secondary)}
.knowledge-tabs{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:var(--ys-space-2);padding:var(--ys-space-2);background:var(--color-bg-subtle);border:var(--card-border);border-radius:var(--card-radius)}.knowledge-tab{display:grid;gap:3px;padding:var(--ys-space-3) var(--ys-space-4);border-radius:var(--ys-radius-sm);color:var(--color-text-secondary);text-decoration:none}.knowledge-tab span{font-size:var(--ys-font-xs);color:var(--color-text-muted)}.knowledge-tab:hover{background:var(--color-bg-surface);color:var(--color-primary-strong)}.knowledge-tab.active{background:var(--color-bg-surface);box-shadow:var(--card-shadow);color:var(--color-primary-strong)}
@media(max-width:700px){.knowledge-shell{padding:var(--ys-space-4)}.knowledge-tabs{grid-template-columns:1fr}.knowledge-tab{display:flex;justify-content:space-between;gap:var(--ys-space-3)}}
</style>
