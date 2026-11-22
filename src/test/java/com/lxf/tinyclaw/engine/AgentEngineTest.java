package com.lxf.tinyclaw.engine;

import static com.lxf.tinyclaw.support.TestData.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lxf.tinyclaw.context.ContextManager;
import com.lxf.tinyclaw.schema.Message;
import com.lxf.tinyclaw.schema.Role;
import com.lxf.tinyclaw.support.ScriptedLlmProvider;
import com.lxf.tinyclaw.support.TestData;

/**
 * AgentEngine 端到端测试：用 {@link ScriptedLlmProvider} 扮演模型，工具是真实的，文件读写发生在临时目录。
 *
 * <p>
 * 阅读建议：每个用例的"剧本"就是模型在每一轮的回复，对照引擎的日志输出（控制台）理解 ReAct 循环。
 */
class AgentEngineTest {

    @TempDir
    Path root;

    private Path agentDir;
    private Path workspaces;
    private ScriptedLlmProvider llm;

    @BeforeEach
    void setUp() {
        agentDir = root.resolve("agent");
        workspaces = root.resolve("workspaces");
        TestData.copy("agent/AGENT.md", agentDir, "AGENT.md");
        llm = new ScriptedLlmProvider();
    }

    private AgentEngine engine(int compactThreshold) {
        return new AgentEngine(llm, TestData::registry, new ContextManager(agentDir, compactThreshold),
                new Reminder(), workspaces);
    }

    private AgentEngine engine() {
        return engine(60_000);
    }

    @Nested
    @DisplayName("1. Session 与工作区")
    class Sessions {

        @Test
        @DisplayName("新 session 自动创建 workspaces/<sessionId>，工具在该目录下读写")
        void newSessionCreatesWorkDir() throws Exception {
            llm.callTool("write_file", "{\"path\":\"hello.txt\",\"content\":\"hi\"}").reply("写好了");
            AgentEngine engine = engine();

            engine.run("s1", "写一个 hello.txt");

            Path sessionDir = workspaces.resolve("s1");
            assertThat(engine.getWorkDir()).isEqualTo(sessionDir.toString());
            assertThat(Files.readString(sessionDir.resolve("hello.txt"))).isEqualTo("hi");
        }

        @Test
        @DisplayName("不同 session 的文件互相隔离")
        void sessionsAreIsolated() {
            llm.callTool("write_file", "{\"path\":\"secret.txt\",\"content\":\"A 的数据\"}").reply("ok")
                    .callTool("read_file", "{\"path\":\"secret.txt\"}").reply("没找到");
            AgentEngine engine = engine();

            engine.run("alice", "写文件");
            engine.run("bob", "读 alice 的文件");

            // bob 的 read_file 返回的是"读取失败"，看不到 alice 的文件
            Message bobRead = llm.lastRequest().last();
            assertThat(bobRead.getToolCallId()).isNotNull();
            assertThat(bobRead.getContent()).contains("读取文件失败");
        }

        @Test
        @DisplayName("非法 sessionId（路径穿越）被拒绝；空 sessionId 自动生成")
        void sessionIdValidation() {
            AgentEngine engine = engine();
            assertThatThrownBy(() -> engine.run("../evil", "x")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> engine.run("a/b", "x")).isInstanceOf(IllegalArgumentException.class);

            llm.reply("ok");
            engine.run(null, "x");
            assertThat(engine.getSessionId()).startsWith("session-");
            assertThat(Path.of(engine.getWorkDir())).isDirectory();
        }

        @Test
        @DisplayName("原始对话写入 .claw/history.jsonl")
        void historyIsPersisted() throws Exception {
            llm.callTool("bash", "{\"command\":\"echo hi\"}").reply("done");

            engine().run("h1", "随便做点什么");

            Path history = workspaces.resolve("h1/.claw/history.jsonl");
            assertThat(history).exists();
            String text = Files.readString(history);
            assertThat(text).contains("\"event\":\"run_start\"").contains("随便做点什么").contains("\"role\":\"ASSISTANT\"");
        }
    }

    @Nested
    @DisplayName("2. 系统提示词来自 AGENT.md")
    class SystemPrompt {

        @Test
        @DisplayName("第一条消息是 SYSTEM，内容来自 agentDir/AGENT.md 并包含运行环境")
        void systemPromptFromAgentMd() {
            llm.reply("ok");

            engine().run("p1", "你好");

            Message system = llm.requests().get(0).messages().get(0);
            assertThat(system.getRole()).isEqualTo(Role.SYSTEM);
            assertThat(system.getContent()).contains("TEST-AGENT-MARKER").contains("# 运行环境");
        }
    }

    @Nested
    @DisplayName("3. Plan Mode")
    class PlanMode {

