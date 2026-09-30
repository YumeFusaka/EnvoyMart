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
// 收藏同购物车一样不持久化：它是服务端数据的影子，token 一变就整体作废
export * from './modules/favorite.ts'
// 管理台的待办计数同样不持久化：待办是「现在有多少」，存下来的一律是过去的
export * from './modules/admin.ts'
