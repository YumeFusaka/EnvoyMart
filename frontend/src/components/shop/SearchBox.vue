<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { getHotKeywords, suggestProducts } from '@/api/product'
import type { SuggestItem } from '@/types/models'

/**
 * 一次搜索的意图。
 *
 * 联想结果里既有「词」也有「物」：点品牌名和点商品名要去的地方不一样。
 * 这里只描述意图，**去哪里由调用方决定**——组件不知道 /shop 长什么样，
 * 换个页面复用时不至于把路由硬编码搬过去。
 */
export type SearchIntent =
  | { kind: 'keyword'; keyword: string }
  | { kind: 'product'; id: number }
  | { kind: 'brand'; id: number }
  | { kind: 'category'; id: number }

const keyword = defineModel<string>({ default: '' })

const props = withDefaults(defineProps<{ placeholder?: string }>(), {
  placeholder: '搜索商品、成分、品牌',
})

const emit = defineEmits<{ search: [intent: SearchIntent] }>()

const HISTORY_KEY = 'envoymart:search-history'
const HISTORY_MAX = 10
/** 联想是按键触发的旁路：抖动窗口太短会把「一次输入」打成十几个请求，太长则像卡住 */
const DEBOUNCE_MS = 200

const root = ref<HTMLElement | null>(null)
const inputRef = ref<HTMLInputElement | null>(null)
const open = ref(false)
const suggestions = ref<SuggestItem[]>([])
const hot = ref<string[]>([])
const history = ref<string[]>([])
const activeIndex = ref(-1)
/** 请求序号：慢的联想响应回来了也不许覆盖新输入的结果 */
let suggestSeq = 0
let debounceTimer: ReturnType<typeof setTimeout> | undefined

const listboxId = `search-suggest-${useId()}`
const optionId = (index: number) => `${listboxId}-opt-${index}`

/** 单字符中文就是一个完整的搜索意图（「肌」「钙」），单字符西文则不是 */
const trimmed = computed(() => keyword.value.trim())
const queryReady = computed(() => {
  const text = trimmed.value
  if (!text) {
    return false
  }
  return /[一-龥]/.test(text) || text.length >= 2
})

interface Option {
  key: string
  text: string
  kind: SuggestItem['type'] | 'HISTORY' | 'HOT' | 'RAW'
  id?: number
}

/**
 * 面板内容按输入状态二选一：空输入给「最近搜过 / 大家都在搜」，有输入给联想。
 *
 * 有输入时首项固定是「搜索『原样输入』」——联想的第一个候选未必是用户想要的，
 * 没有这一项，用户就只能挑一个别人替他决定的词。输入与候选完全一致时省掉它，
 * 否则会出现两行一模一样的文字。
 */
const options = computed<Option[]>(() => {
  const text = trimmed.value
  if (!text) {
    return [
      ...history.value.map((word) => ({ key: `h:${word}`, text: word, kind: 'HISTORY' as const })),
      ...hot.value.map((word) => ({ key: `k:${word}`, text: word, kind: 'HOT' as const })),
    ]
  }
  const exact = suggestions.value.some((item) => item.text.toLowerCase() === text.toLowerCase())
  return [
    ...(exact ? [] : [{ key: 'raw', text, kind: 'RAW' as const }]),
    ...suggestions.value.map((item) => ({
      key: `${item.type}:${item.id}`,
      text: item.text,
      kind: item.type,
      id: item.id,
    })),
  ]
})

const kindLabel: Record<Option['kind'], string> = {
  PRODUCT: '商品',
  BRAND: '品牌',
  CATEGORY: '类目',
  HISTORY: '历史',
  HOT: '热搜',
  RAW: '搜索',
}

/** 命中片段高亮。用插值渲染而不是 v-html —— 联想文本来自索引，不该有被当标签解析的机会 */
function highlight(text: string) {
  const query = trimmed.value.toLowerCase()
  const at = query ? text.toLowerCase().indexOf(query) : -1
  if (at < 0) {
    return [{ text, hit: false }]
  }
  return [
    { text: text.slice(0, at), hit: false },
    { text: text.slice(at, at + query.length), hit: true },
    { text: text.slice(at + query.length), hit: false },
  ].filter((part) => part.text)
}

function loadHistory() {
  try {
    const raw = localStorage.getItem(HISTORY_KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : []
    history.value = Array.isArray(parsed) ? parsed.filter((w) => typeof w === 'string') : []
  } catch {
    // 存储被禁用或内容被别的东西写坏时，宁可不显示历史，也不能让搜索框打不开
    history.value = []
  }
}

function remember(word: string) {
  const text = word.trim()
  if (!text) {
    return
  }
  // 去重后置顶：同一个词搜十次，历史里也只占一行
  const next = [text, ...history.value.filter((w) => w !== text)].slice(0, HISTORY_MAX)
  history.value = next
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(next))
  } catch {
    // 写不进去只影响下次，不影响这次搜索
  }
}

