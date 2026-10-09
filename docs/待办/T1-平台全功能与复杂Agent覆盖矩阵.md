# T1 平台全功能与复杂 Agent 覆盖矩阵

## 基线

基线由 `frontend/scripts/fixtures/platform-feature-matrix.json` 固定，包含 38 个路由、15 个 Agent 工具和 10 条失败恢复契约。矩阵版本为 `t1-2026-10-10`；执行时由验收脚本补充 commit、服务健康状态、请求 ID 和运行时间。

能力状态含义：`UI_ONLY` 只由页面承载，`API_ONLY` 只由接口/评测页承载，`AGENT_READ` 为只读工具，`AGENT_WRITE_CONFIRM` 为必须服务端审批的写工具，`UNAVAILABLE` 明确不可用，`OUT_OF_SCOPE` 表示本批不实现的 Multi-Agent 能力。

## 复杂对话契约

`complex-agent-dialogues.json` 固定 48 条场景：24 条至少三轮，20 条使用两个及以上工具，12 条包含确认后写操作，10 条覆盖失败恢复或重规划，8 条覆盖重启恢复。每条记录 `caseId`、消息序列、工具 DAG、是否写操作、恢复标记和重启标记。确定性回放必须额外输出参数来源、operationId、确认状态、最终权威状态和失败阶段。

## 验收不变量

1. 任何写操作必须有服务端确认令牌、稳定 `operationId` 和最终状态复查。
2. 列表引用必须显式下标；多元素列表上的无下标引用不得默认取第一项。
3. 重复确认、网络重试和重规划不得产生第二次副作用。
4. 评测页只读快照；快照损坏和版本不兼容必须展示为错误状态。
5. 图谱拒绝记录必须可由批次、文档、阶段、原因码追溯到候选和原文 quote。
6. Multi-Agent 不出现在工具表、MCP instructions、验收脚本和材料中。

## 验收证据

确定性证据由后端 `AgentComplexDialogueContractTest`、`AgentCommerceToolContractTest` 和 `AgentSideEffectVerificationTest` 提供；运行期证据由 `verify-platform-matrix.mjs`、`verify-complex-agent-dialogues.mjs`、`verify-agent-side-effects.mjs`、`verify-failure-recovery.mjs`、`verify-persistence-restart.mjs` 和 `verify-permission-matrix.mjs` 生成。脚本输出必须含 `caseId`、`requestId`、工具顺序、确认状态、最终状态和失败阶段。
