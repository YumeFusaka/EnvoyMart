/**
 * 图谱的画布计算与词汇表。
 *
 * 与后端 ToolOutputFormattingTest 守的是同一条规矩：**给用户看的文本必须经过转换**。
 * `INTERACTS_WITH` 直接落到界面上，用户看到的是一串内部标识。
 */

import type { GraphEdge, GraphNode } from '@/types/models'

// ==================== 词汇表 ====================

/**
 * 实体类型 → 中文。
 *
 * 与 `sourceLabel` 同样的规矩：**未知取值原样返回**。图谱里加一类实体时，
 * 前端还没跟上应该显示 `METABOLITE`（一看就知道是没翻译），
 * 而不是显示空白（看起来像数据坏了）。
 */
const KIND_LABELS: Record<string, string> = {
  PRODUCT: '商品',
  INGREDIENT: '成分',
  NUTRIENT: '营养素',
  DRUG: '药物',
  DRUG_CLASS: '药物类别',
  POPULATION: '人群',
  // 组合节点没有对应的现实实体，只是「这几样一起」这个结构。图例里单列一项，
  // 用户看到的是「铁剂 + 钙剂」这样一个圆，标成「组合」才明白它代表什么
  COMBINATION: '组合',
}

/** 关系类型 → 中文。与后端 GraphRelation.label() 是同一份词表的两侧 */
const RELATION_LABELS: Record<string, string> = {
  CONTAINS: '含有',
  PROVIDES: '提供',
  INTERACTS_WITH: '相互作用',
  CAUTION_FOR: '禁忌人群',
  COMBINED_WITH: '组合禁忌',
}

/**
 * 图例里列出的类型顺序。
 *
 * 刻意不是字典的键顺序：**风险优先级的顺序**。用户先要看见的是
 * 「有几种药、和什么冲突」，而不是「这个商品含什么成分」。
 */
export const KIND_ORDER = ['PRODUCT', 'INGREDIENT', 'NUTRIENT', 'DRUG', 'DRUG_CLASS', 'POPULATION', 'COMBINATION']

/** 图例里的关系顺序，同样是风险优先 */
export const RELATION_ORDER = ['COMBINED_WITH', 'INTERACTS_WITH', 'CAUTION_FOR', 'CONTAINS', 'PROVIDES']

export function kindLabel(kind: string | null | undefined): string {
  if (!kind) return ''
  return KIND_LABELS[kind] ?? kind
}

export function relationLabel(relation: string | null | undefined): string {
  if (!relation) return ''
  return RELATION_LABELS[relation] ?? relation
}

/**
 * 一个「关系」的标识：只看三元组，不看它由哪篇文档支撑。
 *
 * 图谱里同一件事常被多篇文档各说一遍（DHA 与阿司匹林相冲，三篇说明书都写了），
 * 那是三条独立的边、各有各的出处。但用户眼里那是**一件事**，列表里列三遍就是重复。
 * 所以界面按「关系」聚合、把出处收在下面 —— 这与图谱的语义模型一致：
 * 一条关系、多个证据。
 */
export function relationKey(edge: GraphEdge): string {
  return [edge.head.name, edge.relation, edge.tail.name].join('|')
}

/** 一条关系，加上支撑它的全部出处（边）。同一件事的不同文档证据收在这里 */
export interface EvidenceGroup {
  /** 关系标识，选中态用它 */
  key: string
  head: GraphNode
  tail: GraphNode
  relation: string
  /** 支撑这条关系的边。至少一条；多条时按文档号排序，保证每次渲染顺序一致 */
  sources: GraphEdge[]
}

/**
 * 把边按「关系」聚合。
 * <p>
 * 保留首次出现的顺序（也就是画布上的绘制顺序），同一关系内按 docId 排 ——
 * 顺序不稳的话，同一份数据每次刷新列表都在跳。
 */
export function groupByRelation(edges: GraphEdge[]): EvidenceGroup[] {
  const groups = new Map<string, EvidenceGroup>()
  for (const edge of edges) {
    const key = relationKey(edge)
    const existing = groups.get(key)
    if (existing) {
      existing.sources.push(edge)
    } else {
      groups.set(key, {
        key,
        head: edge.head,
        tail: edge.tail,
        relation: edge.relation,
        sources: [edge],
      })
    }
  }
  for (const g of groups.values()) {
    g.sources.sort((a, b) => a.docId.localeCompare(b.docId))
  }
  return [...groups.values()]
}

/**
 * 一条边的稳定标识。
 *
 * 同一个切片上可能有好几条边（同一句话支撑的不同三元组），所以键里必须带上
 * 两端与关系 —— 只用 chunkId 的话，点第二条会高亮到第一条上去。
 */