function clearHistory() {
  history.value = []
  try {
    localStorage.removeItem(HISTORY_KEY)
  } catch {
    // 同上：清不掉就下次再说
  }
}

async function fetchSuggest(text: string) {
  const seq = ++suggestSeq
  try {
    const items = await suggestProducts(text, 8)
    // 请求发出后用户又敲了键，这个结果已经过期
    if (seq === suggestSeq) {
      suggestions.value = items
    }
  } catch {
    if (seq === suggestSeq) {
      suggestions.value = []
    }
  }
}

async function fetchHot() {
  try {
    hot.value = await getHotKeywords(8)
  } catch {
    // 热门词是锦上添花，取不到就不显示这一栏，不打扰用户
    hot.value = []
  }
}

watch(keyword, (value) => {
  activeIndex.value = -1
  clearTimeout(debounceTimer)
  if (!queryReady.value) {
    suggestSeq++
    suggestions.value = []
    return
  }
  const text = value.trim()
  debounceTimer = setTimeout(() => fetchSuggest(text), DEBOUNCE_MS)
})

/** 点空白处收起面板。用 pointerdown 而不是 click：等 click 触发时输入框已经失焦，面板会闪一下 */
function onPointerDown(event: PointerEvent) {
  if (!root.value?.contains(event.target as Node)) {
    open.value = false
  }
}

function onFocus() {
  open.value = true
}

/**
 * 点输入框也要展开，不能只靠 focus。
 * <p>
 * 收起面板（Esc、或刚提交完一次搜索）之后焦点仍在输入框里，此时再点它**不会触发 focus**，
 * 面板就一直不出来——用户只能重新敲一个字。键盘用户尤其容易撞上：按完 Esc 想换个词，
 * 第一下点击像是失灵了。
 */
function onFieldClick() {
  open.value = true
}

function close() {
  open.value = false
  activeIndex.value = -1
}

function submit(intent: SearchIntent, word = '') {
  if (word) {
    remember(word)
  }
  close()
  emit('search', intent)
}

function choose(option: Option) {
  if (option.kind === 'PRODUCT' || option.kind === 'BRAND' || option.kind === 'CATEGORY') {
    // 点「物」不进历史：用户记住的是「我搜过什么词」，而点一个品牌名不是一次搜索。
    // 输入框里也不留这个词——落地后生效的筛选是品牌/类目，会显示在「已选」条件条上，
    // 输入框只反映 URL 上真实生效的关键词，两处口径一致才不会互相打架
    submit({ kind: option.kind.toLowerCase() as 'product' | 'brand' | 'category', id: option.id! })
    return
  }
  // 点热搜词/历史词等于用它发起一次搜索，输入框也要跟着变，
  // 否则用户回到商城页看到的仍是上次输入的词
  keyword.value = option.text
  submit({ kind: 'keyword', keyword: option.text })
}

function onSubmit() {
  const active = options.value[activeIndex.value]
  if (active) {
    choose(active)
    return
  }
  const text = trimmed.value
  if (text) {
    submit({ kind: 'keyword', keyword: text }, text)
  }
}

function move(delta: number) {
  if (!options.value.length) {
    return
  }
  open.value = true
  const next = activeIndex.value + delta
  // 循环而不是停在两端：从最后一项再按一下回到第一项，比「按不动」更容易理解
  activeIndex.value = next < 0 ? options.value.length - 1 : next % options.value.length
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    move(event.key === 'ArrowDown' ? 1 : -1)
  } else if (event.key === 'Enter') {
    event.preventDefault()
    onSubmit()
  } else if (event.key === 'Escape') {
    close()
  }
}

onMounted(() => {
  loadHistory()
  fetchHot()
  document.addEventListener('pointerdown', onPointerDown)
})

onBeforeUnmount(() => {
  clearTimeout(debounceTimer)
  document.removeEventListener('pointerdown', onPointerDown)
})

defineExpose({ focus: () => inputRef.value?.focus() })
</script>

