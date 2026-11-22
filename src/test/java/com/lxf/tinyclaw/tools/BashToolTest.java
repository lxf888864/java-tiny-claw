package com.lxf.tinyclaw.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lxf.tinyclaw.schema.ToolCall;
import com.lxf.tinyclaw.schema.ToolResult;

/**
 * bash 工具。依赖本机能找到 bash（Windows 下为 Git Bash，或通过环境变量 CLAW_BASH 指定），
 * 找不到时这些用例会被跳过而不是失败。
 */
class BashToolTest {

    @TempDir
    Path workDir;

    private RegistryImpl registry;

    private ToolResult bash(String command) {
        return registry.execute(new ToolCall("t", "bash", "{\"command\":\"" + command + "\"}"));
    }

    @BeforeEach
    void setUp() {
        registry = new RegistryImpl();
        registry.register(new BashTool(workDir.toString()));
        ToolResult probe = bash("echo probe");
        assumeFalse(probe.getOutput().contains("启动进程失败") || !probe.getOutput().contains("probe"),
                "本机找不到可用的 bash，跳过：" + probe.getOutput());
    }

    @Test
    @DisplayName("命令在 session 工作区中执行")
    void runsInWorkDir() throws Exception {
        Files.writeString(workDir.resolve("marker.txt"), "x");

        ToolResult r = bash("ls");

        assertThat(r.isFailure()).isFalse();
        assertThat(r.getOutput()).contains("marker.txt");
    }

    @Test
    @DisplayName("退出码非 0：不是工具报错（isError=false），但标记为动作失败（softFailure=true），供 Reminder 检测")
    void nonZeroExitIsSoftFailure() {
        ToolResult r = bash("echo boom >&2; exit 3");

        assertThat(r.isError()).isFalse();
        assertThat(r.isSoftFailure()).isTrue();
        assertThat(r.isFailure()).isTrue();
        assertThat(r.getOutput()).contains("exit 3").contains("boom");
    }

    @Test
    @DisplayName("成功的命令：既不是 isError 也不是 softFailure")
    void successIsNotFailure() {
        ToolResult r = bash("echo hello");

        assertThat(r.isFailure()).isFalse();
        assertThat(r.getOutput()).contains("hello");
    }
}
