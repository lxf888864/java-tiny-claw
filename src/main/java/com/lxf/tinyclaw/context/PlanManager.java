package com.lxf.tinyclaw.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.lxf.tinyclaw.schema.Message;
import com.lxf.tinyclaw.schema.Role;
import com.lxf.tinyclaw.schema.ToolCall;
import com.lxf.tinyclaw.schema.ToolResult;
import com.lxf.tinyclaw.tools.Registry;

/**
 * Plan Mode：用工作区里的 PLAN.md / TODO.md 作为 Agent 的"外部记忆"。
 *
 * <ul>
 * <li>文件不存在：在首条用户消息中追加规划指令，要求模型先用 write_file 生成两个文件再动手。</li>
 * <li>文件已存在：由引擎主动执行 read_file，并以"模型调用 read_file + 工具返回"的形式注入上下文，
 * 同时显式告诉模型第一个未完成的任务。这些消息会被 Compactor 视为钉住（pinned）内容，永不掩码。</li>
 * </ul>
 *
 * TODO 标记格式：{@code - [ ] T3: 描述}（未完成）/ {@code - [x] T3: 描述}（已完成）/ {@code - [!] T3: 描述}（阻塞）。
 * 编号 T&lt;n&gt; 保证每一行唯一，使 edit_file 的 oldText 匹配总是精确的。
 */
public class PlanManager {

    public static final String PLAN_FILE = "PLAN.md";
    public static final String TODO_FILE = "TODO.md";

    /** 带此前缀的 toolCallId 表示由引擎注入的钉住消息，Compactor 不会对其做任何压缩。 */
    public static final String PINNED_ID_PREFIX = "pinned_";

    // 匹配 "- [ ] T3: xxx"、"* [x] T12 xxx"、"- [!] T4：xxx"
    private static final Pattern TODO_LINE = Pattern.compile(
            "^\\s*[-*]\\s*\\[([ xX!])\\]\\s*(T\\d+)\\s*[:：]?\\s*(.*)$");

    static final String PLAN_MODE_RULES = """
            # Plan Mode 规则（已开启）

            工作区中的 PLAN.md 与 TODO.md 是本任务的长期记忆，上下文被压缩后你只能依赖它们恢复进度，请认真维护：

            - PLAN.md：记录对任务的理解、整体架构设计、技术选型及理由、关键约束与风险。
            - TODO.md：拆解出的可执行步骤，每行一个步骤，格式严格为：
              - [ ] T1: 步骤描述（未完成）
              - [x] T1: 步骤描述（已完成）
              - [!] T1: 步骤描述 —— 阻塞原因（无法完成）
              编号 T1、T2…… 全局唯一且不可复用，步骤粒度以"一次可以完成并验证"为宜。

            执行纪律：
            1. 每次只做第一个未完成（- [ ]）的步骤，完成并验证后，立即用 edit_file 把该行的 "- [ ] Tn" 改为 "- [x] Tn"
               （oldText 只需包含该行，编号保证唯一），然后再开始下一个步骤。
            2. 执行中发现计划需要调整时，先更新 PLAN.md / TODO.md，再继续执行。
            3. 某步骤确实无法完成时，标记为 - [!] 并写明原因，不要无限重试。
            4. 所有步骤都标记完成后，再向用户做最终总结。
            """;

    public record TodoItem(String id, char mark, String text) {

        public boolean isPending() {
            return mark == ' ';
        }

        @Override
        public String toString() {
            return "- [" + mark + "] " + id + ": " + text;
        }
    }