        @Test
        @DisplayName("新任务：先规划 → 逐项执行并勾选 → 想提前结束时被提醒 → 全部完成")
        void fullPlanFlow() throws Exception {
            String todo = "# TODO\n- [ ] T1: 创建 Calculator.java\n- [ ] T2: 编译验证\n";
            llm.callTools(
                    "write_file", "{\"path\":\"PLAN.md\",\"content\":" + json("# PLAN\n递归下降解析") + "}",
                    "write_file", "{\"path\":\"TODO.md\",\"content\":" + json(todo) + "}")   // 轮 1：规划
                    .callTool("edit_file", "{\"path\":\"TODO.md\",\"oldText\":\"- [ ] T1\",\"newText\":\"- [x] T1\"}")
                    .reply("我觉得做完了")                                                     // 轮 3：想提前结束
                    .callTool("edit_file", "{\"path\":\"TODO.md\",\"oldText\":\"- [ ] T2\",\"newText\":\"- [x] T2\"}")
                    .reply("全部完成");
            AgentEngine engine = engine();
            engine.setPlanModeOn(true);

            engine.run("plan-new", "实现一个计算器");

            // 首次请求：提示词要求先写计划
            assertThat(llm.requests().get(0).last().getContent()).contains("还没有计划文件");
            assertThat(llm.requests().get(0).messages().get(0).getContent()).contains("Plan Mode 规则");
            // 轮 3 想结束时，引擎注入了提醒，并点名未完成的 T2
            assertThat(llm.requests().get(3).last().getContent()).contains("Plan Mode 提醒").contains("T2");
            // 最终 TODO 全部勾选
            assertThat(Files.readString(workspaces.resolve("plan-new/TODO.md")))
                    .contains("- [x] T1").contains("- [x] T2").doesNotContain("- [ ]");
            assertThat(llm.remaining()).isZero();
        }

        @Test
        @DisplayName("恢复任务：自动读取已有 PLAN/TODO，并指出从 T3 继续")
        void resumeExistingPlan() {
            Path dir = workspaces.resolve("plan-resume");
            TestData.copy("plan/PLAN.md", dir, "PLAN.md");
            TestData.copy("plan/TODO.md", dir, "TODO.md");
            llm.reply("好的").reply("嗯").reply("真的不做了");   // 一直不干活：会被提醒 maxPlanNudges(2) 次
            AgentEngine engine = engine();
            engine.setPlanModeOn(true);

            engine.run("plan-resume", "继续");

            ScriptedLlmProvider.Request first = llm.requests().get(0);
            assertThat(first.allContent())
                    .contains("递归下降解析")                    // PLAN.md 已读入
                    .contains("- [ ] T3: 实现递归下降 Parser")  // TODO.md 已读入且指出当前任务
                    .contains("当前进度 2/6");
            assertThat(first.messages()).anyMatch(m -> "pinned_read_todo".equals(m.getToolCallId()));
            // 提醒 2 次后放弃提醒，任务结束
            assertThat(llm.requests()).hasSize(3);
            assertThat(llm.remaining()).isZero();
        }
    }

    @Nested
    @DisplayName("4. 死循环防护")
    class LoopGuard {

        @Test
        @DisplayName("模型反复读取同一个不存在的文件：第 3 次后注入警告，警告无效则终止任务")
        void reminderWarnsThenAborts() {
            llm.repeatToolCall(10, "read_file", "{\"path\":\"no-such-file.txt\"}");

            engine().run("loop", "读文件");

            // 第 3 次失败后的下一次请求，最后一条是警告
            assertThat(llm.requests().get(3).last().getContent()).contains("SYSTEM REMINDER");
            // 警告 3 次后，第 8 次调用时再次触发 → 终止，剧本没有用完（详见 ReminderTest.escalatesThenAborts）
            assertThat(llm.requests()).hasSize(8);
            assertThat(llm.remaining()).isEqualTo(2);
        }

        @Test
        @DisplayName("maxTurns 硬上限：即使每次调用都不同，也会在上限处停止")
        void maxTurnsStopsTheLoop() {
            Stream.iterate(1, i -> i + 1).limit(10)
                    .forEach(i -> llm.callTool("bash", "{\"command\":\"echo step-" + i + "\"}"));
            AgentEngine engine = engine();
            engine.setMaxTurns(3);

            engine.run("max", "无限循环");

            assertThat(llm.requests()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("5. 上下文压缩")
    class Compaction {

        @Test
        @DisplayName("超过阈值后，发给模型的超长日志被掐头去尾，而 history.jsonl 中保留完整原文")
        void longOutputTruncatedButRawKeptInHistory() throws Exception {
            Path dir = workspaces.resolve("compact");
            TestData.copy("logs/stackoverflow.log", dir, "error.log");
            llm.callTool("read_file", "{\"path\":\"error.log\"}").reply("找到原因了：括号不匹配");

            engine(5_000).run("compact", "分析 error.log");

            String seenByModel = llm.lastRequest().last().getContent();
            assertThat(seenByModel).contains("HEAD-MARKER").contains("...[内容过长，中间 ").hasSizeLessThan(1100);
            assertThat(Files.size(dir.resolve(".claw/history.jsonl"))).isGreaterThan(8_000);
        }
    }
}
