<script setup lang="ts">
/**
 * 图谱画布。纯 SVG，不引任何图库。
 *
 * <b>为什么是同心环而不是力导向。</b>力导向布局每次跑出来的形状都不一样，
 * 而且半径不携带任何含义 —— 它好看，但说不出「这个药物离你买的商品有多远」。
 * 同心环把跳数直接画成半径：中心是当前实体，外面每一圈多一跳。
 * 位置本身在回答一个力导向答不了的问题，代价是它要求布局是确定的，
 * 所以计算全在 {@link ringLayout} 这个纯函数里，这里只负责画。
 *
 * <b>遮蔽靠绘制顺序，不靠几何。</b>连线从节点中心画到节点中心，节点后画，
 * 自身的填充把线头挡住。求「线段与圆角矩形交点」能省掉这一层，
 * 但那段几何是纯负担：它不影响任何可见结果，却多一处会算错的地方。
 *
 * <b>键盘可达性不在这里。</b>{@code <g>} 本来就拿不到焦点，硬加 tabindex
 * 又会造出「焦点在图上但用户不知道该按什么」的困境。选择依据这件事交给
 * 右侧的依据清单（真正的按钮），这里只做鼠标增强。
 */
import type { GraphEdge } from '@/types/models'
import type { GraphLayout } from '@/utils/graph'
import { kindLabel, relationLabel } from '@/utils/graph'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{
  layout: GraphLayout
  /** 当前选中的边。命中时加粗并高亮，与右侧依据栏联动 */
  selectedKey: string
  /** 当前的中心实体，画成实心药丸 */
  selectedNode: string
  /** 缩放读数（如 "120%"）。100% 表示整图铺满 */
  zoomPercent?: string
  /** 还能不能继续放大 / 缩小。到顶到底时按钮置灰，别让用户对着没反应的按钮一直点 */
  canZoomIn?: boolean
  canZoomOut?: boolean
}>()

const emit = defineEmits<{
  selectEdge: [edge: GraphEdge]
  selectNode: [name: string]
  /** 缩放读数。工具栏要显示「现在离原图多远」，由画布自己算才不用两处维护同一份公式 */
  'update:zoomPercent': [value: string]
  'update:canZoomIn': [value: boolean]
  'update:canZoomOut': [value: boolean]
}>()

/** 缩放/平移要在屏幕坐标与 viewBox 坐标之间换算，需要一个真实的 DOM 锚点 */
const canvasEl = ref<SVGSVGElement | null>(null)

/**
 * 世界坐标 = 布局坐标。viewBox 就是布局算出来的尺寸，内容在这个坐标系里
 * 铺满、不变形；缩放与平移全在外层「镜头」的 transform 上做。
 */

/** 每个节点在第几跳。边的入场延迟按它算，逐条去 find 是白扫的 */
const depthOf = computed(() => {
  const map = new Map<string, number>()
  for (const item of props.layout.nodes) map.set(item.node.name, item.depth)
  return map
})

/** 药丸的半高。圆角取它才能是标准胶囊形 */
const RADIUS_Y = computed(() => (props.layout.nodes[0]?.height ?? 30) / 2)

/* ==================== 视图缩放与平移 ====================
 *
 * <b>要解决的是一个已经发生的问题。</b>布局尺寸随节点数增长（每多一跳多一圈，
 * RING_GAP/PADDING 都是绝对坐标），而 SVG 会把整张 viewBox 等比塞进容器 ——
 * 节点越多、屏幕上越小。实测两跳 58 个节点时画布 2558x2400，一个药丸只剩
 * 9 像素高，字和点击区一起没了。
 *
 * <b>为什么镜头挂在屏幕像素上，而不是 viewBox 坐标上。</b>两种平移语义都能自圆
 * 其说（「拖纸」是内容跟手，「拖镜头」是反方向），但能自圆其说的实现未必正确：
 * 位移若按 viewBox 单位换算，屏幕到 viewBox 之间还隔着一次等比缩放，位移量与
 * 手感差一个系数 —— 同一个手势在小图上飞得很远、在大图上几乎不动。所以位移直接
 * 取屏幕像素：拖过的距离必须等于画面走过的距离，这一条与 viewBox 尺寸无关。
 * 缩放上限也因此表达为「1 个布局单位最多占几个屏幕像素」。
 *
 * <b>默认「整图铺满」。</b>容器再宽也保证整张图落在里头：布局尺寸本来就是按
 * 内容包围盒算的，没有理由只显示一部分。看局部靠放大，不靠默认裁掉一半。
 *
 * <b>裁剪落在外层容器上。</b>放大后内容必然溢出画布，而 SVG 自身的 overflow
 * 在浏览器里不可靠（实测节点会画到页头上去，那里的点击也就落到别的元素上），
 * 所以 .viewport-box 上必须有 overflow: hidden。
 */

/**
 * 放大的上限，单位是「1 个布局单位占几个屏幕像素」。
 * 布局里药丸高 30 单位，6 倍即 180px —— 再大也不会有更多信息，只是徒增模糊；
 * 下限则由「整图铺满」动态给出（见 baseScale）。
 */
