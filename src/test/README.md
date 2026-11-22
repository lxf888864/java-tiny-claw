# 测试说明

所有测试都**离线运行**，不调用任何大模型 API：引擎测试用 `ScriptedLlmProvider` 按剧本扮演模型，工具是真实的，文件读写发生在 JUnit 的临时目录里。

## 运行

```bash
# 需要 JDK 21+（本机：~/.jdks/openjdk-22.0.2）
export JAVA_HOME="C:/Users/Administrator/.jdks/openjdk-22.0.2"

mvn test                                          # 全部
mvn test -Dtest=CompactorTest                     # 单个类
mvn test -Dtest=ReminderTest#escalatesThenAborts  # 单个方法
mvn test -Dtest='AgentEngineTest*'                # 引擎端到端（含 @Nested 子类）
```

IDE 中直接运行测试类即可，`@DisplayName` 会显示中文用例名。

## 建议阅读顺序

| 顺序 | 测试类 | 学什么 |
|---|---|---|
| 1 | `tools/FileToolsTest` | 工作区越界防护；edit_file 勾选 TODO；为什么 TODO 要带编号 |
| 2 | `tools/BashToolTest` | 退出码非 0 = softFailure（不是工具报错，但会被 Reminder 计为失败）。找不到 bash 时自动跳过 |
| 3 | `context/PromptBuilderTest` | AGENT.md 的分层加载与系统提示词组装 |
| 4 | `context/PlanManagerTest` | TODO 标记解析；新会话 / 恢复会话时注入的消息序列 |
| 5 | `context/CompactorTest` | 掩码、掐头去尾、轮次对齐、钉住消息、幂等、紧急降级 |
| 6 | `engine/ReminderTest` | 连续失败、无效重复、来回摆动、逐级升级到终止 |
| 7 | `engine/AgentEngineTest` | 把以上组件串起来的完整 ReAct 循环（按功能分成 5 个 @Nested 组） |

## 测试数据（`src/test/resources/data/`）

| 文件 | 用途 |
|---|---|
| `plan/PLAN.md` | 示例计划（命令行计算器） |
| `plan/TODO.md` | 含 `[x]` / `[ ]` / `[!]`、`*` 列表符、中文冒号、无编号干扰行的 TODO，第一个未完成项是 T3 |
| `agent/AGENT.md` | 测试用全局 AGENT.md（含 `TEST-AGENT-MARKER`） |
| `agent/session-AGENT.md` | 会话级补充规则（含 `SESSION-RULE-MARKER`） |
| `logs/stackoverflow.log` | 约 1.2 万字符的递归堆栈日志，开头有 `HEAD-MARKER`、结尾有 `TAIL-MARKER`，用于演示掐头去尾 |

## 自己写剧本

```java
ScriptedLlmProvider llm = new ScriptedLlmProvider()
        .callTool("write_file", "{\"path\":\"a.txt\",\"content\":\"hi\"}")   // 第 1 轮：调用工具
        .callTools("read_file", "{\"path\":\"a.txt\"}",                       // 第 2 轮：并行调用
                   "bash", "{\"command\":\"ls\"}")
        .reply("完成");                                                         // 第 3 轮：纯文本 → 结束

AgentEngine engine = new AgentEngine(llm, TestData::registry,
        new ContextManager(agentDir, 60_000), new Reminder(), workspaces);
engine.run("my-session", "任务描述");

llm.requests().get(1).messages();   // 第 2 次请求时模型"看到"的完整上下文
```

`TestData.json(String)` 可以把含换行、引号的文本转成 JSON 字符串字面量，方便拼装 write_file 参数。
