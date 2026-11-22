package com.lxf.tinyclaw.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.lxf.tinyclaw.context.ContextManager;
import com.lxf.tinyclaw.context.PlanManager.TodoItem;
import com.lxf.tinyclaw.context.SessionHistory;
import com.lxf.tinyclaw.provider.LlmProvider;
import com.lxf.tinyclaw.schema.Message;
import com.lxf.tinyclaw.schema.Role;
import com.lxf.tinyclaw.schema.ToolCall;
import com.lxf.tinyclaw.schema.ToolDefinition;
import com.lxf.tinyclaw.schema.ToolResult;
import com.lxf.tinyclaw.tools.Registry;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 *
 * <p>
 * 职责：按 session 锁定工作区，维护上下文历史，跑 ReAct（Reason + Action）循环。
 */
@Getter
@Setter
public final class AgentEngine {

    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final LlmProvider llmProvider;
    // 工具绑定 workDir，因此每个 session 按自己的工作区创建一份 Registry
    private final Function<String, Registry> registryFactory;
    private final ContextManager contextManager;
    private final Reminder reminder;
    // 所有 session 工作区的根目录：workDir = workspaceRoot/sessionId
    private final Path workspaceRoot;

    private boolean enableThinking;
    private boolean planModeOn;
    // 硬上限：无论如何，单次 run 不超过这么多轮
    private int maxTurns = 50;
    // Plan Mode 下，模型在 TODO 未完成时就想收工，最多提醒几次
    private int maxPlanNudges = 2;

    // 当前（最近一次）运行的 session 与工作区，run 时设置
    @Setter(AccessLevel.NONE)
    private String sessionId;
    @Setter(AccessLevel.NONE)
    private String workDir;

    public AgentEngine(LlmProvider llmProvider, Function<String, Registry> registryFactory,
            ContextManager contextManager, Reminder reminder, Path workspaceRoot) {
        this.llmProvider = llmProvider;
        this.registryFactory = registryFactory;
        this.contextManager = contextManager;
        this.reminder = reminder;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    // 启动 Agent 的生命周期
    public void run(String sessionId, String userPrompt) {
        // 0. 锁定 session 工作区（新 session 自动创建目录）
        this.sessionId = normalizeSessionId(sessionId);
        Path sessionDir = prepareWorkDir(this.sessionId);
        this.workDir = sessionDir.toString();

        System.out.println("[Engine] 引擎启动，Session: " + this.sessionId + "，锁定工作区: " + workDir);
        System.out.println("[Engine] 思考模式 (Thinking Phase): " + enableThinking + "，计划模式 (Plan Mode): " + planModeOn);

        Registry registry = registryFactory.apply(workDir);
        SessionHistory history = new SessionHistory(sessionDir);
        history.markRunStart(this.sessionId, userPrompt);
        reminder.reset();

        // 1. 初始化会话的 Context：System Prompt 由 AGENT.md 动态组装，Plan Mode 下自动挂载计划文件
        List<Message> contextHistory = new ArrayList<>();
        for (Message message : contextManager.openSession(sessionDir, registry, userPrompt, planModeOn)) {
            append(contextHistory, history, message);
        }

        int turnCount = 0;
        int planNudges = 0;

        // 2. The Main Loop: 心跳开始 （标准的 ReAct 循环） (ReAct = Reason + Action)
        while (true) {
            turnCount++;
            if (turnCount > maxTurns) {
                System.out.println("[Engine] ⛔ 已达到最大轮次 " + maxTurns + "，强制终止任务。");
                break;
            }
            System.out.println("======= [Turn " + turnCount + "] 开始 =======");

            // 每轮请求前检查水位；压缩结果写回上下文（持久化），两次压缩之间历史前缀保持稳定
            contextHistory = new ArrayList<>(contextManager.compact(contextHistory));

            // 获取当前挂载的所有工具定义
            List<ToolDefinition> availableTools = registry.getAvailableTools();

            // Phase 1: 思考阶段 (Thinking Phase) - 剥夺工具，强制规划
            if (enableThinking) {
                System.out.println("[Engine][Phase 1] 剥夺工具访问权，强制进入思考与规划阶段...");

                // 核心机制：传入的 availableTools 为 null!
                // 大模型看不到任何 JSON Schema，被迫只能输出纯文本的思考过程
                Message thinkResponse = llmProvider.generate(contextHistory, null);
                if (thinkResponse.getContent() != null && !thinkResponse.getContent().isBlank()) {
                    System.out.println("🧠 [内部思考 Trace]: " + thinkResponse.getContent());
                    append(contextHistory, history, thinkResponse);
                }
            }

            // Phase 2: 行动阶段 (Action Phase) - 恢复工具访问权，执行行动
            System.out.println("[Engine][Phase 2] 恢复工具挂载，等待模型采取行动...");

            Message actionResponse = llmProvider.generate(contextHistory, availableTools);
            if (actionResponse.getContent() != null && !actionResponse.getContent().isBlank()) {
                System.out.println("🤖 [对外回复]: " + actionResponse.getContent());
            }
            append(contextHistory, history, actionResponse);

            // 3. 退出条件判断
            List<ToolCall> toolCalls = actionResponse.getToolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                // Plan Mode：TODO 还有未完成项就想收工，提醒有限次数，防止模型过早放弃
                if (planModeOn && planNudges < maxPlanNudges) {
                    List<TodoItem> pending = contextManager.pendingTodos(sessionDir);
                    if (!pending.isEmpty()) {
                        planNudges++;
                        System.out.println("[Engine] TODO.md 仍有 " + pending.size() + " 项未完成，提醒模型继续（"
                                + planNudges + "/" + maxPlanNudges + "）。");
                        append(contextHistory, history, new Message(Role.USER, buildPlanNudge(pending), null, null));
                        continue;
                    }
                }
                System.out.println("[Engine] 模型未请求调用工具，任务宣告完成。");
                break;
            }

            // 4. 执行行动 （Action） 与 获取观察结果 （Observation）
            System.out.println(String.format("[Engine] 模型请求调用 %d 个工具...", toolCalls.size()));
            List<ToolResult> results = executeTools(registry, toolCalls);
            for (int i = 0; i < toolCalls.size(); i++) {
                ToolResult result = results.get(i);
                append(contextHistory, history, new Message(Role.USER, result.getOutput(), null, toolCalls.get(i).getId()));
                reminder.observe(toolCalls.get(i), result);
            }

            // 5. 死循环检测：必要时在上下文尾部注入提醒，或直接终止
            Reminder.Verdict verdict = reminder.check();
            if (verdict.action() == Reminder.Action.ABORT) {
                System.out.println("[Engine] ⛔ 任务因陷入死循环被终止：" + verdict.reason());
                break;
            }
            if (verdict.action() == Reminder.Action.WARN) {
                append(contextHistory, history, verdict.message());
            }
            // 循环回到开头，模型将带着新加入的 Observation 继续它的下一轮思考...
        }
    }