const MAX_ZOOM = 6
/** 一格滚轮 / 一次按钮的缩放倍数。1.2 在「一次就能看出变化」与「不会跳过去」之间 */
const ZOOM_STEP = 1.2
/** 取景留白。整图铺满时给内容四周留一圈呼吸，别贴着边框 */
const FIT_PADDING = 12
/** 药丸在屏幕上至少要占多少像素高。可读倍率由它反推，见 defaultZoom */
const MIN_READABLE_NODE_PX = 10
/**
 * 默认取景要给到的可读性：药丸在屏幕上至少 10px 高。
 *
 * <p>三跳图实测布局有 2710x2606 单位（每圈半径 = 上一圈 + 104，还要按周长
 * 摊开这一圈的节点），若默认「整图铺满」，0.27 的比例会让 30 单位的药丸只剩
 * 8px —— 字和点击区一起没了，正是这次要修的问题。
 *
 * <p>所以默认不铺满：10px 对应一屏（802px 宽）能看到约 ±1180 布局单位，刚好把
 * 三跳图的两跳连同大半圈三跳收进视野，中心实体与它的一跳邻居是清楚的。
 * 想更近用滚轮或档位放大，想看全貌按「全图」——那是有意的动作，不该是默认状态。
 *
 * <p>它只由节点高度与目标像素决定，不再写成一个裸的 scale：裸 scale 会随
 * baseScale 的含义变化而失效，正是「改了这里、坏在那里」的来源。
 */

/**
 * viewBox 的宽高。取容器本身的尺寸，**不再强制正方形**。
 *
 * <p>曾经用正方形 viewBox（边长取长边）再过 preserveAspectRatio 的 meet 居中，
 * 代价是短边方向两侧各留一条 letterbox 空带：802x720 的容器上有 41px 宽的空白，
 * 内容是画不进去的（SVG 不绘制 viewBox 以外），看着就像「画布没铺满」。
 * 为了让布局、镜头、裁剪三处的换算不被这条空带污染，代码里得处处带着
 * letterboxScale / letterboxX / letterboxY 三个补偿量 —— 补偿量本身没错，
 * 但它们让「一个用户单位到底是多少像素」不再是个常量，是这类 bug 的温床。
 *
 * <p>改成与容器同比之后，viewBox→屏幕就是恒等映射（1 单位 = 1px），
 * 三个补偿量一起消失，裁剪矩形就是 0,0,宽,高，等于容器本身。
 */
const viewBoxW = computed(() => boxSize.value.width || 1)
const viewBoxH = computed(() => boxSize.value.height || 1)

/**
 * 1 个布局单位在 100% 时占几个屏幕像素（对标基准，不是最终比例）。
 *
 * <p>取两个容器方向里**更宽松**的那一个，且直接按整条边长算。
 * 按短边收紧会让可读倍率比可拖范围算出来的更小一圈，结果是「内容看着铺满了，
 * 却怎么都拖不动」—— 而底下的 maxPan 用的正是同一套尺寸，两处必须共用同一基准。
 *
 * <p>留白靠 FIT_PADDING 给，不需要再用短边把比例压下来。
 */
const baseScale = computed(() => {
  const box = boxSize.value
  if (!box.width || !box.height) return 1
  const side = Math.max(box.width, box.height) - FIT_PADDING * 2
  return Math.max(1, side) / Math.max(worldW.value, worldH.value)
})


/**
 * 自动缩放倍数（相对 baseScale）。
 *
 * <p>1 = 整图铺满；改变它意味着用户开始自己取景。**初始值不是 1**：大图上一来
 * 就铺满等于把节点缩到看不清，所以铺满比例低于可读下限时以可读下限为起点。
 */
const zoom = ref(1)

/**
 * 默认倍率：铺满够清楚就用铺满，不够就抬到可读下限。
 *
 * <p>可读下限直接写成「一个节点高要占多少屏幕像素」，不写成一个裸的 scale ——
 * 后者会随 baseScale 的含义变化而变化，是「改了这里、坏在那里」的典型来源。
 * 除出来的倍率再夹到放大上限，免得在超长图上一步冲到顶。
 */
const minZoom = computed(() => 1)
const defaultZoom = computed(() => {
  // 药丸高度取自布局本身，不写死 30：写死后一旦排版改了高度，可读倍率会静默失准，
  // 表现就是「打开页面节点又小到看不清」，而阈值那边还显示正常。
  // viewBox 与容器同比后一个用户单位就是 1px，可读倍率只由节点高度与 baseScale 决定
  const nodeUnits = props.layout.nodes[0]?.height ?? 30
  const readable = MIN_READABLE_NODE_PX / (nodeUnits * baseScale.value)
  return clampZoom(Math.max(minZoom.value, readable))
})
/** 倍率统一夹在 [100%, 放大上限] 之间。上限随 baseScale 变，所以只能在这里算 */
function clampZoom(value: number): number {
  return Math.min(MAX_ZOOM / baseScale.value, Math.max(minZoom.value, value))
}
// 名字带 internal 前缀：上面同名的 props 是对外读数，这里是对内的真实判据，
// 重名会让 Vue 的编译期校验分不清两者
const zoomPercentInternal = computed(() => `${Math.round(zoom.value * 100)}%`)
const canZoomInInternal = computed(() => zoom.value < MAX_ZOOM / baseScale.value - 1e-6)
const canZoomOutInternal = computed(() => zoom.value > minZoom.value + 1e-6)

