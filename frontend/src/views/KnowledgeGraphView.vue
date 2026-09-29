<script setup lang="ts">
/**
 * 知识图谱页。
 *
 * <b>这一页要回答的问题不是「图长什么样」，而是「这个结论凭什么」。</b>
 * 用户从商品详情点进来时带着一个很具体的疑惑（我买的这个和我在吃的药冲突吗），
 * 离开时要么拿到一句有出处的话，要么明确知道图谱里没收录 —— 两种都行，
 * 唯独不能是「看起来像没有冲突」。
 *
 * 所以每一处措辞都按这条线走：查不到 ≠ 没关系，未收录 ≠ 无风险。
 */
import { checkInteractions, entityNeighborhood, graphStats, searchEntities } from '@/api/graph'
import GraphCanvas from '@/components/knowledge/GraphCanvas.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { GraphEdge, GraphNode, InteractionReport } from '@/types/models'
import { KIND_ORDER, RELATION_ORDER, edgeKey, kindLabel, relationLabel, ringLayout } from '@/utils/graph'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

/** 演示用入口。图谱里收录了这些，点一下就有东西可看，不必先猜名字 */
const SAMPLES = ['SPU7', '深海鱼油', '华法林', '维生素D3']

const edges = ref<GraphEdge[]>([])
const stats = ref<Awaited<ReturnType<typeof graphStats>> | null>(null)
const report = ref<InteractionReport | null>(null)
const loading = ref(false)
const error = ref('')

/** 当前中心实体。放在 URL 上 —— 刷一下页面回到同一个视角，链接也能直接发给别人 */
const root = computed(() => (route.query.root as string | undefined) ?? '')
const depth = computed(() => Number(route.query.depth ?? 2))
const selectedKey = ref('')

/** 关系类型筛选。默认为空表示「全都要」—— 默认全关的话第一眼是一张空图 */
const hidden = ref<string[]>([])

/** 被端上来的边。图上画的和右边列的必须是同一份，否则用户会数不上 */
const visibleEdges = computed(() => edges.value.filter((e) => !hidden.value.includes(e.relation)))

const layout = computed(() => ringLayout(visibleEdges.value, root.value || null))

/** 出现过的类型，按词表的固定顺序排 —— 顺序随数据变的话，图例每次刷新都在跳 */
const kindsInGraph = computed(() => {
  const seen = new Set<string>()
  for (const item of layout.value.nodes) seen.add(item.node.kind)
  return KIND_ORDER.filter((k) => seen.has(k)).concat([...seen].filter((k) => !KIND_ORDER.includes(k)))
})

const relationsInGraph = computed(() => {
  const seen = new Set<string>()
  for (const edge of edges.value) seen.add(edge.relation)
  // 风险类排前面：用户先要知道的是「有没有冲突」，不是「含什么成分」
  return RELATION_ORDER.filter((r) => seen.has(r)).concat(
    [...seen].filter((r) => !RELATION_ORDER.includes(r)),
  )
})

/**
 * 把查询错误转成一句人话。
 * <p>
 * 拦截器抛的是后端的响应体（不是 Error），所以 `e.message` 取不到东西 ——
 * 而图谱不可用时后端特意给了 503 与一段说明（「这不代表没有查到风险」），
 * 那句话正是这里最该显示的内容，不能丢。
 */
function reasonOf(e: unknown, fallback: string): string {
  if (e && typeof e === 'object' && 'msg' in e) return String((e as { msg: unknown }).msg)
  return e instanceof Error ? e.message : fallback
}

async function load() {
  if (!root.value) {
    // 没指定中心就挑一个演示实体，让页面一进来就有东西可看。
    // 这里用 replace：它是一次自动跳转，不该在浏览器的后退栈里留一格
    router.replace({ query: { ...route.query, root: SAMPLES[0] } })
    return
  }
  loading.value = true
  error.value = ''
  selectedKey.value = ''
  try {
    edges.value = await entityNeighborhood(root.value, depth.value)
  } catch (e) {
    edges.value = []
    error.value = reasonOf(e, '图谱加载失败')
  } finally {
    loading.value = false
  }
}

/** 「这几样能不能一起吃」。只在用户主动点的时候发请求 —— 它要带商品与药物，不是每次都要看 */
async function runInteractionCheck() {
  const items = layout.value.nodes
    .filter((n) => n.node.kind === 'PRODUCT' || n.node.kind === 'INGREDIENT' || n.node.kind === 'DRUG')
    .map((n) => n.node.name)
    .slice(0, 5)
  if (items.length < 2) return
  try {
    report.value = await checkInteractions(items)
  } catch (e) {
    // 请求本身就失败（图谱挂了会走 503），按「未检查」呈现。
    // 这里绝不能把 catch 吞成一句「无冲突」——那是把故障说成安全
    report.value = { available: false, note: reasonOf(e, '图谱暂时不可用'), items: [] }
  }
}

