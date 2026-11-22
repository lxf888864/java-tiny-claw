package com.lxf.tinyclaw.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.lxf.tinyclaw.engine.Reminder.Action;
import com.lxf.tinyclaw.engine.Reminder.Verdict;
import com.lxf.tinyclaw.schema.Role;
import com.lxf.tinyclaw.schema.ToolCall;
import com.lxf.tinyclaw.schema.ToolResult;

/**
 * 死循环检测。用法与 AgentEngine 一致：每个工具结果调用一次 observe，每轮结束调用一次 check。
 * 默认参数：连续失败 3 次触发；最近 12 次调用中同一调用出现 4 次触发；最多警告 3 次，第 4 次终止。
 */
class ReminderTest {

    private Reminder reminder;

    private static final ToolCall BUILD = new ToolCall("1", "bash", "{\"command\":\"javac Calculator.java\"}");
    private static final ToolCall READ_A = new ToolCall("2", "read_file", "{\"path\":\"A.java\"}");
    private static final ToolCall READ_B = new ToolCall("3", "read_file", "{\"path\":\"B.java\"}");

    private static ToolResult ok() {
        return new ToolResult("x", "ok", false, false);
    }

    private static ToolResult toolError() {
        return new ToolResult("x", "参数解析失败", true, false);
    }

    /** bash 退出码非 0：工具本身没报错，但动作失败 */
    private static ToolResult exitNonZero() {
        return new ToolResult("x", "执行报错：exit 1", false, true);
    }

    /** 模拟一轮只调用一个工具。 */
    private Verdict turn(ToolCall call, ToolResult result) {
        reminder.observe(call, result);
        return reminder.check();
    }

    /** 关闭重复检测（阈值设得很大），只观察"连续失败"这一条规则。 */
    private static Reminder failureOnly() {
        return new Reminder(3, 12, 100, 3);
    }

    @BeforeEach
    void setUp() {
        reminder = new Reminder();
    }

    @Test
    @DisplayName("同一调用连续失败 3 次：第 3 次注入 USER 角色的警告")
    void warnsAfterThreeConsecutiveFailures() {
        assertThat(turn(BUILD, exitNonZero()).action()).isEqualTo(Action.NONE);
        assertThat(turn(BUILD, exitNonZero()).action()).isEqualTo(Action.NONE);

        Verdict third = turn(BUILD, exitNonZero());

        assertThat(third.action()).isEqualTo(Action.WARN);
        assertThat(third.message().getRole()).isEqualTo(Role.USER);
        assertThat(third.message().getToolCallId()).isNull();
        assertThat(third.message().getContent()).contains("SYSTEM REMINDER").contains("连续 3 次").contains("javac");
    }

    @Test
    @DisplayName("bash 退出码非 0（softFailure）和工具报错（isError）都计为失败")
    void bothErrorKindsCount() {
        turn(BUILD, toolError());
        turn(BUILD, exitNonZero());

        assertThat(turn(BUILD, toolError()).action()).isEqualTo(Action.WARN);
    }

    @Test
    @DisplayName("中间成功一次会清零该调用的失败计数")
    void successResetsFailureCount() {
        reminder = failureOnly();
        turn(BUILD, exitNonZero());
        turn(BUILD, exitNonZero());
        turn(BUILD, ok());
        turn(BUILD, exitNonZero());

        assertThat(turn(BUILD, exitNonZero()).action()).isEqualTo(Action.NONE);
    }

    @Test
    @DisplayName("一个调用成功，不会清零另一个调用的失败计数（按指纹独立计数）")
    void successOfOtherCallDoesNotReset() {
        for (int i = 0; i < 2; i++) {
            reminder.observe(BUILD, exitNonZero());
            reminder.observe(READ_A, ok()); // 同一轮的并行调用
            assertThat(reminder.check().action()).isEqualTo(Action.NONE);
        }
        reminder.observe(BUILD, exitNonZero());
        reminder.observe(READ_B, ok());

        assertThat(reminder.check().action()).isEqualTo(Action.WARN);
    }

    @Test
    @DisplayName("参数不同就是不同的调用：换参数重试不会被误判")
    void differentArgumentsAreDifferentCalls() {
        for (int i = 0; i < 5; i++) {
            ToolCall attempt = new ToolCall("x", "bash", "{\"command\":\"javac -source " + (17 + i) + " Calculator.java\"}");
            assertThat(turn(attempt, exitNonZero()).action()).isEqualTo(Action.NONE);
        }
    }

    @Test
    @DisplayName("即便每次都成功，反复读同一个文件也会被识别为原地打转")
    void detectsRepeatedSuccessfulCalls() {
        turn(READ_A, ok());
        turn(READ_A, ok());
        turn(READ_A, ok());

        Verdict v = turn(READ_A, ok());

        assertThat(v.action()).isEqualTo(Action.WARN);
        assertThat(v.message().getContent()).contains("原地打转");
    }

    @Test
    @DisplayName("A→B→A→B 来回摆动也会被识别")
    void detectsPingPong() {
        Action last = Action.NONE;
        for (int i = 0; i < 4 && last == Action.NONE; i++) {
            last = turn(READ_A, ok()).action();
            if (last == Action.NONE) {
                last = turn(READ_B, ok()).action();
            }
        }
        assertThat(last).isEqualTo(Action.WARN);
    }

    @Test
    @DisplayName("逐级升级：第 2 次起为严重警告，警告 3 次后再触发则要求终止")
    void escalatesThenAborts() {
        // 一直以相同参数失败：失败计数与重复窗口两条规则会交替触发
        // 第 3 次调用（连续失败 3 次）→ 警告1；第 4 次（窗口内出现 4 次，窗口清空）→ 警告2；
        // 第 6 次（又连续失败 3 次）→ 警告3；第 8 次（窗口内再次出现 4 次）→ 终止
        List<Verdict> verdicts = new ArrayList<>();
        int calls = 0;
        while (verdicts.isEmpty() || verdicts.get(verdicts.size() - 1).action() != Action.ABORT) {
            calls++;
            Verdict v = turn(BUILD, exitNonZero());
            if (v.action() != Action.NONE) {
                verdicts.add(v);
            }
            assertThat(calls).as("应在有限次数内终止").isLessThan(20);
        }

        assertThat(verdicts).extracting(Verdict::action)
                .containsExactly(Action.WARN, Action.WARN, Action.WARN, Action.ABORT);
        assertThat(verdicts.get(0).message().getContent()).contains("[SYSTEM REMINDER 警告]");
        assertThat(verdicts.get(1).message().getContent()).contains("第 2 次严重警告");
        assertThat(verdicts.get(3).message()).isNull();
        assertThat(verdicts.get(3).reason()).contains("javac");
        assertThat(calls).isEqualTo(8);
    }

    @Test
    @DisplayName("reset 清空所有状态（每次 run 开始时调用）")
    void resetClearsState() {
        turn(BUILD, exitNonZero());
        turn(BUILD, exitNonZero());
        reminder.reset();

        assertThat(turn(BUILD, exitNonZero()).action()).isEqualTo(Action.NONE);
    }
}