/** 缩放后的绝对值（像素/布局单位），以及内容在屏幕上的实际尺寸 */
const autoScale = computed(() => baseScale.value * zoom.value)

/** 裁剪路径 id。同页可能挂多张画布，加随机后缀避免 id 撞车 */
const clipId = 'graph-clip-' + Math.random().toString(36).slice(2, 9)

/** 视口的像素尺寸。铺满比例、平移边界、复位锚点都依赖它，随容器变化更新 */
const boxSize = ref({ width: 0, height: 0 })
let boxObserver: ResizeObserver | null = null

/** 世界（布局）尺寸。布局为空时兜 1，避免除零。铺满比例按它算 */
const worldW = computed(() => Math.max(1, props.layout.width || 1))
const worldH = computed(() => Math.max(1, props.layout.height || 1))

/**
 * 内容在屏幕上的直径。
 *
 * <p>用**外接圆**而不是节点包围盒，是因为这张图的内容分布不是矩形的：节点铺在
 * 一圈圈同心环上，最外圈与圆心之间是一大片空心区。按包围盒划平移边界时，
 * 镜头可以停进那片洞里 —— 用户往一个方向拖几下，看到的是整屏空白，
 * 而图其实还在，只是视野掉进了环内的洞（实测三跳 150 条边能拖出九成白屏）。
 *
 * <p>外接圆直径是「内容真实占据的圆」：镜头边界因此退化成一条圆切线，
 * 最多把镜头推到最外圈的节点上，再往里就只剩另一侧的节点，洞永远进不来。
 * 它比节点包围盒大一圈是刻意的 —— 宽出来的正是那个洞。
 */


/** 内容外接圆在屏幕上的半径。平移边界（视口中心不许跑出这个圆）按它算 */
const contentRadiusScreen = computed(() => props.layout.contentRadius * autoScale.value)

/**
 * 镜头平移量，单位是**屏幕像素**，以「内容居中」为原点。
 *
 * <p>**正负号按用户直觉定：往右下拖 = 内容往右下走。**早期版本反着来，
 * 拖拽时屏幕上的内容逆着手走 —— 这是用户第一时间报上来的问题。
 *
 * <p>语义上它同时是「内容相对视口中心的偏移」：画布变换写成
 * 「内容坐标 = 锚点 + autoScale × 布局坐标」，而锚点 = 视口中心 + pan，
 * 所以 pan 加多少，内容就往那个方向走多少，手势增量可以直接加进来。
 */
const pan = ref({ x: 0, y: 0 })

/**
 * 镜头最多能推多远：**视口中心不得离开内容外接圆**。
 *
 * <p>为什么是「内容圆半径」而不是一个固定的屏占比。这张图的内容是同心环带，
 * 外接圆是内容真实占据的范围；视口中心一旦跑到圆外，屏幕上就只剩圆外的空白。
 * 所以「中心留在圆内」这条约束恰好等价于「视野里始终有内容」，而且它天然随
 * 缩放与内容尺寸变化 —— 放大后能推得更远，正好够把最外圈的节点拖到屏幕边缘。
 *
 * <p>曾经写的是「位移不超过视口短边的一半」（实测 720 高的容器上只有 360px）。
 * 那个数字是为了压掉「拖进环内空洞看到白屏」的问题而拍的，代价是把一个正常手势
 * 也一并禁掉了：3 倍放大时最右侧节点的中心离画布中心 420px，360px 的上限让它
 * 永远进不到容器右边缘 —— 用户报的「放大后往边缘拖，拖不动、最外圈的节点看不到」
 * 就是这里。
 */
const maxPan = computed(() => contentRadiusScreen.value)

/**
 * 把待落盘的位移夹进边界。
 *
 * <p>纯函数、只有一个出口：`pan.value = clampPan(...)` 不会有第二处赋值。
 * 中间版本在这里顺手给 ref 赋过值，调用方又赋一遍，两处时机不同的赋值会在
 * 边界中途收窄时互相覆盖，画面就停在界外。
 */
function clampPan(next: { x: number; y: number }) {
  const limit = maxPan.value
  if (!limit) return { x: 0, y: 0 }
  const distance = Math.hypot(next.x, next.y)
  if (distance <= limit) return { x: next.x, y: next.y }
  const ratio = limit / distance
  return { x: next.x * ratio, y: next.y * ratio }
}

