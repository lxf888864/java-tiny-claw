# tiny-claw（Java 版）

一个用 Java 21 从零实现的**极简编码 Agent**，用于学习 AI Agent 的核心工程机制。
它让大模型通过工具（读写文件、执行 bash）在一个隔离的工作区里自主完成编码任务，并在此基础上实现了生产级 Agent 必备的几道"驾驭"防线：会话隔离、动态系统提示词、计划驱动执行、上下文压缩和死循环防护。


## 功能一览

| 能力 | 说明 |
|---|---|
| **ReAct 主循环** | Reason + Action：模型思考 → 调用工具 → 观察结果 → 继续，直到模型认为任务完成 |
| **并行工具调用** | 同一轮的多个工具调用用虚拟线程并行执行 |
| **思考阶段**（可选） | 先剥夺工具强制模型纯文本规划，再恢复工具执行 |
| **Session 隔离** | 每个 sessionId 一个独立工作区 `workspaces/<sessionId>`，工具无法越界访问 |
| **动态系统提示词** | 从 `AGENT.md` 加载身份与规则，并自动注入运行环境信息 |
| **Plan Mode** | 用 `PLAN.md` / `TODO.md` 作为 Agent 的外部长期记忆，逐项执行、勾选、可跨会话恢复 |
| **上下文压缩** | 超过阈值时掩码远期工具输出、截断超长输出，保留完整的工具调用逻辑链 |
****| **死循环防护** | 识别重复失败与原地打转，逐级注入提醒，必要时终止任务 |****
| **多模型接入** | OpenAI 兼容协议与 Anthropic 兼容协议（默认接 DeepSeek） |

---

## 整体架构

```
                         ┌─────────────────────── AgentEngine.run(sessionId, prompt) ───────────────────────┐
                         │                                                                                  │
  sessionId ──► 工作区 workspaces/<id>  ──►  Registry（按工作区创建工具）                                  │
                         │                                                                                  │
                         ▼                                                                                  │
               ContextManager.openSession                                                                   │
               ├─ PromptBuilder  : AGENT.md + 会话级 AGENT.md + 运行环境 (+ Plan Mode 规则) → System Prompt │
               └─ PlanManager    : 无计划 → 要求先写 PLAN/TODO；有计划 → 自动 read_file 并指出下一个任务    │
                         │                                                                                  │
                         ▼                                                                                  │
          ┌──────────► ContextManager.compact（Compactor：超过阈值才压缩）                                 │
          │              │                                                                                  │
          │              ▼                                                                                  │
          │         LlmProvider.generate(上下文, 工具定义)                                                  │
          │              │                                                                                  │
          │              ├─ 无工具调用 ──► Plan Mode 下 TODO 未完成？──是──► 注入提醒（有限次）──┐        │
          │              │                       └─否──► 结束                                     │        │
          │              ▼                                                                         │        │
          │         并行执行工具（虚拟线程） ──► 工具结果写回上下文                                 │        │
          │              │                                                                         │        │
          │              ▼                                                                         │        │
          │         Reminder.observe / check ──► 注入警告 或 终止任务                               │        │
          │              │                                                                         │        │
          └──────────────┴─────────────────────────────── 下一轮（不超过 maxTurns） ◄──────────────┘        │
                                                                                                            │
          所有原始消息同时追加写入 workspaces/<id>/.claw/history.jsonl                                       │
                         └──────────────────────────────────────────────────────────────────────────────────┘
```

---

## 目录结构

