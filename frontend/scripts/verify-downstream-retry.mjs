/**
 * 下游「HTTP 200 + 业务码 500」时的重试与诚实降级（批次 12e）。
 *
 * 为什么必须端到端验：本项目的服务间应答一律是 HTTP 200 + 体里的 code
 * （见 GlobalExceptionHandler：参数错、状态冲突、非预期异常都这么回）。
 * 于是工具里那句 `.getData()` 对三种完全不同的结局给出同一个 null——
 * 查到了、查不到、下游刚崩了一下。工具分不出来，就按最常见的那种解释：「没有找到」。
 * 实测下游搜索服务 5xx 时，商品检索工具回的是「没有找到相关商品」，模型转述给用户，
 * 用户以为商品下架了。**这不是文案问题，是工具在编事实。**
 *
 * 单测（DownstreamTest，12 项）钉的是判据；本脚本钉的是**接线**——
 * 真实 Feign 链路上这条路真的被走到了、重试真的发生了、
 * 而且救不回来时说的话没有变成「没有」。判据对而接线错，是本项目踩过好几次的坑。
 *
 * 做法：把 product-service 停下来，在它原来的端口上放一个桩。之所以必须换掉真服务，
 * 是因为「业务码 500」在本机没有别的制造方式——参数错回 400、状态冲突回 409，
 * 唯一现成的 500 是中间件停掉时下游抛异常，而那条路是传输层失败（Feign 那层管），
 * 不是这里要验的「连上了、下游说这次没做成」。
 *
 * 场景 A：桩在第一次请求上回 500，之后回真实数据 → 重试应当救回来，用户拿到真商品
 * 场景 B：桩一直回 500 → 工具应当报「暂时不可用」，**不能**说成「没有找到」
 *
 * 数据影响：无。桩只读不改，脚本结束时 product-service 会被拉回来。
 * 前置条件：九个服务已启动（网关 8080、ai-service 9004 可达）。
 * 用法：node scripts/verify-downstream-retry.mjs
 */
import { createServer } from 'node:http'
import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import net from 'node:net'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const GW = process.env.VERIFY_GW ?? 'http://127.0.0.1:8080'
const PRODUCT = process.env.VERIFY_PRODUCT ?? 'http://127.0.0.1:9002'
const PORT = Number(process.env.VERIFY_PRODUCT_PORT ?? 9002)
const HERE = dirname(fileURLToPath(import.meta.url))
const BACKEND = resolve(HERE, '../../backend') // 服务与 run-local.sh 都在这
const LOG_CANDIDATES = [
  resolve(HERE, '../../logs/logs-local/ai-service.log'),
  resolve(HERE, '../logs/logs-local/ai-service.log'),
]

/** 两个场景都用这句：它已在 verify-tool-trace.mjs 里稳定触发 product_search */
const QUESTION = '帮我推荐几款乳清蛋白粉'
const CAPTURE_QUERY = '乳清蛋白粉'

/**
 * 失败最容易被读成否定，而它听着像个结论：用户会以为这些商品不存在。
 * 这条闸拦的是「把查不成说成没有」——下游只是暂时不可用，商品并没有下架。
 * 「未查到」「没能拿到」这类如实表述不在其中：那是它真没查成，本来就该这么说。
 */
const ABSENCE = /没有找到|未找到|找不到|没有搜到|查无|不存在|暂无[^，。；]{0,6}(商品|结果)|已下架|没有相关/

/**
 * ABSENCE 是子串匹配，会误伤**假设句**——实测模型写过「若暂未找到明确标注『分离乳清蛋白』
 * 且价格≤200元的商品，也可从半勺开始试用」：那是在给用户建议，不是在替平台下结论
 * （同一段里另有一句明确说了搜索服务不可用）。判据落到句子级：命中词所在的那一句里、
 * 它之前出现假设连词的，不算「说成没有」。
 */
function absenceClaim(text) {
  for (const m of text.matchAll(new RegExp(ABSENCE.source, 'g'))) {
    const cut = Math.max(
      text.lastIndexOf('。', m.index),
      text.lastIndexOf('！', m.index),
      text.lastIndexOf('？', m.index),
      text.lastIndexOf('\n', m.index),
    )
    if (!/(若|如果|要是|万一|假如|倘若)/.test(text.slice(cut + 1, m.index))) {
      return m[0]
    }
  }
  return null
}

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
function skip(name, why) {
  console.log(`  \x1b[33mSKIP\x1b[0m ${name}——${why}`)
}

