package com.lxf.tinyclaw.context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.lxf.tinyclaw.schema.Message;
import com.lxf.tinyclaw.schema.Role;
import com.lxf.tinyclaw.schema.ToolCall;

/**
 * 上下文压缩器。实现了：
 * <ul>
 * <li><b>高低水位 + 持久化</b>：仅当长度超过高水位 {@code maxChars} 时触发，压缩结果由调用方写回历史。
 * 两次触发之间历史前缀保持稳定，对 API 的前缀缓存（prompt cache）友好；且所有操作幂等，
 * 已掩码/截断的消息不会被重复处理。</li>
 * <li><b>按轮次边界划分保护区</b>：保护区起点若落在工具结果上，会回退到发起调用的 assistant 消息，
 * 避免同一批并行调用的结果一半被掩码、一半被保留。</li>
 * <li><b>可恢复的掩码</b>：掩码文本携带工具名与参数摘要，模型知道可以重新调用工具找回内容。</li>
 * <li><b>钉住消息</b>：toolCallId 以 {@link PlanManager#PINNED_ID_PREFIX} 开头的消息（PLAN/TODO）永不压缩。</li>
 * <li><b>紧急降级</b>：常规压缩后仍高于低水位时，逐步缩小保护区（不小于 {@code minRetainMsgs}）。</li>
 * </ul>
 */
public class Compactor {

    /** Working Memory 保护区：最近 N 条消息（暂时写死）。 */
    public static final int RETAIN_LAST_MSGS = 10;
    /** 紧急降级时保护区的下限。 */
    private static final int MIN_RETAIN_MSGS = 4;

    /** 远期历史中，超过该长度的工具输出 / 推理文本才会被掩码。 */
    private static final int FAR_MASK_MIN_CHARS = 200;
    /** Working Memory 中单条工具输出的上限，超过则掐头去尾。 */
    private static final int WORKING_MAX_CHARS = 1000;
    private static final int HEAD_CHARS = 500;
    private static final int TAIL_CHARS = 500;
    private static final int ARGS_PREVIEW_CHARS = 80;

    private static final String MASK_MARK = "...[为了节省内存，早期的工具输出已被系统清理";
    private static final String FOLD_MARK = "...[早期的推理思考过程已折叠";
    private static final String TRUNCATE_MARK = "...[内容过长，中间 ";

    private final int maxChars;
    private final int lowWatermark;
    private final int retainLastMsgs;

    /**
     * @param maxChars 触发压缩的高水位（字符数）
     */
    public Compactor(int maxChars) {
        this(maxChars, 0.6);
    }

    /**
     * @param maxChars      触发压缩的高水位（字符数），可参考所用模型的上下文窗口大小
     * @param lowWaterRatio 低水位 = maxChars * ratio，压缩的目标长度
     */
    public Compactor(int maxChars, double lowWaterRatio) {
        if (maxChars <= 0 || lowWaterRatio <= 0 || lowWaterRatio > 1) {
            throw new IllegalArgumentException("maxChars 必须为正数，lowWaterRatio 必须在 (0, 1] 之间");
        }
        this.maxChars = maxChars;
        this.lowWatermark = (int) (maxChars * lowWaterRatio);
        this.retainLastMsgs = RETAIN_LAST_MSGS;
    }

    /**
     * 检查并压缩上下文。未超过高水位时原样返回同一个列表；否则返回新的列表（原列表不被修改）。
     */
    public List<Message> compact(List<Message> msgs) {
        int currentLength = estimateLength(msgs);
        if (currentLength < maxChars) {
            return msgs;
        }

        System.out.printf("[Compactor] ⚠️ 内存告警：当前上下文长度 (%d 字符) 超过阈值 (%d)，触发压缩清理...%n",
                currentLength, maxChars);

        Map<String, ToolCall> callIndex = indexToolCalls(msgs);
        int retain = retainLastMsgs;
        List<Message> compacted = compactWith(msgs, retain, callIndex);
        int newLength = estimateLength(compacted);

        while (newLength > lowWatermark && retain > MIN_RETAIN_MSGS) {
            retain = Math.max(MIN_RETAIN_MSGS, retain / 2);
            System.out.printf("[Compactor] 常规压缩后 (%d 字符) 仍高于低水位 (%d)，紧急降级：保护区缩小至最近 %d 条%n",
                    newLength, lowWatermark, retain);
            compacted = compactWith(msgs, retain, callIndex);
            newLength = estimateLength(compacted);
        }

        System.out.printf("[Compactor] ✅ 压缩完成。上下文长度从 %d 降至 %d 字符。%n", currentLength, newLength);
        if (newLength >= maxChars) {
            System.out.println("[Compactor] ⚠️ 压缩后仍超过阈值，主要内容来自用户输入或钉住的计划文件，无法进一步压缩。");
        }
        return compacted;
    }