export function edgeKey(edge: GraphEdge): string {
  return [edge.head.name, edge.relation, edge.tail.name, edge.docId, edge.chunkId ?? edge.quoteStart].join(
    '|',
  )
}

/** 边的两端。图谱的边是有向的（「含有」反过来读不通），但画图与遍历都当无向处理 */
export function endpoints(edge: GraphEdge): [GraphNode, GraphNode] {
  return [edge.head, edge.tail]
}

/**
 * 节点键的规范化 —— 与后端 `EntityNames.normalize` 是同一套规则：去空白 + 转小写。
 * <p>
 * <b>不做这一步会静默出错。</b>图谱里商品键是 `spu7`，而商品详情页拼出来的是 `SPU7`；
 * 直接拿它去 `Map.has()` 一律落空，于是 {@link ringLayout} 认不出指定的中心，
 * 悄悄回落到「连接最多的节点」——页面上看是「图正常画出来了」，
 * 只是中心是别人。这种错不报错，只答错。
 */
export function normalizeName(name: string): string {
  return name.replace(/\s+/g, '').toLowerCase()
}

// ==================== 布局 ====================

/** 一个节点在画布上的落位。宽度是按标签估的，见 estimateWidth */
export interface PlacedNode {
  node: GraphNode
  /** 距中心实体的跳数，0 即中心 */
  depth: number
  x: number
  y: number
  width: number
  height: number
}

/** 一条边的两端坐标。连线画在节点下面，由节点的填充挡住多余的部分，不做几何裁剪 */
export interface PlacedEdge {
  edge: GraphEdge
  key: string
  x1: number
  y1: number
  x2: number
  y2: number
}

export interface GraphLayout {
  nodes: PlacedNode[]
  edges: PlacedEdge[]
  /** 每跳一圈的半径，下标即跳数。用来画那几道同心参考圈 */
  ringRadii: number[]
  /**
   * viewBox 的尺寸。**不是正方形** —— 见图下方的说明。
   */
  width: number
  height: number
  /**
   * 最外一圈节点的外接圆半径（布局单位，含节点自身半宽）。
   *
   * <p>它存在的唯一理由是**给镜头划边界**。同心环图的节点铺在一条条圆环上，
   * 环与环之间是空的：三跳图实测外圈半径 1273，而节点包围盒只覆盖 2606 单位直径 ——
   * 按包围盒允许平移，镜头正好能停进「圆环内部那片空心区」，拖一下就是整屏空白
   * （图还在，只是视野落进了洞里，实测 150 条边的三跳图能拖出九成白屏）。
   *
   * <p>改用外接圆直径当内容尺寸后，边界退化成一条圆切线：镜头最多推到最外圈，
   * 再往里就只能停在另一侧，洞里那片空白永远进不了视口。它比节点包围盒大一圈
   * 是**刻意的** —— 宽出来的正是圆环内部的空洞，按包围盒算就会把它算成可看内容。
   */
  contentRadius: number
}

const NODE_HEIGHT = 30
/** 一个全角字在 14px 字号下的宽度 */
const FULL_CHAR = 14
const HALF_CHAR = 7.7
/** 药丸左右内边距 + 类型圆点占位 */
const NODE_PADDING = 34
/** 相邻两环的最小间距。要同时容得下节点高度与它上下的呼吸 */
const RING_GAP = 104
/** 同一环上相邻节点之间的最小空隙 */
const ARC_GAP = 20
const CANVAS_PADDING = 64
/**
 * 画布高宽比的下限。
 * <p>
 * 只按内容包围盒定尺寸的话，「一条链」形状的图会得到一张极扁的画布，
 * 而画布是 {@code width:100%} 撑满、按比例定高的 —— 高度一小，整张图连同
 * 标签一起缩到读不出来。宁可上下留白。
 */
const MIN_ASPECT = 0.55

/**
 * 估一个标签要占多宽。
 *
 * <b>刻意不做真实测宽</b>：`getComputedTextLength()` 要求先渲染再回读，
 * 会把布局从「纯函数、算完就能画」变成两趟渲染，第二趟还会看到第一趟的中间态。
 * 按全角/半角估宽的误差在一个字以内，而布局要的只是一个「够不够摆下」的量级。
 */
function estimateWidth(label: string): number {
  let width = 0
  for (const ch of label) {
    // 中日韩统一表意文字 + 全角标点。够用即可，不求覆盖所有 Unicode 平面
    width += /[　-鿿＀-￯]/.test(ch) ? FULL_CHAR : HALF_CHAR
  }
  return Math.round(width + NODE_PADDING)
}