```
java-tiny-claw
├── pom.xml
├── AGENT.md                       # 全局 Agent 规则（首次运行时自动从模板生成，可自行修改）
├── workspaces/                    # 运行时生成：每个 session 一个子目录
├── src/main/java/com/egjaedong/tinyclaw
│   ├── Claw.java                  # 入口：组装 Provider / 工具 / 上下文管理 / Reminder，发起任务
│   ├── TinyClawApplication.java   # Spring Boot 启动类，委托给 Claw.main
│   ├── engine
│   │   ├── AgentEngine.java       # ReAct 主循环、session 工作区、Plan Mode 提醒、maxTurns
│   │   └── Reminder.java          # 死循环检测与逐级干预
│   ├── context
│   │   ├── ContextManager.java    # 上下文管理门面（注入 AgentEngine）
│   │   ├── PromptBuilder.java     # 系统提示词组装
│   │   ├── PlanManager.java       # PLAN.md / TODO.md 的初始化、读取与解析
│   │   ├── Compactor.java         # 上下文压缩
│   │   └── SessionHistory.java    # 原始对话持久化（history.jsonl）
│   ├── provider
│   │   ├── LlmProvider.java       # 模型接口
│   │   ├── OpenaiProvider.java    # OpenAI 兼容协议（DeepSeek）
│   │   ├── AnthropicProvider.java # Anthropic 兼容协议（DeepSeek /anthropic）
│   │   └── MockLlmProvider.java   # 早期的演示用假模型
│   ├── tools
│   │   ├── Registry / RegistryImpl# 工具注册与分发
│   │   ├── ReadFileTool.java      # read_file
│   │   ├── WriteFileTool.java     # write_file
│   │   ├── EditFileTool.java      # edit_file（多级模糊匹配替换）
│   │   ├── BashTool.java          # bash（30s 超时、输出截断、Windows 自动定位 Git Bash）
│   │   └── ExecResult.java
│   ├── schema                     # Message / Role / ToolCall / ToolDefinition / ToolResult
│   └── util
│       ├── Env.java               # 读取 .env
│       └── PathGuard.java         # 工作区越界防护、跨平台文件权限
├── src/main/resources
│   ├── AGENT.md                   # 默认 AGENT.md 模板
│   └── application.properties
├── src/test                       # 离线测试（详见 src/test/README.md）
```


---

## 快速开始

### 1. 环境要求

