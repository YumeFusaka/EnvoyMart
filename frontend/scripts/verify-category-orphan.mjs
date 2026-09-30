/**
 * 类目编辑「只改名字、不挪挂载点」的回归验证。
 *
 * 为什么需要它：编辑对话框里的 `parentId` 是**库里记的挂载点**，不是它在树上被渲染到的位置。
 * 写死 0（或改成「树上的父」）的后果是「改一次名字，一整棵子树换位置」—— 而对话框里
 * 根本没有「挪动类目」这个选项，事后只能从「商品怎么全跑错了地方」倒推回来。
 *
 * 树上的位置与库里的挂载点确实会分叉，但**不是**在管理台：`GET /categories/admin/tree`
 * 查的是全量节点（含停用），父节点总能在内存 map 里命中，所以管理树永远不产生孤儿，
 * 停用的父类目连同子树照常挂在原位、只是多一个「停用」标记 —— 这也正是管理台还能编辑
 * 一个「父类目已停用」的子类目、且编辑完它不会挪窝的原因。
 * 分叉只发生在**公开树** `GET /categories/tree`：它按 status=1 过滤，父类目停用后
 * 子节点在 map 里找不到父，于是被挂到根上（见 CategoryTreeAssembler 的 parent == null 分支）。
 * 换句话说，管理台「编辑时不挪挂载点」的正确性，靠的是**那个查询不带状态过滤**这一条，
 * 而它是可以被改掉的 —— 所以这里既验证编辑请求本身，也把这条依赖钉住。
 *
 * 检查方式：制造「父类目停用」的中间态，在两个状态下各开一次编辑对话框、拦住保存请求，
 * 直接读请求体里的 `parentId`。请求被拦下不会真的发出去，因此这个脚本**不改任何数据**
 * （除了临时停用类目又恢复，恢复结果在收尾处复查）。
 *
 * 前置条件：后端九个服务 + 前端 dev server 已启动。
 *
 * 用法：
 *   node scripts/verify-category-orphan.mjs        # 默认拿类目 1（营养保健）与它的子类目
 */
import { chromium } from 'playwright-core'

const BASE = process.env.VERIFY_BASE ?? 'http://localhost:5173'
const GW = process.env.VERIFY_GW ?? 'http://localhost:8080'
const CHROMIUM =
  process.env.PLAYWRIGHT_CHROMIUM ??
  'C:/Users/j/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe'

const PARENT_NAME = process.env.VERIFY_PARENT ?? '营养保健'
const CHILD_NAME = process.env.VERIFY_CHILD ?? '维生素矿物质'

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

