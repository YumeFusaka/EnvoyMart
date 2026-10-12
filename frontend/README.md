# EnvoyMart 前端

Vue 3、TypeScript、Vite与Element Plus构建的商城、管理后台和AI助手。项目架构与当前交付边界见[主README](../README.md)，T1剩余工作见[实施计划](../docs/待办/T1-平台全功能与复杂Agent一次性收口实施计划.md)。

开发命令使用既有pnpm锁文件：`pnpm dev`启动前端，`pnpm type-check`检查类型，`pnpm build`生成生产产物，`pnpm lint`执行既有代码检查。完整本地依赖与九服务由`backend/run-local.sh`管理，浏览器默认访问`http://localhost:5173`。

验收脚本位于`scripts/`。当前阶段先运行受改动影响的专项，全部专项通过后再使用`node scripts/verify-all.mjs`执行最终全站；`--list`仅列计划，不执行业务。真实模型、支付回调、故障注入和跨重启脚本须按T1实施计划提供独立夹具及证据。`.evidence/`、`.screenshots/`、日志、依赖与构建产物不提交。