/** 圆均值。直接取算术平均的话，350° 与 10° 会平均成 180°（正对面） */
function circularMean(angles: number[]): number {
  let sin = 0
  let cos = 0
  for (const a of angles) {
    sin += Math.sin(a)
    cos += Math.cos(a)
  }
  return Math.atan2(sin / angles.length, cos / angles.length)
}

/**
 * 把边表摆成同心环。
 *
 * <b>半径就是跳数</b>，这是这张图唯一比列表强的地方：中心是用户正在看的商品，
 * 往外第一圈是它直接关联的东西，第二圈是那些东西再牵出来的。位置本身在回答
 * 「这个结论离我有多远」——一条列表说不清这件事。
 *
 * <b>确定性是硬要求。</b>同一个 root 每次画出来位置必须一样：演示时刷新一下
 * 布局全变，看起来就像随机生成的。所以排序只依赖名字，不依赖数组顺序、
 * 时间或随机数。
 *
 * @param edges 邻域查询返回的边表。节点是它两端的并集，不需要单独传
 * @param root  中心实体的键。传 null 或图上没有它时，取连接最多的那个当中心
 */
export function ringLayout(edges: GraphEdge[], root: string | null): GraphLayout {
  const nodes = new Map<string, GraphNode>()
  for (const edge of edges) {
    for (const node of endpoints(edge)) {
      if (!nodes.has(node.name)) nodes.set(node.name, node)
    }
  }
  if (nodes.size === 0) {
    return { nodes: [], edges: [], ringRadii: [], width: 0, height: 0, contentRadius: 0 }
  }

  const adjacency = new Map<string, Set<string>>()
  for (const name of nodes.keys()) adjacency.set(name, new Set())
  for (const edge of edges) {
    adjacency.get(edge.head.name)?.add(edge.tail.name)
    adjacency.get(edge.tail.name)?.add(edge.head.name)
  }

  const center = pickCenter(nodes, adjacency, root)
  const depthOf = bfs(center, adjacency)

  // 按跳数分环。够不到的节点（图上孤立）统一挂到最外一圈 ——
  // 漏掉不画的话，用户会以为图谱里没有这个东西
  const byDepth = new Map<number, string[]>()
  const orphans: string[] = []
  let deepest = 0
  for (const name of nodes.keys()) {
    const depth = depthOf.get(name)
    if (depth === undefined) {
      orphans.push(name)
      continue
    }
    const bucket = byDepth.get(depth)
    if (bucket) bucket.push(name)
    else byDepth.set(depth, [name])
    deepest = Math.max(deepest, depth)
  }
  if (orphans.length) byDepth.set(deepest + 1, orphans)

  // 摊成密集数组：下标即跳数，后面几处都能直接按 depth 取
  const lastRing = deepest + (orphans.length ? 1 : 0)
  const rings: string[][] = Array.from({ length: lastRing + 1 }, (_, d) => byDepth.get(d) ?? [])

  // 半径：既要一圈比一圈大，又要装得下这一圈所有节点。
  // 累加而不是每圈各算各的 —— 否则「第 2 圈挤了 30 个节点」算出的半径
  // 可能比第 3 圈还大，两圈就叠在一起了
  const ringRadii: number[] = []
  let radius = 0
  for (let depth = 0; depth <= lastRing; depth++) {
    if (depth === 0) {
      ringRadii.push(0)
      continue
    }
    // 多留一份 ARC_GAP：环的首尾之间也要有空隙，否则周长刚好用完时最后一个是贴着第一个的
    const needed =
      (rings[depth]!.reduce((sum, name) => sum + estimateWidth(nodes.get(name)!.label) + ARC_GAP, 0) +
        ARC_GAP) /
      (2 * Math.PI)
    radius = Math.max(radius + RING_GAP, needed)
    ringRadii.push(radius)
  }

  const placed = new Map<string, PlacedNode>()
  const angleOf = new Map<string, number>()

  const place = (name: string, depth: number, angle: number) => {
    const node = nodes.get(name)!
    const r = ringRadii[depth] ?? 0
    const width = estimateWidth(node.label)
    placed.set(name, {
      node,
      depth,
      width,
      height: NODE_HEIGHT,
      x: Math.round(Math.cos(angle) * r),
      y: Math.round(Math.sin(angle) * r),
    })
    angleOf.set(name, angle)
  }

  place(center, 0, 0)

  for (let depth = 1; depth <= lastRing; depth++) {
    const ring = rings[depth]!
    if (!ring.length) continue
    // 同环内按「父节点方向」排，兄弟挨着兄弟，边才不会绕着中心乱穿。
    // 没有父节点可参考的排在最后，用名字兜底排序
    const ordered = ring
      .map((name) => {
        const parents = [...(adjacency.get(name) ?? [])].filter((nb) => angleOf.has(nb))
        return { name, angle: parents.length ? circularMean(parents.map((p) => angleOf.get(p)!)) : NaN }
      })
      .sort((a, b) => {
        const an = Number.isNaN(a.angle)
        const bn = Number.isNaN(b.angle)
        if (an !== bn) return an ? 1 : -1
        if (!an && a.angle !== b.angle) return a.angle - b.angle
        return nodes.get(a.name)!.label.localeCompare(nodes.get(b.name)!.label, 'zh-Hans-CN')
      })

    // 每个节点分到的角宽**按它自己的宽度算**，不是 N 等分。
    // 等分在这个语料上会真的叠起来：「正在服用抗凝药物者」是「EPA」的三倍宽，
    // 一样分 360/N 度的话，它会盖住两边各半个邻居。半径已经按周长配够，
    // 这里只要把每一度按比例发下去，就不会有谁越界
    const weights = ordered.map((entry) => estimateWidth(nodes.get(entry.name)!.label) + ARC_GAP)
    const total = weights.reduce((sum, w) => sum + w, 0)
    let cursor = -Math.PI / 2
    ordered.forEach((entry, index) => {
      const span = ((weights[index] ?? 0) / total) * 2 * Math.PI
      place(entry.name, depth, cursor + span / 2)
      cursor += span
    })
  }

  const placedEdges: PlacedEdge[] = []
  for (const edge of edges) {
    const from = placed.get(edge.head.name)
    const to = placed.get(edge.tail.name)
    if (!from || !to) continue
    // 自环（同一实体两端）画不出线，也没信息量
    if (from === to) continue
    placedEdges.push({
      edge,
      key: edgeKey(edge),
      x1: from.x,
      y1: from.y,
      x2: to.x,
      y2: to.y,
    })
  }

  // 画布要装下的是**节点本身**，不是那几道参考圈。
  // 按最大半径留边的话，一个 160px 宽的药丸挂在半径 154 的环上时，
  // 它的两端会伸到半径 234 的地方 —— 比参考圈远得多，会被 viewBox 裁掉一半
  const placedNodes = [...placed.values()]
  const extentX = placedNodes.reduce((max, n) => Math.max(max, Math.abs(n.x) + n.width / 2), 0)
  const extentY = placedNodes.reduce((max, n) => Math.max(max, Math.abs(n.y) + n.height / 2), 0)

  // 外接圆半径：取每个节点「圆心到它中心 + 自身半宽」的最大值。
  // 节点挂在环上，半径才是它到中心的真实距离；包围盒的 x/y 分量在斜向上会低估
  const contentRadius = placedNodes.reduce(
    (max, n) => Math.max(max, Math.hypot(n.x, n.y) + n.width / 2),
    0,
  )

  const width = Math.round((extentX + CANVAS_PADDING) * 2)
  // 两个轴分开算，**不再强制正方形**。画布宽 100% 高 auto，正方形时
  // 内容的上下留白会白白占掉一屏的一半 —— 实测 802×802 的画布里内容只有
  // 647×518。但也不能纯按内容比例走：一条长链状的图会压成一条细带，
  // 整张图缩到看不清字。所以给高度兜一个下限，宁可留白也不要小到读不出
  const height = Math.round(Math.max((extentY + CANVAS_PADDING) * 2, width * MIN_ASPECT))

  // 节点画在边之后，靠自身填充挡住线头，省掉一套「求线段与药丸交点」的几何
  return { nodes: placedNodes, edges: placedEdges, ringRadii, width, height, contentRadius }
}

/**
 * 选中心实体。
 * <p>
 * 没指定就用连接最多的那个 —— 一张以孤立点为圆心的图，外面挂着一圈彼此相连的
 * 节点，看起来像随机撒的。
 */
function pickCenter(
  nodes: Map<string, GraphNode>,
  adjacency: Map<string, Set<string>>,
  root: string | null,
): string {
  if (root) {
    const wanted = normalizeName(root)
    for (const name of nodes.keys()) {
      if (normalizeName(name) === wanted) return name
    }
  }
  let best = ''
  let bestDegree = -1
  for (const name of nodes.keys()) {
    const degree = adjacency.get(name)?.size ?? 0
    if (degree > bestDegree) {
      best = name
      bestDegree = degree
    }
  }
  return best
}

function bfs(center: string, adjacency: Map<string, Set<string>>): Map<string, number> {
  const depth = new Map<string, number>([[center, 0]])
  const queue = [center]
  while (queue.length) {
    const current = queue.shift()!
    for (const next of adjacency.get(current) ?? []) {
      if (depth.has(next)) continue
      depth.set(next, depth.get(current)! + 1)
      queue.push(next)
    }
  }
  return depth
}
