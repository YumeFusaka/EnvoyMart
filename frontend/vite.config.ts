import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// 引入插件
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import Icons from 'unplugin-icons/vite'
import IconsResolver from 'unplugin-icons/resolver'

// https://vite.dev/config/
// 不用对象式配置是因为插件列表要按环境变量算：默认不挂 Vue DevTools。
export default defineConfig(() => ({
  plugins: [
    vue(),
    // Vue DevTools 的**悬浮面板**会自己创建并常驻页面角落，投屏演示时正好压住内容。
    // 试过 appendTo（只换 overlay 的注入点，面板照样自建），插件也没有隐藏开关 ——
    // 所以改成显式开关：默认不挂，需要时 `DEVTOOLS=1 pnpm dev` 再启用。
    // 13a 那批为了绕开它把演示链路换成 build + preview，现在 dev 也干净了。
    ...(process.env.DEVTOOLS == '1' ? [vueDevTools()] : []),
      AutoImport({
      // Auto import functions from Vue, e.g. ref, reactive, toRef...
      // 自动导入 Vue 相关函数，如：ref, reactive, toRef 等
      imports: ['vue'],

      // Auto import functions from Element Plus, e.g. ElMessage, ElMessageBox... (with style)
      // 自动导入 Element Plus 相关函数，如：ElMessage, ElMessageBox... (带样式)
      resolvers: [
        // importStyle: false —— 样式不在按需导入里注入。
        //
        // 全量样式已由 main.ts 的 `import 'element-plus/theme-chalk/src/index.scss'`
        // 一次性引入，而按需注入的样式是**运行时异步**塞进 <head> 的：它晚于
        // `@/styles/index.css`（我们的主题覆盖），而它自己又带着一份
        // `:root { --el-color-primary: #409eff }`——同特异性下后来者获胜，
        // 于是主题覆盖被悄悄盖回 EP 默认蓝。表现为「主按钮是蓝的、改了没效果」，
        // 且只在**首次用到某个组件的那个页面**才出现，构建期完全看不出来。
        // 关掉它之后，EP 样式只剩 main.ts 里那一份，注入顺序与 import 顺序一致，
        // element.css 的覆盖（注释里声明的前提）才真正成立。
        ElementPlusResolver({ importStyle: false }),

        // Auto import icon components
        // 自动导入图标组件
        IconsResolver({
          prefix: 'Icon',
        }),
      ],
    }),

    Components({
      resolvers: [
        // Auto register icon components
        // 自动注册图标组件
        IconsResolver({
          enabledCollections: ['ep'],
        }),
        // Auto register Element Plus components
        // 自动导入 Element Plus 组件（样式理由同上：由 main.ts 全量引入）
        ElementPlusResolver({ importStyle: false }),
      ],
      directoryAsNamespace: true
    }),

    Icons({
      autoInstall: true,
      compiler: 'vue3'
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    },
  },
}))