function focusOn(name: string) {
  router.push({ query: { ...route.query, root: name } })
}

/**
 * 搜索实体。
 * <p>
 * 走接口而不是本地过滤：本页手里只有<b>当前邻域</b>的节点，
 * 拿它当搜索范围的话，用户搜一个图上恰好没有的词会得到「没有这个实体」——
 * 而它可能好端端地在图里，只是不在这两跳之内。
 */
async function queryEntities(keyword: string, cb: (results: { value: string; label: string }[]) => void) {
  if (!keyword) return cb([])
  try {
    const found: GraphNode[] = await searchEntities(keyword, 12)
    cb(found.map((n) => ({ value: n.name, label: `${n.label}（${kindLabel(n.kind)}）` })))
  } catch {
    cb([])
  }
}

function toggleRelation(relation: string) {
  hidden.value = hidden.value.includes(relation)
    ? hidden.value.filter((r) => r !== relation)
    : [...hidden.value, relation]
}

onMounted(async () => {
  try {
    stats.value = await graphStats()
  } catch {
    // 规模只是个锦上添花的数字，拿不到就不显示，不该因此让整页失败
  }
})

watch([root, depth], load, { immediate: true })
</script>

<template>
  <div class="graph-page">
    <header class="graph-head">
      <nav class="graph-crumb" aria-label="面包屑">
        <RouterLink to="/knowledge">知识库</RouterLink>
        <span aria-hidden="true">/</span>
        <span>关系图谱</span>
      </nav>

      <div class="graph-head__row">
        <div>
          <h1>成分与相互作用图谱</h1>
          <p class="graph-head__sub">
            每个商品沿「成分 → 营养素 → 药物」连出去。<strong>每条线都能点回原文</strong>——
            图谱不生产结论，它只把散落在说明书里的那几句话连起来。
          </p>
        </div>
        <p v-if="stats?.available" class="graph-scale">
          <span class="graph-scale__num">{{ stats.entities }}</span> 个实体
          <span class="graph-scale__sep">·</span>
          <span class="graph-scale__num">{{ stats.relations }}</span> 条关系
        </p>
      </div>

      <div class="graph-tools">
        <label class="graph-tools__field">
          <span>中心实体</span>
          <el-select
            :model-value="root"
            filterable
            remote
            reserve-keyword
            placeholder="输入商品编号或成分名"
            :remote-method="queryEntities"
            style="width: 220px"
            @update:model-value="focusOn"
          >
            <el-option v-for="s in SAMPLES" :key="s" :value="s" :label="s" />
          </el-select>
        </label>

        <label class="graph-tools__field">
          <span>跳数</span>
          <el-radio-group
            :model-value="depth"
            size="small"
            @update:model-value="
              (v: string | number | boolean | undefined) =>
                router.push({ query: { ...route.query, depth: String(v) } })
            "
          >
            <el-radio-button :value="1">1</el-radio-button>
            <el-radio-button :value="2">2</el-radio-button>
            <el-radio-button :value="3">3</el-radio-button>
          </el-radio-group>
        </label>
      </div>

      <!--
        图例即筛选器。分成两个控件的话，用户得先在图例上读到「相互作用」，
        再去筛选器里找到同一个词才能关掉它 —— 一个动作被拆成了两步
      -->
      <ul v-if="relationsInGraph.length" class="legend">
        <li v-for="relation in relationsInGraph" :key="relation">
          <button
            type="button"
            class="legend__item"
            :class="[`legend__item--${relation}`, { 'is-off': hidden.includes(relation) }]"
            :aria-pressed="!hidden.includes(relation)"
            @click="toggleRelation(relation)"
          >
            <span class="legend__swatch" aria-hidden="true" />
            {{ relationLabel(relation) }}
          </button>
        </li>
      </ul>
    </header>

    <ErrorState v-if="error" :message="error" :on-retry="load" />

    <div v-else class="graph-body">
      <section class="graph-stage surface">
        <div v-if="loading" class="graph-stage__loading">正在读取图谱…</div>

        <!--
          「没有关系」与「图谱里没有这个词」是两件事，措辞必须分开：
          前者是结论，后者是「我没查过这个东西」，把它说成前者等于给了用户一个假的安全感
        -->
        <div v-else-if="!layout.nodes.length" class="graph-stage__empty">
          <p class="graph-stage__empty-title">图谱里没有收录「{{ root }}」</p>
          <p>
            这不代表它没有风险，只代表没有关于它的知识条目。可以改看
            <RouterLink to="/knowledge">知识库文档</RouterLink>，或换一个中心实体。
          </p>
          <div class="graph-samples">
            <button v-for="s in SAMPLES" :key="s" type="button" @click="focusOn(s)">{{ s }}</button>
          </div>
        </div>

        <GraphCanvas
          v-else
          :layout="layout"
          :selected-key="selectedKey"
          :selected-node="root"
          @select-edge="(e) => (selectedKey = edgeKey(e))"
          @select-node="focusOn"
        />

        <!--
          半径的含义必须写出来。同心环这个形状本身不会说话 —— 不解释的话，
          用户只会看到「重要的东西在中间，别的东西在周围」，而这恰恰不是它想说的
        -->
        <div v-if="!loading && layout.nodes.length" class="graph-stage__foot">
          <p class="graph-stage__note">
            中心是「{{ layout.nodes.find((n) => n.depth === 0)?.node.label ?? root }}」，
            <strong>每往外一圈多一跳</strong>，圈上的虚线只是刻度。
          </p>
          <ul v-if="kindsInGraph.length > 1" class="kind-legend">
            <li
              v-for="k in kindsInGraph"
              :key="k"
              :class="`kind-legend__item kind-legend__item--${k}`"
            >
              <span class="kind-legend__dot" aria-hidden="true" />
              {{ kindLabel(k) }}
            </li>
          </ul>
        </div>
      </section>

      <aside class="graph-rail surface" aria-label="依据清单">
        <div class="graph-rail__head">
          <h2>依据清单</h2>
          <p>
            {{ visibleEdges.length }} 条
            <template v-if="hidden.length">（已隐藏 {{ hidden.length }} 类）</template>
          </p>
        </div>

        <!-- 「筛选把关系都关了」与「压根没有这个实体」要分开说：
            前者是用户自己拧出来的，后者是平台的缺口。都写成「没有可显示的关系」，
            用户会去翻筛选器找一个并不存在的问题 -->
        <p v-if="!visibleEdges.length" class="graph-rail__empty">
          {{
            layout.nodes.length
              ? '当前筛选下没有可显示的关系。'
              : '图谱里没有收录这个实体，没有依据可列。'
          }}
        </p>

        <!--
          这张清单同时干三件事：点一条看它的依据、键盘可达（SVG 里的 <g> 拿不到焦点）、
          以及图上太密看不过来时的兜底。所以它列的必须是**全部**边，不只是选中的那条
        -->
        <ul v-else class="evidence">
          <li v-for="edge in visibleEdges" :key="edgeKey(edge)" class="evidence__row">
            <button
              type="button"
              class="evidence__head"
              :class="{ 'is-active': edgeKey(edge) === selectedKey }"
              :aria-expanded="edgeKey(edge) === selectedKey"
              @click="selectedKey = edgeKey(edge) === selectedKey ? '' : edgeKey(edge)"
            >
              <span class="evidence__rel" :class="`evidence__rel--${edge.relation}`">
                {{ relationLabel(edge.relation) }}
              </span>
              <span class="evidence__pair">
                {{ edge.head.label }} <span aria-hidden="true">→</span> {{ edge.tail.label }}
              </span>
            </button>

            <div v-if="edgeKey(edge) === selectedKey" class="evidence__detail">
              <p v-if="edge.effect" class="evidence__effect">{{ edge.effect }}</p>

              <!-- 逐字引文是这条边的全部价值所在：没有它，上面那行「A → B」就只是模型的断言 -->
              <blockquote class="evidence__quote">{{ edge.quote }}</blockquote>

              <p class="evidence__source">
                <RouterLink :to="{ path: `/knowledge/${edge.docId}`, query: { chunk: edge.chunkId } }">
                  《{{ edge.docTitle || edge.docId }}》· 查看原文
                </RouterLink>
              </p>

              <p v-if="edge.chain.length" class="evidence__chain">
                关联路径：{{ edge.chain.join(' → ') }}
              </p>

              <button type="button" class="evidence__focus" @click="focusOn(edge.tail.name)">
                以「{{ edge.tail.label }}」为中心
              </button>
            </div>
          </li>
        </ul>

        <div v-if="visibleEdges.length" class="interaction">
          <button type="button" class="interaction__btn" @click="runInteractionCheck">
            检查这几样能不能一起用
          </button>

          <!--
            available=false 必须说成「没查成」。空列表与「无冲突」在界面上长得一模一样，
            而在这个场景里它们是相反的两句话
          -->
          <p v-if="report && !report.available" class="interaction__note interaction__note--warn">
            未检查：{{ report.note || '图谱暂时不可用' }}
          </p>
          <p v-else-if="report" class="interaction__note">
            已检查 {{ report.items.length }} 项。冲突与禁忌见上方清单，每条都带原文。
          </p>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.graph-page {
  max-width: var(--layout-content-max);
  margin: 0 auto;
  padding: var(--ys-space-8);
  display: grid;
  gap: var(--ys-space-5);
}

