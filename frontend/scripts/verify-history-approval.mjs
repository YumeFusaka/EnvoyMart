import { chromium } from 'playwright-core'

const gateway = 'http://127.0.0.1:8080'
const login = await (await fetch(`${gateway}/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'alice', password: '123456' }),
})).json()
if (login.code !== 200) throw new Error('登录失败')
const browser = await chromium.launch({
  executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true,
})
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  const problems = []
  page.on('pageerror', (error) => problems.push(error.message))
  await page.goto('http://localhost:5173/#/login', { waitUntil: 'networkidle' })
  await page.getByPlaceholder('请输入用户名').fill('alice')
  await page.getByPlaceholder('请输入密码').fill('123456')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.goto('http://localhost:5173/#/assistant', { waitUntil: 'networkidle' })
  const session = page.locator('.session-list__main').filter({ hasText: '最近眼睛疲劳' }).first()
  await session.click()
  await page.locator('.approval').waitFor()
  const before = await page.locator('.approval').innerText()
  if (!before.includes('SKU27') || !before.includes('SKU28')) throw new Error('历史确认参数缺失')
  const another = page.locator('.session-list__main').filter({ hasNotText: '最近眼睛疲劳' }).first()
  await another.click()
  await session.click()
  await page.locator('.approval').waitFor()
  if (await page.locator('.approval').innerText() !== before) throw new Error('回切确认记录变化')
  if (await page.locator('.approval__buttons').count()) throw new Error('过期确认卡仍可执行')
  if (problems.length) throw new Error(problems.join('\n'))
  await page.screenshot({ path: '.screenshots/approval-history-review.png', fullPage: false })
  console.log('通过：历史确认内容保留、会话回切一致、过期卡不可执行、无页面异常')
} finally {
  await browser.close()
}
