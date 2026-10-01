<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createTicket, TICKET_CATEGORY_OPTIONS } from '@/api/ticket'
import type { TicketCategory } from '@/types/models'

/**
 * 发起工单。三个入口共用：个人中心 / 工单列表 / 订单详情。
 *
 * **为什么是对话框而不是独立页面**：详情页与售后页发起工单时，用户是
 * 「看着那张订单想说点什么」，跳走一整页会把他从上下文里拔出来，
 * 回来还要重新找那条订单。带上 `orderId` 就地写，写完直接进会话。
 *
 * 分类用**单选卡片**而不是下拉：四个选项的含义需要一句解释（"订单问题"包不包括退款？
 * 用户分不清就会随手选一个），下拉框把解释藏起来了。
 */
const props = defineProps<{ modelValue: boolean; orderId?: number; orderNo?: string | null }>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'created', id: number): void
}>()

const category = ref<TicketCategory>('ORDER')
const title = ref('')
const content = ref('')
const submitting = ref(false)

/** 分类的含义在服务端是枚举，这里只负责把四个选项连同解释摆出来，见 `TICKET_CATEGORY_OPTIONS` */
const CATEGORIES = TICKET_CATEGORY_OPTIONS

async function submit() {
  if (!category.value) {
    ElMessage.warning('请选择问题分类')
    return
  }
  if (!title.value.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  if (!content.value.trim()) {
    ElMessage.warning('请描述你遇到的问题')
    return
  }
  submitting.value = true
  try {
    const created = await createTicket({
      category: category.value,
      title: title.value.trim(),
      content: content.value.trim(),
      orderId: props.orderId,
    })
    ElMessage.success('工单已提交')
    emit('update:modelValue', false)
    emit('created', created.ticket.id)
  } catch {
    // 拦截器已经弹过提示。这里吞掉异常只为让对话框留在原地 ——
    // 关掉它等于把用户刚写好的问题描述一起丢掉
  } finally {
    submitting.value = false
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      // 带着订单进来时默认「订单问题」：这是绝大多数情况的来意，少一次选择
      category.value = props.orderId ? 'ORDER' : 'OTHER'
      title.value = ''
      content.value = ''
    }
  },
)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="发起工单"
    width="560px"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <p v-if="orderNo" class="linked">
      关联订单 <strong>{{ orderNo }}</strong>
      <span class="linked__hint">客服会看到订单号，可以直接查这笔订单</span>
    </p>

    <el-form label-position="top">
      <el-form-item label="问题分类">
        <div class="cats">
          <label
            v-for="item in CATEGORIES"
            :key="item.value"
            class="cat"
            :class="{ 'is-active': category === item.value }"
          >
            <input
              v-model="category"
              class="cat__radio"
              type="radio"
              name="ticket-category"
              :value="item.value"
            />
            <span class="cat__label">{{ item.label }}</span>
            <span class="cat__hint">{{ item.hint }}</span>
          </label>
        </div>
      </el-form-item>

      <el-form-item label="标题">
        <el-input v-model="title" maxlength="128" show-word-limit placeholder="一句话说清问题" />
      </el-form-item>

      <el-form-item label="问题描述">
        <el-input
          v-model="content"
          type="textarea"
          :rows="5"
          maxlength="2000"
          show-word-limit
          placeholder="把情况写清楚：什么时候、哪张订单、你期望怎么解决。描述越具体，客服越不用来回问"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">提交工单</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.linked {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--ys-space-2);
  padding: var(--ys-space-2) var(--ys-space-3);
  margin-bottom: var(--ys-space-4);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
  font-size: var(--ys-font-sm);
}

.linked strong {
  font-family: var(--ys-font-mono);
}

.linked__hint {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.cats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: var(--ys-space-2);
  width: 100%;
}

.cat {
  display: grid;
  gap: 2px;
  padding: var(--ys-space-2) var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  cursor: pointer;
  transition:
    border-color var(--ys-duration-fast) var(--ys-ease-out),
    background var(--ys-duration-fast) var(--ys-ease-out);
}

.cat:hover {
  border-color: var(--color-border-strong);
}

.cat.is-active {
  border-color: var(--color-primary);
  background: var(--color-primary-subtle);
}

.cat:focus-within {
  box-shadow: var(--focus-ring);
}

/* 原生 radio 只做可访问性（键盘、读屏），视觉由卡片本身承担 */
.cat__radio {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

.cat__label {
  font-weight: 600;
  font-size: var(--ys-font-sm);
  color: var(--color-text-primary);
}

.cat__hint {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  line-height: var(--ys-leading-tight);
}
</style>
