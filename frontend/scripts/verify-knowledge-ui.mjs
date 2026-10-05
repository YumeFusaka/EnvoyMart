/**
 * 知识层两个公开页面的端到端验收：关系图谱 + 知识文档（含溯源跳转）。
 *
 * 为什么单开一个脚本：知识层的全部说服力在一个闭环上——「图谱上有一条边 →
 * 点开它的逐字引文 → 跳到原文并被精确高亮」。三段里任何一段接线错了，
 * 页面都照样好看：边画不出来只是空画布、引文链接错只是跳错文档、
 * 高亮锚点对不上只是没有 <mark>。构建期发现不了任何一个。
 *
 * 分三段：
 *   一、图谱页 —— 规模数字、画布实体、依据清单的逐字引文
 *   二、溯源跳转 —— 从边上的「查看原文」落到文档页，且锚点高亮真的出现
 *   三、文档页 —— 切片目录联动、悬空锚点必须显式报警
 *
 * 数据是演示语料（种子数据），实体名是稳定的；断言里的名字跟着种子走。
 *
 * 前置条件：后端九个服务 + 前端 dev server 已启动。
 *
 * 用法：
 *   node scripts/verify-knowledge-ui.mjs
 */
import { chromium } from 'playwright-core'

const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const CHROMIUM =
  process.env.PLAYWRIGHT_CHROMIUM ??
  'C:/Users/j/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe'

let pass = 0
let fail = 0
function ck(name, condition, detail = '') {
  if (condition) {
    console.log(`  \x1b[32mPASS\x1b[0m ${name}`)
    pass += 1
  } else {
    console.log(`  \x1b[31mFAIL\x1b[0m ${name}${detail ? `\n        ${detail}` : ''}`)
    fail += 1
  }
}

async function poll(fn, predicate, timeoutMs = 15000, stepMs = 400) {
  let last
  for (let waited = 0; waited <= timeoutMs; waited += stepMs) {
    last = await fn()
    if (predicate(last)) {
      return last
    }
    await new Promise((resolve) => setTimeout(resolve, stepMs))
  }
  return last
}