/* ==================== 头部 ==================== */

.graph-crumb {
  display: flex;
  gap: var(--ys-space-2);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

.graph-crumb a {
  color: var(--color-primary);
}

.graph-head__row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--ys-space-6);
  margin-top: var(--ys-space-2);
}

.graph-head h1 {
  margin: 0 0 var(--ys-space-2);
  font-size: var(--ys-font-2xl);
  line-height: var(--ys-leading-tight);
}

.graph-head__sub {
  max-width: 62ch;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
}

.graph-head__sub strong {
  color: var(--color-text-primary);
}

.graph-scale {
  flex: none;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  font-variant-numeric: tabular-nums;
}

.graph-scale__num {
  color: var(--color-text-primary);
  font-size: var(--ys-font-lg);
  font-weight: 600;
}

.graph-scale__sep {
  margin: 0 var(--ys-space-1);
  color: var(--color-text-muted);
}

.graph-tools {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-6);
  margin-top: var(--ys-space-5);
}

.graph-tools__field {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
}

/* ==================== 图例（兼筛选） ==================== */

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
  margin: var(--ys-space-4) 0 0;
  padding: 0;
  list-style: none;
}

.legend__item {
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-2);
  padding: 5px var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-surface);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  cursor: pointer;
  transition:
    border-color var(--ys-duration-fast) var(--ys-ease-out),
    color var(--ys-duration-fast) var(--ys-ease-out);
}