- **JDK 21+**（使用了虚拟线程）
- Maven 3.9+
- bash：Linux/macOS 自带；Windows 需安装 [Git for Windows](https://git-scm.com/)（会自动定位 Git Bash，也可用环境变量 `CLAW_BASH` 指定 bash 路径）

### 2. 配置 API Key

在项目根目录创建 `.env`（已在 `.gitignore` 中忽略），或直接设置环境变量：

```properties
DEEPSEEK_API_KEY=sk-xxxxxxxx
```

### 3. 编译与运行

```bash
mvn package -DskipTests

# 参数为 sessionId，省略时为 demo；同一个 sessionId 再次运行会在原工作区继续
java -jar target/tiny-claw-0.1.0-SNAPSHOT.jar my-session

# 或者
mvn spring-boot:run -Dspring-boot.run.arguments=my-session
```

任务内容与开关在 `Claw.java` 中修改：

```java
AgentEngine agentEngine = new AgentEngine(
        llmProvider,                                              // 模型
        Claw::createRegistry,                                     // 按工作区创建工具
        new ContextManager(agentDir, COMPACT_THRESHOLD_CHARS),    // AGENT.md 目录 + 压缩阈值
        new Reminder(),                                           // 死循环防护
        workspaceRoot);                                           // 所有 session 的根目录
agentEngine.setEnableThinking(false);   // 思考阶段
agentEngine.setPlanModeOn(true);        // 计划模式
agentEngine.run(sessionId, prompt);
```

---

## 核心机制

### Session 与工作区

- `run(sessionId, prompt)`：工作区为 `workspaces/<sessionId>`，新 session 自动创建目录。
- sessionId 只允许字母、数字、`_`、`-`（最长 64 位），防止路径穿越；为空时自动生成 `session-yyyyMMdd-HHmmss`。
- 工具在构造时绑定工作区，因此引擎通过工厂为每个 session 创建独立的工具注册表。
- 所有文件工具都经过 `PathGuard` 校验，`../` 或绝对路径越界会被拒绝。
- 原始（未压缩）消息追加写入 `.claw/history.jsonl`，便于排查和后续实现会话恢复。

### 系统提示词（AGENT.md）

System Prompt 按顺序拼装：

1. 全局 `AGENT.md`（默认在项目根目录；不存在时从 `src/main/resources/AGENT.md` 模板生成）
2. 会话级 `workspaces/<id>/AGENT.md`（可选，对当前会话的补充规则）
3. 运行环境：工作区路径、操作系统、bash 类型、当前日期
4. Plan Mode 规则（仅开启时）

### Plan Mode

开启后，`PLAN.md` 与 `TODO.md` 成为 Agent 的外部长期记忆：

| 文件 | 内容 |
|---|---|
| `PLAN.md` | 任务理解、架构设计、技术选型、风险 |
| `TODO.md` | 可执行步骤，每行一个 |

TODO 标记格式（编号全局唯一，保证 `edit_file` 能精确匹配到那一行）：

```markdown
- [x] T1: 已完成的步骤
- [ ] T2: 未完成的步骤
- [!] T3: 阻塞的步骤 —— 原因
```

- **新任务**：要求模型先用 `write_file` 写出两个文件，再从 T1 开始执行。
- **已有计划**：引擎主动执行 `read_file` 读入两个文件（以"模型调用 + 工具结果"的形式注入，并钉住不被压缩），同时告诉模型当前进度和第一个未完成的任务。
- 每完成一步，模型用 `edit_file` 将 `- [ ] Tn` 改为 `- [x] Tn`。
- 模型在 TODO 未完成时就想结束，引擎会提醒（默认最多 2 次）。

### 上下文压缩（Compactor）

当上下文超过阈值（默认 60000 字符）时触发：

| 区域 | 策略 |
|---|---|
| System Prompt | 永远保留 |
| 钉住的 PLAN / TODO | 永远保留 |
| 用户消息、提醒消息 | 永远保留 |
| **远期历史**（保护区之外） | ToolCall 原样保留；超过 200 字符的工具输出替换为一句掩码（带工具名与参数，提示模型可重新获取）；冗长推理折叠 |
| **Working Memory**（最近 10 条消息） | 完整保留；但单条工具输出超过 1000 字符时，只保留前 500 和后 500 字符 |

设计要点：

- **持久化 + 幂等**：压缩结果写回上下文，已处理的消息不会被重复处理；两次压缩之间历史前缀保持稳定，对 API 的前缀缓存友好。
- **轮次对齐**：保护区边界不会把同一批并行调用的结果切开。
- **紧急降级**：常规压缩后仍高于低水位（阈值的 60%）时，逐步缩小保护区（最少 4 条）。

### 死循环防护（Reminder）

每轮逐个观察工具调用（指纹 = 工具名 + 参数）：

| 规则 | 默认阈值 |
|---|---|
| 同一调用连续失败（工具报错或 bash 退出码非 0） | 3 次 |
| 最近 12 次调用中同一调用出现（不论成败，可识别 A→B→A→B） | 4 次 |

触发后以 USER 角色注入提醒：第 1 次为警告，之后为严重警告；警告 3 次后再触发则终止任务。另有 `maxTurns`（默认 50 轮）作为兜底的硬上限。

### 工具

| 工具 | 说明 |
|---|---|
| `read_file` | 读取文件，最多 8000 字节 |
| `write_file` | 创建或覆盖文件，自动创建父目录 |
| `edit_file` | 局部替换，依次尝试精确匹配 → 换行归一 → 去首尾空白 → 逐行忽略缩进匹配；匹配不唯一时报错 |
| `bash` | 在工作区执行命令，30 秒超时，输出最多 8000 字节；退出码非 0 不算工具报错，但会被 Reminder 计为失败 |

---

## 测试

所有测试离线运行，不调用模型 API。引擎测试使用 `ScriptedLlmProvider` 按剧本扮演模型，工具真实执行在临时目录中。

```bash
mvn test                                   # 全部（52 个用例）
mvn test -Dtest=CompactorTest              # 单个类
mvn test -Dtest='AgentEngineTest*'         # 引擎端到端
```

测试类、测试数据与建议阅读顺序见 [src/test/README.md](src/test/README.md)。

---

## 已知限制

- 压缩阈值按字符数估算，未使用 API 返回的真实 token 数；中英文的字符/token 比例差异较大。
- `history.jsonl` 目前只做记录，尚未实现从中恢复完整会话（重新运行同一 session 时依靠 PLAN/TODO 恢复进度）。
- bash 工具在命令结束后才读取输出，不支持交互式命令或常驻进程（会在 30 秒后被终止）。
- `AnthropicProvider` 回传工具结果时固定 `isError=false`，`MockLlmProvider` 的工具参数不是合法 JSON，仅作早期演示用。

---

