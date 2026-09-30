<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { AFTER_SALE_REASONS, AFTER_SALE_TYPES, applyAfterSale, previewAfterSale } from '@/api/afterSale'
import { formatPrice } from '@/api/product'
import type { AfterSalePreview, OrderItem } from '@/types/models'

const props = defineProps<{ modelValue: boolean; orderId: number; item: OrderItem | null }>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'applied'): void
}>()

const type = ref<string>('REFUND_ONLY')
const reason = ref<string>('')
const description = ref('')
const qualityIssue = ref(false)
// 输入框的原始文本与提交用的数组分开：textarea 只能绑字符串，
// 直接绑 string[] 会把输入变成字符串传出去，后端解析 List<String> 直接失败
const imagesText = ref('')
const images = computed(() =>
  imagesText.value
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean),
)
const submitting = ref(false)

const preview = ref<AfterSalePreview | null>(null)
const previewing = ref(false)

/**
 * 是否属于质量问题由用户勾选，系统不猜 —— 它需要实物证据，系统看不到。
 * 但质量问题的处理期限更长（15 天 vs 7 天），恶意勾选会走更长的窗口，
 * 所以审核环节必须真的审。
 */
async function runPreview() {
  if (!props.item) {
    return
  }
  previewing.value = true
  try {
    preview.value = await previewAfterSale(props.item.id, type.value, qualityIssue.value)
  } catch {
    preview.value = null
  } finally {
    previewing.value = false
  }
}

async function submit() {
  if (!props.item || !preview.value?.eligible) {
    return
  }
  if (!reason.value) {
    ElMessage.warning('请选择售后原因')
    return
  }
  submitting.value = true
  try {
    await applyAfterSale({
      orderItemId: props.item.id,
      type: type.value,
      reason: reason.value,
      description: description.value || undefined,
      images: images.value.length ? images.value : undefined,
      qualityIssue: qualityIssue.value,
    })
    ElMessage.success('售后申请已提交，等待审核')
    emit('update:modelValue', false)
    emit('applied')
  } finally {
    submitting.value = false
  }
}

// 打开时重置并立刻拉一次资格 —— 让用户在填表之前就看到结论
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      reason.value = ''
      description.value = ''
      qualityIssue.value = false
      imagesText.value = ''
      type.value = 'REFUND_ONLY'
      runPreview()
    }
  },
)

watch(type, runPreview)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="申请售后"
    width="560px"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div v-if="item" class="goods">
      <img v-if="item.skuImage" :src="item.skuImage" :alt="item.spuName" />
      <div>
        <p class="goods__name">{{ item.spuName }}</p>
        <p v-if="item.skuSpecText" class="goods__spec">{{ item.skuSpecText }}</p>
      </div>
      <span class="goods__amount">{{ formatPrice(item.subtotal) }}</span>
    </div>

    <el-form label-position="top">
      <el-form-item label="售后类型">
        <el-radio-group v-model="type">
          <el-radio-button v-for="item in AFTER_SALE_TYPES" :key="item.value" :value="item.value">
            {{ item.label }}
          </el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="是否质量问题">
        <el-switch v-model="qualityIssue" active-text="是" inactive-text="否" @change="runPreview" />
        <span class="hint">质量问题的处理期限更长，但需要审核</span>
      </el-form-item>

      <!-- 填表之前先给出政策结论，而不是提交完才被拒 -->
      <el-alert
        v-if="preview"
        :type="preview.eligible ? 'success' : 'warning'"
        :closable="false"
        show-icon
        class="preview"
      >
        <template #title>
          <span v-if="preview.eligible">
            可申请售后，最多可退 {{ formatPrice(preview.maxRefundAmount) }}
          </span>
          <span v-else>{{ preview.reason }}</span>
        </template>
        <template v-if="preview.requirements" #default>
          <span class="preview__req">{{ preview.requirements }}</span>
          <span v-if="preview.docRef" class="preview__doc">（依据 {{ preview.docRef }}）</span>
        </template>
      </el-alert>
      <el-skeleton v-else-if="previewing" :rows="1" animated class="preview" />

      <el-form-item label="售后原因">
        <el-select v-model="reason" placeholder="请选择" class="full">
          <el-option v-for="item in AFTER_SALE_REASONS" :key="item" :label="item" :value="item" />
        </el-select>
      </el-form-item>

      <el-form-item label="问题描述（选填）">
        <el-input
          v-model="description"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="描述一下具体问题，有助于审核"
        />
      </el-form-item>

      <el-form-item label="图片地址（选填，一行一个）">
        <el-input
          v-model="imagesText"
          type="textarea"
          :rows="2"
          placeholder="演示环境暂未接入文件上传，可直接粘贴图片 URL"
        />
        <!-- 图片无法当场校验 URL 是否有效，至少让用户看到「提交的到底是什么」 -->
        <div v-if="images.length" class="thumbs">
          <img v-for="url in images" :key="url" :src="url" :alt="`凭证 ${url}`" class="thumbs__item" />
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!preview?.eligible || !reason"
        @click="submit"
      >
        提交申请
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.goods {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr) auto;
  gap: var(--ys-space-3);
  align-items: center;
  padding: var(--ys-space-3);
  margin-bottom: var(--ys-space-4);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
}

.goods img {
  width: 48px;
  height: 48px;
  border-radius: var(--ys-radius-sm);
  object-fit: cover;
}

.goods__name {
  font-weight: 600;
}

.goods__spec {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.goods__amount {
  color: var(--color-primary);
  font-weight: 600;
}

.hint {
  margin-left: var(--ys-space-3);
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.preview {
  margin-bottom: var(--ys-space-4);
}

.preview__req {
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.preview__doc {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.full {
  width: 100%;
}

.thumbs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
  margin-top: var(--ys-space-2);
}

.thumbs__item {
  width: 56px;
  height: 56px;
  border-radius: var(--ys-radius-sm);
  border: 1px solid var(--color-border-subtle);
  object-fit: cover;
}
</style>
