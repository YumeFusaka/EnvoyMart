<script setup lang="ts">
import { addBadCaseTest, listBadCases, removeBadCaseTest, reviewBadCase, type FixtureAnnotation } from '@/api/admin/badCases'
import type { BadCase } from '@/api/ai'
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
const rows = ref<BadCase[]>([]); const loading = ref(false)
async function load(){loading.value=true;try{rows.value=await listBadCases()}finally{loading.value=false}}
async function review(row: BadCase, status:'REVIEWED'|'REJECTED'){row=await reviewBadCase(row.badCaseId,status); await load()}
const annotationVisible = ref(false)
const selected = ref<BadCase | null>(null)
const annotation = ref<FixtureAnnotation>({ evalKind: 'ANSWERABLE', expectRefuse: false, mustMention: [], expectedTools: [], annotation: '' })
const mustMentionText = ref('')
const expectedToolsText = ref('')
function openAnnotation(row: BadCase) {
  selected.value = row
  annotation.value = { evalKind: 'ANSWERABLE', expectRefuse: false, mustMention: [], expectedTools: [], annotation: '' }
  mustMentionText.value = ''
  expectedToolsText.value = ''
  annotationVisible.value = true
}
function splitLines(value: string) { return value.split(/[\n,，]/).map(item => item.trim()).filter(Boolean) }
async function submitAnnotation() {
  if (!selected.value || !annotation.value.evalKind.trim()) return
  annotation.value.mustMention = splitLines(mustMentionText.value)
  annotation.value.expectedTools = splitLines(expectedToolsText.value)
  await addBadCaseTest(selected.value.badCaseId, annotation.value)
  annotationVisible.value = false
  await load()
  ElMessage.success('已加入追加测试集')
}
async function remove(row: BadCase) {
  await ElMessageBox.confirm('移出后不会删除 Bad Case，只会撤销追加测试集标记。', '确认移出', { type: 'warning' })
  await removeBadCaseTest(row.badCaseId)
  await load()
  ElMessage.success('已移出追加测试集')
}
onMounted(load)
</script>
<template>
  <div class="admin-page"><header class="page-head"><div><p class="eyebrow">Quality Feedback</p><h1>Bad Case 审核</h1><p>用户点踩只绑定单条回答；审核通过后，补齐评测标注才进入独立追加测试集。</p></div><button class="toolbar-btn" type="button" :disabled="loading" @click="load">刷新</button></header>
  <section class="panel"><el-table v-loading="loading" :data="rows" stripe><el-table-column prop="updatedAt" label="时间" width="180"/><el-table-column prop="badCaseId" label="编号" min-width="220"/><el-table-column prop="question" label="问题" min-width="220" show-overflow-tooltip/><el-table-column prop="reasonCodes" label="原因" width="220"><template #default="scope">{{ scope.row.reasonCodes.join('、') }}</template></el-table-column><el-table-column prop="status" label="状态" width="130"/><el-table-column label="操作" width="300"><template #default="scope"><el-button v-if="scope.row.status==='ACTIVE'" size="small" @click="review(scope.row,'REVIEWED')">审核通过</el-button><el-button v-if="scope.row.status==='ACTIVE'" size="small" type="danger" plain @click="review(scope.row,'REJECTED')">驳回</el-button><el-button v-if="scope.row.status==='REVIEWED'" size="small" type="primary" plain @click="openAnnotation(scope.row)">加入追加集</el-button><el-button v-if="scope.row.status==='IN_TEST_SET'" size="small" type="warning" plain @click="remove(scope.row)">移出追加集</el-button></template></el-table-column><el-table-column type="expand"><template #default="scope"><div class="detail"><p><b>回答：</b>{{ scope.row.answer }}</p><p v-if="scope.row.comment"><b>说明：</b>{{ scope.row.comment }}</p><p><b>requestId：</b>{{ (scope.row.responseSnapshot as any)?.requestId || '未记录' }}</p></div></template></el-table-column></el-table><p v-if="!loading && !rows.length" class="empty">暂无用户反馈</p></section>
  <el-dialog v-model="annotationVisible" title="追加测试集标注" width="560px"><el-form label-position="top"><el-form-item label="评测类型" required><el-select v-model="annotation.evalKind" style="width:100%"><el-option label="可回答" value="ANSWERABLE"/><el-option label="不可回答 / 应拒答" value="UNANSWERABLE"/><el-option label="多跳" value="MULTI_HOP"/></el-select></el-form-item><el-form-item label="拒答期望"><el-switch v-model="annotation.expectRefuse" active-text="应拒答" inactive-text="应回答"/></el-form-item><el-form-item label="必须提及（逗号或换行分隔）"><el-input v-model="mustMentionText" type="textarea" :rows="2" placeholder="例如：适用人群、禁忌提醒"/></el-form-item><el-form-item label="期望工具（逗号或换行分隔）"><el-input v-model="expectedToolsText" type="textarea" :rows="2" placeholder="例如：searchKnowledge、getProduct"/></el-form-item><el-form-item label="人工备注"><el-input v-model="annotation.annotation" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="说明为什么把这条加入追加集，以及希望验证什么"/></el-form-item></el-form><template #footer><el-button @click="annotationVisible=false">取消</el-button><el-button type="primary" :disabled="!annotation.evalKind.trim()" @click="submitAnnotation">确认加入</el-button></template></el-dialog></div>
</template>
<style scoped>
.admin-page{max-width:var(--layout-content-max);margin:0 auto;padding:var(--ys-space-7);display:grid;gap:var(--ys-space-5)}.page-head{display:flex;justify-content:space-between;align-items:end;gap:var(--ys-space-4)}.eyebrow{margin:0;color:var(--color-primary-strong);font-size:var(--ys-font-xs);font-weight:700;text-transform:uppercase}.page-head h1{margin:.25rem 0;font-size:var(--ys-font-2xl)}.page-head p{margin:0;color:var(--color-text-secondary)}.panel{background:var(--color-bg-surface);border:var(--card-border);border-radius:var(--card-radius);padding:var(--ys-space-4)}.toolbar-btn{min-height:36px;padding:0 var(--ys-space-4);border:1px solid var(--color-border);border-radius:var(--ys-radius-sm);background:var(--color-bg-surface);cursor:pointer}.detail{padding:var(--ys-space-3);white-space:pre-wrap;color:var(--color-text-secondary)}.empty{padding:var(--ys-space-8);text-align:center;color:var(--color-text-muted)}
</style>
