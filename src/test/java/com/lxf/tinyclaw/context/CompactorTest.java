package com.lxf.tinyclaw.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.lxf.tinyclaw.schema.Message;
import com.lxf.tinyclaw.schema.Role;
import com.lxf.tinyclaw.schema.ToolCall;
import com.lxf.tinyclaw.support.TestData;

/**
 * 上下文压缩策略：
 * <ul>
 * <li>a. System Prompt 永远保留</li>
 * <li>b. 远期历史：保留 ToolCall，工具输出掩码为一句话</li>
 * <li>c. Working Memory（最近 10 条）：完整保留，但单条工具输出超过 1000 字符时掐头去尾</li>
 * </ul>
 */
class CompactorTest {

    private static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null);
    }

    private static Message user(String content) {
        return new Message(Role.USER, content, null, null);
    }

    private static Message assistantCall(String content, ToolCall... calls) {
        return new Message(Role.ASSISTANT, content, List.of(calls), null);
    }

    private static Message toolResult(String toolCallId, String output) {
        return new Message(Role.USER, output, null, toolCallId);
    }

    /**
     * 构造一段典型对话：system + user + rounds 轮 "assistant 调用 bash → 返回 outputSize 字符"。
     * 消息总数 = 2 + rounds * 2
     */
    private static List<Message> conversation(int rounds, int outputSize) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(system("你是 tiny-claw。".repeat(20)));
        msgs.add(user("帮我排查编译错误"));
        for (int i = 0; i < rounds; i++) {
            msgs.add(assistantCall("第 " + i + " 轮：我需要查看日志。",
                    new ToolCall("call_" + i, "bash", "{\"command\":\"cat build-" + i + ".log\"}")));
            msgs.add(toolResult("call_" + i, ("日志" + i + "-").repeat(outputSize / 4)));
        }
        return msgs;
    }

    @Test
    @DisplayName("未超过阈值：原样返回同一个列表，不做任何处理")
    void belowThresholdReturnsSameList() {
        List<Message> msgs = conversation(3, 400);
        Compactor compactor = new Compactor(100_000);

        assertThat(compactor.compact(msgs)).isSameAs(msgs);
    }

    @Test
    @DisplayName("超过阈值：远期工具输出被掩码，但 ToolCall 原样保留，且掩码里带有工具名和参数")
    void farHistoryToolOutputIsMaskedButToolCallsKept() {
        List<Message> msgs = conversation(10, 2000); // 22 条消息，约 2 万字符
        List<Message> out = new Compactor(10_000).compact(msgs);

        // 第 0 轮（下标 2、3）位于远期历史
        Message call0 = out.get(2);
        Message result0 = out.get(3);
        assertThat(call0.getToolCalls()).hasSize(1);
        assertThat(call0.getToolCalls().get(0).getArguments()).contains("build-0.log");
        assertThat(result0.getContent())
                .startsWith("...[为了节省内存，早期的工具输出已被系统清理")
                .contains("原始长度: " + msgs.get(3).getContent().length())
                .contains("bash(")
                .contains("build-0.log");
        assertThat(result0.getToolCallId()).isEqualTo("call_0"); // 关联关系不变

        // 消息条数不变：压缩只改内容，不删消息，tool_call / tool_result 的配对永远完整
        assertThat(out).hasSameSizeAs(msgs);
        // 原列表没有被修改
        assertThat(msgs.get(3).getContent()).doesNotContain("早期的工具输出");
    }

    @Test
    @DisplayName("System Prompt 和用户消息永远不会被压缩")
    void systemPromptAndUserMessagesAreNeverTouched() {
        List<Message> msgs = conversation(10, 2000);
        List<Message> out = new Compactor(10_000).compact(msgs);

        assertThat(out.get(0)).isSameAs(msgs.get(0));
        assertThat(out.get(1).getContent()).isEqualTo("帮我排查编译错误");
    }

    @Test
    @DisplayName("Working Memory 内超长的工具输出被掐头去尾：保留报错开头和堆栈结尾")
    void workingMemoryLongOutputIsHeadTailTruncated() {
        String log = TestData.read("logs/stackoverflow.log"); // 约 1.1 万字符的真实风格堆栈
        List<Message> msgs = conversation(10, 2000);
        msgs.add(assistantCall("编译一下看看", new ToolCall("call_last", "bash", "{\"command\":\"java Calculator\"}")));
        msgs.add(toolResult("call_last", log));

        List<Message> out = new Compactor(10_000).compact(msgs);
        String truncated = out.get(out.size() - 1).getContent();

        assertThat(truncated)
                .contains("HEAD-MARKER")   // 开头：错误原因
                .contains("TAIL-MARKER")   // 结尾：Caused by 总结
                .contains("...[内容过长，中间 ")
                .hasSizeLessThan(1100);
        assertThat(truncated.substring(0, 500)).isEqualTo(log.substring(0, 500));
        assertThat(truncated).endsWith(log.substring(log.length() - 500));
    }

    @Test
    @DisplayName("Working Memory 内的短输出完整保留")
    void workingMemoryShortOutputIsKept() {
        List<Message> msgs = conversation(10, 2000);
        msgs.add(assistantCall("", new ToolCall("call_ok", "bash", "{\"command\":\"ls\"}")));
        msgs.add(toolResult("call_ok", "Calculator.java\nPLAN.md\nTODO.md"));

        List<Message> out = new Compactor(10_000).compact(msgs);

        assertThat(out.get(out.size() - 1).getContent()).isEqualTo("Calculator.java\nPLAN.md\nTODO.md");
    }

    @Test
    @DisplayName("保护区按轮次对齐：同一批并行调用的结果不会一半掩码一半保留")
    void protectZoneAlignsToTurnBoundary() {
        List<Message> msgs = conversation(6, 2000); // 14 条
        // 最后一轮并行调用 12 个工具，共 13 条消息，超过保护区的 10 条
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            calls.add(new ToolCall("p" + i, "read_file", "{\"path\":\"f" + i + ".txt\"}"));
        }
        msgs.add(new Message(Role.ASSISTANT, "并行读取 12 个文件", calls, null));
        for (int i = 0; i < 12; i++) {
            msgs.add(toolResult("p" + i, "内容".repeat(300)));
        }

        List<Message> out = new Compactor(10_000).compact(msgs);

        // 这一批的 12 个结果都在保护区内：都没有被掩码
        for (int i = out.size() - 12; i < out.size(); i++) {
            assertThat(out.get(i).getContent()).doesNotContain("早期的工具输出");
        }
    }

    @Test
    @DisplayName("钉住的计划文件（toolCallId 以 pinned_ 开头）永不压缩")
    void pinnedPlanMessagesAreNeverCompacted() {
        String todo = TestData.read("plan/TODO.md").repeat(5); // 故意做长一些
        List<Message> msgs = new ArrayList<>();
        msgs.add(system("sys"));
        msgs.add(user("继续任务"));
        msgs.add(assistantCall("读取计划", new ToolCall("pinned_read_todo", "read_file", "{\"path\":\"TODO.md\"}")));
        msgs.add(toolResult("pinned_read_todo", todo));
        msgs.addAll(conversation(10, 2000).subList(2, 22));

        List<Message> out = new Compactor(10_000).compact(msgs);

        assertThat(out.get(3).getContent()).isEqualTo(todo);
    }

    @Test
    @DisplayName("远期的长推理文本被折叠")
    void farHistoryLongReasoningIsFolded() {
        List<Message> msgs = conversation(10, 2000);
        msgs.set(2, assistantCall("我在想……".repeat(100), msgs.get(2).getToolCalls().get(0)));

        List<Message> out = new Compactor(10_000).compact(msgs);

        assertThat(out.get(2).getContent()).startsWith("...[早期的推理思考过程已折叠");
        assertThat(out.get(2).getToolCalls()).isEqualTo(msgs.get(2).getToolCalls());
    }

    @Test
    @DisplayName("幂等：对压缩结果再次压缩不会产生任何变化（保证 API 前缀缓存稳定）")
    void compactionIsIdempotent() {
        List<Message> msgs = conversation(10, 2000);
        msgs.add(assistantCall("", new ToolCall("big", "bash", "{}")));
        msgs.add(toolResult("big", TestData.read("logs/stackoverflow.log")));
        Compactor compactor = new Compactor(5_000);

        List<Message> once = compactor.compact(msgs);
        List<Message> twice = compactor.compact(once);

        for (int i = 0; i < once.size(); i++) {
            assertThat(twice.get(i).getContent()).isEqualTo(once.get(i).getContent());
        }
    }

    @Test
    @DisplayName("紧急降级：常规压缩后仍高于低水位时，保护区会缩小")
    void emergencyShrinksWorkingMemory() {
        // 每条 900 字符：在保护区内不会被截断（<1000），常规压缩后仍然很大
        List<Message> msgs = conversation(10, 900);
        int before = Compactor.estimateLength(msgs);

        List<Message> out = new Compactor(before - 1, 0.3).compact(msgs);

        // 常规保护区（最近 10 条）包含最后 5 轮；降级到最近 4 条后只剩最后 2 轮，
        // 所以倒数第三轮的结果（倒数第 5 条）本应受保护，现在也被掩码了
        assertThat(out.get(out.size() - 5).getContent()).startsWith("...[为了节省内存");
        // 最后一轮始终保留
        assertThat(out.get(out.size() - 1).getContent()).isEqualTo(msgs.get(msgs.size() - 1).getContent());
    }
}
