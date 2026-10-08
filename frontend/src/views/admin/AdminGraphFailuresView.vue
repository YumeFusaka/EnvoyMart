<script setup lang="ts">
import { fetchGraphFailures, type GraphFailureRecord } from '@/api/admin/knowledge'
import { onMounted, ref } from 'vue'
const rows = ref<GraphFailureRecord[]>([])
const loading = ref(false)
async function load() { loading.value = true; try { rows.value = await fetchGraphFailures({ limit: 100 }) } finally { loading.value = false } }
onMounted(load)
</script>
<template>
  <div class="admin-page">
    <header class="page-head"><div><p class="eyebrow">Graph Diagnostics</p><h1>图谱失败记录</h1><p>逐条记录来自图谱构建账本，可按批次、文档和阶段定位，不用从汇总日志反推。</p></div><button class="toolbar-btn" type="button" :disabled="loading" @click="load">刷新</button></header>
    <section class="panel"><el-table v-loading="loading" :data="rows" stripe><el-table-column prop="occurredAt" label="时间" width="180"/><el-table-column prop="batchId" label="批次" min-width="220"/><el-table-column prop="docNo" label="文档" width="130"/><el-table-column prop="stage" label="阶段" width="130"/><el-table-column prop="reasonCode" label="原因" width="180"/><el-table-column prop="detail" label="详情" min-width="260"/><el-table-column label="可重试" width="90"><template #default="scope">{{ scope.row.retryable ? '是' : '否' }}</template></el-table-column></el-table><p v-if="!loading && !rows.length" class="empty">当前没有失败记录</p></section>
  </div>
</template>
<style scoped>
.admin-page{max-width:var(--layout-content-max);margin:0 auto;padding:var(--ys-space-7);display:grid;gap:var(--ys-space-5)}.page-head{display:flex;justify-content:space-between;gap:var(--ys-space-4);align-items:end}.eyebrow{margin:0;color:var(--color-primary-strong);font-size:var(--ys-font-xs);font-weight:700;text-transform:uppercase}.page-head h1{margin:.25rem 0;font-size:var(--ys-font-2xl)}.page-head p{margin:0;color:var(--color-text-secondary)}.panel{background:var(--color-bg-surface);border:var(--card-border);border-radius:var(--card-radius);padding:var(--ys-space-4)}.toolbar-btn{min-height:36px;padding:0 var(--ys-space-4);border:1px solid var(--color-border);border-radius:var(--ys-radius-sm);background:var(--color-bg-surface);cursor:pointer}.empty{padding:var(--ys-space-8);text-align:center;color:var(--color-text-muted)}
</style>
