import { createPinia } from 'pinia'
import persist from 'pinia-plugin-persistedstate'

// 创建 pinia 实例
const pinia = createPinia()
// 使用持久化存储插件
pinia.use(persist)

// 默认导出，给 main.ts 使用
export default pinia

// 模块统一导出。
// 注意购物车**不持久化**：它是服务端数据，本地缓存一份只会在多端操作时给出陈旧结果
export * from './modules/user.ts'
export * from './modules/cart.ts'
