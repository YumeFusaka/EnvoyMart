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
import { entityNeighborhood, graphStats, searchEntities } from '@/api/graph'
import GraphCanvas from '@/components/knowledge/GraphCanvas.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import type { GraphEdge, GraphNode } from '@/types/models'
import type { EvidenceGroup } from '@/utils/graph'
import {
  KIND_ORDER,
  RELATION_ORDER,
  edgeKey,
  groupByRelation,
  kindLabel,
  relationLabel,
  ringLayout,
} from '@/utils/graph'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

/** 画布组件实例。缩放工具栏通过它驱动画布自己的视图状态（见 GraphCanvas 的 defineExpose） */
const canvasRef = ref<InstanceType<typeof GraphCanvas> | null>(null)

/** 画布上报的缩放读数，直接显示在工具栏上。100% = 整图铺满 */
const zoomPercent = ref('100%')
/**
 * 缩放到顶/到底时按钮置灰。
 * <p>
 * 不置灰的话按钮点了没反应，用户会以为页面卡住 —— 反馈缺失比功能缺失更让人怀疑系统。
 */
const canZoomIn = ref(true)
const canZoomOut = ref(true)

/**
 * 缩放档位。与页头的「跳数」同形，都是「先给一个总览，再决定要不要钻进去」。
 * 100% 是铺满全图、200% 是看清一圈、300% 是逐条边核对依据 —— 三档对应三种读法。
 */
const ZOOM_PRESETS = [
  { value: 100, label: '全图' },
  { value: 200, label: '细看' },
  { value: 300, label: '逐条' },
]
/** 档位命中用整数百分比比较，浮点误差不该让按钮看起来没选中 */
const zoomLevel = computed(() => Math.round(Number.parseFloat(zoomPercent.value)))

/**
 * 当前在哪个量级的提示。「离原图多远」这件事百分比已经说了，
 * 这里补一句视角说法，方便用户在缩放时知道自己走到哪一步了
 */
const scaleHint = computed(() => {
  const level = zoomLevel.value
  if (level <= 100) return '整图'
  if (level <= 200) return '局部'
  return '细节'
})

/**
 * 演示用入口。图谱里收录了这些，点一下就有东西可看，不必先猜名字。
 *
 * 这几个名字是**快捷入口**，不是「图谱里一定有」的承诺 —— 图谱随语料重建，
 * 某个演示实体被重命名或删掉是可能的。所以它们只用于下拉与快捷按钮；
 * **首次进入的默认中心实体不取这里**，而是从图谱自己的数据里挑（见 pickDefaultRoot），
 * 否则一个写死的演示名会把整页带进「图谱里没有收录」的空态：页面看上去是坏的，
 * 实际只是默认值过期了。
 */
const SAMPLES = ['SPU7', '深海鱼油', '华法林', '维生素D3']

const edges = ref<GraphEdge[]>([])
const stats = ref<Awaited<ReturnType<typeof graphStats>> | null>(null)
const loading = ref(false)
const error = ref('')

/** 当前中心实体。放在 URL 上 —— 刷一下页面回到同一个视角，链接也能直接发给别人 */
const root = computed(() => (route.query.root as string | undefined) ?? '')
/**
 * 跳数。URL 是可以被手改的：`?depth=abc` 会把 NaN 传进查询，`?depth=0` 会得到一张空图
 * 且页面文案会说「没有邻域」——把参数错误说成事实。非法值一律回落默认 2
 */
const depth = computed(() => {
  const parsed = Number(route.query.depth ?? 2)
  return Number.isInteger(parsed) && parsed >= 1 && parsed <= 3 ? parsed : 2
})
const selectedKey = ref('')

/**
 * 中心实体下拉的候选。**由远程搜索写入，不是写死的常量列表**。
 *
 * 原先是 `v-for="s in SAMPLES"`，于是无论用户搜什么，下拉里永远只有那 4 个演示名——
 * 远程调用的结果根本没被渲染。而 `remote-method` 在 Element Plus 里是
 * **单参数**（`props.remoteMethod(val)`，返回值靠数据源回填），
 * 这里却按 Element UI 2.x 的 callback 风格写成 `(keyword, cb)`，
 * 第二个参数恒为 undefined，一调 `cb(...)` 就抛 `t is not a function`。
 *
 * 正确写法：远程结果写进这个 ref，模板按它渲染 `el-option`。
 */