    private List<Message> compactWith(List<Message> msgs, int retain, Map<String, ToolCall> callIndex) {
        int protectStart = Math.max(0, msgs.size() - retain);
        // 轮次边界对齐：如果起点是工具结果，回退到发起这批调用的 assistant 消息
        while (protectStart > 0 && isToolResult(msgs.get(protectStart))) {
            protectStart--;
        }

        List<Message> compacted = new ArrayList<>(msgs.size());
        for (int i = 0; i < msgs.size(); i++) {
            Message msg = msgs.get(i);

            // 1. System Prompt 与钉住的计划文件：绝对不动
            if (msg.getRole() == Role.SYSTEM || isPinned(msg)) {
                compacted.add(msg);
                continue;
            }

            // 拷贝一份新消息，不修改原引用。注意：ToolCalls 原样保留，它是维系逻辑链的关键证据
            Message newMsg = new Message(msg.getRole(), msg.getContent(), msg.getToolCalls(), msg.getToolCallId());
            boolean inWorkingMemory = i >= protectStart;
            String content = msg.getContent() == null ? "" : msg.getContent();

            if (isToolResult(msg)) {
                if (!inWorkingMemory) {
                    // 【第一道防线：远期历史】全量掩码
                    if (content.length() > FAR_MASK_MIN_CHARS && !content.startsWith(MASK_MARK)) {
                        newMsg.setContent(mask(callIndex.get(msg.getToolCallId()), content.length()));
                    }
                } else if (content.length() > WORKING_MAX_CHARS && !content.contains(TRUNCATE_MARK)) {
                    // 【第二道防线：短期记忆】单条过长，掐头去尾
                    newMsg.setContent(headTail(content));
                }
            } else if (msg.getRole() == Role.ASSISTANT && !inWorkingMemory
                    && content.length() > FAR_MASK_MIN_CHARS && !content.startsWith(FOLD_MARK)) {
                // 远期的冗长推理（Thinking Trace）折叠
                newMsg.setContent(FOLD_MARK + "，原始长度: " + content.length() + " 字符]...");
            }
            // 普通用户消息（任务描述、Reminder 提醒）不压缩

            compacted.add(newMsg);
        }
        return compacted;
    }

    private static String mask(ToolCall call, int originalLength) {
        String source = "";
        if (call != null) {
            String args = call.getArguments() == null ? "" : call.getArguments().replaceAll("\\s+", " ");
            if (args.length() > ARGS_PREVIEW_CHARS) {
                args = args.substring(0, ARGS_PREVIEW_CHARS) + "…";
            }
            source = "，来源: " + call.getName() + "(" + args + ")";
        }
        return MASK_MARK + "。原始长度: " + originalLength + " 字符" + source + "。如仍需要该内容，请重新调用工具获取]...";
    }

    private static String headTail(String content) {
        int headEnd = safeCut(content, HEAD_CHARS);
        int tailStart = safeCut(content, content.length() - TAIL_CHARS);
        String head = content.substring(0, headEnd);
        String tail = content.substring(tailStart);
        return head + "\n\n" + TRUNCATE_MARK + (tailStart - headEnd) + " 字符已被系统截断]...\n\n" + tail;
    }

    /** 避免把 UTF-16 代理对（如 emoji）从中间切开。 */
    private static int safeCut(String s, int index) {
        if (index > 0 && index < s.length() && Character.isLowSurrogate(s.charAt(index))) {
            return index - 1;
        }
        return index;
    }

    private static boolean isToolResult(Message msg) {
        return msg.getRole() == Role.USER && msg.getToolCallId() != null;
    }

    private static boolean isPinned(Message msg) {
        if (msg.getToolCallId() != null) {
            return msg.getToolCallId().startsWith(PlanManager.PINNED_ID_PREFIX);
        }
        List<ToolCall> calls = msg.getToolCalls();
        return calls != null && !calls.isEmpty()
                && calls.stream().allMatch(c -> c.getId() != null && c.getId().startsWith(PlanManager.PINNED_ID_PREFIX));
    }

    private static Map<String, ToolCall> indexToolCalls(List<Message> msgs) {
        Map<String, ToolCall> index = new HashMap<>();
        for (Message msg : msgs) {
            if (msg.getToolCalls() != null) {
                for (ToolCall call : msg.getToolCalls()) {
                    index.put(call.getId(), call);
                }
            }
        }
        return index;
    }

    /** 粗略估算上下文的总字符长度。 */
    public static int estimateLength(List<Message> msgs) {
        int length = 0;
        for (Message msg : msgs) {
            if (msg.getContent() != null) {
                length += msg.getContent().length();
            }
            if (msg.getToolCalls() != null) {
                for (ToolCall tc : msg.getToolCalls()) {
                    length += (tc.getName() == null ? 0 : tc.getName().length())
                            + (tc.getArguments() == null ? 0 : tc.getArguments().length());
                }
            }
        }
        return length;
    }
}
