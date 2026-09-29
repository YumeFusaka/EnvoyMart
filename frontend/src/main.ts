import { createApp } from 'vue'
import pinia from './stores'

import App from './App.vue'
import router from './router'

// Element Plus 的样式必须先于项目样式加载
import 'element-plus/theme-chalk/src/index.scss'
// 项目样式入口：token 层 → 基础层 → Element Plus 主题覆盖。
// **必须最后引入**，否则 element.css 里对 --el-* 的覆盖会被 EP 自己的定义盖回去
import '@/styles/index.css'

const app = createApp(App)

app.use(pinia)
app.use(router)

app.mount('#app')
