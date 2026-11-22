package com.lxf.tinyclaw.context;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.lxf.tinyclaw.schema.Message;
import com.lxf.tinyclaw.schema.Role;
import com.lxf.tinyclaw.tools.Registry;

/**
 * 上下文管理门面，注入到 AgentEngine。内部由三部分组成：
 * <ul>
 * <li>{@link PromptBuilder}：从 AGENT.md 动态组装系统提示词</li>
 * <li>{@link PlanManager}：Plan Mode 下的 PLAN.md / TODO.md 管理</li>
 * <li>{@link Compactor}：上下文压缩</li>
 * </ul>
 */
public class ContextManager {

    private final PromptBuilder promptBuilder;
    private final PlanManager planManager;
    private final Compactor compactor;

    /**
     * @param agentDir           全局 AGENT.md 所在目录
     * @param compactThreshold   触发上下文压缩的字符数阈值
     */
    public ContextManager(Path agentDir, int compactThreshold) {
        this(new PromptBuilder(agentDir), new PlanManager(), new Compactor(compactThreshold));
    }

    public ContextManager(PromptBuilder promptBuilder, PlanManager planManager, Compactor compactor) {
        this.promptBuilder = promptBuilder;
        this.planManager = planManager;
        this.compactor = compactor;
    }

    /**
     * 构造一个会话的初始上下文：System Prompt +（Plan Mode 下的计划引导）+ 用户任务。
     */
    public List<Message> openSession(Path workDir, Registry registry, String userPrompt, boolean planModeOn) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(Role.SYSTEM, promptBuilder.build(workDir, planModeOn), null, null));
        if (planModeOn) {
            messages.addAll(planManager.bootstrap(workDir, registry, userPrompt));
        } else {
            messages.add(new Message(Role.USER, userPrompt, null, null));
        }
        return messages;
    }

    /** 超过阈值时返回压缩后的新列表，否则返回原列表。 */
    public List<Message> compact(List<Message> messages) {
        return compactor.compact(messages);
    }

    public List<PlanManager.TodoItem> pendingTodos(Path workDir) {
        return planManager.pendingTodos(workDir);
    }
}
