/** 真实聊天页迟到流回归；浏览器替代传输生命周期，全部API拦截，不调用模型/后端写入。 */
import assert from 'node:assert/strict'
import { chromium } from 'playwright-core'

const base = process.env.VERIFY_BASE ?? 'http://127.0.0.1:5173'
const browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
let passed = 0
const check = (name, condition) => { assert.ok(condition, name); passed += 1; console.log(`通过：${name}`) }
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const page = await context.newPage()
  const errors = []; const unexpected = []
  page.on('pageerror', error=>errors.push(error.message))
  await context.addInitScript(()=>{
    localStorage.setItem('user',JSON.stringify({token:'race-fixture-token',profile:{id:'race-user',username:'竞态夹具'}}))
    window.raceStreams=[]
  })
  // 保留api模块其余真实实现；只有流传输换成可手动释放的已发请求，模拟Abort后的迟到回调。
  await context.route(/\/src\/api\/ai\.ts(?:\?.*)?$/,async route=>{
    const response=await route.fetch(); let module=await response.text()
    assert.match(module,/export async function chatStream\(/,'必须通过已运行Vite加载真实源码模块，不启动服务')
    module=module.replace('export async function chatStream(', 'async function originalChatStream(')
    module+='\nexport async function chatStream(payload,handlers,signal){const stream={payload,handlers,signal};window.raceStreams.push(stream);handlers.onDelta(window.raceStreams.length===1?"首轮部分正文":"新轮部分正文");return new Promise(resolve=>stream.resolve=resolve)}\n'
    await route.fulfill({response,body:module})
  })
  await context.route('**/*',async route=>{
    const path=new URL(route.request().url()).pathname
    if(!/^\/(?:ai|auth|cart|tickets|products|favorites|orders|promotions|reviews|payments)(?:\/|$)/.test(path)){await route.fallback();return}
    const method=route.request().method()
    if(method!=='GET'){unexpected.push(`${method} ${path}`);await route.fulfill({status:500,json:{code:500,msg:'禁止真实写入'}});return}
    let data=[]
    if(path==='/tickets/summary')data={all:0,open:0,inProgress:0,resolved:0,closed:0,awaitingMe:0}
    await route.fulfill({status:200,json:{code:200,data}})
  })
  await page.goto(`${base}/#/assistant`,{waitUntil:'networkidle'})
  await page.locator('.composer textarea').fill('迟到流测试，不调用真实模型')
  await page.getByRole('button',{name:/发送消息/}).click()
  await page.waitForFunction(()=>window.raceStreams.length===1)
  await page.getByRole('button',{name:/停止生成/}).click()
  check('停止R1同步标记半截回答已停止',await page.evaluate(()=>document.querySelector('.assistant-page').__vueParentComponent.setupState.messages.at(-1).stopped===true))
  await page.getByRole('button',{name:'重新生成',exact:true}).click()
  await page.waitForFunction(()=>window.raceStreams.length===2)
  check('停止R1后R2原地重新生成',await page.locator('.message-card.assistant').count()===1)
  const readMessage=()=>page.evaluate(()=>{
    const state=document.querySelector('.assistant-page').__vueParentComponent.setupState
    const message=state.messages.at(-1)
    return {id:message.id,content:message.content,pending:message.pendingActions??null,approvalToken:message.approvalToken??null,error:message.error??null,loading:state.loading,liveTools:state.liveTools}
  })
  const before=await readMessage()
  await page.evaluate(()=>{
    const old=window.raceStreams[0]
    old.handlers.onDelta('旧delta污染')
    old.handlers.onTool({phase:'start',tool:'order_cancel'})
    old.handlers.onError('旧error污染')
    old.handlers.onDone({reply:'旧done污染',assistantMessageId:'old-assistant',userMessageId:'old-user',pendingActions:['旧取消操作'],pendingActionDetails:[{tool:'order_cancel',arguments:{orderId:999}}],approvalToken:'old-token'})
  })
  const after=await readMessage()
  check('R1迟到delta/done不能覆盖R2正文',after.content===before.content)
  check('R1迟到done不能覆盖R2消息身份',after.id===before.id)
  check('R1迟到审批不能污染R2待确认动作与令牌',after.pending===null&&after.approvalToken===null)
  check('R1迟到tool/error不能污染R2工具状态和错误',after.liveTools.length===0&&after.error===null)
  check('R1迟到收尾不能复位R2生成状态',after.loading===true)
  await page.evaluate(()=>{
    const latest=window.raceStreams[1]
    latest.handlers.onDone({reply:'新轮最终正文',assistantMessageId:'new-assistant',userMessageId:'new-user',pendingActions:[],pendingActionDetails:[],approvalToken:null})
    latest.resolve()
    window.raceStreams[0].resolve()
  })
  await page.waitForFunction(()=>!document.querySelector('.message-live'))
  const final=await readMessage()
  check('正常R2身份与最终正文照常落盘',final.id==='new-assistant'&&final.content==='新轮最终正文')
  check('所有后端写入与模型调用均拦截',unexpected.length===0&&await page.evaluate(()=>window.raceStreams.length)===2)
  check('页面没有未捕获异常',errors.length===0)
  console.log(`迟到流回归：${passed}通过，0失败；真实后端写入0次。`)
} finally {await browser.close()}