.legend__item:hover {
  border-color: var(--color-border-strong);
  color: var(--color-text-primary);
}

.legend__item:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

/* 关掉的状态只压暗，不隐藏 —— 消失的开关是找不回来的 */
.legend__item.is-off {
  opacity: 0.45;
}

.legend__item.is-off .legend__swatch {
  background: var(--color-text-muted);
}

.legend__swatch {
  width: 18px;
  height: 3px;
  border-radius: var(--ys-radius-full);
}

/* 与画布上那条线同色。图例与图对不上的话，图例就成了摆设 */
.legend__item--CONTAINS .legend__swatch {
  background: var(--ys-warm-600);
}

.legend__item--PROVIDES .legend__swatch {
  background: var(--color-success);
}

.legend__item--INTERACTS_WITH .legend__swatch {
  background: repeating-linear-gradient(
    90deg,
    var(--color-danger) 0 5px,
    transparent 5px 8px
  );
}

.legend__item--CAUTION_FOR .legend__swatch {
  background: repeating-linear-gradient(
    90deg,
    var(--color-warning) 0 3px,
    transparent 3px 6px
  );
}

/* ==================== 主体 ==================== */

.graph-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: var(--ys-space-5);
  align-items: start;
}

.graph-stage {
  position: relative;
  padding: var(--ys-space-4);
  /* 容器查询：Tauri 桌面窗口可以被拖到很窄，按屏幕宽度断点会失效 */
  container-type: inline-size;
}

.graph-stage__loading,
.graph-stage__empty {
  padding: var(--ys-space-16) var(--ys-space-4);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  text-align: center;
}

.graph-stage__empty-title {
  margin-bottom: var(--ys-space-2);
  color: var(--color-text-primary);
  font-size: var(--ys-font-md);
  font-weight: 600;
}

.graph-stage__empty a {
  color: var(--color-primary);
}

.graph-samples {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
  justify-content: center;
  margin-top: var(--ys-space-4);
}

.graph-samples button {
  padding: 5px var(--ys-space-3);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-primary-subtle);
  color: var(--color-primary);
  font-size: var(--ys-font-xs);
  cursor: pointer;
}

.graph-samples button:hover {
  border-color: var(--color-primary);
}

.graph-stage__foot {
  display: grid;
  gap: var(--ys-space-3);
  margin-top: var(--ys-space-2);
  padding-top: var(--ys-space-3);
  border-top: 1px dashed var(--color-border);
}