/** 读得到日志才有日志证据；服务跑在别的机器上时属正常，跳过而不是算失败 */
function logReader() {
  const path = LOG_CANDIDATES.find((p) => {
    try {
      readFileSync(p)
      return true
    } catch {
      return false
    }
  })
  if (!path) return null
  // 按字节记位：日志是 UTF-8，按字符下标 slice 会把多字节汉字切成乱码
  const mark = () => readFileSync(path).length
  const since = (offset) => readFileSync(path).subarray(offset).toString('utf8')
  return { path, mark, since }
}

function portOpen(port, timeoutMs = 1000) {
  return new Promise((done) => {
    const sock = net.connect(port, '127.0.0.1')
    const timer = setTimeout(() => {
      sock.destroy()
      done(false)
    }, timeoutMs)
    sock.on('connect', () => {
      clearTimeout(timer)
      sock.destroy()
      done(true)
    })
    sock.on('error', () => {
      clearTimeout(timer)
      done(false)
    })
  })
}

async function waitFor(fn, predicate, timeoutMs, stepMs = 1000) {
  for (let waited = 0; waited <= timeoutMs; waited += stepMs) {
    if (await fn()) return true
    await new Promise((r) => setTimeout(r, stepMs))
  }
  return false
}

/** 停服务：与 run-local.sh 的 kill_port 同一条路，但不走 run-local.sh ——
 *  它每次调用都会重装三个库模块的 jar，而正在跑的服务持有旧文件句柄
 *  （Windows 下被原地覆盖会让尚未加载的类报 NoClassDefFoundError，脚本里注释过这个坑） */
function killPort(port) {
  const out = spawnSync('cmd', ['/c', `netstat -ano | findstr LISTENING | findstr :${port}`], {
    encoding: 'utf8',
  }).stdout
  const pid = String(out ?? '')
    .trim()
    .split('\n')
    .map((l) => l.trim().split(/\s+/).pop())
    .filter((x) => /^\d+$/.test(x ?? ''))
    .pop()
  if (!pid) return false
  spawnSync('taskkill', ['/PID', pid, '/F'], { stdio: 'ignore' })
  return true
}

/** 起服务走 run-local.sh：数据库地址、JWT 密钥、SkyWalking 参数都在它那儿，
 *  复制一份就等于制造第二个会写错的地方 */
/**
 * 找到能用的 bash —— **不能只写 'bash'**。
 *
 * <p>Windows 上 PATH 里的 `bash` 是 `C:\Windows\System32\bash.exe`，
 * 也就是 WSL。WSL 有自己的文件系统命名空间与网络栈，所以：
 * <ul>
 *   <li>它看不见 `E:\...`（要写成 `/mnt/e/...`），报 "No such file or directory"；</li>
 *   <li>它连不上宿主机的 127.0.0.1:3306/9002，`run-local.sh` 的中间件握手必然失败。</li>
 * </ul>
 * 两个症状都指向「脚本有问题」，而真正的问题在「调用了另一个 bash」。
 *
 * <p>项目其余脚本（以及本仓库全部 run-local 用法）都默认 Git Bash。
 * 这里优先用 `git --exec-path` 推出 Git 安装目录，回退到常见安装位置。
 */
function findGitBash() {
  const candidates = [
    process.env.GIT_BASH,
    'E:/Tool/Git/bin/bash.exe',
    'C:/Program Files/Git/bin/bash.exe',
    'C:/Program Files (x86)/Git/bin/bash.exe',
  ].filter(Boolean)
  for (const c of candidates) {
    try {
      if (readFileSync(c)) return c
    } catch {
      /* 试下一个 */
    }
  }
  try {
    const r = spawnSync('git', ['--exec-path'], { encoding: 'utf8' })
    if (r.status === 0 && r.stdout) {
      // <git>/mingw64/libexec/git-core → <git>/bin/bash.exe
      const gitRoot = resolve(r.stdout.trim(), '..', '..')
      const guess = resolve(gitRoot, 'bin/bash.exe')
      try {
        if (readFileSync(guess)) return guess
      } catch {
        /* 落空 */
      }
    }
  } catch {
    /* 没装 git */
  }
  return 'bash'
}

