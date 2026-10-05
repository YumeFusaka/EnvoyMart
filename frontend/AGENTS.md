# AGENTS.md（frontend）

本目录规则。上层 `EnvoyMart/AGENTS.md` 的效率约定与编辑禁令继续适用，这里只补前端特有的部分。

## 改 `.vue` 文件的硬性要求

- 这些文件普遍 1000+ 行，`<script setup>`、`<template>`、`<style scoped>` 三段混排，**行尾是 CRLF**。
- 成块修改一律用 `apply_patch`，或把整段读出来重新拼接后一次性写回。
  **禁止**按行号索引 splice、禁止用 `sed` 式替换、禁止用 PowerShell heredoc 拼模板。
- 每次改完立刻跑：`npx vue-tsc --build`（必须 0）；只有改了构建配置或依赖才需要 `vite build`。
- 改完必须 `git diff --stat` 看行数变化，异常增减先查是不是插重或丢块。

## 自验方式（按改动大小选，不要一律上全量）

- 纯样式/文案：一张截图即可。`node scripts/screenshot.mjs '/#/<路由>' out.png 1440 1500 '.目标选择器'`
- 交互逻辑：截图 + 一个临时的 playwright 脚本点击验证，脚本用完即删，不留在 `scripts/` 里。
- 涉及公共契约、跨模块行为、已知回归：才跑对应的 `scripts/verify-*.mjs`。

## 已知陷阱

- Vite dev server 缓存旧编译错误：文件已修好仍报 `Element is missing end tag` 时，
  先 `curl -s http://127.0.0.1:5173/src/<相对路径>` 看真实返回，别急着改文件。
- 截图脚本的路由是 hash 路由，必须带 `#`：知识图谱是 `/#/knowledge/graph`，不是 `/#/graph`。
- 改了组件里的 class 名，同步更新引用它的 `verify-*.mjs`，否则脚本会以超时报错而不是断言失败。
