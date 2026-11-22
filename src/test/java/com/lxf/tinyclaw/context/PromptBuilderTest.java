package com.lxf.tinyclaw.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lxf.tinyclaw.support.TestData;

/** 系统提示词组装：全局 AGENT.md + 会话级 AGENT.md + 运行环境 + Plan Mode 规则。 */
class PromptBuilderTest {

    @TempDir
    Path agentDir;

    @TempDir
    Path workDir;

    @Test
    @DisplayName("全局 AGENT.md 不存在时，用默认模板生成到磁盘并使用它")
    void createsDefaultAgentMdWhenMissing() throws Exception {
        String prompt = new PromptBuilder(agentDir).build(workDir, false);

        Path generated = agentDir.resolve("AGENT.md");
        assertThat(generated).exists();
        assertThat(prompt).contains("你是 tiny-claw").contains(Files.readString(generated).strip());
    }

    @Test
    @DisplayName("已有的全局 AGENT.md 会被原样使用，不会被覆盖")
    void usesExistingAgentMd() throws Exception {
        TestData.copy("agent/AGENT.md", agentDir, "AGENT.md");

        String prompt = new PromptBuilder(agentDir).build(workDir, false);

        assertThat(prompt).contains("TEST-AGENT-MARKER");
        assertThat(Files.readString(agentDir.resolve("AGENT.md"))).contains("TEST-AGENT-MARKER");
    }

    @Test
    @DisplayName("工作区中的会话级 AGENT.md 会追加在全局规则之后")
    void appendsSessionAgentMd() {
        TestData.copy("agent/AGENT.md", agentDir, "AGENT.md");
        TestData.copy("agent/session-AGENT.md", workDir, "AGENT.md");

        String prompt = new PromptBuilder(agentDir).build(workDir, false);

        assertThat(prompt.indexOf("SESSION-RULE-MARKER")).isGreaterThan(prompt.indexOf("TEST-AGENT-MARKER"));
        assertThat(prompt).contains("会话级补充规则");
    }

    @Test
    @DisplayName("运行环境信息：包含工作区路径和操作系统")
    void includesEnvironment() {
        String prompt = new PromptBuilder(agentDir).build(workDir, false);

        assertThat(prompt).contains("# 运行环境").contains(workDir.toString()).contains(System.getProperty("os.name"));
    }

    @Test
    @DisplayName("Plan Mode 开启时才包含 PLAN/TODO 规则")
    void planRulesOnlyWhenPlanModeOn() {
        PromptBuilder builder = new PromptBuilder(agentDir);

        assertThat(builder.build(workDir, false)).doesNotContain("Plan Mode 规则");
        assertThat(builder.build(workDir, true)).contains("Plan Mode 规则").contains("- [ ] T1: 步骤描述");
    }
}
