<script setup lang="ts">
import {
  chatStream,
  deleteSession,
  dismissApproval,
  fetchSessionMessages,
  fetchSessions,
  type ChatSessionSummary,
  type ToolProgressEvent,
} from '@/api/ai'
import ChatMessageList from '@/components/ai/ChatMessageList.vue'
import ChatSessionList from '@/components/ai/ChatSessionList.vue'
import QuickPromptBar from '@/components/ai/QuickPromptBar.vue'
import { useUserStore } from '@/stores'
import type { ChatMessage, ProductSummary } from '@/types/models'
import { ChatLineRound, Discount, Goods, Van } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const userStore = useUserStore()

// ==================== 会话状态 ====================

const sessions = ref<ChatSessionSummary[]>([])
const sessionsLoading = ref(true)
/** null 表示"新对话还没落号"：会话号在第一条消息发出时才生成，空会话不进侧栏 */
const activeSessionId = ref<string | null>(null)
const messages = ref<ChatMessage[]>([])
const loading = ref(false)
/**
 * 流式期间的工具实时进度（执行中 → 结果态）。
 * <p>
 * `start` 事件原样入列（phase 即「执行中」），对应的 `finish` 到达时就地替换成结果事件——
 * 不进位、不重排，用户看到的是同一枚 chip 从「执行中」落到「完成」。
 * 同一工具并发调用（如一次问句同时查订单与物流）按 FIFO 配对：每条 finish 落到
 * 最早一个还在跑的同类 chip 上，配错会让两枚 chip 的耗时张冠李戴。
 */
const liveTools = ref<ToolProgressEvent[]>([])
/** 切换会话时正在拉历史。与 loading 分开：那是"模型在写"，这是"历史在载"，界面提示不同 */
const bootstrapping = ref(false)
const input = ref('')
const drawerOpen = ref(false)

/**
 * 输入框随内容长高（封顶交给 CSS 的 max-height，这里只负责量）。
 * 一行高的框里滚动多行提问，是聊天输入框最容易被识破的一处——
 * placeholder 还在旁边写着「Shift+Enter 换行」，等于邀请人当场试。
 */
