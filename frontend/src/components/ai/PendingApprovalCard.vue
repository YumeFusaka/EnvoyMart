<script setup lang="ts">
import { computed } from 'vue'
import { formatPrice } from '@/api/product'
import { actionLabel, argEntries, toolLabel } from '@/utils/tools'
import type { PendingActionDetail } from '@/types/models'

const props = defineProps<{
  actions: string[]
  active: boolean
  status?: string
  details?: PendingActionDetail[]
}>()

const actionable = computed(() => props.active && (!props.status || props.status === 'PENDING'))

function text(value: unknown): string {
  const result = typeof value === 'string' ? value.trim() : ''
  return result === '[object Object]' ? '' : result
}

function integer(value: unknown): number | undefined {
  const number = typeof value === 'number'
    ? value
    : typeof value === 'string' && /^\d+$/.test(value.trim()) ? Number(value) : NaN
  return Number.isSafeInteger(number) && number >= 0 ? number : undefined
}

function legacyCartDetail(action: string): PendingActionDetail | undefined {
  if (!/^cart_add\s*(?:\(|$)/.test(action.trim())) {
    return undefined
  }
  const input = /^cart_add\s*\(([\s\S]*)\)$/.exec(action.trim())?.[1] ?? ''
  let arguments_: Record<string, unknown> = {}
  try {
    const parsed: unknown = JSON.parse(input)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      arguments_ = parsed as Record<string, unknown>
    }
  } catch {
    for (const match of input.matchAll(/["']?(skuId|quantity)["']?\s*[:=]\s*(\d+)/g)) {
      arguments_[match[1]!] = Number(match[2])
    }
  }
  return { tool: 'cart_add', arguments: arguments_ }
}

function cartSummary(detail: PendingActionDetail) {
  const args = detail.arguments ?? {}
  const productName = text(args.productName)
  const specification = typeof args.specification === 'string'
    ? args.specification.trim() ? text(args.specification).replace(/^规格[:：]\s*/, '') : '单一规格'
    : args.specification === null ? '单一规格' : ''
  const quantity = integer(args.quantity)
  const skuId = integer(args.skuId)
  const unitPrice = integer(args.unitPrice)
  const subtotal = integer(args.subtotal)
  const missing = [
    !productName ? '商品名称' : '',
    !specification ? '规格' : '',
    !quantity ? '数量' : '',
    !skuId ? '商品规格标识' : '',
    unitPrice === undefined ? '单价' : '',
    subtotal === undefined ? '小计' : '',
  ].filter(Boolean)
  const rows: [string, string][] = [
    ['规格', specification || '规格信息未提供'],
    ['数量', quantity ? String(quantity) : '未提供'],
    ['单价', unitPrice === undefined ? '未提供' : formatPrice(unitPrice)],
    ['小计', subtotal === undefined ? '未提供' : formatPrice(subtotal)],
  ]
  return {
    productName: productName || '商品名称未提供',
    rows,
    skuId,
    missingNotice: missing.length ? `信息不完整：缺少${missing.join('、')}，无法完整核对加购内容。` : '',
  }
}

const displayActions = computed(() => Array.from(
  { length: Math.max(props.actions?.length ?? 0, props.details?.length ?? 0) },
  (_, index) => {
    const action = props.actions?.[index] ?? ''
    const detail = props.details?.[index] ?? legacyCartDetail(action)
    const cart = detail?.tool === 'cart_add' ? cartSummary(detail) : undefined
    return {
      label: detail ? toolLabel(detail.tool) : actionLabel(action),
      raw: actionLabel(action || detail?.tool || ''),
      cart,
      blocked: !!cart && (detail?.confirmable === false || !!cart.missingNotice),
      rows: detail && detail.tool !== 'cart_add' ? argEntries(detail.arguments) : [],
      notice: text(detail?.displayNotice),
    }
  },
))

const approvalBlocked = computed(() => displayActions.value.some((action) => action.blocked))

const emit = defineEmits<{
  approve: []
  dismiss: []
}>()
</script>

<template>
  <section class="approval" :class="{ 'approval--stale': !actionable }" aria-label="交易操作确认">
    <p class="approval__title">{{ actionable ? '请确认操作内容' : status === 'CONFIRMED' ? '已提交确认的操作' : status === 'DISMISSED' ? '已取消的操作' : '操作确认记录' }}</p>

    <ul v-if="displayActions.length" class="approval__list">
      <li v-for="(action, index) in displayActions" :key="index">
        <p class="approval__op">{{ action.label }}</p>
        <template v-if="action.cart">
          <p class="approval__product">{{ action.cart.productName }}</p>
          <dl class="approval__args">
            <template v-for="[label, value] in action.cart.rows" :key="label">
              <dt>{{ label }}</dt>
              <dd :class="{ 'approval__subtotal': label === '小计' }">{{ value }}</dd>
            </template>
          </dl>
          <p v-if="action.cart.missingNotice" class="approval__notice">
            {{ action.cart.missingNotice }}
            <span v-if="action.cart.skuId">规格编号：{{ action.cart.skuId }}。</span>
          </p>
        </template>
        <dl v-else-if="action.rows.length" class="approval__args">
          <template v-for="[label, value] in action.rows" :key="label">
            <dt>{{ label }}</dt>
            <dd>{{ value }}</dd>
          </template>
        </dl>
        <p v-else class="approval__raw">{{ action.raw }}</p>
        <p v-if="action.notice" class="approval__notice">{{ action.notice }}</p>
      </li>
    </ul>
    <p v-else class="approval__notice">暂无可核对的操作信息。</p>

    <p v-if="actionable && approvalBlocked" class="approval__notice" role="status">
      加购信息不完整或商品暂不可购买，请重新查询商品后再确认。
    </p>

    <p v-if="!actionable" class="approval__stale-note">
      操作确认记录。执行结果请查看后续回复与工具轨迹；此记录不能重复执行。
    </p>

    <div v-else-if="displayActions.length" class="approval__buttons">
      <el-button type="danger" size="small" :disabled="approvalBlocked" @click="emit('approve')">确认执行</el-button>
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
  font-family: var(--ys-font-sans);
  font-size: var(--ys-font-xs);
  color: var(--color-text-primary);
  overflow-wrap: anywhere;
}

.approval--stale .approval__list li {
  border-color: var(--color-border);
  color: var(--color-text-secondary);
}

.approval__op {
  margin: 0;
  font-size: var(--ys-font-xs);
  font-weight: 600;
  color: var(--color-text-primary);
}

.approval__product {
  margin: var(--ys-space-2) 0 0;
  font-size: var(--ys-font-sm);
  font-weight: 600;
  line-height: var(--ys-leading-base);
}

.approval__args {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
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
  overflow-wrap: anywhere;
}

.approval__subtotal {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.approval__notice {
  margin: var(--ys-space-2) 0 0;
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-base);
  color: var(--color-text-secondary);
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
  flex-wrap: wrap;
  gap: var(--ys-space-2);
  margin-top: var(--ys-space-4);
}

@media (prefers-reduced-motion: no-preference) {
  .approval {
    animation: approval-in var(--ys-duration-base) var(--ys-ease-out);
  }

  @keyframes approval-in {
    from {
      opacity: 0;
      transform: translateY(-4px);
    }
  }
}
</style>