/**
 * 内容是否还能挪动。整图铺满且离所有节点都近时没有平移余地，画布也不该显示抓手。
 * 判据与 clampPan 同源：只要存在节点落在视野中心之外，就还有可拖的余地。
 */
const pannable = computed(() => maxPan.value > 1)

/**
 * 镜头锚点：**布局原点（0,0，就是中心实体）映到屏幕上的位置**。
 *
 * <p>以布局原点而不是内容包围盒中心做锚点：同心环图的圆心就是当前中心实体，
 * 按包围盒居中会把它挤到一边 —— 而用户第一眼要看的就是它。
 *
 * <p>它同时是镜头换算的原点：用户坐标 = `锚点 + autoScale × 布局坐标`。
 * 锚点落在**可视区中心**时 pan 为 0 就是「内容居中」；平移量直接加在锚点上，
 * 屏幕位移与手势位移因此是 1:1。
 *
 * <p>viewBox 与容器同比之后，「可视区中心」就是「viewBox 中心」，锚点直接取容器中心，
 * 不需要再减 letterbox 那半个偏移 —— 那一项是在正方形 viewBox 下才存在的补偿。
 */
const originOnScreen = computed(() => ({
  x: boxSize.value.width / 2 + pan.value.x,
  y: boxSize.value.height / 2 + pan.value.y,
}))

/**
 * 把镜头挪回默认视角：整图铺满 + 居中。
 * <p>
 * 刻意**不重置 zoom** —— 换实体、换跳数之后用户多半还想用同一个倍率看，
 * 每次跳回 100% 等于每换一次中心就要重新放大一次。
 */
function centerView() {
  pan.value = clampPan({ x: 0, y: 0 })
}

/** 缩放控件上的「复位」回到默认取景：可读倍率 + 居中。与换图时的 centerView 分开 */
function resetView() {
  zoom.value = defaultZoom.value
  centerView()
}

/**
 * 让镜头绕着某个屏幕点缩放：保持该点在屏幕上的位置不动，只有它周围被推开或收拢。
 * <p>
 * 不做这一步的话，放大时目标会滑出视野，用户得一边放大一边追着它平移。
 * 与平移共用同一套屏幕坐标，所以这段换算里不需要动 viewBox。
 */
function zoomAround(clientX: number, clientY: number, factor: number) {
  const el = canvasEl.value
  if (!el) return
  const box = boxSize.value
  if (!box.width || !box.height) return
  const rect = el.getBoundingClientRect()
  const next = Math.min(MAX_ZOOM / baseScale.value, Math.max(minZoom.value, zoom.value * factor))
  if (Math.abs(next - zoom.value) < 1e-9) return
  // 锚点相对画布**内容中心**的偏移。内容中心在屏上的位置 = 视口中心 + pan，
  // 要让这个点在缩放前后不动，就要把「它到内容中心的新旧距离之差」补进 pan。
  // 是减号：pan 表示内容相对视口的位移，内容往右走 pan 变大，而锚点要相对内容
  // 往右退，两者方向相反
  const fromCenter = {
    x: clientX - rect.left - rect.width / 2,
    y: clientY - rect.top - rect.height / 2,
  }
  const ratio = 1 - next / zoom.value
  zoom.value = next
  pan.value = clampPan({
    x: pan.value.x + fromCenter.x * ratio,
    y: pan.value.y + fromCenter.y * ratio,
  })
}

/** 滚轮缩放。按住 ctrl 时给更细的档，方便对准某条边微调 */
function onWheel(event: WheelEvent) {
  event.preventDefault()
  const step = event.ctrlKey ? 1.08 : ZOOM_STEP
  zoomAround(event.clientX, event.clientY, event.deltaY < 0 ? step : 1 / step)
}

/** 按钮缩放：以画布中心为锚点 —— 命中区是整块画布时没有具体的「鼠标在哪」 */
function zoomByStep(direction: 1 | -1) {
  const el = canvasEl.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  zoomAround(
    rect.left + rect.width / 2,
    rect.top + rect.height / 2,
    direction === 1 ? ZOOM_STEP : 1 / ZOOM_STEP,
  )
}

/**
 * 拖拽平移：按住往右，内容跟着往右。
 *
 * <p>按下时**不立刻置 dragging、也不立刻捕获指针**：很多次按下只是要点一个节点，
 * 而指针捕获会把后续 click 派发给捕获元素而不是节点，点击就被吞掉。所以手势先
 * 落进 pendingDrag，越过阈值才升级成拖动。
 *
 * <p>越过阈值后**必须马上建立指针捕获并一直持有**。拖动的最常见形态恰恰是
 * 「从画布里拖到画布外再松手」，没有捕获时画布只会在光标离开时收到 pointerleave，
 * 后面的 pointermove 与 pointerup 一个都收不到：画面停在中途、松手也不结束，
 * 而 dragging 还挂着 —— 鼠标再掠过别处画面会自己动一下。捕获把这一整段手势
 * 收进同一个事件流，拖出界外也就成了正常路径。
 */