const loginRes = await fetch(`${GW}/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: '123456' }),
})
const session = (await loginRes.json()).data
if (!session?.token) {
  throw new Error('管理员登录失败，后端没起？')
}
const auth = { Authorization: `Bearer ${session.token}`, 'Content-Type': 'application/json' }

const tree = async () => (await (await fetch(`${GW}/categories/admin/tree`, { headers: auth })).json()).data
const publicTree = async () => (await (await fetch(`${GW}/categories/tree`)).json()).data
const find = (nodes, name, parent = null) => {
  for (const node of nodes) {
    if (node.name === name) {
      return { node, parent }
    }
    const hit = find(node.children ?? [], name, node)
    if (hit) {
      return hit
    }
  }
  return null
}
/**
 * 改类目状态。没有单独的状态端点，走的就是编辑类目那一个接口 ——
 * 所以这里必须把 parentId 一起带上（顺带也是本脚本要验证的那件事：
 * 拿树的形状去填 parentId 会真的挪动子树）
 */
async function setStatus(node, status) {
  const res = await fetch(`${GW}/categories/admin/${node.id}`, {
    method: 'PUT',
    headers: auth,
    body: JSON.stringify({
      parentId: node.parentId ?? 0,
      name: node.name,
      sort: node.sort ?? 0,
      status,
    }),
  })
  return (await res.json()).code === 200
}

const before = await tree()
const parent = find(before, PARENT_NAME)?.node
const child = find(before, CHILD_NAME)
if (!parent || !child?.node) {
  throw new Error(`类目树里找不到「${PARENT_NAME}」或「${CHILD_NAME}」，换个名字用环境变量指定`)
}
const parentId = parent.id
const childId = child.node.id
console.log(`父类目「${PARENT_NAME}」 id=${parentId}，子类目「${CHILD_NAME}」 id=${childId}`)
console.log(`正常情况下它的挂载点 parentId=${child.node.parentId}\n`)

let browser
try {
  browser = await chromium.launch({ executablePath: CHROMIUM })
  const page = await browser.newPage({ viewport: { width: 1600, height: 1200 } })
  const consoleErrors = []
  page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()))
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message))
  await page.addInitScript(
    ([key, value]) => localStorage.setItem(key, value),
    ['user', JSON.stringify({ token: session.token, profile: session.user })],
  )
  const treePane = page.locator('.admin-detail').filter({ hasText: '类目树' }).first()
  const childNode = treePane.locator('.el-tree-node__content', { hasText: CHILD_NAME }).first()

  /**
   * 打开这个子类目的编辑对话框、点保存、把 PUT 拦下来只读请求体。
   * @returns 请求体，没抓到就是 null（对话框没弹出来 / 校验没过 / 请求没发）
   */
  async function captureEdit(phase) {
    let sent = null
    const intercept = async (route) => {
      // 只拦 PUT。`**/categories/admin/**` 同样匹配到 `GET /categories/admin/tree`，
      // 一并拦掉的话页面拿到一个 data 为 null 的响应，直接炸在 `categoryTree.length` 上 ——
      // 那是我这个脚本自己造的错，不是产品的问题
      if (route.request().method() !== 'PUT') {
        return route.continue()
      }
      sent = route.request().postDataJSON()
      await route.fulfill({
        status: 200,
        contentType: 'application/json;charset=UTF-8',
        body: JSON.stringify({ code: 200, data: null, msg: '拦截验证，未真正保存' }),
      })
    }
    await page.route('**/categories/admin/**', intercept)
    // 第二次进来时地址没变，而哈希路由的 `goto` 到同一个 hash 是不重新加载文档的，
    // 那样阶段二改的类目状态不会体现到页面上
    const target = `${BASE}/#/admin/catalog`
    if (page.url().startsWith(target)) {
      await page.reload({ waitUntil: 'networkidle' })
    } else {
      await page.goto(target, { waitUntil: 'networkidle' })
    }
    await treePane.waitFor({ state: 'visible', timeout: 15000 })
    await childNode.waitFor({ state: 'visible', timeout: 10000 })
    // 树节点上的操作按钮平时是藏着的，悬停到那一行才显形
    await childNode.hover()
    await childNode.locator('button[aria-label^="编辑类目"]').click()
    const dialog = page.locator('.el-dialog', { hasText: '编辑类目' }).first()
    await dialog.waitFor({ state: 'visible', timeout: 10000 })
    await dialog.getByRole('button', { name: '保存' }).click()
    await page.waitForTimeout(1200)
    await page.unroute('**/categories/admin/**', intercept)

    ck(`[${phase}] 保存请求发得出去（对话框表单校验通过）`, sent !== null, sent ? '' : '没抓到 PUT 请求')
    ck(
      `[${phase}] **请求体里的 parentId 是库里记的挂载点 ${parentId}，不是 0**`,
      sent?.parentId === parentId,
      `实发 parentId=${JSON.stringify(sent?.parentId)}（0 就代表把子树挪到了根）`,
    )
    ck(`[${phase}] 名称原样带回`, sent?.name === CHILD_NAME, `实发 name=${JSON.stringify(sent?.name)}`)
  }

  // ---- 阶段一：父类目正常启用 ----
  console.log('【阶段一】父类目启用中')
  await captureEdit('父类目启用')

  // ---- 阶段二：停用父类目，看两棵树的分叉 ----
  if (!(await setStatus(parent, 0))) {
    throw new Error('停用父类目失败')
  }
  console.log('\n【阶段二】已临时停用父类目')

  // 管理树：查的是全量节点，父节点在 map 里命中，所以**不产生孤儿**，子树原位保留
  const adminAfter = await tree()
  const adminChild = find(adminAfter, CHILD_NAME)?.node
  const adminChildNested = find(adminAfter, PARENT_NAME)?.node?.children?.some((n) => n.id === childId)
  ck(
    '管理树不含状态过滤：停用父类目后子类目**仍在它下面**（所以管理台永远看不到孤儿）',
    adminChildNested === true && adminAfter.some((n) => n.id === parentId),
    `子类目在管理树根上？${adminAfter.some((n) => n.id === childId)}；它记录的 parentId=${adminChild?.parentId}`,
  )
  ck(
    '管理树里每个节点都带得出真实挂载点（编辑对话框回填的就是它）',
    adminChild?.parentId === parentId,
    `实读 parentId=${JSON.stringify(adminChild?.parentId)}`,
  )

  // 公开树：按 status=1 过滤，父节点不在 map 里 —— 孤儿只在这里成立
  const pub = await publicTree()
  ck(
    '公开树按 status 过滤：父类目停用后子类目被挂到根上（CategoryTreeAssembler 的孤儿分支在这里生效）',
    pub.some((n) => n.id === childId) && !pub.some((n) => n.id === parentId),
    `公开树根节点：${pub.map((n) => `${n.id}:${n.name}`).join(' | ') || '（空）'}`,
  )

  // ---- 阶段三：父类目停用的状态下再编辑一次 ----
  console.log('\n【阶段三】父类目停用中')
  await captureEdit('父类目停用')

  const realErrors = consoleErrors.filter((t) => !/favicon|DevTools|Failed to load resource/i.test(t))
  if (realErrors.length) {
    console.log(`\n浏览器控制台报错：\n  ${realErrors.join('\n  ')}`)
    fail += realErrors.length
  }
} finally {
  // 不管中间出什么岔子，父类目都要恢复启用 —— 否则前台导航会少一整个分类
  const reEnabled = await setStatus(parent, 1)
  const restored = find(await tree(), CHILD_NAME)?.node?.parentId === parentId
  browser?.close()
  console.log(`\n已恢复「${PARENT_NAME}」为启用：${restored ? '子类目挂载点确认未变' : '⚠ 未确认，请手工检查'}`)
  if (!reEnabled || !restored) {
    fail += 1
  }
}

console.log(`\n== pass=${pass} fail=${fail} ==`)
process.exit(fail === 0 ? 0 : 1)