const browser = await chromium.launch({ executablePath: CHROMIUM })
const context = await browser.newContext({ viewport: { width: 1600, height: 1200 } })
const page = await context.newPage()
const consoleErrors = []
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()))
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message))
/** 匿名访问：知识层是公开的，全程不带登录态 */
const hashOf = () => new URL(page.url()).hash.replace(/^#/, '')

// ==================== 一、图谱页 ====================
console.log('\n一、图谱页：规模、画布与依据清单')

await page.goto(`${BASE}/#/knowledge/graph`, { waitUntil: 'networkidle' })
await page.reload({ waitUntil: 'networkidle' })
await page.locator('svg.canvas').waitFor({ state: 'visible', timeout: 20000 })

// 默认中心实体必须来自图谱自己的数据，而不是写死的演示名。
// 写死过一次（SAMPLES[0]='SPU7'），SPU 改名后它把整页带进「图谱里没有收录」的空态——
// 页面看着像坏了，实际只是默认值过期。这条断言把「默认值不得指向不存在的实体」钉住：
// 它不检查具体挑到谁（那会随语料变），只要求挑到的那个在图谱里真的存在。
// 默认值由 resolveDefaultRoot() 异步挑出并 router.replace 写进 URL，所以等的是
// 「URL 上真的出现 root 且它在图谱里存在」，不是一个固定时长——固定 sleep 在机器忙时
// 会读到还没写进 URL 的那一版，红灯就成了一次假失败。
const resolvedRoot = await poll(
  () => new URLSearchParams(new URL(page.url()).hash.split('?')[1] ?? '').get('root') ?? '',
  (r) => Boolean(r),
  15000,
)
const rootExists = resolvedRoot
  ? (
      (
        await (
          await fetch(`${GW}/knowledge/graph/search?keyword=${encodeURIComponent(resolvedRoot)}`)
        ).json()
      ).data ?? []
    ).some((h) => h.name.toLowerCase() === resolvedRoot.toLowerCase())
  : false
ck(
  `默认中心实体在图谱里真实存在（root=${resolvedRoot}）`,
  Boolean(resolvedRoot) && rootExists,
  `root=${resolvedRoot} 查不到——默认值写死了过期实体，页面会空转`,
)

// 与接口对账，而不是比对写死的数字。
// 原先钉的是「39 实体 / 37 关系」——图谱重建过一次之后就一直是红的，
// 而页面上写的与接口给的完全一致。钉死数据量的断言，坏掉的是断言不是产品，
// 更糟的是它会训练人忽略红灯
const stats = await (await fetch(`${GW}/knowledge/graph/stats`)).json()
const scale = (await page.locator('.graph-scale').textContent()) ?? ''
ck(
  `规模数字与接口一致（${stats.data.entities} 实体 / ${stats.data.relations} 关系）`,
  scale.includes(String(stats.data.entities)) && scale.includes(String(stats.data.relations)),
  scale.replace(/\s+/g, ' ').trim(),
)

const ariaLabel = (await page.locator('svg.canvas').getAttribute('aria-label')) ?? ''
ck('画布可访问名给出实体/关系/跳数', /知识图谱：\d+ 个实体、\d+ 条关系/.test(ariaLabel), ariaLabel)

const nodeCount = await page.locator('svg.canvas g.node').count()
ck('画布上真的画出了实体节点', nodeCount > 0, `nodes=${nodeCount}`)

const rows = page.locator('.evidence__row')
const rowCount = await poll(
  () => rows.count(),
  (n) => n > 0,
  10000,
)
ck('依据清单列出边', rowCount > 0, `rows=${rowCount}`)

// 点开第一条边：逐字引文是这条边的全部价值，必须真的渲染出非空文本
await rows.first().locator('.evidence__head').click()
const quote = await poll(
  () => page.locator('.evidence__quote').first().textContent(),
  (t) => Boolean(t && t.trim().length > 0),
  8000,
)
ck('展开边后给出逐字引文', Boolean(quote && quote.trim().length > 10), (quote ?? '').slice(0, 80))

// 换中心实体（深链）：华法林是相互作用最密的一类实体
await page.goto(`${BASE}/#/knowledge/graph?root=${encodeURIComponent('华法林')}&depth=2`, {
  waitUntil: 'networkidle',
})
await page.reload({ waitUntil: 'networkidle' })
const huaRows = await poll(
  () => page.locator('.evidence__row').count(),
  (n) => n > 0,
  15000,
)
ck('深链换中心实体后重新取到依据', huaRows > 0, `rows=${huaRows}`)

// ---- 中心实体下拉：必须是远程搜索的结果，不是写死的演示名 ----
//
// 这条曾经是个真 bug：模板里 `v-for="s in SAMPLES"` 写死了 4 个演示名，
// 而 `remote-method` 又照 Element UI 2.x 的 callback 风格写成 `(keyword, cb)`，
// 在 Element Plus 里第二个参数恒为 undefined，一调就抛 `t is not a function`。
// 症状是「下拉永远只有 4 项、一点就报错」，而页面其余部分完全正常。
// 断言两层：候选要**来自接口**，数量要**明显多于写死的那 4 个**。
await page.goto(`${BASE}/#/knowledge/graph?root=${encodeURIComponent('华法林')}&depth=2`, {
  waitUntil: 'networkidle',
})
await page.reload({ waitUntil: 'networkidle' })
await page.locator('svg.canvas').waitFor({ state: 'visible', timeout: 20000 })

const apiHits =
  (
    await (
      await fetch(`${GW}/knowledge/graph/search?keyword=${encodeURIComponent('维生素')}&limit=20`)
    ).json()
  ).data ?? []
await page.locator('.graph-tools__field .el-select').first().click()
await page.locator('.el-select input').first().fill('维生素')
const options = await poll(
  () => page.locator('.el-select-dropdown__item').allTextContents(),
  (list) => list.length > 4,
  10000,
)
ck(
  `中心实体下拉给出远程搜索结果（${options.length} 项）`,
  options.length > 4,
  `只有 ${options.length} 项——下拉很可能仍绑着写死的演示名`,
)
ck(
  '下拉候选与接口返回的实体对得上',
  apiHits.length > 0 && options.some((text) => apiHits.some((h) => text.includes(h.label))),
  `接口 ${apiHits.length} 条，下拉 ${options.length} 项`,
)

// 点第一项，中心实体必须真的换过去
const optionBefore = new URL(page.url()).hash
await page.locator('.el-select-dropdown__item').first().click()
const rootChanged = await poll(
  () => new URL(page.url()).hash,
  (h) => h !== optionBefore && h.includes('root='),
  8000,
)
ck('选中下拉项后中心实体切过去', rootChanged !== optionBefore, rootChanged)
await page.keyboard.press('Escape')

// ---- 画布缩放与平移：节点不能再小到看不清 ----
//
// 图谱变大后节点会被整体缩小（三跳 77 个节点、布局 2710x2606 单位时，
// 「整图铺满」只有 0.27 的比例，一个药丸只剩 8px，字和点击区一起没了），
// 所以这一页必须有缩放能力，而且**默认视角不能是铺满**。断言按「用户能得到什么」写：
// 打开就能读、能近看、能拖、能复位、不露空白，而不是比对 transform 的具体数字。
await page.goto(`${BASE}/#/knowledge/graph?root=${encodeURIComponent('华法林')}&depth=3`, {
  waitUntil: 'networkidle',
})
await page.reload({ waitUntil: 'networkidle' })
await page.locator('svg.canvas').waitFor({ state: 'visible', timeout: 20000 })
await poll(
  () => page.locator('svg.canvas g.node rect').first().boundingBox(),
  (b) => Boolean(b && b.height > 0),
  10000,
)

/** 画布与外层的几何，供下面几条断言共用 */
const geometry = () =>
  page.evaluate(() => {
    const box = document.querySelector('.viewport-box').getBoundingClientRect()
    const nodes = [...document.querySelectorAll('svg.canvas g.node rect')].map((e) => {
      const r = e.getBoundingClientRect()
      return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height }
    })
    const center = document.querySelector('g.node.is-center').getBoundingClientRect()
    return {
      box: { l: box.left, t: box.top, r: box.right, b: box.bottom, w: box.width, h: box.height },
      minH: Math.min(...nodes.map((n) => n.h)),
      total: nodes.length,
      visible: nodes.filter(
        (n) => n.x > box.left && n.x < box.right && n.y > box.top && n.y < box.bottom,
      ).length,
      root: { x: center.left + center.width / 2, y: center.top + center.height / 2 },
      origin: (() => {
        const m = /translate\(([-\d.]+),\s*([-\d.]+)\)\s*scale\(([\d.]+)\)/.exec(
          document.querySelector('g.viewport').getAttribute('transform'),
        )
        return { tx: +m[1], ty: +m[2], scale: +m[3] }
      })(),
    }
  })

