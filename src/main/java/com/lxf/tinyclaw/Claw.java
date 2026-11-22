package com.lxf.tinyclaw;

import java.nio.file.Path;

import com.lxf.tinyclaw.context.ContextManager;
import com.lxf.tinyclaw.engine.AgentEngine;
import com.lxf.tinyclaw.engine.Reminder;
import com.lxf.tinyclaw.provider.LlmProvider;
import com.lxf.tinyclaw.provider.OpenaiProvider;
import com.lxf.tinyclaw.tools.BashTool;
import com.lxf.tinyclaw.tools.EditFileTool;
import com.lxf.tinyclaw.tools.ReadFileTool;
import com.lxf.tinyclaw.tools.Registry;
import com.lxf.tinyclaw.tools.RegistryImpl;
import com.lxf.tinyclaw.tools.WriteFileTool;

/**
 * 入口。
 *
 * <p>
 * 在这里组装 Provider、Registry、ContextManager、Reminder、AgentEngine，然后发起一次任务。
 * 用法：{@code Claw [sessionId]}，同一个 sessionId 再次运行会复用其工作区（含 PLAN.md / TODO.md）。
 */
public final class Claw {

    // 触发上下文压缩的字符数阈值
    private static final int COMPACT_THRESHOLD_CHARS = 60_000;

    public static void main(String[] args) {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        // 所有 session 的工作区根目录：<项目根>/workspaces/<sessionId>
        Path workspaceRoot = projectRoot.resolve("workspaces");
        // 全局 AGENT.md 所在目录（不存在时自动从默认模板生成）
        Path agentDir = projectRoot;

        LlmProvider llmProvider = new OpenaiProvider("deepseek-flash");

        AgentEngine agentEngine = new AgentEngine(
                llmProvider,
                Claw::createRegistry,
                new ContextManager(agentDir, COMPACT_THRESHOLD_CHARS),
                new Reminder(),
                workspaceRoot);
        agentEngine.setEnableThinking(false);
        agentEngine.setPlanModeOn(true);

        String sessionId = args.length > 0 && !args[0].startsWith("--") ? args[0] : "demo";
        String prompt = """
                在当前目录下用 Java 实现一个命令行计算器 Calculator.java，支持加减乘除四则运算和括号，
                除数为 0 时给出友好提示。完成后用 javac 编译，并用几个表达式运行验证结果。
                """;
        agentEngine.run(sessionId, prompt);
        System.out.println("任务完成！工作区: " + agentEngine.getWorkDir());
    }

    /** 每个 session 用自己的工作区创建一份工具注册表。 */
    private static Registry createRegistry(String workDir) {
        Registry registry = new RegistryImpl();
        registry.register(new ReadFileTool(workDir));
        registry.register(new WriteFileTool(workDir));
        registry.register(new BashTool(workDir));
        registry.register(new EditFileTool(workDir));
        return registry;
    }
}
