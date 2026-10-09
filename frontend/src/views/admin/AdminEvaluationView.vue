<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getGroundingReport, getProductionRetrievalReport, runGroundingEval, runProductionRetrievalEval, type GroundingLiveRun, type ProductionRetrievalReport } from '@/api/eval'

const retrieval = ref<ProductionRetrievalReport | null>(null)
const grounding = ref<GroundingLiveRun | null>(null)
const loading = ref(true)
const running = ref<'retrieval' | 'grounding' | null>(null)
async function load(){ loading.value=true; try { retrieval.value=await getProductionRetrievalReport(); grounding.value=(await getGroundingReport()).live } finally { loading.value=false } }
onMounted(load)
async function trigger(kind:'retrieval'|'grounding'){
  if(running.value) return
  await ElMessageBox.confirm(kind==='retrieval'?'将调用真实 embedding、图谱检索和重排，可能消耗模型额度。确认开始？':'将逐条调用真实 Agent 并执行回答质量判定，可能消耗较多模型额度。确认开始？','确认触发评测',{type:'warning',confirmButtonText:'开始评测',cancelButtonText:'取消'})
  running.value=kind
  try { if(kind==='retrieval') retrieval.value=await runProductionRetrievalEval(); else grounding.value=await runGroundingEval(); ElMessage.success('评测已启动，状态会自动刷新'); await load() } finally { running.value=null }
}
const retrievalStatus=computed(()=>retrieval.value?.status??'NEVER'); const groundingStatus=computed(()=>grounding.value?.status??'IDLE')
</script>
<template><section class="eval-admin"><div class="intro"><h2>评测运行中心</h2><p>这里是唯一的真实评测触发入口。公开评测页只展示已生成快照，不会启动模型调用。</p></div><div class="run-grid"><article class="run-card"><div class="run-card__icon">检</div><div><h3>检索质量评测</h3><p>真实 embedding、BM25、Neo4j、RRF 与重排，衡量召回和图谱路径。</p><span class="status">当前状态：{{ retrievalStatus }}</span></div><button type="button" :disabled="!!running || retrievalStatus==='RUNNING'" @click="trigger('retrieval')">{{ running==='retrieval'?'启动中…':retrievalStatus==='RUNNING'?'运行中':'手动触发' }}</button></article><article class="run-card"><div class="run-card__icon">答</div><div><h3>回答质量评测</h3><p>真实 Agent、证据门、引用判定和多跳结果，衡量回答是否站得住。</p><span class="status">当前状态：{{ groundingStatus }}</span></div><button type="button" :disabled="!!running || groundingStatus==='RUNNING'" @click="trigger('grounding')">{{ running==='grounding'?'启动中…':groundingStatus==='RUNNING'?'运行中':'手动触发' }}</button></article></div></section></template>
<style scoped>.eval-admin{display:grid;gap:var(--ys-space-5)}.intro{padding:var(--ys-space-5);background:var(--color-bg-surface);border:var(--card-border);border-radius:var(--card-radius)}.intro h2{margin:0 0 var(--ys-space-2)}.intro p{margin:0;color:var(--color-text-secondary)}.run-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:var(--ys-space-4)}.run-card{display:grid;grid-template-columns:auto 1fr auto;gap:var(--ys-space-4);align-items:center;padding:var(--ys-space-5);background:var(--color-bg-surface);border:var(--card-border);border-radius:var(--card-radius);box-shadow:var(--card-shadow)}.run-card__icon{display:grid;place-items:center;width:44px;height:44px;border-radius:50%;background:var(--color-primary-soft);color:var(--color-primary-strong);font-weight:800}.run-card h3{margin:0}.run-card p{margin:var(--ys-space-1) 0;color:var(--color-text-secondary);font-size:var(--ys-font-sm)}.status{color:var(--color-text-muted);font-size:var(--ys-font-xs)}.run-card button{min-height:38px;padding:0 var(--ys-space-4);border:0;border-radius:var(--ys-radius-sm);background:var(--color-primary);color:var(--color-text-inverse);font-weight:700;cursor:pointer}.run-card button:hover{background:var(--color-primary-strong);box-shadow:0 3px 10px color-mix(in srgb,var(--color-primary) 30%,transparent)}.run-card button:disabled{opacity:.55;cursor:wait}@media(max-width:760px){.run-grid{grid-template-columns:1fr}.run-card{grid-template-columns:auto 1fr}.run-card button{grid-column:2;justify-self:start}}</style>