const dragging = ref(false)
let dragStart = { x: 0, y: 0, panX: 0, panY: 0 }
/** 按下但还没越过阈值的那次手势。越过了才升级成拖动 */
let pendingDrag: { x: number; y: number; panX: number; panY: number; pointerId: number } | null =
  null
/** 是否已建立指针捕获。它决定收尾时要不要显式释放 */
let captured = false

/**
 * 拖动 = 拖着内容走：屏幕上移动多少，内容就走多少。
 * <p>
 * 位移**直接用屏幕像素**做加法，不经过 viewBox 换算 —— 后者会让同一个手势
 * 在不同缩放级别下差一个系数，手感就散了。
 */
function onPointerMove(event: PointerEvent) {
  if (!dragging.value) {
    if (!pendingDrag) return
    const moved = Math.hypot(event.clientX - pendingDrag.x, event.clientY - pendingDrag.y)
    if (moved < DRAG_THRESHOLD) return
    dragging.value = true
    dragStart = {
      x: pendingDrag.x,
      y: pendingDrag.y,
      panX: pendingDrag.panX,
      panY: pendingDrag.panY,
    }
    try {
      canvasEl.value?.setPointerCapture(pendingDrag.pointerId)
      captured = true
    } catch {
      // 捕获建立失败（如指针已经消失）时仍让拖动可用，只是失去「移出画布继续拖」
    }
  }
  pan.value = clampPan({
    x: dragStart.panX + (event.clientX - dragStart.x),
    y: dragStart.panY + (event.clientY - dragStart.y),
  })
}

/** 收尾统一走这里：松手、取消、以及捕获丢失后的兜底都复用它 */
function endDrag(event: PointerEvent | null) {
  pendingDrag = null
  if (!dragging.value) return
  dragging.value = false
  if (captured && event) {
    try {
      canvasEl.value?.releasePointerCapture(event.pointerId)
    } catch {
      // 指针已经不在捕获里（浏览器取消了这次交互），忽略即可
    }
  }
  captured = false
}

/**
 * 指针捕获丢失时（浏览器判定交互不可靠，或元素被移除）收到这条事件。
 * 拖动本身不该就此消失：`captured` 归零后由挂在 window 上的 pointerup 兜底收尾，
 * 用户那次「拖出去再松手」仍然被完整地读成一次平移。
 */
function onLostPointerCapture() {
  captured = false
}

/**
 * 捕获丢失期间的抬起事件只会到 window 上。挂一次全局监听，避免画布
 * 停在「按着不放」的状态里 —— 那种状态下鼠标一动，画面就会跟着乱走。
 */
function onWindowPointerUp() {
  endDrag(null)
}

/** 拖动阈值（px）。没有它，一次手抖就会被判成拖动，点击被吞掉 */
const DRAG_THRESHOLD = 4

function onPointerDown(event: PointerEvent) {
  if (!pannable.value || event.button !== 0) return
  pendingDrag = {
    x: event.clientX,
    y: event.clientY,
    panX: pan.value.x,
    panY: pan.value.y,
    pointerId: event.pointerId,
  }
}

// 读数每次变化都告诉外面。用 watch 而不是在 zoomAround 里直接 emit：
// 复位、换图、尺寸变化都会改 zoom，逐条去 emit 迟早漏一处
watch(zoomPercentInternal, (value) => emit('update:zoomPercent', value), { immediate: true })
watch(canZoomInInternal, (value) => emit('update:canZoomIn', value), { immediate: true })
watch(canZoomOutInternal, (value) => emit('update:canZoomOut', value), { immediate: true })

/** 跳到指定百分比。档位按钮用，锚点同样是画布中心 */
function zoomTo(percent: number) {
  const el = canvasEl.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  zoomAround(rect.left + rect.width / 2, rect.top + rect.height / 2, percent / 100 / zoom.value)
}

defineExpose({
  zoomIn: () => zoomByStep(1),
  zoomOut: () => zoomByStep(-1),
  zoomTo,
  reset: resetView,
})

/**
 * 滚轮监听必须手工挂，不能用 `@wheel`。
 *
 * svg 上的 `@wheel` 会被 Vue 注册成**被动**监听器（Chrome 对 document/window/body
 * 上的 wheel 默认这样，SVG 元素也会走到同一条注册路径），被动监听器里
 * `preventDefault()` 只会打出一句 console 警告然后被忽略 —— 页面照旧随滚轮滚动，
 * 而这句警告会让「全程无控制台报错」这条验收断言变成红灯。
 * `{ passive: false }` 是原生 API 才有的开关，模板修饰器给不了，所以这里自己绑。
 */
