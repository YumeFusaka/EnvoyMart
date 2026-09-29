/**
 * 界面截图工具 —— 给人看，也给回归比对用。
 *
 * 为什么需要它：这个项目的界面校验不能只靠 `npm run build` 通过。构建只证明模板能编译，
 * 证明不了「引用卡片渲染成了空壳」「登录后才该出现的购物车挂在游客页上」这类问题。
 * 而这类问题恰恰是最容易漏的一类 —— 它们不报错，只是看起来不对。
 *
 * 用法：
 *   node scripts/screenshot.mjs                        # 拍全部内置路由
 *   node scripts/screenshot.mjs '/#/knowledge'         # 只拍一个（hash 路由，别漏了 #）
 *   node scripts/screenshot.mjs '/#/knowledge/KB-0005?chunk=KB-0005_3' out.png 1440 900 '.doc-content'
 *
 * Git Bash 下要加 `MSYS_NO_PATHCONV=1`：MSYS 会把以 `/` 开头的参数当成路径，
 * 把 `/#/knowledge` 改写成 `E:/Tool/Git/#/knowledge`，报错信息里出现一个磁盘路径。
 *
 * 后四个参数依次是：输出文件名、视口宽、视口高、只截某个选择器。
 *
 * 依赖 playwright-core（**不下载浏览器**）。浏览器内核取自 PLAYWRIGHT_CHROMIUM，
 * 未设置时回落到 playwright 的默认缓存目录；两者都没有就会直接报错，
 * 而不是静默产出一张空白图。
 */
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'

const HERE = dirname(fileURLToPath(import.meta.url))
const OUT_DIR = resolve(HERE, '../.screenshots')
const BASE = process.env.SCREENSHOT_BASE ?? 'http://localhost:5173'

const CHROMIUM =
  process.env.PLAYWRIGHT_CHROMIUM ??
  'C:/Users/j/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe'

/** 内置路由清单。截图文件名即用途，别用 1.png 这种回过头看不懂的名字 */
const PAGES = [
  ['home', '/#/shop'],
  ['product-detail', '/#/product/1'],
  ['knowledge-list', '/#/knowledge'],
  ['knowledge-doc', '/#/knowledge/KB-0005?chunk=KB-0005_3'],
]

/**
 * @param {{route:string,out:string,width:number,height:number,selector?:string}} job
 */
async function shoot(browser, job) {
  const page = await browser.newPage({
    viewport: { width: job.width, height: job.height },
    deviceScaleFactor: 1,
  })
  const problems = []
  page.on('console', (m) => m.type() === 'error' && problems.push(m.text()))
  page.on('pageerror', (e) => problems.push('pageerror: ' + e.message))

  // networkidle：等接口回来再拍。省掉这一步拍到的是骨架屏，看不出真实排版
  await page.goto(BASE + job.route, { waitUntil: 'networkidle', timeout: 30000 })
  await page.waitForTimeout(1000)

  const target = job.selector ? page.locator(job.selector).first() : page
  mkdirSync(dirname(job.out), { recursive: true })
  await target.screenshot({ path: job.out, fullPage: !job.selector })

  await page.close()
  // 控制台错误要回显：白屏与接口 4xx 都只在控制台留痕，图上看不出来
  console.log(`${job.out}${problems.length ? '\n  控制台错误: ' + problems.join('\n  ') : ''}`)
}

const [route, file, width = '1440', height = '1200', selector] = process.argv.slice(2)
const jobs = route
  ? [
      {
        route,
        out: resolve(OUT_DIR, file ?? 'shot.png'),
        width: Number(width),
        height: Number(height),
        selector,
      },
    ]
  : PAGES.map(([name, r]) => ({
      route: r,
      out: resolve(OUT_DIR, `${name}.png`),
      width: 1440,
      height: 1200,
    }))

const browser = await chromium.launch({ executablePath: CHROMIUM })
for (const job of jobs) await shoot(browser, job)
await browser.close()
