package com.lxf.tinyclaw.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.lxf.tinyclaw.schema.Message;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 会话原始历史记录：以 JSON Lines 追加写入 {@code <workDir>/.claw/history.jsonl}。
 *
 * <p>
 * Compactor 会持久化地改写内存中的上下文，这里保留未经压缩的原始消息，便于排查问题和后续实现会话恢复。
 * 写入失败只打印告警，不影响 Agent 运行。
 */
public class SessionHistory {

    public static final String HISTORY_DIR = ".claw";
    private static final String HISTORY_FILE = "history.jsonl";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private boolean warned;

    public SessionHistory(Path workDir) {
        this.file = workDir.resolve(HISTORY_DIR).resolve(HISTORY_FILE);
    }

    public void markRunStart(String sessionId, String userPrompt) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("ts", Instant.now().toString());
        event.put("event", "run_start");
        event.put("sessionId", sessionId);
        event.put("prompt", userPrompt);
        write(event);
    }

    public void append(Message message) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("ts", Instant.now().toString());
        event.put("event", "message");
        event.put("message", message);
        write(event);
    }

    private void write(Map<String, Object> event) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (!warned) {
                warned = true;
                System.out.println("[History] ⚠️ 写入会话历史失败（后续不再提示）: " + e.getMessage());
            }
        }
    }
}