const base = await geometry()
// 默认视角必须是「可读」而不是「铺满」：0.27 的铺满比例下药丸只有 8px
ck(
  `三跳图打开即达可读尺寸（${base.minH.toFixed(1)}px 高、缩放 ${Math.round(base.origin.scale * 100) / 100}）`,
  base.minH >= 9,
  `只有 ${base.minH.toFixed(1)}px——又缩回「看不清」了`,
)
// 中心实体要落在画布正中，而不是被推到角落：同心环图的圆心就是它
ck(
  '中心实体落在画布中心',
  Math.abs(base.root.x - (base.box.l + base.box.w / 2)) < 3 &&
    Math.abs(base.root.y - (base.box.t + base.box.h / 2)) < 3,
  `根节点 ${Math.round(base.root.x)},${Math.round(base.root.y)} / 画布中心 ${Math.round(base.box.l + base.box.w / 2)},${Math.round(base.box.t + base.box.h / 2)}`,
)
// 裁剪必须按内容范围做：曾经 clipPath 写在镜头层自己的用户空间里，
// 结果画面有一大半被当越界裁掉，只剩右下角一块
ck(
  `内容铺满可视区（可见 ${base.visible}/${base.total} 个节点）`,
  base.visible >= base.total * 0.6,
  `只有 ${base.visible} 个节点落在画布里，裁剪范围可能又算错了`,
)

