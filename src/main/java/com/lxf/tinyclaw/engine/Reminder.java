package com.lxf.tinyclaw.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.lxf.tinyclaw.schema.Message;
import com.lxf.tinyclaw.schema.Role;
import com.lxf.tinyclaw.schema.ToolCall;
import com.lxf.tinyclaw.schema.ToolResult;

/**
 * 死循环监控与打断。实现：
 * <ul>
 * <li><b>逐个观察并行调用</b>：同一轮的每个 tool_call 都会被记录，而不是只看最后一个。</li>
 * <li><b>按指纹清零</b>：某个调用成功只清零它自己的失败计数，不影响其他指纹。</li>
 * <li><b>失败信号更全</b>：除了工具报错，bash 退出码非 0 也计为失败（{@link ToolResult#isFailure()}）。</li>
 * <li><b>无效重复检测</b>：即便每次都"成功"，同一调用在最近的滑动窗口内反复出现（含 A→B→A→B 的来回摆动）也会被识别。</li>
 * <li><b>逐级升级</b>：第一次警告、之后更严厉地警告，超过上限后要求引擎终止任务。</li>
 * </ul>
 *
 * 用法：每轮工具执行后依次调用 {@link #observe}，所有结果加入上下文之后调用一次 {@link #check}。
 * Reminder 有状态，每次 run 开始时需要 {@link #reset}。
 */
public class Reminder {

    public enum Action {
        NONE, WARN, ABORT
    }

    public record Verdict(Action action, Message message, String reason) {

        static final Verdict NONE = new Verdict(Action.NONE, null, null);
    }

    private final int failThreshold;
    private final int repeatWindow;
    private final int repeatThreshold;
    private final int maxWarnings;

    private final Map<String, Integer> consecutiveFailures = new HashMap<>();
    private final Deque<String> recentCalls = new ArrayDeque<>();
    private final List<String> pendingIssues = new ArrayList<>();
    private int warningsIssued;

    public Reminder() {
        this(3, 12, 4, 3);
    }

    /**
     * @param failThreshold   同一调用连续失败多少次触发干预
     * @param repeatWindow    重复检测的滑动窗口大小（最近多少次调用）
     * @param repeatThreshold 窗口内同一调用出现多少次视为无效重复
     * @param maxWarnings     最多警告几次；再次触发则要求终止
     */
    public Reminder(int failThreshold, int repeatWindow, int repeatThreshold, int maxWarnings) {
        this.failThreshold = failThreshold;
        this.repeatWindow = repeatWindow;
        this.repeatThreshold = repeatThreshold;
        this.maxWarnings = maxWarnings;
    }

    public void reset() {
        consecutiveFailures.clear();
        recentCalls.clear();
        pendingIssues.clear();
        warningsIssued = 0;
    }

    /** 记录一次工具调用及其结果。 */
    public void observe(ToolCall call, ToolResult result) {
        String fp = fingerprint(call);
        String label = describe(call);

        // 1. 连续失败检测
        if (result.isFailure()) {
            int count = consecutiveFailures.merge(fp, 1, Integer::sum);
            System.out.printf("[Reminder] 监控到 %s 执行失败，该调用连续失败次数: %d%n", call.getName(), count);
            if (count >= failThreshold) {
                pendingIssues.add(String.format("你已经连续 %d 次以完全相同的参数调用 %s，并且都失败了。", count, label));
                consecutiveFailures.remove(fp);
            }
        } else {
            consecutiveFailures.remove(fp);
        }

        // 2. 滑动窗口内的无效重复检测（不论成功与否）
        recentCalls.addLast(fp);
        while (recentCalls.size() > repeatWindow) {
            recentCalls.removeFirst();
        }
        int occurrences = Collections.frequency(recentCalls, fp);
        if (occurrences >= repeatThreshold) {
            pendingIssues.add(String.format("在最近 %d 次工具调用中，你已经 %d 次以相同参数调用 %s，这通常意味着你在原地打转。",
                    recentCalls.size(), occurrences, label));
            recentCalls.removeIf(fp::equals);
        }
    }

    /** 汇总本轮观察结果，决定是否注入提醒或终止任务。 */
    public Verdict check() {
        if (pendingIssues.isEmpty()) {
            return Verdict.NONE;
        }
        String issues = String.join("\n", pendingIssues.stream().map(s -> "- " + s).toList());
        pendingIssues.clear();
        warningsIssued++;

        if (warningsIssued > maxWarnings) {
            System.out.println("[Reminder] ⛔ 已警告 " + maxWarnings + " 次仍未摆脱循环，要求引擎终止任务。");
            return new Verdict(Action.ABORT, null, "多次警告后仍陷入循环：\n" + issues);
        }

        System.out.println("[Reminder] ⚠️ 触发死循环干预（第 " + warningsIssued + " 次），注入修正指令。");
        String content;
        if (warningsIssued == 1) {
            content = """
                    [SYSTEM REMINDER 警告]
                    你似乎陷入了死循环：
                    %s
                    请立即停止这种无效的重试！你的注意力被当前的报错过度吸引了。你需要：
                    1. 停止猜测参数，跳出当前的局部思维，重新审视问题的根因（先读一读相关文件或报错的完整上下文）。
                    2. 彻底改变你的策略。
                    3. 如果你确实无法通过工具解决当前问题，请直接结束任务并向用户说明你需要什么人工帮助，而不是继续盲目消耗资源。
                    """.formatted(issues);
        } else {
            content = """
                    [SYSTEM REMINDER 第 %d 次严重警告]
                    你仍然在重复无效的操作：
                    %s
                    这是第 %d 次警告，达到 %d 次后任务将被系统强制终止。
                    禁止再以相同方式重试。现在只允许两种选择：
                    A. 采用一种与之前完全不同的方法（不同的工具、不同的文件、不同的思路）；
                    B. 立即停止调用工具，向用户总结已完成的部分、当前卡点和你需要的帮助。
                    """.formatted(warningsIssued, issues, warningsIssued, maxWarnings);
        }
        // 必须是 USER 角色，以保证在下一次请求中具有最高的近因效应权重
        return new Verdict(Action.WARN, new Message(Role.USER, content, null, null), issues);
    }

    private static String fingerprint(ToolCall call) {
        String args = call.getArguments() == null ? "" : call.getArguments().replaceAll("\\s+", "");
        return call.getName() + "|" + args;
    }

    private static String describe(ToolCall call) {
        String args = call.getArguments() == null ? "" : call.getArguments().replaceAll("\\s+", " ");
        if (args.length() > 100) {
            args = args.substring(0, 100) + "…";
        }
        return "'" + call.getName() + "'（参数: " + args + "）";
    }
}
