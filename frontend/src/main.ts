import { createApp } from 'vue'
import pinia from './stores'

import App from './App.vue'
import router from './router'
import { ElMessage } from 'element-plus'

// Element Plus 的样式必须先于项目样式加载
import 'element-plus/theme-chalk/src/index.scss'
// 项目样式入口：token 层 → 基础层 → Element Plus 主题覆盖。
// **必须最后引入**，否则 element.css 里对 --el-* 的覆盖会被 EP 自己的定义盖回去
import '@/styles/index.css'

const app = createApp(App)

app.use(pinia)
app.use(router)

/*
 * 全局错误兜底。
 *
 * 为什么需要：项目里有大量「模板事件处理器直接 await 一个接口调用」的写法
 * （@click="handleCancel(order)"），这类调用没人接住 rejection。
 * axios 拦截器会把失败弹成右上角的 toast，但**页面自身没有任何反应** ——
 * 按钮从 loading 复原了、列表还是旧数据，用户不知道操作到底成没成，
 * 典型反应是再点一次。更糟的是它会在控制台留下 Uncaught (in promise)，
 * 而用户看不见控制台。
 *
 * 这里只处理「漏网的」：axios 已经提示过的错误带 __notified 标记，
 * 跳过以免同一个失败弹两次。真正需要页面级反馈的地方（比如商品详情加载失败），
 * 仍应像 ErrorState 那样在页面内给出可操作的提示，而不是靠这个兜底。
 */
app.config.errorHandler = (err, _instance, info) => {
  console.error('[未捕获的组件错误]', info, err)
  const e = err as { __notified?: boolean; message?: string }
  if (!e?.__notified) {
    ElMessage({ message: e?.message || '页面出现异常，请稍后重试', type: 'error' })
  }
}

window.addEventListener('unhandledrejection', (event) => {
  const reason = event.reason as { __notified?: boolean; message?: string } | undefined
  // 已经提示过的（axios 拦截器）不再重复弹；静默记录便于排查
  if (reason?.__notified) {
    console.warn('[接口错误已提示]', reason.message)
    return
  }
  console.error('[未处理的 Promise 拒绝]', reason)
  ElMessage({ message: reason?.message || '操作失败，请稍后重试', type: 'error' })
})

app.mount('#app')
