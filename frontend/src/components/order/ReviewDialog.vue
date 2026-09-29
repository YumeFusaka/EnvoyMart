<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createReview } from '@/api/review'
import type { OrderItem } from '@/types/models'

const props = defineProps<{ modelValue: boolean; orderId: number; item: OrderItem | null }>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submitted'): void
}>()

const rating = ref(5)
const content = ref('')
const anonymous = ref(false)
const imagesText = ref('')
const submitting = ref(false)

async function submit() {
  if (!props.item) {
    return
  }
  if (rating.value < 1) {
    ElMessage.warning('请先打分')
    return
  }
  submitting.value = true
  try {
    const images = imagesText.value
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean)
      .slice(0, 9)

    await createReview({
      orderId: props.orderId,
      // 粒度是订单行：同一个人在不同订单里买同一件商品可以各评一次，
      // 同一笔订单里的同一行只能评一次
      orderItemId: props.item.id,
      rating: rating.value,
      content: content.value || undefined,
      images: images.length ? images : undefined,
      anonymous: anonymous.value,
    })
    ElMessage.success('评价已发布')
    emit('update:modelValue', false)
    emit('submitted')
  } finally {
    submitting.value = false
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      rating.value = 5
      content.value = ''
      anonymous.value = false
      imagesText.value = ''
    }
  },
)

/** 星级对应的文案，帮用户确认自己打的是几分 */
const RATING_TEXT = ['', '很差', '较差', '一般', '满意', '非常满意']
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="发表评价"
    width="520px"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div v-if="item" class="goods">
      <img v-if="item.skuImage" :src="item.skuImage" :alt="item.spuName" />
      <div>
        <p class="goods__name">{{ item.spuName }}</p>
        <p v-if="item.skuSpecText" class="goods__spec">{{ item.skuSpecText }}</p>
      </div>
    </div>

    <el-form label-position="top">
      <el-form-item label="评分">
        <div class="rating">
          <el-rate v-model="rating" show-text :texts="RATING_TEXT" />
        </div>
      </el-form-item>

      <el-form-item label="评价内容（选填）">
        <el-input
          v-model="content"
          type="textarea"
          :rows="4"
          maxlength="1000"
          show-word-limit
          placeholder="说说这件商品怎么样"
        />
      </el-form-item>

      <el-form-item label="图片地址（选填，一行一个，最多 9 张）">
        <el-input
          v-model="imagesText"
          type="textarea"
          :rows="2"
          placeholder="演示环境暂未接入文件上传，可直接粘贴图片 URL"
        />
      </el-form-item>

      <el-form-item>
        <el-checkbox v-model="anonymous">匿名评价</el-checkbox>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">发布评价</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.goods {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr);
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
</style>