<template>
  <div ref="root" class="search-box">
    <div class="search-box__field" :class="{ 'is-open': open }">
      <el-icon class="search-box__icon" aria-hidden="true"><Search /></el-icon>
      <input
        ref="inputRef"
        v-model="keyword"
        class="search-box__input"
        type="text"
        role="combobox"
        aria-autocomplete="list"
        :aria-expanded="open && options.length > 0"
        :aria-controls="listboxId"
        :aria-activedescendant="activeIndex >= 0 ? optionId(activeIndex) : undefined"
        :aria-label="props.placeholder"
        :placeholder="props.placeholder"
        @focus="onFocus"
        @click="onFieldClick"
        @keydown="onKeydown"
      />
      <button type="button" class="search-box__submit" aria-label="搜索" @click="onSubmit">
        <el-icon><Search /></el-icon>
      </button>
    </div>

    <div v-if="open && options.length" class="search-box__panel">
      <ul :id="listboxId" class="search-box__list" role="listbox" aria-label="搜索建议">
        <li
          v-for="(option, index) in options"
          :id="optionId(index)"
          :key="option.key"
          class="search-box__option"
          :class="{ 'is-active': index === activeIndex }"
          role="option"
          :aria-selected="index === activeIndex"
          @pointerdown.prevent="choose(option)"
          @mousemove="activeIndex = index"
        >
          <span class="search-box__kind" :data-kind="option.kind">{{ kindLabel[option.kind] }}</span>
          <span class="search-box__text">
            <span
              v-for="(part, partIndex) in highlight(option.text)"
              :key="partIndex"
              :class="{ 'is-hit': part.hit }"
              >{{ part.text }}</span
            >
          </span>
        </li>
      </ul>

      <div v-if="!trimmed && history.length" class="search-box__footer">
        <button type="button" class="search-box__clear" @click="clearHistory">清空历史</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.search-box {
  position: relative;
  flex: 1;
  /* 搜索框是电商首页最该被看见的控件，但不该长到横跨整个视口——1280 的版心里
     再放一条 900 宽的输入框，中间会空出两块没有内容的区域 */
  max-width: 560px;
}

.search-box__field {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  height: 38px;
  padding: 0 var(--ys-space-1) 0 var(--ys-space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-surface);
  transition:
    border-color var(--ys-duration-fast) var(--ys-ease-out),
    box-shadow var(--ys-duration-fast) var(--ys-ease-out);
}

.search-box__field:focus-within,
.search-box__field.is-open {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-subtle);
}

.search-box__icon {
  color: var(--color-text-muted);
}

.search-box__input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--color-text-primary);
  font-family: inherit;
  font-size: var(--ys-font-base);
}

.search-box__input::placeholder {
  color: var(--color-text-muted);
}

.search-box__submit {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: var(--ys-radius-full);
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  cursor: pointer;
  transition: background-color var(--ys-duration-fast) var(--ys-ease-out);
}

.search-box__submit:hover {
  background: var(--color-primary-hover);
}

.search-box__panel {
  position: absolute;
  inset-inline: 0;
  inset-block-start: calc(100% + var(--ys-space-2));
  z-index: var(--ys-z-dropdown);
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--ys-radius-md);
  background: var(--color-bg-surface);
  box-shadow: var(--ys-shadow-dropdown);
}

.search-box__list {
  max-height: 360px;
  margin: 0;
  padding: var(--ys-space-1) 0;
  overflow-y: auto;
  list-style: none;
}

.search-box__option {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  padding: 8px var(--ys-space-4);
  cursor: pointer;
}

/* 键盘和鼠标共用同一个高亮。只认键盘的话，鼠标划过时没有任何反馈 */
.search-box__option.is-active {
  background: var(--color-primary-subtle);
}

.search-box__kind {
  flex: none;
  padding: 1px 6px;
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-sunken);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-xs);
}

.search-box__kind[data-kind='BRAND'],
.search-box__kind[data-kind='CATEGORY'] {
  background: var(--color-accent-subtle);
  color: var(--color-accent-hover);
}

.search-box__kind[data-kind='HISTORY'],
.search-box__kind[data-kind='HOT'] {
  background: transparent;
  padding-inline: 0;
  color: var(--color-text-muted);
}

.search-box__text {
  overflow: hidden;
  color: var(--color-text-primary);
  font-size: var(--ys-font-base);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-box__text .is-hit {
  color: var(--color-primary);
  font-weight: 600;
}

.search-box__footer {
  display: flex;
  justify-content: flex-end;
  padding: var(--ys-space-2) var(--ys-space-4);
  border-top: 1px solid var(--color-border);
}

.search-box__clear {
  border: 0;
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  cursor: pointer;
}

.search-box__clear:hover {
  color: var(--color-primary);
}

@media (prefers-reduced-motion: reduce) {
  .search-box__field,
  .search-box__submit {
    transition: none;
  }
}

@media (max-width: 720px) {
  /* 窄屏优先保搜索框：品牌只剩图标，占的位置还给输入 */
  .search-box {
    max-width: none;
  }

  .search-box__kind {
    display: none;
  }
}
</style>