.graph-stage__note {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  text-align: center;
}

.graph-stage__note strong {
  color: var(--color-text-primary);
  font-weight: 600;
}

.kind-legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-4);
  justify-content: center;
  margin: 0;
  padding: 0;
  list-style: none;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.kind-legend__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.kind-legend__dot {
  width: 8px;
  height: 8px;
  border-radius: var(--ys-radius-full);
}

.kind-legend__item--PRODUCT .kind-legend__dot {
  background: var(--color-primary);
}

.kind-legend__item--INGREDIENT .kind-legend__dot {
  background: var(--color-accent);
}

.kind-legend__item--NUTRIENT .kind-legend__dot {
  background: var(--color-success);
}

.kind-legend__item--DRUG .kind-legend__dot {
  background: var(--color-danger);
}

.kind-legend__item--DRUG_CLASS .kind-legend__dot {
  background: var(--color-warning);
}

.kind-legend__item--POPULATION .kind-legend__dot {
  background: var(--ys-warm-600);
}

/* ==================== 依据栏 ==================== */

.graph-rail {
  /* 顶栏高度 + 呼吸。清单比画布长得多，跟着滚才是对的 */
  position: sticky;
  top: calc(var(--layout-header-height) + var(--ys-space-4));
  display: grid;
  gap: var(--ys-space-3);
  max-height: calc(100vh - var(--layout-header-height) - var(--ys-space-8));
  padding: var(--ys-space-4);
  overflow-y: auto;
}

.graph-rail__head h2 {
  margin: 0;
  font-size: var(--ys-font-md);
}

.graph-rail__head p,
.graph-rail__empty {
  margin-top: var(--ys-space-1);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.evidence {
  display: grid;
  gap: var(--ys-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.evidence__head {
  display: grid;
  gap: 4px;
  width: 100%;
  padding: var(--ys-space-2) var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface);
  text-align: left;
  cursor: pointer;
}

.evidence__head:hover {
  border-color: var(--color-border-strong);
}

.evidence__head:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.evidence__head.is-active {
  border-color: var(--color-border-focus);
  background: var(--color-primary-subtle);
}

.evidence__rel {
  font-size: var(--ys-font-xs);
  font-weight: 600;
}

/* 关系名的颜色与图上那条线一一对应：清单和画布之间不该有第二套配色 */
.evidence__rel--CONTAINS {
  color: var(--ys-warm-600);
}

.evidence__rel--PROVIDES {
  color: var(--color-success);
}

.evidence__rel--INTERACTS_WITH {
  color: var(--color-danger);
}

.evidence__rel--CAUTION_FOR {
  color: var(--color-warning);
}

.evidence__pair {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.evidence__detail {
  display: grid;
  gap: var(--ys-space-2);
  padding: var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-top: none;
  border-radius: 0 0 var(--ys-radius-sm) var(--ys-radius-sm);
  background: var(--color-bg-surface-muted);
}

.evidence__effect {
  color: var(--color-text-primary);
  font-size: var(--ys-font-sm);
  font-weight: 600;
}

/* 引文用左侧竖线标注「这是原文」。引号会与文档里的中文引号打架，竖线不会 */
.evidence__quote {
  margin: 0;
  padding-left: var(--ys-space-3);
  border-left: 3px solid var(--color-primary-border);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
}

.evidence__source,
.evidence__chain {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.evidence__source a {
  color: var(--color-primary);
}

.evidence__focus {
  justify-self: start;
  padding: 4px var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-surface);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  cursor: pointer;
}

.evidence__focus:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.interaction {
  display: grid;
  gap: var(--ys-space-2);
  padding-top: var(--ys-space-3);
  border-top: 1px dashed var(--color-border);
}

.interaction__btn {
  padding: var(--ys-space-2) var(--ys-space-3);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--ys-radius-sm);
  background: var(--color-primary-subtle);
  color: var(--color-primary);
  font-size: var(--ys-font-sm);
  cursor: pointer;
}

.interaction__btn:hover {
  border-color: var(--color-primary);
}

.interaction__note {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
}

.interaction__note--warn {
  color: var(--color-warning);
}

/* ==================== 窄容器 ==================== */

@container (max-width: 720px) {
  .kind-legend {
    gap: var(--ys-space-3);
  }
}

@media (max-width: 1080px) {
  .graph-body {
    grid-template-columns: minmax(0, 1fr);
  }

  .graph-rail {
    position: static;
    max-height: none;
  }

  .graph-head__row {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
