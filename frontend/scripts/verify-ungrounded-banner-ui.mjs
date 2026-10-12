/** 真实组件ungrounded布尔契约回归，全部业务请求阻断，不认证、不调用模型或写后端。 */
import assert from 'node:assert/strict'
import { mkdirSync } from 'node:fs'
import { chromium } from 'playwright-core'

const base=process.env.VERIFY_BASE??'http://127.0.0.1:5173'
const browser=await chromium.launch({executablePath:process.env.PLAYWRIGHT_CHROMIUM??'C:/Program Files/Google/Chrome/Application/chrome.exe'})
let pass=0
const check=(name,ok)=>{assert.ok(ok,name);pass++;console.log(`通过：${name}`)}
try{
  const page=await browser.newPage({viewport:{width:1200,height:900}})
  const errors=[];const attempted=[]
  page.on('pageerror',error=>errors.push(error.message))
  await page.route(/\/src\/main\.ts(?:\?.*)?$/,route=>route.fulfill({contentType:'application/javascript',body:'document.getElementById("app").id="fixture";'}))
  await page.route('**/*',async route=>{
    const path=new URL(route.request().url()).pathname
    if(/^\/(ai|auth|cart|tickets|products|favorites|orders|reviews|payments|knowledge)(\/|$)/.test(path)){
      attempted.push(`${route.request().method()} ${path}`)
      await route.fulfill({status:500,json:{code:500,msg:'回归禁止业务请求'}})
    }else await route.fallback()
  })
  await page.goto(base,{waitUntil:'networkidle'})
  assert.equal(await page.locator('#fixture').count(),1)
  await page.evaluate(async()=>{
    const source=await fetch('/src/components/ai/ChatMessageList.vue').then(r=>r.text())
    const vueUrl=source.match(/from "(\/node_modules\/\.vite\/deps\/vue\.js\?v=[^"]+)"/)?.[1]??'/node_modules/.vite/deps/vue.js'
    const {createApp,h,reactive,nextTick}=await import(vueUrl)
    const {default:pinia}=await import('/src/stores/index.ts')
    const {default:MessageList}=await import('/src/components/ai/ChatMessageList.vue')
    await import('/src/styles/index.css')
    const state=reactive({messages:[{id:'u1',role:'user',content:'确定性问题'},{id:'a1',role:'assistant',content:'同一份正文',ungrounded:true}],sessionId:null,streamingIndex:-1})
    createApp({render:()=>h(MessageList,state)}).use(pinia).mount('#fixture')
    window.ungroundedFixture={state,nextTick}
  })
  const card=page.locator('.message-card.assistant')
  await card.locator('.ungrounded').waitFor()
  check('权威true明确显示整篇无平台依据横幅',await card.locator('.ungrounded').count()===1)
  check('横幅说明模型自身知识不代表平台规则',(await card.locator('.ungrounded').innerText()).includes('不代表平台规则'))
  await page.evaluate(async()=>{window.ungroundedFixture.state.messages[1].ungrounded=false;await window.ungroundedFixture.nextTick()})
  check('权威false删除横幅，同一正文不推导ungrounded',await card.locator('.ungrounded').count()===0)
  await page.evaluate(async()=>{window.ungroundedFixture.state.messages[1].ungrounded=true;await window.ungroundedFixture.nextTick()})
  check('false再切true恢复横幅，防永远不显示假通过',await card.locator('.ungrounded').count()===1)
  check('真实正文保持一致',(await card.locator('.message-content').innerText()).trim()==='同一份正文')
  check('没有业务请求、模型调用或页面异常',attempted.length===0&&errors.length===0)
  mkdirSync('.screenshots',{recursive:true})
  await page.screenshot({path:'.screenshots/ungrounded-banner-ui.png',fullPage:true})
  console.log(`无依据横幅确定性回归：${pass}通过，0失败；真实业务请求0次。`)
}finally{await browser.close()}
