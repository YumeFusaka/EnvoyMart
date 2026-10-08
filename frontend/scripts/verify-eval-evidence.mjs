import { chromium } from 'playwright-core'

const browser = await chromium.launch({
  executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true,
})
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('http://localhost:5173/#/knowledge/eval/answer', { waitUntil: 'networkidle' })
  const badge = page.locator('[data-cite]').first()
  await badge.waitFor()
  await badge.click()
  const caseRow = badge.locator('xpath=ancestor::li[contains(@class,"case-row")]')
  const source = caseRow.locator('.citations a').first()
  await source.waitFor()
  if (!(await source.getAttribute('href'))?.includes('/knowledge/')) throw new Error('证据链接缺少原文路由')
  await page.screenshot({ path: '.screenshots/eval-evidence-review.png', fullPage: false })
  await source.click()
  await page.waitForURL('**/#/knowledge/KB-**')
  await page.goto('http://localhost:5173/#/knowledge/eval', { waitUntil: 'networkidle' })
  const document = page.locator('.case-row__docs a').first()
  await document.waitFor()
  await document.click()
  await page.waitForURL('**/#/knowledge/KB-**')
  if (errors.length) throw new Error(errors.join('\n'))
  console.log('通过：回答角标点击、当次证据显示、证据原文跳转、检索文档跳转、无页面异常')
} finally {
  await browser.close()
}
