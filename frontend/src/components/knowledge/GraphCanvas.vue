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
import { computed } from 'vue'

const props = defineProps<{
  layout: GraphLayout
  /** 当前选中的边。命中时加粗并高亮，与右侧依据栏联动 */
  selectedKey: string
  /** 当前的中心实体，画成实心药丸 */
  selectedNode: string
}>()

const emit = defineEmits<{
  selectEdge: [edge: GraphEdge]
  selectNode: [name: string]
}>()

/** 圆心的画布坐标。布局算出的坐标以中心为原点，这里整体平移过去 */
const originX = computed(() => props.layout.width / 2)
const originY = computed(() => props.layout.height / 2)

/** 每个节点在第几跳。边的入场延迟按它算，逐条去 find 是白扫的 */
const depthOf = computed(() => {
  const map = new Map<string, number>()
  for (const item of props.layout.nodes) map.set(item.node.name, item.depth)
  return map
})

/** 药丸的半高。圆角取它才能是标准胶囊形 */
const RADIUS_Y = computed(() => (props.layout.nodes[0]?.height ?? 30) / 2)
</script>

<template>
  <svg
    class="canvas"
    :viewBox="`0 0 ${layout.width} ${layout.height}`"
    role="img"
    :aria-label="`知识图谱：${layout.nodes.length} 个实体、${layout.edges.length} 条关系，共 ${Math.max(layout.ringRadii.length - 1, 0)} 跳`"
  >
    <g :transform="`translate(${originX}, ${originY})`">
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
          <text class="node__label" x="5" y="0" text-anchor="middle" dominant-baseline="central">
            {{ item.node.label }}
          </text>
          <title>{{ item.node.label }}（{{ kindLabel(item.node.kind) }}）· 点击以它为中心</title>
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
  </svg>
</template>

<style scoped>
.canvas {
  display: block;
  width: 100%;
  /* 高按 viewBox 的比例定，但封顶：深一点的图（3 跳）能算出近千像素高，
     把下方的说明和依据挤到屏幕外。封顶后交给 preserveAspectRatio 留白，
     不会变形 */
  height: auto;
  max-height: 78vh;
  /* 画布是可以点的，但拖动页面时不该被 SVG 抢走手势 */
  touch-action: manipulation;
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