function startProductService() {
  const bash = findGitBash()
  const scriptPath = resolve(BACKEND, 'run-local.sh')
  const r = spawnSync(bash, [scriptPath, 'product-service'], {
    stdio: 'inherit',
  })
  if (r.error) {
    console.error(`  \x1b[31m拉不起 product-service：${r.error.message}\x1b[0m`)
    console.error(`  手动恢复：bash ${resolve(BACKEND, 'run-local.sh')} product-service`)
    return false
  }
  return true
}

// ─────────── 前置 ───────────
console.log('\n零、前置')
// 用一次性用户而不是 admin：这句话会被记忆个性化——admin 账号上曾挂着
// 「预算 200 以内 / 乳糖不耐受」的画像（verify-memory 早期版本留下的），
// 于是模型给检索补上了用户没说的条件。两个场景验的是「下游抖动时怎么应答」，
// 带一份别人的画像进来，回答里就会混进与本场景无关的筛选描述（实测踩到）。
const login = await (async () => {
  const username = `dwr${Date.now().toString().slice(-9)}`
  const registered = await fetch(`${GW}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: 'verify12345', nickname: '重试验收用户' }),
  }).then((r) => r.json())
  if (registered.code !== 200) {
    console.error(`FATAL: 注册重试验收用户失败：${registered.msg}`)
    process.exit(1)
  }
  const res = await fetch(`${GW}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: 'verify12345' }),
  })
  return (await res.json()).data
})()
if (!login?.token) {
  console.error('FATAL: 登录失败——先启动后端')
  process.exit(1)
}

async function chat(sessionId, message) {
  const res = await fetch(`${GW}/ai/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${login.token}` },
    body: JSON.stringify({ sessionId, message }),
  })
  const body = await res.json()
  if (body.code !== 200) throw new Error(`对话失败：${body.msg}`)
  return body.data
}

const searchCall = (data) => (data.toolCalls ?? []).find((t) => t.tool === 'product_search')

// 先把真实应答抓下来：桩要回放它，才谈得上「重试拿到了和真服务一样的数据」
const captured = await fetch(
  `${PRODUCT}/products/search?keyword=${encodeURIComponent(CAPTURE_QUERY)}&size=3`,
).then((r) => r.json())
ck(
  '下游此刻可用，抓到了真实商品数据（桩回放用）',
  captured?.code === 200 && (captured.data?.records ?? []).length > 0,
  `GET /products/search 回的是 ${JSON.stringify(captured).slice(0, 200)}`,
)
if (captured?.code !== 200 || !(captured.data?.records ?? []).length) {
  console.error('FATAL: 拿不到真实数据，无法构造回放；先确认 product-service 正常')
  process.exit(1)
}
const productNames = captured.data.records.map((p) => p.name).filter(Boolean)
const productKeys = captured.data.records.map((p) => `SPU${p.id}`)
console.log(`  回放素材：${productNames.join(' / ')}`)

// ─────────── 换桩 ───────────
console.log('\n一、把 product-service 换成桩')

let mode = 'glitch' // glitch：第一次 500 之后放行；down：一直 500
let searchHits = 0
const stub = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1')
  const reply = (code, body) => {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify(body))
  }
  if (url.pathname === '/products/search') {
    searchHits += 1
    const failThis = mode === 'down' || searchHits === 1
    console.log(`  [桩] 第 ${searchHits} 次 ${url.searchParams.get('query')} → ${failThis ? '500' : '200（回放真实数据）'}`)
    return failThis ? reply(500, { code: 500, msg: 'stub：搜索服务抖了一下', data: null }) : reply(200, captured)
  }
  console.log(`  [桩] 未覆盖的路径 ${url.pathname} → 404`)
  reply(404, { code: 404, msg: `stub 未覆盖 ${url.pathname}`, data: null })
})

/**
 * 绑桩要重试：端口刚空出来时 bind 会回 WSAEACCES（「以访问权限禁止的方式访问套接字」）——
 * 上一条连接还挂在 TIME_WAIT 上。实测第一次跑就栽在这里，而且是在 finally 之前，
 * 于是服务停在半路没人拉回来。
 */
async function listenStub() {
  for (let i = 0; i < 40; i++) {
    try {
      await new Promise((r, j) => stub.listen(PORT, '127.0.0.1', r).once('error', j))
      return true
    } catch (e) {
      if (!['EACCES', 'EADDRINUSE'].includes(e.code)) throw e
      await new Promise((r) => setTimeout(r, 500))
    }
  }
  return false
}

