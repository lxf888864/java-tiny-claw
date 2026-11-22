package com.lxf.tinyclaw.provider;

import java.util.List;

import com.lxf.tinyclaw.schema.Message;
import com.lxf.tinyclaw.schema.ToolDefinition;

/**
 * 与大模型通信的统一契约。
 */
public interface LlmProvider {

    Message generate(List<Message> messages, List<ToolDefinition> availableTools);
}
