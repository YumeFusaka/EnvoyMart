<script setup lang="ts">
import { addBadCaseTest, listBadCases, reviewBadCase } from '@/api/admin/badCases'
import type { BadCase } from '@/api/ai'
import { onMounted, ref } from 'vue'
const rows = ref<BadCase[]>([]); const loading = ref(false)
async function load(){loading.value=true;try{rows.value=await listBadCases()}finally{loading.value=false}}
async function review(row: BadCase, status:'REVIEWED'|'REJECTED'){row=await reviewBadCase(row.badCaseId,status); await load()}
async function add(row: BadCase){await addBadCaseTest(row.badCaseId); await load()}
onMounted(load)
</script>
<template>
  <div class="admin-page"><header class="page-head"><div><p class="eyebrow">Quality Feedback</p><h1>Bad Case 审核</h1><p>用户点踩只绑定单条回答；审核通过后才进入独立追加测试集。</p></div><button class="toolbar-btn" type="button" :disabled="loading" @click="load">刷新</button></header>
  <section class="panel"><el-table v-loading="loading" :data="rows" stripe><el-table-column prop="updatedAt" label="时间" width="180"/><el-table-column prop="badCaseId" label="编号" min-width="220"/><el-table-column prop="question" label="问题" min-width="220" show-overflow-tooltip/><el-table-column prop="reasonCodes" label="原因" width="220"><template #default="scope">{{ scope.row.reasonCodes.join('、') }}</template></el-table-column><el-table-column prop="status" label="状态" width="130"/><el-table-column label="操作" width="250"><template #default="scope"><el-button v-if="scope.row.status==='ACTIVE'" size="small" @click="review(scope.row,'REVIEWED')">审核通过</el-button><el-button v-if="scope.row.status==='ACTIVE'" size="small" type="danger" plain @click="review(scope.row,'REJECTED')">驳回</el-button><el-button v-if="scope.row.status==='REVIEWED' || scope.row.status==='IN_TEST_SET'" size="small" type="primary" plain @click="add(scope.row)">加入追加集</el-button></template></el-table-column><el-table-column type="expand"><template #default="scope"><div class="detail"><p><b>回答：</b>{{ scope.row.answer }}</p><p v-if="scope.row.comment"><b>说明：</b>{{ scope.row.comment }}</p><p><b>requestId：</b>{{ (scope.row.responseSnapshot as any)?.requestId || '未记录' }}</p></div></template></el-table-column></el-table><p v-if="!loading && !rows.length" class="empty">暂无用户反馈</p></section></div>
</template>
<style scoped>
.admin-page{max-width:var(--layout-content-max);margin:0 auto;padding:var(--ys-space-7);display:grid;gap:var(--ys-space-5)}.page-head{display:flex;justify-content:space-between;align-items:end;gap:var(--ys-space-4)}.eyebrow{margin:0;color:var(--color-primary-strong);font-size:var(--ys-font-xs);font-weight:700;text-transform:uppercase}.page-head h1{margin:.25rem 0;font-size:var(--ys-font-2xl)}.page-head p{margin:0;color:var(--color-text-secondary)}.panel{background:var(--color-bg-surface);border:var(--card-border);border-radius:var(--card-radius);padding:var(--ys-space-4)}.toolbar-btn{min-height:36px;padding:0 var(--ys-space-4);border:1px solid var(--color-border);border-radius:var(--ys-radius-sm);background:var(--color-bg-surface);cursor:pointer}.detail{padding:var(--ys-space-3);white-space:pre-wrap;color:var(--color-text-secondary)}.empty{padding:var(--ys-space-8);text-align:center;color:var(--color-text-muted)}
</style>