let restored = false
function restore() {
  if (restored) return
  restored = true
  if (stub.listening) stub.close()
  console.log('\n五、恢复 product-service')
  startProductService()
}
// 崩在 try 之前也要把服务拉回来：上一次就是这么把 9002 留在空档上的
process.on('uncaughtException', (e) => {
  console.error(e)
  restore()
  process.exit(1)
})
process.on('SIGINT', () => {
  restore()
  process.exit(130)
})

killPort(PORT)
const freed = await waitFor(() => portOpen(PORT), (open) => !open, 20000, 500)
ck('product-service 已停止，端口空出', freed, `端口 ${PORT} 仍被占用——桩绑不上，后面场景全部作废`)
if (!freed) process.exit(1)

const log = logReader()
if (!log) console.log(`  （读不到 ai-service 日志，日志类断言将跳过）`)

try {
  const bound = await listenStub()
  ck('桩接管了 9002', bound, '端口一直绑不上（EACCES/EADDRINUSE）')
  if (!bound) {
    // 桩不在，后面两个场景问的就不是「重试」而是「连不上」，接着跑只会输出一屏误导人的 FAIL
    restore()
    console.log(`\n${pass} 通过 / ${fail} 失败`)
    process.exit(1)
  }
  console.log(`  桩已监听 127.0.0.1:${PORT}`)

  // ─────────── 场景 A：抖一下 ───────────
  console.log('\n二、场景 A：下游第一次回 500，之后正常')
  const markA = log ? log.mark() : 0
  // 模型有时会先追问偏好而不是直接检索（详见 verify-tool-trace 里的同款说明）——
  // 两个场景要验的是「工具失败/重试时怎么应答」，追问轮只是前置步骤没达成：
  // 在原会话里回一句明确指令，接着验那一轮
  const sessionA = `verify-retry-glitch-${Date.now()}`
  let a = await chat(sessionA, QUESTION)
  if (!searchCall(a)) {
    console.log('  （第一问被模型追回了偏好，回一句明确指令继续）')
    a = await chat(sessionA, '没有特别要求，直接帮我搜吧')
  }
  const callA = searchCall(a)
  const replyA = String(a.reply ?? '')
  console.log(`  工具=${(a.toolCalls ?? []).map((t) => `${t.tool}(success=${t.success})`).join(', ')}`)
  console.log(`  回答全文=\n${replyA}\n  ──`)

  ck('这一轮调了 product_search', Boolean(callA), `轨迹=${JSON.stringify((a.toolCalls ?? []).map((t) => t.tool))}`)
  ck('第一次 500 之后重发了一次（桩上看到第二次请求）', searchHits >= 2, `桩只接到 ${searchHits} 次请求`)
  ck('重试把结果救回来了', callA?.success === true, `工具回的是 success=${callA?.success}，output=${String(callA?.output ?? '').slice(0, 120)}`)
  // 判据是 SPU 编号而不是商品名：「乳清蛋白」这四个字知识库里到处都是，
  // 拿它当"回答里有这个商品"的证据，等于放行了一条永远为真的断言
  ck('重试拿回来的是真数据（工具输出里带着真实商品的 SPU 编号）',
    productKeys.some((k) => String(callA?.output ?? '').includes(k)),
    `桩回放的是 ${productKeys.join('/')}，工具输出=${String(callA?.output ?? '').slice(0, 200)}`)
  // 这一条**不能拿「没有」这种措辞判罪**。它与场景 B 的守卫长得像，但前提不同：
  // B 里工具确实没查成，任何「没有」都是把失败读成了否定；A 里重试把它救回来了
  // （上面两条断言已经钉死），模型拿到的是**真实数据**，于是它说「这三条里没有
  // 同时满足条件的」是如实转述数据，不是编事实。实测同一份代码上它时而说这句、
  // 时而说「暂无完全匹配的商品」，两次的产品行为一模一样 —— 拿措辞判罪，
  // 红的就只是模型这一轮怎么遣词（13b 的教训：假红灯会训练人忽略红灯）。
  // **判据锚在「谁造成的」上**：这一轮该守的是「救回来了就不许说这次没查成」。
  const 说系统坏了 = replyA.match(/暂时不可用|服务不可用|查询失败|检索失败|系统异常|服务异常/)
  ck('重试救回来之后，回答没有反过来说「这次没查成」', !说系统坏了,
    `回答里出现了「${说系统坏了?.[0]}」——工具有数据、返回 success=true，这句是凭空来的`)

  if (log) {
    const since = log.since(markA)
    ck('日志里记下了重试发生的那一次', since.includes('[下游重试] 商品服务 第 1 次重试：上次业务码 500'),
      '「重试过了」不能只靠结果反推——用户看到的「稍后再试」和从没重试过长得一模一样')
    ck('日志里记下了重试成功', since.includes('[下游重试] 商品服务 第 1 次重试成功'))
    const lineA = since.split('\n').find((l) => l.includes('[Tool] product_search')) ?? ''
    ck('救回来的这一轮不标瞬时失败', /success=true .*transient=false/.test(lineA),
      `日志行=${lineA.trim()}`)
  } else {
    skip('日志里的重试记录', '读不到日志文件')
  }

  // ─────────── 场景 B：一直不可用 ───────────
  console.log('\n三、场景 B：下游一直回 500')
  mode = 'down'
  searchHits = 0
  const markB = log ? log.mark() : 0
  const sessionB = `verify-retry-down-${Date.now()}`
  let b = await chat(sessionB, QUESTION)
  if (!searchCall(b)) {
    console.log('  （第一问被模型追回了偏好，回一句明确指令继续）')
    b = await chat(sessionB, '没有特别要求，直接帮我搜吧')
  }
  const callB = searchCall(b)
  console.log(`  工具=${(b.toolCalls ?? []).map((t) => `${t.tool}(success=${t.success})`).join(', ')}`)
  console.log(`  回答全文=\n${String(b.reply ?? '')}\n  ──`)
  console.log(`  桩共接到 ${searchHits} 次请求（每次工具调用=1 次首查 + 2 次重试）`)

  ck('工具如实报了失败', callB && callB.success === false,
    `工具回的是 success=${callB?.success}——下游一直 500，成功就等于在编事实`)

  const reply = String(b.reply ?? '')
  const absence = absenceClaim(reply)
  ck('回答没有把「查不成」说成「没有」', !absence,
    `回答里出现了「${absence}」——下游只是暂时不可用，商品并没有下架`)

  // 判据落在「用户能不能看出这只是一次瞬时失败、值不值得重来」上，所以除了
  // 「稍后再试」这类连写，也要认「稍后（再）让我试一次」「等服务恢复」这两种
  // 语义等价但中间插了字的说法——它们是同一个意思，卡字面会把正确的回答判成错的。
  const HONEST =
    /暂时|稍后|稍等|不可用|无法(查询|完成|获取|检索)|服务.{0,8}(异常|故障|不可用|恢复)|恢复(后|了).{0,6}(再|就|帮)/
  ck('回答明确说了这次没查成、稍后再试', HONEST.test(reply),
    `回答既没说「没有」也没说「稍后再试」，用户不知道该不该重来：${reply.slice(0, 200)}`)

  if (log) {
    const since = log.since(markB)
    const line = since.split('\n').find((l) => l.includes('[Tool] product_search')) ?? ''
    ck('日志把这次失败标成了瞬时失败', /\[Tool\] product_search success=false .*transient=true/.test(line),
      `日志行=${line.trim()}——不标出来的话，事后分不清「下游当时不可用」和「这件事本身做不成」`)
    ck('日志里有「重试用尽」的痕迹', since.includes('[下游重试] 商品服务 第 2 次重试'),
      '两次重试都没成，应当在日志里看得到第二次')
  } else {
    skip('日志里的瞬时失败标记', '读不到日志文件')
  }
} finally {
  restore()
}

// ─────────── 恢复 ───────────
const back = await waitFor(
  async () => {
    try {
      const r = await fetch(`${PRODUCT}/products/search?keyword=${encodeURIComponent(CAPTURE_QUERY)}&size=1`)
      return (await r.json())?.code === 200
    } catch {
      return false
    }
  },
  (ok) => ok,
  // 恢复窗口必须覆盖 run-local.sh 的**全流程**，不只是 Spring 启动那几秒。
  // run-local.sh 在起服务前会先跑一次 clean compile（U71 的构建防呆），
  // 而 product-service 那一步实测约 3~5 分钟；原先的 180 秒卡在编译刚过半，
  // 于是一条"服务其实能起来"的断言稳定报红。
  // 这种红最坏的地方不是误报本身，而是它把「脚本自己的等待时间不够」
  // 伪装成「服务起不来」——下一个人会去查服务，而问题在脚本里。
  600000,
  5000,
)
ck('product-service 已恢复（9002 重新提供真实数据）', back,
  `没起来的话手动执行：bash ${resolve(BACKEND, 'run-local.sh')} product-service`)

console.log(`\n${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
