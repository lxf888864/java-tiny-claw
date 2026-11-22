package com.lxf.tinyclaw.tools;

import static com.openai.core.ObjectMappers.jsonMapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.lxf.tinyclaw.schema.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;

public class BashTool implements BaseTool {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_LEN = 8000;
    // Windows 下直接执行 "bash" 往往命中 System32 中的 WSL 启动器，没装发行版时必然失败，因此优先定位 Git Bash
    private static final String BASH = resolveBash();

    private final String name;
    private final String description;
    private final String workDir;
    private final String inputSchema;

    public BashTool(String workDir) {
        this.name = "bash";
        this.description = "在当前工作区执行任意的 bash 命令。支持链式命令(如 &&)。返回标准输出(stdout)和标准错误(stderr)。";
        this.workDir = workDir;
        this.inputSchema = """
                            {
                                "type": "object",
                                "properties":
                                {
                                    "command":
                                    {
                                        "type": "string",
                                        "description": "要执行的 bash 命令，例如: ls -la 或者 go test ./..."
                                    }
                                },
                                "required":
                                [
                                    "command"
                                ]
                            }
                """;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public ToolDefinition getDefinition() {
        return new ToolDefinition(this.name, this.description, this.inputSchema);
    }

    @Override
    public ExecResult execute(String arguments) {
        BashArgs input;
        try {
            input = jsonMapper().readValue(arguments, BashArgs.class);
        } catch (JsonProcessingException e) {
            return new ExecResult("参数解析失败：" + e.getMessage(), false);
        }

        if (input.command == null || input.command.isBlank()) {
            return new ExecResult("command 为空，未执行任何命令：", false);
        }

        // 【底线1】超时：防止 top / 常驻 web 把引擎卡死
        // 【底线2】工作区：命令在 workDir 里跑，不是 JVM 启动目录
        // bash -c：才能用 &&、管道、环境变量
        var pb = new ProcessBuilder(BASH, "-c", input.command);
        pb.directory(new File(this.workDir));
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new ExecResult("启动进程失败：" + e.getMessage(), false);
        }

        boolean finished;
        try {
            finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ExecResult("执行被中断：" + e.getMessage(), false);
        }

        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(); // 等杀干净再读取管道，避免读到一半
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var partial = readOutput(process);
            return new ExecResult(
                    partial + "\n[警告: 命令执行超时(" + TIMEOUT_SECONDS
                            + "s)，已被系统强制终止。如果是启动常驻服务，请尝试将其转入后台。]",
                    true);
        }

        var output = readOutput(process);

        // 【底线3】bash 失败不要当成工具崩溃：把报错文本交给模型自愈
        if (process.exitValue() != 0) {
            // softFailure=true：不算工具崩溃，但作为失败信号交给 Reminder 做死循环检测
            return new ExecResult("执行报错：exit " + process.exitValue() + "\n输出：\n" + output, true, true);
        }

        if (output.isEmpty()) {
            return new ExecResult("命令执行成功，无终端输出。", true);
        }

        // 【底线4】截断，防止上下文被刷爆
        if (output.length() > MAX_LEN) {
            return new ExecResult(
                    output.substring(0, MAX_LEN)
                            + "\n\n...[终端输出过长，已截断至前 " + MAX_LEN + " 字节]...",
                    true);
        }

        return new ExecResult(output, true);
    }

    private static String readOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "读取进程输出失败：" + e.getMessage();
        }
    }

    /**
     * 定位 bash 可执行文件：环境变量 CLAW_BASH 优先；Windows 下根据 PATH 中的 git.exe 推导 Git Bash 位置；
     * 都找不到时退回 "bash"。
     */
    private static String resolveBash() {
        String configured = System.getenv("CLAW_BASH");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return "bash";
        }
        String pathEnv = System.getenv().getOrDefault("PATH", System.getenv().getOrDefault("Path", ""));
        for (String entry : pathEnv.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path dir = Path.of(entry.strip());
                if (Files.isRegularFile(dir.resolve("git.exe")) && dir.getParent() != null) {
                    // git.exe 位于 <GitRoot>/cmd 或 <GitRoot>/bin 或 <GitRoot>/mingw64/bin
                    for (Path root = dir.getParent(); root != null; root = root.getParent()) {
                        for (String candidate : new String[] { "bin/bash.exe", "usr/bin/bash.exe" }) {
                            if (Files.isRegularFile(root.resolve(candidate))) {
                                return root.resolve(candidate).toString();
                            }
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // PATH 中的非法条目直接跳过
            }
        }
        for (String fallback : new String[] { "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe" }) {
            if (Files.isRegularFile(Path.of(fallback))) {
                return fallback;
            }
        }
        return "bash";
    }

    private record BashArgs(String command) {
    }
}