onMounted(() => {
  canvasEl.value?.addEventListener('wheel', onWheel, { passive: false })
  // 捕获丢失期间的松手事件只到 window，兜底收尾挂在它上面
  window.addEventListener('pointerup', onWindowPointerUp)
  window.addEventListener('pointercancel', onWindowPointerUp)
  const measure = () => {
    const el = canvasEl.value?.parentElement
    if (!el) return
    const rect = el.getBoundingClientRect()
    // 第一次量到尺寸时 baseScale 才有效，此时把倍率落到默认取景。
    // 初始化时 box 是 0x0，baseScale 兜的是 1，那时算出来的默认倍率没有意义
    const first = boxSize.value.width === 0
    boxSize.value = { width: rect.width, height: rect.height }
    if (first) zoom.value = defaultZoom.value
    // 尺寸变了，铺满比例与平移边界都跟着变，重新夹一次镜头，别让它留在界外
    pan.value = clampPan(pan.value)
  }
  measure()
  // Tauri 桌面窗口可被随意拖拽缩放；视口尺寸一变，可取景的范围就变了
  boxObserver = new ResizeObserver(measure)
  if (canvasEl.value?.parentElement) boxObserver.observe(canvasEl.value.parentElement)
})

onBeforeUnmount(() => {
  boxObserver?.disconnect()
  canvasEl.value?.removeEventListener('wheel', onWheel)
  window.removeEventListener('pointerup', onWindowPointerUp)
  window.removeEventListener('pointercancel', onWindowPointerUp)
})

// 内容换了（换中心实体/换跳数）就回到默认取景：留着上一次的镜头位置，
// 新图可能整个落在视口外，用户看到一片空白却以为加载失败
watch(
  () => [props.layout.width, props.layout.height],
  () => {
    zoom.value = defaultZoom.value
    centerView()
  },
)
</script>

<template>
  <div class="viewport-box">
    <svg
      ref="canvasEl"
      class="canvas"
      :class="{ 'is-dragging': dragging }"
      :viewBox="`0 0 ${viewBoxW} ${viewBoxH}`"
      preserveAspectRatio="xMidYMid meet"
      role="img"
      :aria-label="`知识图谱：${layout.nodes.length} 个实体、${layout.edges.length} 条关系，共 ${Math.max(layout.ringRadii.length - 1, 0)} 跳。可用滚轮缩放、拖拽平移`"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="endDrag"
      @pointercancel="endDrag"
      @lostpointercapture="onLostPointerCapture"
    >
      <!--
      外层这个 transform 就是「镜头」：缩放视图用，不是缩放节点。
      读法是「把布局原点（中心实体）放到屏幕上的 originOnScreen，再按
      autoScale 放大」。布局坐标本身以中心实体为原点，所以不需要第三个
      translate；镜头位移与拖拽位移都是屏幕像素，手感才 1:1。
    -->
      <!--
      显式裁剪路径。放大后内容必然溢出画布，而 SVG 自身的 overflow 在浏览器里
      不可靠（实测节点会画到页头上去，那里的点击也就落到别的元素上），
      只有 clipPath 是确定生效的
    -->
      <defs>
        <!-- 白底。透明时节点会与页头的深色预览条目叠在一起，读不清 -->
        <rect :width="viewBoxW" :height="viewBoxH" fill="var(--color-bg-surface)" />
        <!--
        裁剪矩形就是 viewBox 本身（0,0 到 容器宽,容器高），且**挂在没有 transform 的
        那一层**。两点都是踩出来的：

        1. clipPath 未声明 clipPathUnits 时默认 userSpaceOnUse，矩形坐标系取的是
           「引用它的元素自己的用户空间」。挂在带 transform 的 <g> 上时矩形会跟着镜头
           一起平移缩放 —— 0..720 在屏幕上变成 (360,731)-(1762,2133)，内容只剩右下角、
           左上被切掉一大片，而明显还没到组件边界。所以这一层不能有 transform。
        2. 矩形是 0..尺寸 这一整段，不是以 0 为中心的 ±半宽。中心式写法会让裁剪整体
           平移半个画布，症状是「一侧被切、另一侧大片空白」。

        另一条路是在镜头层里写逆变换把矩形挪回去，但那个式子要同时跟住 originOnScreen
        与 autoScale，漏乘哪一项都让裁剪落到别处，而症状长得一模一样 —— 不值得再赌。
        -->
        <clipPath :id="clipId">
          <rect x="0" y="0" :width="viewBoxW" :height="viewBoxH" />
        </clipPath>
      </defs>
      <g
        class="viewport-clip"
        :clip-path="`url(#${clipId})`"
        :class="{ 'is-dragging': dragging, 'is-pannable': pannable }"
      >
        <g
          class="viewport"
          :transform="`translate(${originOnScreen.x}, ${originOnScreen.y}) scale(${autoScale})`">
          <!--
        同心参考圈。它的作用是把「半径 = 跳数」这条隐含约定画出来 ——
        没有它，用户只会看到一堆点散布在圆里，不知道远近是什么意思。

        圈上**不写字**：曾经在每个圈的正上方标过「1 跳」，实测那个位置
        正好是节点分布的起点，标签被药丸压得看不见。跳数的说法挪到了
        画布下方的静态说明里 —— 一句话讲完的事，不值得为它维护一套避让逻辑
      -->
          <g class="rings">
            <circle
              v-for="(radius, depth) in layout.ringRadii"
              v-show="depth > 0"
              :key="`ring-${depth}`"
              class="rings__circle"
              :r="radius"
            />
          </g>

          <g class="edges">
            <g
              v-for="item in layout.edges"
              :key="item.key"
              class="edge"
              :class="[`edge--${item.edge.relation}`, { 'is-active': item.key === selectedKey }]"
              :style="{ '--delay': `${(depthOf.get(item.edge.head.name) ?? 0) * 70}ms` }"
              @click="emit('selectEdge', item.edge)"
            >
              <line class="edge__hit" :x1="item.x1" :y1="item.y1" :x2="item.x2" :y2="item.y2" />
              <line class="edge__line" :x1="item.x1" :y1="item.y1" :x2="item.x2" :y2="item.y2" />
            </g>
          </g>

          <g class="nodes">
            <g
              v-for="item in layout.nodes"
              :key="item.node.name"
              class="node"
              :class="[
                `node--${item.node.kind}`,
                { 'is-center': item.depth === 0, 'is-active': item.node.name === selectedNode },
              ]"
              :style="{ '--delay': `${item.depth * 70}ms` }"
              :transform="`translate(${item.x}, ${item.y})`"
              @click="emit('selectNode', item.node.name)"
            >
              <rect
                class="node__box"
                :x="-item.width / 2"
                :y="-item.height / 2"
                :width="item.width"
                :height="item.height"
                :rx="RADIUS_Y"
              />
              <!-- 类型圆点。它就是图例的那把钥匙，比给整个药丸上色克制得多 -->
              <circle class="node__dot" :cx="-item.width / 2 + 15" cy="0" r="4" />
              <text
                class="node__label"
                x="5"
                y="0"
                text-anchor="middle"
                dominant-baseline="central"
              >
                {{ item.node.label }}
              </text>
              <title>
                {{ item.node.label }}（{{ kindLabel(item.node.kind) }}）· 点击以它为中心
              </title>
            </g>
          </g>

          <!--
        关系名单画一层，**排在节点之后**。原本它跟着边一起画，于是谁离中心近
        谁就被节点盖住 —— 实测「相互作用」四个字被中心药丸吃掉一半，
        而这条边恰恰是整张图里最该被看见的那条。

        压在节点上仍然读得清，靠的是文字底下那圈描边（CSS 里的 paint-order），
        不是靠避开 —— 挪开它就得算避让，就得知道每个标签多宽，那是另一套几何
      -->
          <g class="edge-labels">
            <text
              v-for="item in layout.edges"
              :key="item.key"
              class="edge__label"
              :class="{ 'is-active': item.key === selectedKey }"
              :style="{ '--delay': `${(depthOf.get(item.edge.head.name) ?? 0) * 70}ms` }"
              :x="(item.x1 + item.x2) / 2"
              :y="(item.y1 + item.y2) / 2 - 5"
            >
              {{ relationLabel(item.edge.relation) }}
            </text>
          </g>
        </g>
      </g>
    </svg>
  </div>
