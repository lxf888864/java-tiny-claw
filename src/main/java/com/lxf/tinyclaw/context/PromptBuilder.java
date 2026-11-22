package com.lxf.tinyclaw.context;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * 动态系统提示词组装器。
 *
 * <p>
 * 组成（按顺序）：
 * <ol>
 * <li>全局 {@code <agentDir>/AGENT.md}：不存在时用 classpath 下的默认模板初始化</li>
 * <li>会话级 {@code <workDir>/AGENT.md}（可选）：对当前会话的追加规则</li>
 * <li>运行环境信息：工作区、操作系统、日期等，防止模型对环境做错误假设</li>
 * <li>Plan Mode 规则（仅 planModeOn 时）</li>
 * </ol>
 */
public class PromptBuilder {

    public static final String AGENT_FILE = "AGENT.md";
    private static final String DEFAULT_TEMPLATE = "/AGENT.md";

    private final Path agentDir;

    public PromptBuilder(Path agentDir) {
        this.agentDir = agentDir.toAbsolutePath().normalize();
    }

    public String build(Path workDir, boolean planModeOn) {
        StringBuilder sb = new StringBuilder();
        sb.append(loadGlobalAgentMd().strip()).append("\n");

        Path sessionAgentMd = workDir.resolve(AGENT_FILE);
        if (Files.isRegularFile(sessionAgentMd)) {
            sb.append("\n# 会话级补充规则（来自工作区 AGENT.md）\n")
                    .append(readString(sessionAgentMd).strip()).append("\n");
        }

        sb.append("\n# 运行环境\n")
                .append("- 工作区（所有相对路径都基于此目录，禁止访问其外部）：").append(workDir).append("\n")
                .append("- 操作系统：").append(System.getProperty("os.name")).append("\n")
                .append("- bash 工具使用 bash 执行命令")
                .append(isWindows() ? "（Windows 下为 Git Bash，请使用 POSIX 命令与正斜杠路径）" : "")
                .append("\n")
                .append("- 当前日期：").append(LocalDate.now()).append("\n");

        if (planModeOn) {
            sb.append("\n").append(PlanManager.PLAN_MODE_RULES);
        }
        return sb.toString();
    }

    /** 读取全局 AGENT.md；不存在则从默认模板初始化到磁盘，方便用户后续自行修改。 */
    private String loadGlobalAgentMd() {
        Path file = agentDir.resolve(AGENT_FILE);
        if (Files.isRegularFile(file)) {
            return readString(file);
        }
        String template = loadDefaultTemplate();
        try {
            Files.createDirectories(agentDir);
            Files.writeString(file, template, StandardCharsets.UTF_8);
            System.out.println("[Context] 未找到 AGENT.md，已用默认模板初始化: " + file);
        } catch (IOException e) {
            System.out.println("[Context] ⚠️ 初始化 AGENT.md 失败（本次使用内置模板）: " + e.getMessage());
        }
        return template;
    }

    private static String loadDefaultTemplate() {
        try (InputStream is = PromptBuilder.class.getResourceAsStream(DEFAULT_TEMPLATE)) {
            if (is == null) {
                return "You are tiny-claw, an expert coding assistant. You have full access to tools in the workspace.";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + file + " 失败", e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