const viewportTf = () => page.locator('g.viewport').getAttribute('transform')
const tfBase = await viewportTf()
await page.locator('button[aria-label="放大"]').click()
await page.locator('button[aria-label="放大"]').click()
const tfZoomed = await viewportTf()
ck('放大按钮改变镜头', tfZoomed !== tfBase, `${tfBase} → ${tfZoomed}`)
const zoomed = await geometry()
ck(
  '放大后节点在屏幕上变大',
  zoomed.minH > base.minH,
  `${base.minH.toFixed(1)} → ${zoomed.minH.toFixed(1)}`,
)

await page.mouse.move(600, 700)
await page.mouse.wheel(0, -300)
const tfWheel = await viewportTf()
ck('滚轮可直接缩放', tfWheel !== tfZoomed, `${tfZoomed} → ${tfWheel}`)
ck('滚轮缩放不带动页面滚动', (await page.evaluate(() => window.scrollY)) === 0)

// 拖拽必须「内容跟手」：按住往右下拖，画面里的节点也要往右下走。
// 反向（拖镜头）是最容易写错的一处——两者都能自圆其说，但用户要的是跟手
await page.mouse.move(600, 700)
await page.mouse.down()
await page.mouse.move(720, 820, { steps: 12 })
await page.mouse.up()
// 「拖得动」与「拖得动多少」是同一件事：进入可拖状态时 clamp 会把这次手势
// 已经走掉的那段吃掉一部分，所以不能拿手势起点算期望位移 —— 用拖前那一帧量。
// 另外这一步紧跟在滚轮缩放之后，读到还没落到 DOM 的 transform 会算成 0 位移，
// 因此先等一帧再开始量。
await page.waitForTimeout(250)
const dragFrom = (await geometry()).root
await page.mouse.move(600, 700)
await page.mouse.down()
await page.mouse.move(720, 820, { steps: 12 })
await page.mouse.up()
const dragged = await geometry()
ck(
  '拖拽方向跟手（往右下拖，画面往右下走）',
  dragged.root.x - dragFrom.x > 8 && dragged.root.y - dragFrom.y > 8,
  `根节点位移 ${Math.round(dragged.root.x - dragFrom.x)},${Math.round(dragged.root.y - dragFrom.y)}（应为正）`,
)
ck('拖拽可平移画布', (await viewportTf()) !== tfWheel)

// 拖到极限也不能露出空白：空白意味着用户找不回图，只能靠复位，那是种惊吓
await page.mouse.move(600, 700)
await page.mouse.down()
await page.mouse.move(2600, 2700, { steps: 8 })
await page.mouse.up()
const nearestNode = Math.min(
  ...(await page.evaluate(() => {
    const box = document.querySelector('.viewport-box').getBoundingClientRect()
    const nodes = [...document.querySelectorAll('svg.canvas g.node')].map((n) => {
      const r = n.getBoundingClientRect()
      return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
    })
    const corners = [
      [box.left + 16, box.top + 16],
      [box.right - 16, box.top + 16],
      [box.left + 16, box.bottom - 16],
      [box.right - 16, box.bottom - 16],
      [box.left + box.width / 2, box.top + box.height / 2],
    ]
    return corners.map(([cx, cy]) => Math.min(...nodes.map((p) => Math.hypot(p.x - cx, p.y - cy))))
  })),
)
ck(
  `拖到极限也不露空白（四角与中心离最近节点 ${Math.round(nearestNode)}px）`,
  nearestNode < 400,
  `画布边缘离最近的节点 ${Math.round(nearestNode)}px，出现大片空白`,
)

await page.locator('button[aria-label="复位视图"]').click()
ck('复位回到默认取景', (await viewportTf()) === tfBase)
const reset = await geometry()
ck(
  '复位后中心实体回到画布中心',
  Math.abs(reset.root.x - base.root.x) < 3,
  `${Math.round(reset.root.x)} vs ${Math.round(base.root.x)}`,
)