const entityOptions = ref<{ value: string; label: string }[]>([])

/** 关系类型筛选。默认为空表示「全都要」—— 默认全关的话第一眼是一张空图 */
const hidden = ref<string[]>([])

/** 被端上来的边。图上画的和右边列的必须是同一份，否则用户会数不上 */
const visibleEdges = computed(() => edges.value.filter((e) => !hidden.value.includes(e.relation)))

const layout = computed(() => ringLayout(visibleEdges.value, root.value || null))

/** 中心实体的展示名。清单标题要说清「这些关系是围绕谁列的」，「依据清单」四个字没说对象 */
const rootLabel = computed(() => {
  const node = layout.value.nodes.find((n) => n.depth === 0)
  return node?.node.label ?? root.value
})

/**
 * 当前中心并进候选项，且永远用展示名覆盖：`el-select` 只认选项列表里的 label，
 * 找不到就退回去显示原始值 —— 于是键 `spu7` 露在标题栏上，和图上的「鱼油软胶囊」对不上。
 */
watch([root, rootLabel], () => {
  if (!root.value) return
  const rest = entityOptions.value.filter((o) => o.value !== root.value)
  entityOptions.value = [{ value: root.value, label: rootLabel.value }, ...rest]
}, { immediate: true })

/**
 * 下拉展示文本的 key。**只靠改选项 label 不够**：`el-select` 把已选项的显示文本
 * 缓存住了，选项列表变化不会让它重算，首屏那几百毫秒的 `spu7` 就一直在标题栏上。
 * 让它在展示名变化时重建一次，代价只落在这一个控件上。
 */
const selectKey = computed(() => `${root.value}::${rootLabel.value}`)