    /**
     * 构造会话开头的消息序列（不含 System Prompt）。
     *
     * <p>
     * 顺序为：user(任务) → [assistant(read_file 调用) → tool 结果...] → user(进度指引)。
     * 让用户消息排在最前，兼容要求首条消息必须为 user 的 Provider。
     */
    public List<Message> bootstrap(Path workDir, Registry registry, String userPrompt) {
        List<Message> messages = new ArrayList<>();
        boolean planExists = Files.isRegularFile(workDir.resolve(PLAN_FILE));
        boolean todoExists = Files.isRegularFile(workDir.resolve(TODO_FILE));

        if (!planExists && !todoExists) {
            System.out.println("[Plan] 未发现 PLAN.md / TODO.md，要求模型先完成规划。");
            messages.add(new Message(Role.USER, userPrompt + "\n\n" + """
                    [Plan Mode] 当前工作区还没有计划文件。在进行任何具体实现之前，请先：
                    1. 使用 write_file 创建 PLAN.md，写明任务理解、架构设计、技术选型；
                    2. 使用 write_file 创建 TODO.md，按 "- [ ] T1: 描述" 格式列出可执行步骤；
                    然后从 T1 开始逐项执行。""", null, null));
            return messages;
        }

        messages.add(new Message(Role.USER, userPrompt, null, null));

        // 由引擎主动发起 read_file，并伪造成模型的调用，以保持 tool_call → tool_result 的逻辑链
        List<ToolCall> reads = new ArrayList<>();
        if (planExists) {
            reads.add(new ToolCall(PINNED_ID_PREFIX + "read_plan", "read_file", "{\"path\":\"" + PLAN_FILE + "\"}"));
        }
        if (todoExists) {
            reads.add(new ToolCall(PINNED_ID_PREFIX + "read_todo", "read_file", "{\"path\":\"" + TODO_FILE + "\"}"));
        }
        messages.add(new Message(Role.ASSISTANT, "我先读取已有的计划文件，了解当前进度。", reads, null));
        for (ToolCall call : reads) {
            ToolResult result = registry.execute(call);
            System.out.println("[Plan] 已自动读取 " + call.getArguments() + "（" + result.getOutput().length() + " 字符）");
            messages.add(new Message(Role.USER, result.getOutput(), null, call.getId()));
        }

        messages.add(new Message(Role.USER, buildProgressGuide(workDir, planExists, todoExists), null, null));
        return messages;
    }

    private String buildProgressGuide(Path workDir, boolean planExists, boolean todoExists) {
        StringBuilder sb = new StringBuilder("[Plan Mode] 以上是已有的计划文件。");
        if (!planExists) {
            sb.append("PLAN.md 缺失，请先用 write_file 补充 PLAN.md。");
        }
        if (!todoExists) {
            sb.append("TODO.md 缺失，请先用 write_file 按 \"- [ ] T1: 描述\" 格式创建 TODO.md，然后从 T1 开始执行。");
            return sb.toString();
        }

        List<TodoItem> todos = readTodos(workDir);
        TodoItem first = todos.stream().filter(TodoItem::isPending).findFirst().orElse(null);
        if (first != null) {
            long done = todos.stream().filter(t -> t.mark() == 'x' || t.mark() == 'X').count();
            System.out.println("[Plan] 进度 " + done + "/" + todos.size() + "，继续执行: " + first);
            sb.append("当前进度 ").append(done).append("/").append(todos.size())
                    .append("。请从第一个未完成的任务继续执行：\n").append(first)
                    .append("\n完成后用 edit_file 将其标记为 [x]。如果用户本次的输入提出了新需求，请先更新 PLAN.md 并在 TODO.md 末尾追加新步骤。");
        } else {
            System.out.println("[Plan] TODO.md 中的任务已全部完成或阻塞。");
            sb.append("TODO.md 中没有未完成的任务。如果用户本次的输入提出了新需求，请先更新 PLAN.md，")
                    .append("并在 TODO.md 末尾追加新的步骤（编号沿用递增），再逐项执行；否则直接向用户汇报结果。");
        }
        return sb.toString();
    }

    /** 解析 TODO.md；文件不存在或无法读取时返回空列表。 */
    public List<TodoItem> readTodos(Path workDir) {
        Path todo = workDir.resolve(TODO_FILE);
        if (!Files.isRegularFile(todo)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(todo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[Plan] ⚠️ 读取 TODO.md 失败: " + e.getMessage());
            return List.of();
        }
        List<TodoItem> items = new ArrayList<>();
        for (String line : lines) {
            Matcher m = TODO_LINE.matcher(line);
            if (m.matches()) {
                items.add(new TodoItem(m.group(2), m.group(1).charAt(0), m.group(3).strip()));
            }
        }
        return items;
    }

    public List<TodoItem> pendingTodos(Path workDir) {
        return readTodos(workDir).stream().filter(TodoItem::isPending).collect(Collectors.toList());
    }
}
