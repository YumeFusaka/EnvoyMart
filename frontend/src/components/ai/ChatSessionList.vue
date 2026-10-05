<script setup lang="ts">
import type { ChatSessionSummary } from '@/api/ai'
import { formatChatStamp } from '@/utils/format'
import { computed } from 'vue'

const props = defineProps<{
  sessions: ChatSessionSummary[]
  activeId: string | null
  loading: boolean
}>()

const emit = defineEmits<{
  create: []
  select: [sessionId: string]
  remove: [sessionId: string]
}>()

/**
 * 按时间分桶。会话多了以后，平铺列表找一段昨天的对话要靠回忆时间，
 * 分组之后"最近在聊什么"一眼可见 —— 这是侧栏存在的意义。
 */
type Bucket = { label: string; items: ChatSessionSummary[] }

const buckets = computed<Bucket[]>(() => {
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const weekAgo = startOfToday - 6 * 24 * 3600 * 1000
  const result: Bucket[] = [
    { label: '今天', items: [] },
    { label: '近 7 天', items: [] },
    { label: '更早', items: [] },
  ]
  for (const session of props.sessions) {
    const stamp = new Date(session.updatedAt).getTime()
    if (Number.isNaN(stamp) || stamp >= startOfToday) {
      // 三个桶本函数内创建，下标一定存在
      result[0]!.items.push(session)
    } else if (stamp >= weekAgo) {
      result[1]!.items.push(session)
    } else {
      result[2]!.items.push(session)
    }
  }
  return result.filter((bucket) => bucket.items.length > 0)
})
</script>

<template>
  <div class="session-list">
    <el-button class="session-list__new" type="primary" plain @click="emit('create')">
      <span aria-hidden="true">＋</span> 新对话
    </el-button>

    <!-- 首屏加载给三个灰条占位：直接显示"暂无会话"会先闪一屏空态再跳回列表 -->
    <div v-if="loading" class="session-list__skeleton" aria-hidden="true">
      <span v-for="i in 3" :key="i" class="session-list__skeleton-bar" />
    </div>

    <p v-else-if="!sessions.length" class="session-list__empty">
      还没有历史会话。问点什么，这里会留下记录。
    </p>

    <nav v-else class="session-list__groups" aria-label="历史会话">
      <section v-for="bucket in buckets" :key="bucket.label">
        <h3 class="session-list__label">{{ bucket.label }}</h3>
        <ul>
          <li
            v-for="session in bucket.items"
            :key="session.sessionId"
            class="session-list__item"
            :class="{ 'is-active': session.sessionId === activeId }"
          >
            <button
              type="button"
              class="session-list__main"
              :aria-current="session.sessionId === activeId ? 'true' : undefined"
              @click="emit('select', session.sessionId)"
            >
              <!-- 标题会被省略号截断（侧栏只有 207px 宽），补一个原生 tooltip
                   让用户悬停能看到全文：截断本身是对的，但截断后无处可看就不对了 -->
              <span class="session-list__title" :title="session.title">{{ session.title }}</span>
              <span class="session-list__meta">
                {{ formatChatStamp(session.updatedAt) }} · {{ session.messageCount }} 条
              </span>
            </button>
            <button
              type="button"
              class="session-list__remove"
              :aria-label="`删除会话「${session.title}」`"
              @click="emit('remove', session.sessionId)"
            >
              ✕
            </button>
          </li>
        </ul>
      </section>
    </nav>
  </div>
</template>

<style scoped>
.session-list {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-4);
  min-height: 0;
}

.session-list__new {
  width: 100%;
}

.session-list__groups {
  overflow-y: auto;
  min-height: 0;
  display: grid;
  gap: var(--ys-space-4);
}

.session-list__label {
  margin: 0 0 var(--ys-space-2);
  padding-inline-start: var(--ys-space-2);
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  font-weight: 600;
}

.session-list__groups ul {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 2px;
}

.session-list__item {
  position: relative;
  border-radius: var(--ys-radius-md);
}

.session-list__item:hover {
  background: var(--color-bg-surface-muted);
}

.session-list__item.is-active {
  background: var(--color-primary-subtle);
}

.session-list__main {
  width: 100%;
  display: grid;
  gap: 2px;
  padding: var(--ys-space-2) var(--ys-space-2);
  /* 右侧给删除按钮留位：标题跑到删除按钮底下时，点击删除会误开会话 */
  padding-inline-end: var(--ys-space-8);
  border: 0;
  background: transparent;
  text-align: start;
  cursor: pointer;
  border-radius: var(--ys-radius-md);
}

.session-list__main:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.session-list__title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-text-primary);
  font-size: var(--ys-font-base);
}

.session-list__item.is-active .session-list__title {
  color: var(--color-primary-active);
  font-weight: 600;
}

.session-list__meta {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

/*
 * 删除按钮常态隐藏、悬停或键盘聚焦时出现：它是破坏性操作，
 * 常驻在每一行里会让人误点，也让侧栏显得密。
 * focus-within 保证纯键盘用户 Tab 到这一行时按钮就会出现、够得着。
 */
.session-list__remove {
  position: absolute;
  inset-block-start: 50%;
  inset-inline-end: var(--ys-space-2);
  translate: 0 -50%;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: var(--ys-radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--ys-duration-fast) var(--ys-ease-out);
}

.session-list__item:hover .session-list__remove,
.session-list__item:focus-within .session-list__remove {
  opacity: 1;
}

.session-list__remove:hover {
  background: var(--color-danger-subtle);
  color: var(--color-danger-strong);
}

.session-list__remove:focus-visible {
  opacity: 1;
  outline: none;
  box-shadow: var(--focus-ring);
}

/* 触屏没有 hover：藏起来的删除按钮等于不存在（看得见才点得到），常显 */
@media (hover: none) {
  .session-list__remove {
    opacity: 1;
  }
}

.session-list__empty {
  margin: 0;
  padding: 0 var(--ys-space-2);
  color: var(--color-text-muted);
  font-size: var(--ys-font-sm);
  line-height: var(--ys-leading-base);
}

.session-list__skeleton {
  display: grid;
  gap: var(--ys-space-2);
}

.session-list__skeleton-bar {
  height: 40px;
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface-muted);
  animation: skeleton-pulse 1.2s ease-in-out infinite;
}

@keyframes skeleton-pulse {
  50% {
    opacity: 0.5;
  }
}

@media (prefers-reduced-motion: reduce) {
  .session-list__skeleton-bar {
    animation: none;
  }
}
</style>