const composerRef = ref<HTMLTextAreaElement | null>(null)
function autosize() {
  const el = composerRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${el.scrollHeight}px`
}

const activeSession = computed(
  () => sessions.value.find((item) => item.sessionId === activeSessionId.value) ?? null,
)

/**
 * 会话指针跟着用户存在 sessionStorage，而不是 localStorage：
 * 关掉标签页就结束一段浏览是符合直觉的，下次进来从最近一段继续即可（服务端有完整历史）。
 * 按 userId 分开，避免换账号后打开别人的会话。
 */
const SESSION_KEY = 'envoymart.ai.session'
function sessionPointerKey() {
  return `${SESSION_KEY}.${userStore.profile?.id ?? 'anonymous'}`
}

/** 会话号形如 `alice-1759280000000`，带上用户便于服务端排查"这段对话是谁的" */
function newSessionId() {
  const owner = userStore.profile?.id ?? 'anonymous'
  return `${owner}-${Date.now()}`
}

/**
 * 用户在输入法里打字时的 Enter 是「选词」，不是「发送」。
 * 不挡这一步，中文用户每选一次词就多发一条消息 —— 这是中文输入场景最常见的坑，
 * `isComposing` 与 keyCode 229 是两个浏览器纪元的不同信号，都要认。
 */
function isComposing(event: KeyboardEvent) {
  return event.isComposing || event.keyCode === 229
}

/**
 * 刷新序号：删除会话与"这一轮结束后的自愈刷新"可能同时在飞，
 * 先发后到的旧快照会把刚删掉的行放回侧栏。只应用最新一次请求的结果。
 */
let refreshToken = 0

async function refreshSessions() {
  const token = ++refreshToken
  try {
    const list = await fetchSessions()
    if (token !== refreshToken) return
    sessions.value = list
  } catch {
    // 拦截器已提示；侧栏保持原样比清空好 —— 清空看起来像"会话被删了"
  } finally {
    if (token === refreshToken) {
      sessionsLoading.value = false
    }
  }
}

/**
 * 把服务端历史摊回界面消息。当轮响应里的引用、工具轨迹、用量一并还原。
 * <p>
 * 确认内容也是历史的一部分；是否能执行仍由服务端校验签名、归属与有效期。
 */
function toChatMessage(stored: Awaited<ReturnType<typeof fetchSessionMessages>>[number]): ChatMessage {
  const response = stored.response
  return {
    id: stored.id,
    role: stored.role,
    turnId: stored.turnId ?? response?.turnId ?? undefined,
    content: stored.content,
    at: stored.at,
    knowledge: response?.knowledge?.length ? response.knowledge : undefined,
    toolCalls: response?.toolCalls?.length ? response.toolCalls : undefined,
    recommendedProducts: response?.recommendedProducts?.length
      ? response.recommendedProducts
      : undefined,
    pendingPayments: response?.pendingPayments?.length ? response.pendingPayments : undefined,
    pendingActions: response?.pendingActions ?? undefined,
    pendingActionDetails: response?.pendingActionDetails ?? undefined,
    approvalToken: response?.approvalToken ?? undefined,
    approvalStatus: response?.approvalStatus ?? (response?.approvalToken ? 'PENDING' : 'HISTORY'),
    stage: response?.stage,
    evidenceLevel: response?.evidenceLevel ?? undefined,
    retrievalQuery: response?.retrievalQuery ?? undefined,
    expansion: response?.expansion?.applied ? response.expansion : undefined,
    unsupportedClaims: response?.unsupportedClaims?.length ? response.unsupportedClaims : undefined,
    unsupportedStripped: response?.unsupportedStripped,
    ungrounded: response?.ungrounded,
    factMismatches: response?.factMismatches?.length ? response.factMismatches : undefined,
    factStripped: response?.factStripped,
    conflicts: response?.conflicts?.length ? response.conflicts : undefined,
    usage: response?.usage ?? undefined,
  }
}

/** 切换会话的令牌：连续快点两下时，先发的请求回来晚了不能覆盖后选的那个会话 */
let loadToken = 0

async function selectSession(sessionId: string) {
  if (sessionId === activeSessionId.value) {
    drawerOpen.value = false
    return
  }
  abortStream()
  const token = ++loadToken
  activeSessionId.value = sessionId
  sessionStorage.setItem(sessionPointerKey(), sessionId)
  drawerOpen.value = false
  bootstrapping.value = true
  messages.value = []
  try {
    const stored = await fetchSessionMessages(sessionId)
    if (token !== loadToken) return
    messages.value = stored.map(toChatMessage)
    for (let index = 0; index < messages.value.length; index++) {
      const current = messages.value[index]
      if (!current?.pendingActions?.length) continue
      const following = messages.value.slice(index + 1).find((item) => item.role === 'user')
      if (current.approvalStatus !== 'DISMISSED' && following && CONFIRM_PATTERN.test(following.content.trim())) current.approvalStatus = 'CONFIRMED'
    }
    await scrollToBottom()
  } catch {
    if (token !== loadToken) return
    messages.value = []
  } finally {
    if (token === loadToken) {
      bootstrapping.value = false
    }
  }
}

function startNewSession() {
  abortStream()
  // loadToken 自增让在飞的历史请求作废，但它自己的 finally 也因令牌不匹配而不再复位 ——
  // 少了这一行，加载中切到新对话会永远停在骨架屏上
  loadToken++
  bootstrapping.value = false
  activeSessionId.value = null
  sessionStorage.removeItem(sessionPointerKey())
  messages.value = []
  drawerOpen.value = false
  input.value = ''
}

async function handleDelete(sessionId: string) {
  const target = sessions.value.find((item) => item.sessionId === sessionId)
  try {
    await ElMessageBox.confirm(
      `删除会话「${target?.title ?? sessionId}」？聊天记录将一并删除，且不可恢复。`,
      '删除会话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    // 用户取消。ElMessageBox 用 reject 表达取消，不是错误
    return
  }
  try {
    await deleteSession(sessionId)
  } catch {
    // 可能已被别处删掉（404）。刷新列表让界面与服务端对齐，而不是假装还成功
    await refreshSessions()
    return
  }
  sessions.value = sessions.value.filter((item) => item.sessionId !== sessionId)
  if (sessionId === activeSessionId.value) {
    const next = sessions.value[0]
    if (next) {
      await selectSession(next.sessionId)
    } else {
      startNewSession()
    }
  }
}

// ==================== 滚动锚定 ====================

const scrollRef = ref<HTMLElement | null>(null)
/**
 * 是否贴着底部。用"事件驱动"而不是"每次渲染都滚到底"：
 * 用户向上翻看历史时新内容到达，强制滚底会把他正在读的那段顶走 ——
 * 这是聊天界面最讨厌的体验之一。
 */
const pinnedToBottom = ref(true)
const showJumpToBottom = computed(() => !pinnedToBottom.value && messages.value.length > 0)

function onScroll() {
  const el = scrollRef.value
  if (!el) return
  pinnedToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

async function scrollToBottom(smooth = false) {
  await nextTick()
  const el = scrollRef.value
  if (!el) return
  el.scrollTo({
    top: el.scrollHeight,
    behavior:
      smooth && !window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'smooth' : 'auto',
  })
  pinnedToBottom.value = true
}

/** 流式增量到达时调用：只在用户本来就贴着底部时跟随 */
function followIfPinned() {
  if (pinnedToBottom.value) {
    void scrollToBottom()
  }
}

/**
 * 内容变高不一定伴随 scroll 事件：代码块高亮、表格渲染、引用面板展开、字体落位
 * 都会让内容长高，而此刻 scrollTop 没变——用户明明还看着最后一行，
 * 视口却已经不在底部了，流式结束后正文后面空出一截。
 * ResizeObserver 补的正是这一类「不是滚动引起的滚动位置漂移」。
 */
let contentObserver: ResizeObserver | null = null

function observeContent() {
  const inner = scrollRef.value?.querySelector('.chat-inner')
  if (!inner || typeof ResizeObserver === 'undefined') return
  contentObserver?.disconnect()
  contentObserver = new ResizeObserver(() => followIfPinned())
  contentObserver.observe(inner)
}

// ==================== 发送 / 流式 ====================

// 四条示例各对应一种能力（商品检索 / 知识问答 / 订单物流 / 知识图谱），
// 问题贴着商品库的真实品类写——示例点下去必须真的答得上来，它是能力说明不是装饰
const prompts = [
  { icon: Goods, label: '挑商品', text: '推荐一款适合送长辈的钙片' },
  { icon: Discount, label: '问活动', text: '平台的满减活动规则是什么' },
  { icon: Van, label: '查物流', text: '帮我查一下最近一笔订单到哪了' },
  { icon: ChatLineRound, label: '问知识', text: '维生素 D 和钙片能一起吃吗' },
]

/**
 * 用户手打确认词时，要替他点那张卡片——把令牌带上重发。
 * <p>
 * 服务端的确认提示就写着「回复「确认执行」」，而令牌是随请求走的独立字段——
 * 前端不翻译这句话，它就会以一次普通提问重发，**再次撞上同一个闸口**，
 * 用户看到的是一模一样的提示，永远取消不掉订单。
 * <p>
 * 判据收得很紧：必须是「最新一条助手消息正挂着待确认」**且**「整句就是一个确认词」。
 * 不做成全局关键词——那样用户在别的语境里回一句「好的」，就可能批准一次高危操作。
 */
const CONFIRM_PATTERN = /^(确认|确认执行|确定|是|好的|好|yes|ok)[。！!，,]*$/i

/** 当前在飞的流。用户点「停止」或离开页面时用它取消 */
let abortController: AbortController | null = null
/**
 * 流的代号。切换会话/停止时代号自增，旧流的收尾代码据此判断"我已经不是当前那轮了" ——
 * 否则旧流结束时会把新一轮的 loading 错误地清掉，发送键提前复活，放出第二条并发流。
 */
let streamSeq = 0

function abortStream() {
  abortController?.abort()
  abortController = null
  streamSeq++
  loading.value = false
  // 停止后不会再有 finish 到达，挂着「执行中」的 chip 会永远转下去——立即收起
  liveTools.value = []
}

async function sendMessage(message = input.value, approvalToken?: string) {
  const content = message.trim()
  if (!content) return
  // 上一轮还在生成时不接新的发送：两条流会各自往自己的占位消息里写，界面看似正常，
  // 但令牌是随消息走的——确认词可能被配到错误的那一轮上
  if (loading.value) return
  // 历史还在加载时也不接：`selectSession` 拿到历史后会整体替换消息列表，
  // 此刻发出去的消息会连同回复一起被覆盖掉——界面上凭空消失，服务端却照常生成
  if (bootstrapping.value) return

  // 手打确认词等价于点那张卡片：把最新一条助手消息挂着的令牌取来带上。
  // 拿不到令牌（卡片已经收起、或用户只是碰巧说了声「好」）就只是一次普通提问，
  // 服务端会照常重新规划——撞上闸口就再出一次卡，这是正确的保守行为
  const latest = messages.value[messages.value.length - 1]
  const token =
    approvalToken ??
    (Boolean(latest?.pendingActions?.length) && CONFIRM_PATTERN.test(content)
      ? latest?.approvalToken
      : undefined)

  // 新会话在第一条消息发出时才落号，空会话不进侧栏
  if (!activeSessionId.value) {
    activeSessionId.value = newSessionId()
    sessionStorage.setItem(sessionPointerKey(), activeSessionId.value)
  }
  const sessionId = activeSessionId.value

  const now = new Date().toISOString()
  messages.value.push({
    id: `user-${Date.now()}`,
    role: 'user',
    content,
    at: now,
  })
  input.value = ''
  await nextTick(autosize)
  loading.value = true
  const seq = ++streamSeq
  liveTools.value = []
  abortController = new AbortController()
  await scrollToBottom()

  // 先插入占位的助手消息，随后按流式增量填充
  const assistantMessage = reactive<ChatMessage>({
    id: `assistant-${Date.now()}`,
    role: 'assistant',
    content: '',
    at: now,
  })
  messages.value.push(assistantMessage)

  await runStream(sessionId!, content, assistantMessage, seq, { approvalToken: token })
}

/**
 * 重新生成：把这一条回答就地重写，不新增一轮对话。
 * <p>
 * 界面与服务端说同一句话（`regenerate=true`）：用户点「重新生成」期待的是答案重写，
 * 不是把同一句话再问一遍——后者会在对话里凭空多出一个一模一样的问句，
 * 刷新后还能看到第二遍，像回声。
 * <p>
 * 只在最后一条回答上出现（按钮由 ChatMessageList 控制）：中间某条重写会让它
 * 后面的回答全部对不上它，那是一次分叉，不是一次重试。
 */
async function regenerate(assistantId: string) {
  if (loading.value || bootstrapping.value) return
  const index = messages.value.findIndex((item) => item.id === assistantId)
  const target = messages.value[index]
  if (!target || target.role !== 'assistant') return
  // 这条回答对应的问题是它前面最近的一条用户消息
  const prompt = messages.value.slice(0, index).reverse().find((item) => item.role === 'user')
  const sessionId = activeSessionId.value
  if (!prompt || !sessionId) return

  const assistantMessage = target
  Object.assign(assistantMessage, {
    content: '',
    at: new Date().toISOString(),
    knowledge: undefined,
    toolCalls: undefined,
    recommendedProducts: undefined,
    pendingPayments: undefined,
    pendingActions: undefined,
    pendingActionDetails: undefined,
    approvalToken: undefined,
    approvalStatus: undefined,
    error: undefined,
    stopped: undefined,
  })

  loading.value = true
  const seq = ++streamSeq
  liveTools.value = []
  abortController = new AbortController()
  await scrollToBottom()

  await runStream(sessionId, prompt.content, assistantMessage, seq, { regenerate: true })
}

/**
 * sendMessage 与 regenerate 共用的流式主体：事件分发与收尾。
 * 两条路各抄一份的代价不是重复本身，而是将来只在其中一份上修 bug——
 * 「断流保留半截回答」这类处理恰好是每条路都需要的那种。
 */
async function runStream(
  sessionId: string,
  content: string,
  assistantMessage: ChatMessage,
  seq: number,
  options: { approvalToken?: string; regenerate?: boolean } = {},
) {
  try {
    await chatStream(
      {
        sessionId,
        message: content,
        approvalToken: options.approvalToken,
        regenerate: options.regenerate,
      },
      {
        onDelta: (text) => {
          assistantMessage.content += text
          followIfPinned()
        },
        onTool: (event) => {
          if (event.phase === 'start') {
            liveTools.value.push({ phase: 'start', tool: event.tool })
          } else {
            // FIFO 配对：finish 落到最早一个还在跑的同类 chip 上（见 liveTools 注释）
            const index = liveTools.value.findIndex(
              (item) => item.phase === 'start' && item.tool === event.tool,
            )
            if (index >= 0) {
              liveTools.value[index] = event
            } else {
              // 理论上 start 必然先到；真出现孤立的 finish，也要让它可见，
              // 而不是因为「没有配对对象」把一次真实执行吞掉
              liveTools.value.push(event)
            }
          }
          followIfPinned()
        },
        onDone: (response) => {
          // done 事件里的身份是服务端唯一真相：替换本地占位 ID，后续点踩、撤销和刷新都用同一值。
          const assistantIndex = messages.value.findIndex((item) => item === assistantMessage)
          if (response.assistantMessageId) {
            assistantMessage.id = response.assistantMessageId
          }
          assistantMessage.turnId = response.turnId ?? undefined
          if (response.userMessageId && assistantIndex >= 0) {
            const userMessage = messages.value
              .slice(0, assistantIndex)
              .reverse()
              .find((item) => item.role === 'user')
            if (userMessage) {
              userMessage.id = response.userMessageId
              userMessage.turnId = response.turnId ?? undefined
            }
          }
          assistantMessage.content = response.reply || assistantMessage.content
          assistantMessage.knowledge = response.knowledge
          assistantMessage.toolCalls = response.toolCalls
          assistantMessage.recommendedProducts = response.recommendedProducts
          // 非空即本轮被中断：回复是确认提示，没有任何工具真正执行过
          assistantMessage.pendingPayments = response.pendingPayments?.length
            ? response.pendingPayments
            : undefined
          assistantMessage.pendingActions = response.pendingActions ?? undefined
          assistantMessage.pendingActionDetails = response.pendingActionDetails ?? undefined
          // 卡片与令牌同生共死：只留卡片不留令牌，用户点确认时无从证明自己批的是哪一次
          assistantMessage.approvalToken = response.approvalToken ?? undefined
          // 任务阶段。它与 pendingActions 表达同一件事的两个层次：阶段说「停在哪里」，
          // 载荷说「停下的是哪几次调用」。两者都留一份，是为了将来新增
          // 「正在核对」这类没有卡片的中间态时，前端不必再改一次数据流
          assistantMessage.stage = response.stage ?? undefined
          assistantMessage.evidenceLevel = response.evidenceLevel ?? undefined
          assistantMessage.retrievalQuery = response.retrievalQuery ?? undefined
          assistantMessage.expansion = response.expansion?.applied ? response.expansion : undefined
          // 后置校验的两项结果。流式下 delta 已经渲染过了，`content` 的赋值在上面
          // ——它会把没有出处的句子擦掉，用户看到的最终文本与校验结果是一致的
          assistantMessage.unsupportedClaims = response.unsupportedClaims ?? undefined
          assistantMessage.unsupportedStripped = response.unsupportedStripped
          assistantMessage.ungrounded = response.ungrounded
          // 事实核对是最后一道关，它删掉的句子同样已经在 `content` 里没了
          assistantMessage.factMismatches = response.factMismatches ?? undefined
          assistantMessage.factStripped = response.factStripped
          assistantMessage.conflicts = response.conflicts ?? undefined
          assistantMessage.usage = response.usage
          followIfPinned()
        },
        onError: (msg) => {
          // 断的是连接，不是已经流出来的正文：半截回答照留，错误另挂一条，
          // 用户读到一半的内容不会因为一次网络抖动整段消失
          assistantMessage.error = msg
        },
      },
      abortController!.signal,
    )
  } catch (e) {
    if (e instanceof Error && e.name === 'AbortError') {
      // 用户主动停止不是故障：半截回答照留，但必须标出来 ——
      // 不标的话它看起来像一段说完了的完整回答。标记单独存，不拼进正文：
      // 拼进去会被「复制」原样带走，粘出去的是一段带着舞台说明的文本
      assistantMessage.stopped = true
    } else {
      assistantMessage.error = assistantMessage.content
        ? '连接中断，回答可能不完整'
        : '智能助手暂时不可用，请稍后再试。'
    }
  } finally {
    if (seq === streamSeq) {
      loading.value = false
      abortController = null
      // 收尾不是立刻清空：最后一次工具调用刚落成「完成」，马上抹掉用户根本来不及看。
      // 留 600ms 再看一遍结果态，之后由正式的工具轨迹接管（停止/新一轮发送会自增 seq，
      // 这里到点也不会误清别人的 chip）
      window.setTimeout(() => {
        if (seq === streamSeq) {
          liveTools.value = []
        }
      }, 600)
    }
    // 刷新侧栏拿权威的标题与条数。**即使这一轮已被切换/停止作废也要刷**：
    // 服务端在流结束时才落历史，此刻列表里正是这一段会话的旧数据；
    // 不刷的话，侧栏会一直停在旧标题上，直到下一次发送才自愈
    void refreshSessions()
  }
}

function handleStop() {
  abortStream()
}

// 离开页面就取消在飞的流：不取消的话它会继续读，写进一个已经不在屏幕上的消息里
onBeforeUnmount(() => {
  abortStream()
  contentObserver?.disconnect()
  contentObserver = null
})

// ==================== 进场 ====================

onMounted(async () => {
  observeContent()
  await refreshSessions()
  const pointer = sessionStorage.getItem(sessionPointerKey())
  const target =
    sessions.value.find((item) => item.sessionId === pointer) ?? sessions.value[0] ?? null
  if (target) {
    await selectSession(target.sessionId)
  } else {
    sessionsLoading.value = false
  }
})

/**
 * 推荐卡片点开要进**商品详情**，不是拿商品名去搜索。
 * <p>
 * 原先跳的是 `/shop?keyword=<商品名>` —— 用户点的是一个具体商品，落到的却是一页
 * 搜索结果；商品名稍有出入（规格、副标题）就一条都搜不到，看到的是空列表。
 * 推荐卡片带着 id，直接进详情是唯一不会错的做法。
 */
function openProduct(product: ProductSummary) {
  router.push({ name: 'product-detail', params: { id: product.id } })
}

/** 先收起卡片再重发：新消息一入列，这张卡就不是「最新一条」，会立刻变成过期的灰态 */
function handleApprove() {
  const latest = messages.value[messages.value.length - 1]
  if (!latest?.approvalToken) return
  const token = latest.approvalToken
  latest.approvalToken = undefined
  latest.approvalStatus = 'CONFIRMED'
  void sendMessage('确认执行', token)
}

/**
 * 取消只是本地收起卡片，<b>不发任何请求</b>——服务端那次计划已经丢弃，没有副作用要撤销，
 * 也没有「拒绝」这个接口可调。
 * <p>
 * 但要把这条回复的正文改掉：它还写着「确认无误请点击确认执行」，
 * 而按钮已经没了。留着那句话，用户会以为是自己看漏了一个按钮。
 */
async function handleDismiss() {
  const latest = messages.value[messages.value.length - 1]
  if (latest && activeSessionId.value) {
    const records = await fetchSessionMessages(activeSessionId.value)
    const record = records.at(-1)
    if (!record?.response?.pendingActions?.length) return
    await dismissApproval(activeSessionId.value, record.id)
    latest.approvalToken = undefined
    latest.approvalStatus = 'DISMISSED'
    latest.content = '已取消，本次没有执行任何操作。'
  }
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && loading.value) {
    handleStop()
    return
  }
  if (event.key !== 'Enter' || isComposing(event)) return
  // Shift+Enter 是换行，交给输入框默认行为；其余 Enter（含 Ctrl+Enter）发送
  if (event.shiftKey) return
  event.preventDefault()
  void sendMessage()
}
</script>

<template>
  <div class="assistant-page">
    <aside class="assistant-sidebar" aria-label="会话侧栏">
      <header class="assistant-sidebar__head">
        <p class="eyebrow">EnvoyMart AI</p>
        <h2>对话</h2>
      </header>
      <ChatSessionList
        :sessions="sessions"
        :active-id="activeSessionId"
        :loading="sessionsLoading"
        @create="startNewSession"
        @select="selectSession"
        @remove="handleDelete"
      />
    </aside>

    <section class="assistant-chat">
      <header class="chat-header">
        <button
          type="button"
          class="chat-header__menu"
          aria-label="打开会话列表"
          @click="drawerOpen = true"
        >
          ☰
        </button>
        <div class="chat-header__title">
          <strong>{{ activeSession?.title ?? '新对话' }}</strong>
          <span>购物、活动、订单、售后都能问 · 回答逐条可溯源</span>
        </div>
        <div class="chat-header__actions">
          <el-button plain size="small" @click="router.push('/shop')">返回商城</el-button>
          <el-avatar :size="30" :src="userStore.profile?.avatar ?? undefined" />
        </div>
      </header>

      <div ref="scrollRef" class="chat-scroll" @scroll.passive="onScroll">
        <div class="chat-inner">
          <!-- 首屏：空态引导。把"能问什么"直接摆出来，而不是一句欢迎语 -->
          <div v-if="bootstrapping" class="chat-loading" aria-label="正在载入历史会话">
            <span v-for="i in 3" :key="i" class="chat-loading__bar" />
          </div>

          <div v-else-if="!messages.length" class="chat-empty">
            <span class="chat-empty__mark" aria-hidden="true">EM</span>
            <h1>今天想了解点什么？</h1>
            <p>
              商品推荐、活动规则、售后政策，或者订单与物流 —— 都可以直接问。
              回答会标注知识库出处，取消订单这类高危操作会先请你确认。
            </p>
            <QuickPromptBar :prompts="prompts" @select="sendMessage" />
          </div>

          <ChatMessageList
            v-else
            :messages="messages"
            :session-id="activeSessionId"
            :streaming-index="loading ? messages.length - 1 : -1"
            :live-tools="liveTools"
            :live-tools-index="liveTools.length ? messages.length - 1 : -1"
            @open-product="openProduct"
            @approve="handleApprove"
            @dismiss="handleDismiss"
            @regenerate="regenerate"
          />
        </div>

        <button
          v-if="showJumpToBottom"
          type="button"
          class="chat-jump"
          @click="scrollToBottom(true)"
        >
          ↓ 回到底部
        </button>
      </div>

      <footer class="composer">
        <div class="composer__box" :class="{ 'is-busy': loading }">
          <textarea
            ref="composerRef"
            v-model="input"
            class="composer__input"
            rows="1"
            placeholder="输入商品、活动、售后、订单或物流问题，Enter 发送，Shift+Enter 换行"
            aria-label="输入消息"
            @keydown="handleComposerKeydown"
            @input="autosize"
          />
          <div class="composer__actions">
            <span class="composer__hint">
              {{ loading ? '正在生成，Esc 可停止' : 'Enter 发送 · Shift + Enter 换行' }}
            </span>
            <!-- 生成中把发送换成停止：按钮同时承担「这一轮还没完」的状态提示 -->
            <el-button v-if="loading" plain @click="handleStop">停止生成</el-button>
            <!-- 历史载入中也置灰：sendMessage 会直接 return，按钮亮着等于骗用户点了个没反应的按钮 -->
            <el-button
              v-else
              type="primary"
              :disabled="!input.trim() || bootstrapping"
              @click="sendMessage()"
            >
              发送消息
            </el-button>
          </div>
        </div>
        <p class="composer__note">
          回答由 AI 生成，涉及订单与售后的关键操作会先请你确认；依据可在回答下方逐条核对。
        </p>
      </footer>
    </section>

    <!-- 窄屏下侧栏收进抽屉：对话内容优先，会话切换仍要够得着 -->
    <el-drawer
      v-model="drawerOpen"
      direction="ltr"
      size="280px"
      :with-header="false"
      class="assistant-drawer"
    >
      <div class="assistant-drawer__body">
        <ChatSessionList
          :sessions="sessions"
          :active-id="activeSessionId"
          :loading="sessionsLoading"
          @create="startNewSession"
          @select="selectSession"
          @remove="handleDelete"
        />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
/*
 * 页面占满头部以下的整个视口高度，内部两栏各自滚动 ——
 * 对话页的滚动应该发生在消息区里，而不是整页：整页滚的话输入框会被滚出屏幕。
 */
.assistant-page {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  height: calc(100vh - var(--layout-header-height));
  /* 移动端浏览器地址栏收放时 vh 不跟着变，输入框会被顶出可视区；dvh 跟着变 */
  height: calc(100dvh - var(--layout-header-height));
  background: var(--color-bg-page);
}

.assistant-sidebar {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-4);
  padding: var(--ys-space-5) var(--ys-space-4);
  border-inline-end: 1px solid var(--color-border);
  background: var(--color-bg-surface-muted);
  min-height: 0;
}

.assistant-sidebar__head {
  display: grid;
  gap: 2px;
}

.assistant-sidebar__head h2 {
  margin: 0;
  font-size: var(--ys-font-lg);
}

.eyebrow {
  margin: 0;
  color: var(--color-primary-strong);
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  font-weight: 700;
}

.assistant-chat {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: var(--color-bg-surface);
}

.chat-header {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  padding: var(--ys-space-3) var(--ys-space-5);
  border-bottom: 1px solid var(--color-border);
  background: color-mix(in srgb, var(--color-bg-surface) 88%, transparent);
  backdrop-filter: blur(12px);
}

.chat-header__menu {
  display: none;
  width: 32px;
  height: 32px;
  border: var(--card-border);
  border-radius: var(--ys-radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--ys-font-md);
  cursor: pointer;
}

.chat-header__menu:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

.chat-header__title {
  display: grid;
  gap: 1px;
  min-width: 0;
  flex: 1;
}

.chat-header__title strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--ys-font-base);
}

.chat-header__title span {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-header__actions {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
}

.chat-scroll {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.chat-inner {
  max-width: 860px;
  margin: 0 auto;
  padding: var(--ys-space-6) var(--ys-space-5) var(--ys-space-8);
}

.chat-loading {
  display: grid;
  gap: var(--ys-space-3);
  padding-block: var(--ys-space-6);
}

.chat-loading__bar {
  height: 72px;
  border-radius: var(--ys-radius-lg);
  background: var(--color-bg-surface-muted);
  animation: loading-pulse 1.2s ease-in-out infinite;
}

@keyframes loading-pulse {
  50% {
    opacity: 0.5;
  }
}

@media (prefers-reduced-motion: reduce) {
  .chat-loading__bar {
    animation: none;
  }
}

/* ==================== 空态 ==================== */

.chat-empty {
  display: grid;
  justify-items: center;
  text-align: center;
  gap: var(--ys-space-4);
  padding: clamp(40px, 12vh, 120px) var(--ys-space-4) 0;
}

.chat-empty__mark {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  border-radius: var(--ys-radius-lg);
  background: linear-gradient(135deg, var(--color-primary), var(--color-accent));
  color: var(--color-text-on-primary);
  font-weight: 800;
  letter-spacing: 0.04em;
  box-shadow: var(--ys-shadow-raised);
}

.chat-empty h1 {
  margin: 0;
  font-size: clamp(22px, 3vw, 28px);
}

.chat-empty p {
  margin: 0;
  max-width: 560px;
  color: var(--color-text-secondary);
  line-height: var(--ys-leading-loose);
}

.chat-empty :deep(.quick-prompts) {
  max-width: 640px;
}

/* ==================== 回到底部 ==================== */

.chat-jump {
  position: sticky;
  bottom: var(--ys-space-4);
  left: 50%;
  translate: -50% 0;
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
  margin-inline: auto;
  width: max-content;
  padding: var(--ys-space-2) var(--ys-space-4);
  border: var(--card-border);
  border-radius: var(--ys-radius-full);
  background: var(--color-bg-surface);
  color: var(--color-text-secondary);
  font-size: var(--ys-font-sm);
  cursor: pointer;
  box-shadow: var(--ys-shadow-dropdown);
  z-index: var(--ys-z-raised);
}

.chat-jump:hover {
  color: var(--color-primary-strong);
}

.chat-jump:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

/* ==================== 输入区 ==================== */

.composer {
  display: grid;
  gap: var(--ys-space-2);
  padding: var(--ys-space-3) var(--ys-space-5) var(--ys-space-4);
  border-top: 1px solid var(--color-border);
  background: var(--color-bg-surface);
}

.composer__box {
  display: grid;
  gap: var(--ys-space-2);
  max-width: 860px;
  width: 100%;
  margin: 0 auto;
  padding: var(--ys-space-3) var(--ys-space-4);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--ys-radius-lg);
  background: var(--color-bg-surface);
  transition:
    border-color var(--ys-duration-fast) var(--ys-ease-out),
    box-shadow var(--ys-duration-fast) var(--ys-ease-out);
}

.composer__box:focus-within {
  border-color: var(--color-border-focus);
  box-shadow: var(--focus-ring);
}

.composer__input {
  width: 100%;
  max-height: 168px;
  border: 0;
  background: transparent;
  color: var(--color-text-primary);
  font-family: inherit;
  font-size: var(--ys-font-base);
  line-height: var(--ys-leading-base);
  resize: none;
}

.composer__input:focus {
  outline: none;
}

.composer__input::placeholder {
  color: var(--color-text-muted);
}

.composer__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-3);
}

.composer__hint {
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.composer__note {
  max-width: 860px;
  width: 100%;
  margin: 0 auto;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
  text-align: center;
}

/* ==================== 响应式 ==================== */

/* 容器变窄先收侧栏：对话内容优先于会话列表 */
@media (max-width: 1023px) {
  .assistant-page {
    grid-template-columns: minmax(0, 1fr);
  }

  .assistant-sidebar {
    display: none;
  }

  .chat-header__menu {
    display: grid;
    place-items: center;
  }
}

@media (max-width: 640px) {
  .chat-inner {
    padding: var(--ys-space-4) var(--ys-space-3) var(--ys-space-6);
  }

  .composer {
    padding: var(--ys-space-3);
  }

  /* iOS 对字号 <16px 的输入控件会在聚焦时把整页放大，缩不回去；
     窄屏下单独把输入框提到 16px，其余排版仍走设计系统的 14px 基准 */
  .composer__input {
    font-size: 16px;
  }

  .composer__note {
    display: none;
  }
}
</style>

<style>
/* 抽屉挂在 body 下，作用域样式够不着 —— 用非 scoped 块包一层限定类名 */
.assistant-drawer .assistant-drawer__body {
  height: 100%;
  padding: var(--ys-space-5) var(--ys-space-4);
  display: flex;
  flex-direction: column;
  background: var(--color-bg-surface-muted);
}
</style>