// 档位按钮：给「想直接到某个距离」的用户一条捷径，不必一直点加减
await page.locator('.graph-zoom__btn--preset').first().click()
const fullView = await geometry()
ck(
  `点「全图」后所有节点都在画布内（${fullView.visible}/${fullView.total}）`,
  fullView.visible >= fullView.total - 2,
  `只有 ${fullView.visible} 个`,
)
await page.locator('.graph-zoom__btn--preset').last().click()
const detail = await geometry()
ck(
  '点「逐条」后节点显著放大',
  detail.minH > fullView.minH * 1.8,
  `${fullView.minH.toFixed(1)} → ${detail.minH.toFixed(1)}`,
)
ck('档位按钮反映当前倍率', (await page.locator('.graph-zoom__percent').textContent()) !== '100%')
// 缩放后点击节点仍要命中：指针捕获、裁剪、层级任何一处出错都会让点击失效
const clickBefore = page.url()
await page.locator('button[aria-label="放大"]').click()
await page.locator('svg.canvas g.node').nth(1).click({ timeout: 8000 })
const clickAfter = await poll(
  () => page.url(),
  (u) => u !== clickBefore,
  8000,
)
ck('放大后点击节点仍能切换中心', clickAfter !== clickBefore, clickAfter)

// ---- 平移边界：拖出画布外再松手，是「画布只剩一角」那条 bug 的必经路径 ----
//
// 两个独立缺陷都会让它露出来：位移上限写成 (内容-视口)/2 时算不出「内容整块
// 可以被推走」，而拖动中途光标离开画布又会让手势断在「按着不放」的状态里。
// 断言按用户能看到的结果写 —— 拖到极限后画布里仍然满是内容，且松手后画面不再自己动。
const vbRect = await page.locator('.viewport-box').boundingBox()
const coverage = () =>
  page.evaluate(() => {
    const vb = document.querySelector('.viewport-box').getBoundingClientRect()
    const nodes = [...document.querySelectorAll('svg.canvas g.node')].map((n) => {
      const r = n.getBoundingClientRect()
      return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
    })
    const corners = [
      [vb.left + 16, vb.top + 16],
      [vb.right - 16, vb.top + 16],
      [vb.left + 16, vb.bottom - 16],
      [vb.right - 16, vb.bottom - 16],
      [vb.left + vb.width / 2, vb.top + vb.height / 2],
    ]
    return Math.min(
      ...corners.map(([x, y]) => Math.min(...nodes.map((p) => Math.hypot(p.x - x, p.y - y)))),
    )
  })
/** 从画布正中按住，一路拖到画布外并在那里松手 */
const dragOut = async (dx, dy) => {
  const sx = vbRect.x + vbRect.width / 2
  const sy = vbRect.y + vbRect.height / 2
  await page.mouse.move(sx, sy)
  await page.mouse.down()
  for (let k = 1; k <= 16; k += 1) await page.mouse.move(sx + dx * k * 80, sy + dy * k * 60)
  await page.mouse.up()
  await page.waitForTimeout(250)
}
await page.locator('button[aria-label="复位视图"]').click()
const beforeCorner = await coverage()
await dragOut(1, 1)
const afterCorner = await coverage()
ck(
  `拖到右下极限仍看不到空白（画布四角离最近节点 ${Math.round(afterCorner)}px）`,
  afterCorner < 400,
  `复位时 ${Math.round(beforeCorner)}px → 拖完后 ${Math.round(afterCorner)}px，内容被整块推出了视口`,
)
// 松手之后画面必须彻底停住：拖动状态没收尾时，鼠标只是掠过画布都会带动平移
const tfAfterUp = await viewportTf()
const rootAfterUp = (await geometry()).root
await page.mouse.move(vbRect.x + 30, vbRect.y + 30)
await page.mouse.move(vbRect.x + vbRect.width - 30, vbRect.y + vbRect.height - 30)
await page.waitForTimeout(200)
const rootIdle = (await geometry()).root
ck(
  '松手后画面不再跟手（拖动已收尾）',
  (await viewportTf()) === tfAfterUp &&
    Math.abs(rootIdle.x - rootAfterUp.x) < 2 &&
    Math.abs(rootIdle.y - rootAfterUp.y) < 2,
  `松手后画面又移动了 ${Math.round(rootIdle.x - rootAfterUp.x)},${Math.round(rootIdle.y - rootAfterUp.y)}`,
)
await page.locator('button[aria-label="复位视图"]').click()

