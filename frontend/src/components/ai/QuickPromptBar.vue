<script setup lang="ts">
defineProps<{
  prompts: string[]
}>()

const emit = defineEmits<{
  select: [prompt: string]
}>()
</script>

<template>
  <div class="quick-prompts">
    <el-button
      v-for="prompt in prompts"
      :key="prompt"
      round
      @click="emit('select', prompt)"
    >
      {{ prompt }}
    </el-button>
  </div>
</template>

<style scoped>
.quick-prompts {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
</style>
