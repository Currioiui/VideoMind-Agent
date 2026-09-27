# VideoMind Agent V3.1：Java Harness + 受控 ReAct

V3 不把整个系统改成不确定的 Agent，而是把 ReAct 限制在“复杂视频证据研究”阶段。
上传、转码、消息、权限、存储、Checkpoint 和最终证据校验仍由 Java 确定性工作流负责。

```text
用户目标
  -> 确定性混合检索（始终先执行，也是安全兜底）
  -> AgentTaskRouter
       -> 简单单跳问题：直接使用混合检索结果
       -> 多跳/视觉/复杂问题：进入受控 ReAct
            -> AgentHarnessPolicy
            -> searchEvidence
            -> expandTimestamp
            -> inspectFrame
  -> Planner -> Executor -> Critic
  -> Java 证据校验与持久化
```

## V3.1 可靠性加固

- `ObservedEvidenceLedger` 记录工具真实返回的片段起止时间，最终选择会剔除模型自行生成的时间戳；
- 每轮 ReAct 模型调用都进入 `AgentTelemetry.modelCall`，立即检查 Token 和成本预算；
- ReAct 使用独立 `reactChatModel`，底层传输超时不得大于 Harness 超时；
- 被策略拒绝的调用也消耗全局调用预算，避免无效调用绕过限制；
- 明确的单跳查找问题优先走确定性检索，不再仅因文字较长进入 ReAct。

## V3 核心能力

### 1. 确定性任务路由

`AgentTaskRouter` 不消耗模型 Token，根据问题长度及多跳、对比、因果、视觉标记选择
`DETERMINISTIC` 或 `REACT`。路由规则可测试、可解释，也可通过配置强制 ReAct 做演示。

### 2. Agent Harness 策略边界

`AgentHarnessPolicy` 在 Java 中强制执行：

- 工具白名单，仅开放三个只读工具；
- 第一次调用必须是证据搜索；
- 总工具调用数和单工具调用数限制；
- 查询字符串长度限制；
- 时间戳必须属于当前视频；
- 时间窗口最大值限制；
- 模型不能传入或切换 `mediaId`。

模型提示词不是安全边界，`AgentHarnessPolicy` 才是最终边界。

### 3. 可追溯证据链

`AgentTelemetry` 的 trace 新增 `events`：

- `evidence.route`：路由结果与原因；
- `tool.call`：轮次、工具、状态和耗时；
- `tool.denied`：Harness 拒绝及原因；
- `evidence.fallback`：ReAct 失败后的确定性回退。

已有 `/analysis/agent-trace` 接口会返回这些事件，前端“分析详情”展示为 ReAct 证据链。
系统不保存或展示模型隐藏思维，只展示可验证的工具执行轨迹。

### 4. 兼容与降级

原接口和 `AgentLoopService` 调用方式不变。模型不支持工具、超时、格式错误、达到轮次上限
或策略拒绝导致研究失败时，系统继续使用最开始生成的确定性混合检索结果。

## 配置

```properties
agent.router.enabled=true
agent.router.force-react=false
agent.router.simple-goal-max-chars=36
agent.react.max-tool-rounds=4
agent.react.max-tool-calls=8
agent.react.max-same-tool-calls=3
agent.react.max-query-chars=200
agent.react.max-window-seconds=90
agent.react.model-timeout-seconds=75
```

演示 ReAct 时可临时设置：

```properties
agent.router.force-react=true
```

生产环境建议保持 `false`，让简单问题避免不必要的模型调用。

## 主要类

- `AgentTaskRouter`：确定性路由；
- `EvidenceResearchRoute`：路由值对象；
- `AgentHarnessPolicy`：工具权限和预算；
- `ToolCallingEvidenceService`：受控 ReAct 循环及回退；
- `EvidenceResearchResult`：带工具轨迹的研究结果；
- `AgentTelemetry`：持久化可观测事件；
- `AgentLoopService`：Planner–Executor–Critic 外层工作流。

## 后续建议

1. 使用真实问题集评测路由准确率、Recall@K、MRR 和答案引用正确率；
2. 增加视觉语言模型工具，但继续保持只读和时间戳边界；
3. 实现主模型/备用模型故障切换，仅针对超时、限流和服务端错误；
4. 将路由规则和工具策略放入版本化配置，支持灰度发布。
