<script setup lang="ts">
import { actionLabel, argEntries, toolLabel } from '@/utils/tools'
import type { PendingActionDetail } from '@/types/models'

defineProps<{
  /** 服务端给的可读描述，形如 `order_cancel(orderId=12)`。结构化渲染失败时的兜底，永远保留 */
  actions: string[]
  /**
   * 是否可操作。<b>只有最新一条消息上的卡片是真的</b>。
   * <p>
   * 确认动作会把「确认执行」当作一条新消息重发，服务端据此**重新规划**——
   * 也就是说，确认的实际内容取决于当前对话上下文，而不是这张卡片当初列出的东西。
   * 用户回滚到三条之前点一下「确认」，批准的可能完全是另一件事。
   * 过期卡片因此只留记录、不留按钮。
   */
  active: boolean
  /**
   * 结构化参数（`{ tool, arguments }`）。有它就把工具名与入参渲染成可读的键值行；
   * 缺它、或遇到不认识的工具时，逐条退回 `actions` 的字符串原文——字符串版永远是兜底，
   * 卡片不会因为渲染不出可读文案就少显示一个参数。
   */
  details?: PendingActionDetail[]
}>()

/**
 * 一条操作的可读渲染：`{ tool, arguments }` → 工具中文名 + 参数键值行。
 * <p>
 * **取值一律原样展示**：用户核对的是「要动的是哪一个对象」，翻译或截断取值等于替他改授权对象。
 * 这里只把键名换中文、把 `order_cancel(orderId=12)` 摆成「订单取消 · 订单号 12」。
 */
function detailRows(detail: PendingActionDetail): [string, string][] {
  return argEntries(detail.arguments)
}

const emit = defineEmits<{
  approve: []
  dismiss: []
}>()
</script>

<template>
  <section class="approval" :class="{ 'approval--stale': !active }" aria-label="高危操作确认">
    <p class="approval__title">需要你确认的高危操作</p>

    <!--
      有结构化参数时渲染可读卡片；缺它、或该工具不在清单里时退回字符串原文。
      两条路都保留原始取值——**卡片只做可读化，不做省略**。
    -->
    <ul v-if="details && details.length" class="approval__list">
      <li v-for="(detail, index) in details" :key="index">
        <p class="approval__op">{{ toolLabel(detail.tool) }}</p>
        <dl v-if="detailRows(detail).length" class="approval__args">
          <template v-for="[label, value] in detailRows(detail)" :key="label">
            <dt>{{ label }}</dt>
            <dd>{{ value }}</dd>
          </template>
        </dl>
        <p v-else class="approval__raw">{{ actionLabel(actions[index] ?? detail.tool) }}</p>
      </li>
    </ul>
    <ul v-else class="approval__list">
      <li v-for="action in actions" :key="action">{{ actionLabel(action) }}</li>
    </ul>

    <p v-if="!active" class="approval__stale-note">
      这条确认已过期：对话已经往下走了，确认的对象可能已经不是它。请对最新一条回复。
    </p>

    <div v-else class="approval__buttons">
      <el-button type="danger" size="small" @click="emit('approve')">确认执行</el-button>
      <el-button size="small" @click="emit('dismiss')">取消</el-button>
    </div>
  </section>
</template>

<style scoped>
/*
  警示卡的视觉是「一条左侧危险色导轨 + 中性底」，不是整块红底。
  整块红会把一句话的确认框渲染成报错弹窗，而这里要的是「停下来看清」而不是「出事了」。
*/
.approval {
  margin-top: var(--ys-space-3);
  padding: var(--ys-space-4) var(--ys-space-4) var(--ys-space-4) var(--ys-space-5);
  border: 1px solid color-mix(in srgb, var(--color-danger) 30%, var(--color-border));
  border-left: 3px solid var(--color-danger);
  border-radius: var(--ys-radius-md);
  background: var(--color-danger-subtle);
}

.approval--stale {
  border-color: var(--color-border);
  border-left-color: var(--color-border-strong);
  background: var(--color-bg-surface-muted);
}

.approval__title {
  margin: 0;
  font-size: var(--ys-font-sm);
  font-weight: 600;
  color: var(--color-text-primary);
}

.approval__list {
  margin: var(--ys-space-3) 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: var(--ys-space-2);
}

.approval__list li {
  padding: var(--ys-space-2) var(--ys-space-3);
  border: 1px solid color-mix(in srgb, var(--color-danger) 20%, transparent);
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-surface);
  font-family: var(--ys-font-mono);
  font-size: var(--ys-font-xs);
  color: var(--color-text-primary);
  word-break: break-all;
}

.approval--stale .approval__list li {
  border-color: var(--color-border);
  color: var(--color-text-secondary);
}

/* 结构化卡片：工具名是标题、参数是可读的键值对，正文用正文字体而非等宽——
   等宽是为「原始代码」准备的，可读文案再用等宽只会更难读 */
.approval__op {
  margin: 0;
  font-family: var(--ys-font-mono);
  font-size: var(--ys-font-xs);
  font-weight: 600;
  color: var(--color-text-primary);
}

.approval__args {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--ys-space-1) var(--ys-space-3);
  margin: var(--ys-space-2) 0 0;
  font-size: var(--ys-font-xs);
}

.approval__args dt {
  color: var(--color-text-muted);
  white-space: nowrap;
}

.approval__args dd {
  margin: 0;
  color: var(--color-text-primary);
  word-break: break-all;
}

.approval__raw {
  margin: var(--ys-space-2) 0 0;
  font-family: var(--ys-font-mono);
  font-size: var(--ys-font-xs);
  color: var(--color-text-secondary);
  word-break: break-all;
}

.approval__stale-note {
  margin: var(--ys-space-3) 0 0;
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
  color: var(--color-text-muted);
}

.approval__buttons {
  display: flex;
  gap: var(--ys-space-2);
  margin-top: var(--ys-space-4);
}

@media (prefers-reduced-motion: no-preference) {
  .approval {
    animation: approval-in 240ms ease-out;
  }

  @keyframes approval-in {
    from {
      opacity: 0;
      transform: translateY(-4px);
    }
  }
}
</style>