</template>

<style scoped>
/* 视口宿主。**裁剪必须落在它身上**：SVG 元素自己的 overflow 在浏览器里
   不可靠（放大后的内容会溢出去盖住页头与依据栏，点击也会落到别的元素上） */
.viewport-box {
  position: relative;
  overflow: hidden;
  /* 视口固定高度，**不再跟着 viewBox 比例走**。
     这是这张图能从「看个大概」变成「看清某个节点」的关键：原先 height:auto +
     viewBox 随节点数增长，两跳的图还算正常，三跳能算出 2400 高的 viewBox，
     等比塞进容器后每个节点只剩 9 像素高——字和点击区一起没了。
     视口固定后，节点尺寸由容器宽度决定，深度再大也不缩水，要看得更多就放大平移 */
  height: min(78vh, 720px);
  border-radius: var(--ys-radius-sm);
}

.canvas {
  display: block;
  width: 100%;
  height: 100%;
  /* 允许拖拽平移，并禁止浏览器把滚轮/手势抢去做页面缩放 */
  touch-action: none;
  cursor: default;
}

/* 缩放后画布可拖：给出抓手光标，并让拖动中的文字/节点不被选中 */
.canvas.is-dragging {
  user-select: none;
}

.viewport-clip.is-pannable {
  cursor: grab;
}

.viewport-clip.is-dragging {
  cursor: grabbing;
}

/* 拖动时子元素的 hover 高亮会闪，压掉它 */
.viewport-clip.is-dragging .node:hover .node__box,
.viewport-clip.is-dragging .edge__hit:hover + .edge__line {
  stroke-width: initial;
}

/* ==================== 参考圈 ==================== */

.rings__circle {
  fill: none;
  stroke: var(--color-border);
  stroke-width: 1;
  stroke-dasharray: 2 6;
}

/* ==================== 边 ==================== */

.edge {
  opacity: 0;
  animation: fade-in var(--ys-duration-slow) var(--ys-ease-out) var(--delay, 0ms) forwards;
}

