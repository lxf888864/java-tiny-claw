package com.lxf.tinyclaw.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import com.lxf.tinyclaw.provider.LlmProvider;
import com.lxf.tinyclaw.schema.Message;
import com.lxf.tinyclaw.schema.Role;
import com.lxf.tinyclaw.schema.ToolCall;
import com.lxf.tinyclaw.schema.ToolDefinition;

/**
 * 按剧本回复的假模型，用于离线测试 AgentEngine（不调用任何真实 API）。
 *
 * <p>
 * 每次 generate 都会：
 * <ol>
 * <li>把收到的上下文做一份快照记录到 {@link #requests()}，测试可以据此断言"模型当时看到了什么"；</li>
 * <li>从剧本队列中取出下一个回复。剧本项既可以是固定消息，也可以是根据上下文动态生成回复的函数。</li>
 * </ol>
 */
public class ScriptedLlmProvider implements LlmProvider {

    /** 一次请求的快照。 */
    public record Request(List<Message> messages, List<ToolDefinition> tools) {

        public Message last() {
            return messages.get(messages.size() - 1);
        }

        /** 本次请求中所有消息内容的拼接，方便做 contains 断言。 */
        public String allContent() {
            StringBuilder sb = new StringBuilder();
            for (Message m : messages) {
                sb.append(m.getContent()).append('\n');
            }
            return sb.toString();
        }

        /** 断言失败时打印每条消息的"角色 + 长度 + 摘要"，便于排查。 */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Request[");
            for (Message m : messages) {
                String c = m.getContent() == null ? "" : m.getContent().replace('\n', ' ');
                sb.append("\n  ").append(m.getRole()).append(m.getToolCallId() != null ? "(tool)" : "")
                        .append(" len=").append(c.length()).append(" ").append(c, 0, Math.min(60, c.length()));
            }
            return sb.append("\n]").toString();
        }
    }

    private final Deque<Function<List<Message>, Message>> script = new ArrayDeque<>();
    private final List<Request> requests = new ArrayList<>();
    private int idSeq;

    /** 剧本：调用一个工具。 */
    public ScriptedLlmProvider callTool(String name, String argumentsJson) {
        String id = "call_" + (++idSeq);
        script.add(ctx -> new Message(Role.ASSISTANT, "", List.of(new ToolCall(id, name, argumentsJson)), null));
        return this;
    }

    /** 剧本：一轮内并行调用多个工具，参数为 name, args, name, args... */
    public ScriptedLlmProvider callTools(String... nameAndArgs) {
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < nameAndArgs.length; i += 2) {
            calls.add(new ToolCall("call_" + (++idSeq), nameAndArgs[i], nameAndArgs[i + 1]));
        }
        script.add(ctx -> new Message(Role.ASSISTANT, "", calls, null));
        return this;
    }

    /** 剧本：纯文本回复（不调用工具，引擎会认为模型想结束任务）。 */
    public ScriptedLlmProvider reply(String text) {
        script.add(ctx -> new Message(Role.ASSISTANT, text, List.of(), null));
        return this;
    }

    /** 剧本：根据上下文动态决定回复。 */
    public ScriptedLlmProvider dynamic(Function<List<Message>, Message> responder) {
        script.add(responder);
        return this;
    }

    /** 剧本：把同一个工具调用重复 times 次（模拟模型陷入死循环）。 */
    public ScriptedLlmProvider repeatToolCall(int times, String name, String argumentsJson) {
        for (int i = 0; i < times; i++) {
            callTool(name, argumentsJson);
        }
        return this;
    }

    @Override
    public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
        requests.add(new Request(List.copyOf(messages), availableTools == null ? null : List.copyOf(availableTools)));
        Function<List<Message>, Message> next = script.poll();
        if (next == null) {
            throw new IllegalStateException("剧本已用完，但引擎仍在请求第 " + requests.size() + " 次回复");
        }
        return next.apply(messages);
    }

    public List<Request> requests() {
        return requests;
    }

    public Request lastRequest() {
        return requests.get(requests.size() - 1);
    }

    public int remaining() {
        return script.size();
    }
}