/** 出现过的类型，按词表的固定顺序排 —— 顺序随数据变的话，图例每次刷新都在跳 */
const kindsInGraph = computed(() => {
  const seen = new Set<string>()
  for (const item of layout.value.nodes) seen.add(item.node.kind)
  return KIND_ORDER.filter((k) => seen.has(k)).concat(
    [...seen].filter((k) => !KIND_ORDER.includes(k)),
  )
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
 * 右栏的两段结构。用户是带着「这个中心实体有什么」进来的，所以中心实体的关系必须
 * 先给、并且单独成段；其余节点是背景，放在第二段。
 *
 * 第二段按「离中心最近的那一端」归组：一条边可能两端都不是中心（成分→药物），
 * 挂到跳数小的那一端，组名就是那个节点。这样每个组读起来都是
 * 「从中心往外第 N 圈的某个节点，和它牵出去的所有线」。
 * 人群只作为宾语出现，不单独成组 —— 它不是「一样东西」，用户不会去找「妊娠期女性」这个组。
 */
const evidenceSections = computed(() => {
  const edges = visibleEdges.value
  const depthOf = new Map<string, number>()
  for (const n of layout.value.nodes) depthOf.set(n.node.name, n.depth)
  const rootName = layout.value.nodes.find((n) => n.depth === 0)?.node.name ?? root.value

  const isRoot = (e: GraphEdge) => e.head.name === rootName || e.tail.name === rootName
  const core = edges.filter(isRoot)
  const rest = edges.filter((e) => !isRoot(e))

  /**
   * 组内按关系聚合后再按种类分组。
   * <p>
   * 聚合放在这里而不是更早：画布仍然画每一条边（它们确实存在），
   * 只有**给人读的列表**按关系合并 —— 同一件事被三篇文档说过，用户要看的是一件事 +
   * 三处出处，不是同一行重复三遍。风险类排前面：用户先要知道的是「有没有冲突」。
   */
  const byRelation = (list: GraphEdge[]) => {
    const byKind = new Map<string, EvidenceGroup[]>()
    for (const g of groupByRelation(list)) {
      const arr = byKind.get(g.relation) ?? []
      arr.push(g)
      byKind.set(g.relation, arr)
    }
    return RELATION_ORDER.filter((r) => byKind.has(r))
      .concat([...byKind.keys()].filter((r) => !RELATION_ORDER.includes(r)))
      .map((relation) => ({ relation, relations: byKind.get(relation)! }))
  }

  /** 一组里有多少条关系、一共多少处依据。两个数字都要给，否则计数和展开后的条数对不上 */
  const tally = (groups: { relations: EvidenceGroup[] }[]) => ({
    relations: groups.reduce((n, g) => n + g.relations.length, 0),
    sources: groups.reduce(
      (n, g) => n + g.relations.reduce((m, r) => m + r.sources.length, 0),
      0,
    ),
  })

  // 归组：取离中心更近的那一端；两端跳数相同（都是外圈互连）时取 head
  const anchored = new Map<string, { label: string; kind: string; edges: GraphEdge[] }>()
  for (const e of rest) {
    const candidates = [e.head, e.tail].filter((n) => n.kind !== 'POPULATION')
    if (!candidates.length) continue
    const picked = candidates.reduce((a, b) =>
      (depthOf.get(a.name) ?? 99) <= (depthOf.get(b.name) ?? 99) ? a : b,
    )
    const g = anchored.get(picked.name) ?? { label: picked.label, kind: picked.kind, edges: [] }
    g.edges.push(e)
    anchored.set(picked.name, g)
  }
  const outer = [...anchored.entries()]
    .sort((a, b) => (depthOf.get(a[0]) ?? 99) - (depthOf.get(b[0]) ?? 99))
    .map(([name, g]) => {
      const groups = byRelation(g.edges)
      return { name, label: g.label, kind: g.kind, groups, ...tally(groups) }
    })

  const coreGroups = byRelation(core)
  const outerRelations = outer.reduce((n, e2) => n + e2.relations, 0)
  const outerSources = outer.reduce((n, e2) => n + e2.sources, 0)
  const all = groupByRelation(edges)
  return {
    core: coreGroups,
    ...tally(coreGroups),
    outer,
    outerRelations,
    outerSources,
    /** 全文（两段合计）的关系数与依据处数，给顶部那句读 */
    totalRelations: all.length,
    totalSources: edges.length,
  }
})

/**
 * 把查询错误转成一句人话。
 * <p>
 * 图谱不可用时后端特意给了 503 与一段说明（「这不代表没有查到风险」），
 * 拦截器把那句话包成 Error 抛出，它正是这里最该显示的内容，不能丢。
 */
function reasonOf(e: unknown, fallback: string): string {
  return e instanceof Error ? e.message : fallback
}

async function load() {
  if (!root.value) {
    // 没指定中心就挑一个实体，让页面一进来就有东西可看。
    // 这里用 replace：它是一次自动跳转，不该在浏览器的后退栈里留一格
    const fallback = await resolveDefaultRoot()
    router.replace({ query: { ...route.query, root: fallback } })
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

/**
 * 首次进入时的默认中心实体 —— **从图谱自己的数据里挑，不写死名字**。
 *
 * 写死一个演示名的代价不是「偶尔空一次」：默认值过期后，用户每次打开这页
 * 看到的都是一个没有画布的空态，页面上还写着「图谱里没有收录『SPU7』」——
 * 那读起来是「这个知识库有问题」，而不是「默认值该换了」。
 *
 * 挑选顺序：先按 SAMPLES 找仍然在册的那个（保住演示体验），都找不到就用
 * 图谱里第一个实体兜底。用 search 而不是直接拿 stats：stats 只有数量，
 * 拿不到实体名；search 传空串会按 limit 返回一批实体。
 */
async function resolveDefaultRoot(): Promise<string> {
  for (const candidate of SAMPLES) {
    try {
      const hits = await searchEntities(candidate, 5)
      if (hits.some((h) => h.name.toLowerCase() === candidate.toLowerCase())) {
        return candidate
      }
    } catch {
      // 单个候选查不到就试下一个；全试完还有兜底，不让这一步把整页拖垮
    }
  }
  try {
    // 兜底优先挑商品：这页的主题是「每个商品沿成分连出去」，拿一个营养素当中心
    // 画出来的图与用户对「商品图谱」的预期对不上。取一批再筛，比多问接口一次便宜
    const any = await searchEntities('', 20)
    const product = any.find((h) => h.kind === 'PRODUCT')
    if (product) return product.name
    if (any.length) return any[0]!.name
  } catch {
    // 连兜底都拿不到：回一个空串，页面按「没有中心实体」提示，并让用户自己搜
  }
  return ''
}


function focusOn(name: string) {
  router.push({ query: { ...route.query, root: name } })
}

/**
 * 画布上点一个节点。**主行为是「以它为中心」**，这是这张图最主要的用法。
 * 顺带把它加进「一起吃安全吗」的名单 —— 用户刚点过它，多半正在关心它。
 * 加名单只是副作用，所以只在它属于可查品类时才做，也不改变跳转结果。
 */
function onCanvasNode(name: string) {
  focusOn(name)
}

/**
 * 搜索实体。
 * <p>
 * 走接口而不是本地过滤：本页手里只有<b>当前邻域</b>的节点，
 * 拿它当搜索范围的话，用户搜一个图上恰好没有的词会得到「没有这个实体」——
 * 而它可能好端端地在图里，只是不在这两跳之内。
 */
async function queryEntities(keyword: string) {
  if (!keyword) {
    entityOptions.value = []
    return
  }
  try {
    const found: GraphNode[] = await searchEntities(keyword, 12)
    entityOptions.value = found.map((n) => ({
      value: n.name,
      label: `${n.label}（${kindLabel(n.kind)}）`,
    }))
  } catch {
    // 搜索失败回空表：下拉显示「无匹配」，比留着一批过期候选误导用户要好
    entityOptions.value = []
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
            每个商品沿「成分 → 营养素 → 药物」连出去。<strong>每条线都能点回原文</strong>。
          </p>
        </div>
        <div class="graph-head__aside">
          <p v-if="stats?.available" class="graph-scale">
            <span class="graph-scale__num">{{ stats.entities }}</span> 个实体
            <span class="graph-scale__sep">·</span>
            <span class="graph-scale__num">{{ stats.relations }}</span> 条关系
          </p>
          <!-- 这张图是给「看清知识层长什么样」用的；真要结论，用户应该去问助手。
               图谱本身是助手的检索底座，不是让人在图上自己找关系的工具 -->
          <RouterLink class="graph-head__ask" to="/assistant">
            想问「这样能不能一起吃」，去问 AI 助手
          </RouterLink>
        </div>
      </div>

      <div class="graph-tools">
        <label class="graph-tools__field">
          <span>中心实体</span>
          <el-select
            :key="selectKey"
            :model-value="root"
            filterable
            remote
            reserve-keyword
            placeholder="输入商品编号或成分名"
            :remote-method="queryEntities"
            style="width: 220px"
            @update:model-value="focusOn"
          >
            <el-option
              v-for="s in entityOptions"
              :key="s.value"
              :value="s.value"
              :label="s.label"
            />
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

        <div v-else class="graph-viewport">
          <GraphCanvas
            ref="canvasRef"
            v-model:zoom-percent="zoomPercent"
            v-model:can-zoom-in="canZoomIn"
            v-model:can-zoom-out="canZoomOut"
            :layout="layout"
            :selected-key="selectedKey"
            :selected-node="root"
            @select-edge="(e) => (selectedKey = edgeKey(e))"
            @select-node="focusOn"
          />

          <!--
            缩放控件固定在画布右下角。放这里而不是顶部工具栏：它作用于**画布**，
            和「换中心实体/换跳数」不是一类操作；贴在画布上，鼠标不用离开图再回来。
            按钮是给「没有滚轮 / 只用键盘」的场景兜底，滚轮与拖拽同样可用
          -->
          <div class="graph-zoom" role="group" aria-label="图谱缩放">
            <button
              type="button"
              class="graph-zoom__btn"
              aria-label="放大"
              title="放大"
              :disabled="!canZoomIn"
              @click="canvasRef?.zoomIn()"
            >
              +
            </button>
            <button
              type="button"
              class="graph-zoom__btn"
              aria-label="缩小"
              title="缩小"
              :disabled="!canZoomOut"
              @click="canvasRef?.zoomOut()"
            >
              −
            </button>

            <!--
              百分比与档位必须一起给。百分比是「我现在离原图多远」的读数，
              档位是「直接到某个距离」的入口 —— 只有加减按钮时，用户想知道
              自己是不是已经放到最大，只能一直点下去试
            -->
            <span class="graph-zoom__percent" aria-live="polite">{{ zoomPercent }}</span>
            <button
              v-for="preset in ZOOM_PRESETS"
              :key="preset.value"
              type="button"
              class="graph-zoom__btn graph-zoom__btn--preset"
              :class="{ 'is-current': zoomLevel === preset.value }"
              :aria-label="`缩放到 ${preset.value}%`"
              :aria-pressed="zoomLevel === preset.value"
              :title="`缩放到 ${preset.value}%`"
              @click="canvasRef?.zoomTo(preset.value)"
            >
              {{ preset.label }}
            </button>

            <button
              type="button"
              class="graph-zoom__btn graph-zoom__btn--reset"
              aria-label="复位视图"
              title="复位视图"
              @click="canvasRef?.reset()"
            >
              复位
            </button>
            <span class="graph-zoom__scale" aria-hidden="true">{{ scaleHint }}</span>
          </div>
        </div>

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

      <aside class="graph-rail surface" aria-label="关系与出处">
        <div class="graph-rail__head">
          <h2>关系与出处</h2>
          <p>
            <template v-if="rootLabel">以「{{ rootLabel }}」为中心，</template>
            {{ evidenceSections.totalRelations }} 条关系
            <template v-if="hidden.length">（另有 {{ hidden.length }} 类被筛掉）</template>
          </p>
          <p class="graph-rail__hint">点开任意一条，能读到它的原文依据。</p>
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
          两段结构：先给中心实体自己的关系，再给其余节点。用户是带着「这个中心有什么」
          进来的，背景节点不该和主角混在一起平铺 —— 这也是之前「为什么是这几样」的来源。
          第一段按关系种类分组，第二段按实体分组、组内再按关系种类。
          这张清单同时兼三件事：点一条看它的依据、键盘可达（SVG 的 <g> 拿不到焦点）、
          图上太密看不过来时的兜底，所以它列的是全部边，不只是选中的那条。
        -->
        <div v-else class="evidence">
          <section v-if="evidenceSections.core.length" class="evidence__section">
            <h3 class="evidence__section-title">
              「{{ rootLabel }}」自己的关系
              <span class="evidence__section-count">
                {{ evidenceSections.relations }} 条
                <template v-if="evidenceSections.sources > evidenceSections.relations">
                  · {{ evidenceSections.sources }} 处依据
                </template>
              </span>
            </h3>
            <template v-for="group in evidenceSections.core" :key="group.relation">
              <p class="evidence__group">
                {{ relationLabel(group.relation) }}
                <span class="evidence__group-count">{{ group.relations.length }}</span>
              </p>
              <ul class="evidence__list">
                <template v-for="rel in group.relations" :key="rel.key">
                  <li class="evidence__row">
                    <button
                      type="button"
                      class="evidence__head"
                      :class="{ 'is-active': rel.key === selectedKey }"
                      :aria-expanded="rel.key === selectedKey"
                      @click="selectedKey = rel.key === selectedKey ? '' : rel.key"
                    >
                      <span class="evidence__rel" :class="`evidence__rel--${rel.relation}`">
                        {{ relationLabel(rel.relation) }}
                      </span>
                      <span class="evidence__pair">
                        <span class="evidence__subject">{{ rel.head.label }}</span>
                        <span class="evidence__verb" aria-hidden="true">→</span>
                        <span class="evidence__object">{{ rel.tail.label }}</span>
                        <!--
                          出处数量写在行上：同一件事被几篇文档说过，用户在这里一眼看到「3 处」，
                          而不是把同一行读三遍才反应过来那是重复
                        -->
                        <span v-if="rel.sources.length > 1" class="evidence__kinds">
                          {{ rel.sources.length }} 处依据
                        </span>
                      </span>
                      <span class="evidence__chevron" aria-hidden="true">
                        {{ rel.key === selectedKey ? '收起' : '看依据' }}
                      </span>
                    </button>

                    <div v-if="rel.key === selectedKey" class="evidence__detail">
                                        <!--
                        每条出处单独一块：效果 / 逐字引文 / 文档链接。多篇文档对同一件事的表述会有差异，
                        合成一段会丢掉差异，而差异恰恰是「信哪一篇」的依据
                      -->
                      <ul class="evidence__sources">
                        <li v-for="src in rel.sources" :key="edgeKey(src)" class="evidence__source-item">
                          <p v-if="src.effect" class="evidence__effect">{{ src.effect }}</p>
                          <blockquote class="evidence__quote">{{ src.quote }}</blockquote>
                          <p class="evidence__source">
                            <RouterLink :to="{ path: `/knowledge/${src.docId}`, query: { chunk: src.chunkId } }">
                              《{{ src.docTitle || src.docId }}》· 查看原文
                            </RouterLink>
                          </p>
                          <p v-if="src.chain.length" class="evidence__chain">
                            关联路径：{{ src.chain.join(' → ') }}
                          </p>
                        </li>
                      </ul>
                      <div class="evidence__actions">
                        <button type="button" class="evidence__focus" @click="focusOn(rel.tail.name)">
                          以「{{ rel.tail.label }}」为中心
                        </button>
                      </div>
                    </div>
                  </li>
                </template>
              </ul>
            </template>
          </section>

          <section v-if="evidenceSections.outer.length" class="evidence__section">
            <h3 class="evidence__section-title">
              与「{{ rootLabel }}」相关的其他实体
              <span class="evidence__section-count">
                {{ evidenceSections.outerRelations }} 条关系
                · {{ evidenceSections.outerSources }} 处依据
              </span>
            </h3>
            <p class="evidence__section-note">
              这些是中心实体之外的连线，用来说明上下文。
            </p>
            <template v-for="entity in evidenceSections.outer" :key="entity.name">
              <p class="evidence__group">
                {{ entity.label }}
                <span class="evidence__group-count">{{ entity.relations }}</span>
              </p>
              <template v-for="group in entity.groups" :key="group.relation">
                <p class="evidence__subgroup">{{ relationLabel(group.relation) }}</p>
                <ul class="evidence__list">
                  <template v-for="rel in group.relations" :key="rel.key">
                    <li class="evidence__row">
                      <button
                        type="button"
                        class="evidence__head"
                        :class="{ 'is-active': rel.key === selectedKey }"
                        :aria-expanded="rel.key === selectedKey"
                        @click="selectedKey = rel.key === selectedKey ? '' : rel.key"
                      >
                        <span class="evidence__rel" :class="`evidence__rel--${rel.relation}`">
                          {{ relationLabel(rel.relation) }}
                        </span>
                        <span class="evidence__pair">
                          <span class="evidence__subject">{{ rel.head.label }}</span>
                          <span class="evidence__verb" aria-hidden="true">→</span>
                          <span class="evidence__object">{{ rel.tail.label }}</span>
                          <!--
                            出处数量写在行上：同一件事被几篇文档说过，用户在这里一眼看到「3 处」，
                            而不是把同一行读三遍才反应过来那是重复
                          -->
                          <span v-if="rel.sources.length > 1" class="evidence__kinds">
                            {{ rel.sources.length }} 处依据
                          </span>
                        </span>
                        <span class="evidence__chevron" aria-hidden="true">
                          {{ rel.key === selectedKey ? '收起' : '看依据' }}
                        </span>
                      </button>

                      <div v-if="rel.key === selectedKey" class="evidence__detail">
                        <!--
                          每条出处单独一块：效果 / 逐字引文 / 文档链接。多篇文档对同一件事的表述会有差异，
                          合成一段会丢掉差异，而差异恰恰是「信哪一篇」的依据
                        -->
                        <ul class="evidence__sources">
                          <li v-for="src in rel.sources" :key="edgeKey(src)" class="evidence__source-item">
                            <p v-if="src.effect" class="evidence__effect">{{ src.effect }}</p>
                            <blockquote class="evidence__quote">{{ src.quote }}</blockquote>
                            <p class="evidence__source">
                              <RouterLink :to="{ path: `/knowledge/${src.docId}`, query: { chunk: src.chunkId } }">
                                《{{ src.docTitle || src.docId }}》· 查看原文
                              </RouterLink>
                            </p>
                            <p v-if="src.chain.length" class="evidence__chain">
                              关联路径：{{ src.chain.join(' → ') }}
                            </p>
                          </li>
                        </ul>
                        <div class="evidence__actions">
                          <button type="button" class="evidence__focus" @click="focusOn(rel.tail.name)">
                            以「{{ rel.tail.label }}」为中心
                          </button>
                        </div>
                      </div>
                    </li>
                  </template>
                </ul>
              </template>
            </template>
          </section>
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
  color: var(--color-primary-strong);
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

/* 右侧一列：规模数字 + 去问助手的入口。两者都是「离开这张图」的动作，放一起 */
.graph-head__aside {
  display: grid;
  justify-items: end;
  gap: var(--ys-space-1);
  flex: none;
}

.graph-scale {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  font-variant-numeric: tabular-nums;
}

/* 图谱是助手的检索底座：要结论就顺着这里过去，不要在图上自己找 */
.graph-head__ask {
  color: var(--color-primary-strong);
  font-size: var(--ys-font-xs);
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
  background: repeating-linear-gradient(90deg, var(--color-danger) 0 5px, transparent 5px 8px);
}

.legend__item--CAUTION_FOR .legend__swatch {
  background: repeating-linear-gradient(90deg, var(--color-warning) 0 3px, transparent 3px 6px);
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

/* 缩放控件的定位基准：贴画布右下角，不随画布内部平移走动 */
.graph-viewport {
  position: relative;
}

.graph-zoom {
  position: absolute;
  right: var(--ys-space-3);
  bottom: var(--ys-space-3);
  z-index: var(--ys-z-raised);
  display: flex;
  /* 居中而不是默认的 baseline：这一排里 +/- 是 16px、档位与读数是 12px，
     baseline 对齐会把小字整体抬高一段，肉眼就是「几个字不在一条水平线上」。
     所有子项高度都已经拉成 30px，居中对齐即可让字面都落在同一条中线 */
  align-items: center;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-surface);
  box-shadow: var(--ys-shadow-raised);
}

/* 可点击区下限 24×24（WCAG 2.2 AA）。密集工具条取到 30px */
.graph-zoom__btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 30px;
  height: 30px;
  padding: 0 var(--ys-space-1);
  border: none;
  border-radius: var(--ys-radius-full);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-md);
  line-height: 1;
  cursor: pointer;
}

.graph-zoom__btn:hover:not(:disabled) {
  background: var(--color-primary-subtle);
  color: var(--color-primary-strong);
}

/* 到顶/到底：按钮留着占位，但明确地不可点，而不是点了没反应 */
.graph-zoom__btn:disabled {
  color: var(--color-text-muted);
  cursor: not-allowed;
  opacity: 0.5;
}

.graph-zoom__btn:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.graph-zoom__btn--reset {
  width: auto;
  padding: 0 var(--ys-space-3);
  font-size: var(--ys-font-xs);
}

/* 缩放到某个档位。与 +/- 同一排、同一形状，但用主色底强调「可以直接跳过去」 */
.graph-zoom__btn--preset {
  width: auto;
  padding: 0 var(--ys-space-2);
  font-size: var(--ys-font-xs);
}

.graph-zoom__btn--preset.is-current {
  background: var(--color-primary-subtle);
  color: var(--color-primary-strong);
  font-weight: 600;
}

/* 读数。等宽数字：缩放时它会一位一位跳，比例字宽会让整排按钮跟着抖 */
.graph-zoom__percent {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 30px;
  min-width: 4.5ch;
  padding-inline: var(--ys-space-1);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.graph-zoom__scale {
  display: inline-flex;
  align-items: center;
  height: 30px;
  padding-inline: var(--ys-space-1) var(--ys-space-2);
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
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
  color: var(--color-primary-strong);
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
  color: var(--color-primary-strong);
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
/* 「点开能看原文」是用法说明，要弱于副标题 */
.graph-rail__hint {
  margin-top: 2px;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.graph-rail__empty {
  margin-top: var(--ys-space-1);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.evidence {
  display: grid;
  gap: var(--ys-space-3);
}

/* 两段之间留白 + 细分隔线：它们是两种读法（主角 / 背景），不该看起来像同一张表 */
.evidence__section {
  display: grid;
  gap: var(--ys-space-2);
}

.evidence__section + .evidence__section {
  padding-top: var(--ys-space-3);
  border-top: 1px dashed var(--color-border);
}

.evidence__section-title {
  display: flex;
  align-items: baseline;
  gap: var(--ys-space-2);
  margin: 0;
  color: var(--color-text-primary);
  font-size: var(--ys-font-sm);
  font-weight: 600;
}

.evidence__section-count {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  font-weight: 400;
}

.evidence__section-note {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
}

/* 关系种类的组头：风险类在 RELATION_ORDER 里排在前面，所以「有冲突」的组天然在最上面 */
.evidence__group {
  display: flex;
  align-items: baseline;
  gap: var(--ys-space-1);
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
  font-weight: 600;
}

.evidence__group-count {
  color: var(--color-text-muted);
  font-weight: 400;
}

/* 第二段里每个实体下面的关系种类，比实体名再低一级 */
.evidence__subgroup {
  margin: var(--ys-space-1) 0 0;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.evidence__list {
  display: grid;
  gap: var(--ys-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

/* 三栏：关系名 / 一句话 / 展开指示。第三栏让「可以点开」写在脸上 */
.evidence__head {
  display: grid;
  grid-template-columns: 4.6em minmax(0, 1fr) auto;
  align-items: start;
  gap: var(--ys-space-2);
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
  color: var(--color-success-strong);
}

.evidence__rel--INTERACTS_WITH {
  color: var(--color-danger-strong);
}

.evidence__rel--CAUTION_FOR {
  color: var(--color-warning-strong);
}

/* 一句话要有主宾之分：两端同色时读不出「谁对谁」 */
.evidence__pair {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px;
  min-width: 0;
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
}

.evidence__subject {
  color: var(--color-text-primary);
  font-weight: 600;
}

.evidence__verb {
  color: var(--color-text-muted);
}

.evidence__object {
  color: var(--color-text-secondary);
}

.evidence__chevron {
  color: var(--color-primary-strong);
  font-size: var(--ys-font-xs);
  white-space: nowrap;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.evidence__head.is-active .evidence__chevron {
  color: var(--color-text-secondary);
}

/* 出处数量徽标：同一件事有几篇文档支撑，写在主语那一行 */
.evidence__kinds {
  margin-inline-start: var(--ys-space-1);
  padding: 0 var(--ys-space-1);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

/* 每条出处独立一块：文档之间可能有表述差异，合起来会丢掉差异 */
.evidence__sources {
  display: grid;
  gap: var(--ys-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.evidence__source-item + .evidence__source-item {
  padding-top: var(--ys-space-3);
  border-top: 1px dashed var(--color-border);
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
  color: var(--color-primary-strong);
}

.evidence__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
}

.evidence__focus {
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
  color: var(--color-primary-strong);
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