// ---- 放大后拖动同样是白屏的高发路径 ----
//
// 上面的极限拖动跑在默认取景（整图铺满）下，那时内容比视口小，边界几乎贴着
// 内容边缘，怎么拖都不会空。真正会露出大片空白的是**放大之后**：三跳图的节点
// 铺在半径 184 / 1121 / 1226 的同心环上，而外圈只有 19 个节点 —— 镜头一旦被
// 推进环与环之间的空心带，屏幕上就只剩几根斜线。
//
// 断言按用户能看到的结果写：放大到「逐条」再拖到极限，视口内必须仍有足够多的
// 节点，且分布不能塌到某一个角上（塌到角上时四角采样里会有一个离得很远）。
const visibleNodes = () =>
  page.evaluate(() => {
    const vb = document.querySelector('.viewport-box').getBoundingClientRect()
    return [...document.querySelectorAll('svg.canvas g.node')].filter((n) => {
      const r = n.getBoundingClientRect()
      const x = r.left + r.width / 2
      const y = r.top + r.height / 2
      return x > vb.left && x < vb.right && y > vb.top && y < vb.bottom
    }).length
  })

await page.locator('.graph-zoom__btn--preset').last().click()
await page.waitForTimeout(300)
const zoomedVisible = await visibleNodes()
await dragOut(-1, 1)
const draggedVisible = await visibleNodes()
ck(
  `放大后拖到极限视口内仍有多个节点（${zoomedVisible} → ${draggedVisible}）`,
  draggedVisible >= 3,
  `放大后拖到极限只剩 ${draggedVisible} 个节点可见——镜头掉进了环间的空白带`,
)
await page.locator('button[aria-label="复位视图"]').click()

// ==================== 二、溯源跳转 ====================
console.log('\n二、溯源跳转：从边的引文落到原文高亮')

await page.locator('.evidence__row').first().locator('.evidence__head').click()
const sourceLink = page.locator('.evidence__source a').first()
await sourceLink.waitFor({ state: 'visible', timeout: 8000 })
const sourceText = (await sourceLink.textContent()) ?? ''
await sourceLink.click()

const mark = page.locator('mark.doc__mark')
const marked = await poll(
  () => mark.count(),
  (n) => n > 0,
  15000,
)
ck('跳转后原文锚点高亮出现', marked > 0)
const markText = (await mark.first().textContent()) ?? ''
ck('高亮的是引文对应的那段原文', markText.trim().length > 4, `mark="${markText.slice(0, 60)}"`)
ck(
  '落在文档页且带着 chunk 锚点',
  hashOf().startsWith('/knowledge/') && hashOf().includes('chunk='),
  `hash=${hashOf()}`,
)
ck('来源链接文案指向文档', sourceText.includes('查看原文'), sourceText)

// ==================== 三、文档页 ====================
console.log('\n三、文档页：目录联动与悬空锚点')

const tocItems = page.locator('.doc-toc__item')
const tocCount = await poll(
  () => tocItems.count(),
  (n) => n > 0,
  10000,
)
ck('切片目录渲染', tocCount > 0, `toc=${tocCount}`)

// 点第二个切片：URL 锚点跟随、高亮跟随、aria-current 跟随
if (tocCount >= 2) {
  const second = tocItems.nth(1)
  const secondNo = (await second.locator('.doc-toc__no').textContent()) ?? ''
  await second.click()
  await poll(
    () => hashOf(),
    (h) => h.includes('chunk='),
    8000,
  )
  const current = await poll(
    () => page.locator('.doc-toc__item[aria-current="true"] .doc-toc__no').textContent(),
    (t) => Boolean(t),
    8000,
  )
  ck(
    '点击目录后 aria-current 跟随到该切片',
    current === secondNo,
    `current=${current} 期望=${secondNo}`,
  )
  ck('高亮跟随切片', (await mark.count()) > 0)
}

// 悬空锚点：检索侧与存储侧切分参数不一致时唯一会喊出来的地方
await page.goto(`${BASE}/#/knowledge/KB-0005?chunk=KB-0005_does_not_exist`, {
  waitUntil: 'networkidle',
})
const alert = page.locator('.doc-alert[role="alert"]')
const alertVisible = await poll(
  () => alert.count(),
  (n) => n > 0,
  10000,
)
ck('悬空锚点显式报警（不能静默）', alertVisible > 0)

ck('全程无控制台报错', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))

await browser.close()
console.log(`\n结果：${pass} 通过，${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