    private List<ToolResult> executeTools(Registry registry, List<ToolCall> toolCalls) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<ToolResult>> tasks = new ArrayList<>();
            for (int i = 0; i < toolCalls.size(); i++) {
                int idx = i;
                var toolCall = toolCalls.get(i);
                tasks.add(() -> {
                    System.out.println(String.format(" -> [Java-%d] 🛠️ 触发并行执行: %s", idx, toolCall.getName()));

                    var result = registry.execute(toolCall);
                    if (result.isError()) {
                        System.out.println(String.format(" -> [Java-%d] ❌ 工具执行报错: %s", idx, result.getOutput()));
                    } else if (result.isSoftFailure()) {
                        System.out.println(String.format(" -> [Java-%d] ⚠️ 工具执行完成但动作失败（返回 %d 字节）", idx,
                                result.getOutput().length()));
                    } else {
                        System.out.println(String.format(" -> [Java-%d] ✅ 工具执行成功（返回 %d 字节）", idx,
                                result.getOutput().length()));
                    }
                    return result;
                });
            }

            List<ToolResult> results = new ArrayList<>();
            for (Future<ToolResult> future : executor.invokeAll(tasks)) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待工具执行被中断", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("工具执行失败", e);
        }
    }

    private static void append(List<Message> context, SessionHistory history, Message message) {
        context.add(message);
        history.append(message);
    }

    private static String buildPlanNudge(List<TodoItem> pending) {
        String list = pending.stream().limit(5).map(TodoItem::toString).collect(Collectors.joining("\n"));
        return """
                [Plan Mode 提醒] TODO.md 中仍有 %d 个未完成的步骤，例如：
                %s
                请继续执行第一个未完成的步骤。如果某个步骤其实已经完成，请用 edit_file 将其标记为 [x]；
                如果确实无法完成，请将其标记为 [!] 并写明原因，然后再向用户总结。
                """.formatted(pending.size(), list);
    }

    /** 校验 sessionId，防止路径穿越；为空时按时间戳生成新 session。 */
    private static String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "session-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        }
        String id = sessionId.strip();
        if (!SESSION_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("非法的 sessionId: " + sessionId + "（只允许字母、数字、下划线和短横线，最长 64 位）");
        }
        return id;
    }

    private Path prepareWorkDir(String id) {
        Path dir = workspaceRoot.resolve(id).normalize();
        try {
            if (Files.isDirectory(dir)) {
                System.out.println("[Engine] 恢复已有 session 工作区: " + dir);
            } else {
                Files.createDirectories(dir);
                System.out.println("[Engine] 新 session，已创建工作区: " + dir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("创建 session 工作区失败: " + dir, e);
        }
        return dir;
    }
}