.edge__line {
  fill: none;
  stroke-width: 2;
  stroke-linecap: round;
}

/* 细线不好点。加一条透明的粗线专门吃点击，是最省事的做法 */
.edge__hit {
  stroke: transparent;
  stroke-width: 18;
  cursor: pointer;
}

.edge__label {
  fill: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  text-anchor: middle;
  pointer-events: none;
  /* 文字下的底色描边。它压在节点或连线上时靠这圈底色把字托出来 ——
     没有它，标签画在节点之上反而更糟：浅灰字压在主色药丸上几乎不可读 */
  stroke: var(--color-bg-surface);
  stroke-width: 3;
  stroke-linejoin: round;
  paint-order: stroke;
  opacity: 0;
  animation: fade-in var(--ys-duration-slow) var(--ys-ease-out) var(--delay, 0ms) forwards;
}

/* 关系类型靠**线型 + 颜色**两个通道区分：只靠颜色的话，
   色觉障碍用户看到的是四条一模一样的线。
   颜色只用语义 token —— 这四种关系本身就是四种语义，不该另造一套色卡 */
/* 含有：最安静的一条（中性灰，无彩色），但**不能安静到看不见**。
   原先用 --ys-warm-300，那是 --color-border-strong 的原值，在白底上
   对比度只有 1.5:1 —— 而它恰恰是中心商品唯一的那条边。按 WCAG 对非文字
   图形的要求，2px 的线也要有 3:1 */
.edge--CONTAINS .edge__line {
  stroke: var(--ys-warm-600);
}

.edge--PROVIDES .edge__line {
  stroke: var(--color-success);
}

/* 相互作用：虚线。它是唯一会随剂量与人群变化的结论，形状上也该是「不连续」的 */
.edge--INTERACTS_WITH .edge__line {
  stroke: var(--color-danger);
  stroke-dasharray: 7 5;
}

/* 人群禁忌：点线。比相互作用更弱 —— 它是一条提醒，不是一条禁令 */
.edge--CAUTION_FOR .edge__line {
  stroke: var(--color-warning);
  stroke-dasharray: 2 4;
}

.edge__hit:hover + .edge__line,
.edge.is-active .edge__line {
  stroke-width: 3.5;
}

.edge__label.is-active {
  fill: var(--color-text-primary);
  font-weight: 600;
}

/* ==================== 节点 ==================== */

.node {
  opacity: 0;
  animation: fade-in var(--ys-duration-slow) var(--ys-ease-out) var(--delay, 0ms) forwards;
  cursor: pointer;
}

.node__box {
  fill: var(--color-bg-surface);
  stroke: var(--color-border-strong);
  stroke-width: 1.5;
}

.node__label {
  fill: var(--color-text-primary);
  font-size: var(--ys-font-base);
  font-weight: 500;
  pointer-events: none;
}

/* 类型色只上在圆点上，药丸保持中性 ——
   六个类型各染一个底色的话，整张图会变成两张互相打架的色卡 */
.node--PRODUCT .node__dot {
  fill: var(--color-primary);
}

.node--INGREDIENT .node__dot {
  fill: var(--color-accent);
}

.node--NUTRIENT .node__dot {
  fill: var(--color-success);
}

.node--DRUG .node__dot {
  fill: var(--color-danger);
}

.node--DRUG_CLASS .node__dot {
  fill: var(--color-warning);
}

.node--POPULATION .node__dot {
  fill: var(--ys-warm-600);
}

/* 只有这两个类型给整个药丸上边色：商品是主角，药物是安全关键 */
.node--PRODUCT .node__box {
  stroke: var(--color-primary);
}

.node--DRUG .node__box {
  stroke: var(--color-danger);
}

.node:hover .node__box {
  stroke-width: 2.5;
}

/* 中心实体反过来上色：整张图只有它是实心的，一眼看得出「这是你现在看的东西」 */
.node.is-center .node__box {
  fill: var(--color-primary);
  stroke: var(--color-primary);
  stroke-width: 2;
  /* 让它在视觉上浮起来，与外围节点拉开层次 */
  filter: drop-shadow(0 0 0.5rem rgba(75, 44, 16, 0.18));
}

.node.is-center .node__label {
  fill: var(--color-text-on-primary);
  font-weight: 600;
}

.node.is-center .node__dot {
  fill: var(--color-text-on-primary);
}

/* 当前被选中作为下一跳中心的节点。与 .is-center 互斥 —— 中心那个已经够显眼了 */
.node.is-active:not(.is-center) .node__box {
  stroke-width: 3;
  stroke: var(--color-border-focus);
}

@keyframes fade-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

/*
 * 动效降级是硬要求，不是加分项：这张图有几十个元素在错峰入场，
 * 对前庭敏感的用户来说是不适，不是精致。
 */
@media (prefers-reduced-motion: reduce) {
  .edge,
  .node,
  .edge__label {
    animation: none;
    opacity: 1;
  }
}
</style>
