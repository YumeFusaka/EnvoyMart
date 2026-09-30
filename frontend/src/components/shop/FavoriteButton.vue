<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useFavoriteStore, useUserStore } from '@/stores'

const props = withDefaults(
  defineProps<{
    spuId: number
    /** 商品名，只用于无障碍标签，让读屏念出「收藏 深海鱼油软胶囊」而不是一个光秃秃的按钮 */
    label?: string
    /** 卡片上的小尺寸（浮在图上），详情页用默认尺寸 */
    small?: boolean
    /** 带文字。详情页放在「加入购物车」旁边，两颗都是圆按钮时只有一个能读出来是什么 */
    showText?: boolean
  }>(),
  { label: '', small: false, showText: false },
)

/**
 * 收藏状态**真的**变了才发（失败回滚过的不发）。
 * 收藏夹页据此把这一条从列表里移除——心形灰了但商品还杵在原地，用户会怀疑刚才那下生效没有。
 */
const emit = defineEmits<{ (e: 'change', favorited: boolean): void }>()

const favorite = useFavoriteStore()
const userStore = useUserStore()
const router = useRouter()
const busy = ref(false)
/** 只在用户真的点过之后才播动画：进页面时的心形会「跳」一下，那是装饰，不是反馈 */
const justToggled = ref(false)

const on = computed(() => favorite.isFavorited(props.spuId))
const actionText = computed(() => (on.value ? '取消收藏' : '收藏'))
const ariaLabel = computed(() =>
  props.label ? `${actionText.value} ${props.label}` : actionText.value,
)

onMounted(() => favorite.ensure([props.spuId]))

async function onClick() {
  if (!userStore.token) {
    ElMessage.info('登录后即可收藏商品')
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
    return
  }
  if (busy.value) return
  busy.value = true
  try {
    await favorite.toggle(props.spuId)
    justToggled.value = true
    emit('change', on.value)
    ElMessage.success(on.value ? '已加入收藏' : '已取消收藏')
  } catch (e) {
    // store 已经把心形回滚了，这里只负责说一句话
    ElMessage.error(e instanceof Error ? e.message : '操作失败，请稍后再试')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <button
    type="button"
    class="fav"
    :class="{ 'is-on': on, 'fav--sm': small, 'fav--text': showText, 'is-busy': busy }"
    :aria-pressed="on"
    :aria-label="ariaLabel"
    :title="actionText"
    @click.stop.prevent="onClick"
    @animationend="justToggled = false"
  >
    <svg
      class="fav__icon"
      :class="{ 'fav__icon--pop': justToggled }"
      viewBox="0 0 24 24"
      aria-hidden="true"
    >
      <path
        d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"
      />
    </svg>
    <span v-if="showText" class="fav__text">{{ on ? '已收藏' : '收藏' }}</span>
  </button>
</template>

<style scoped>
.fav {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
  /* 浮在商品图上，背景要挡住底下的图片，否则浅色图上心形看不清 */
  background: color-mix(in srgb, var(--color-bg-surface) 88%, transparent);
  backdrop-filter: blur(4px);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition:
    color var(--ys-duration-fast) var(--ys-ease-out),
    border-color var(--ys-duration-fast) var(--ys-ease-out),
    background-color var(--ys-duration-fast) var(--ys-ease-out);
}

.fav--sm {
  width: 30px;
  height: 30px;
}

/* 带文字时不再固定宽度：中文标签比图标长，定宽会把字挤出去 */
.fav--text {
  display: inline-flex;
  width: auto;
  height: 40px;
  gap: 6px;
  padding: 0 var(--ys-space-4);
  border-radius: var(--ys-radius-md);
  font-size: var(--ys-font-base);
  font-weight: 600;
}

.fav:hover {
  border-color: var(--color-primary-border);
  color: var(--color-primary);
}

.fav:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.fav.is-on {
  border-color: var(--color-primary-border);
  background: var(--color-primary-subtle);
  color: var(--color-primary);
}

.fav.is-busy {
  /* 只降透明度、不禁用：禁用的按钮会丢焦点，键盘用户点完一次就得重新 Tab 回来 */
  opacity: 0.7;
}

.fav__icon {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.fav.is-on .fav__icon {
  fill: currentColor;
}

.fav__icon--pop {
  animation: fav-pop var(--ys-duration-base) var(--ys-ease-out);
}

@keyframes fav-pop {
  0% {
    transform: scale(1);
  }
  40% {
    transform: scale(1.35);
  }
  100% {
    transform: scale(1);
  }
}

/* 开了「减少动态效果」就不播：这是装饰性反馈，不是状态信息——状态由填充色表达 */
@media (prefers-reduced-motion: reduce) {
  .fav__icon--pop {
    animation: none;
  }
}
</style>
