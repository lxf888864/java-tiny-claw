package com.lxf.tinyclaw.tools;

import java.util.List;

import com.lxf.tinyclaw.schema.ToolCall;
import com.lxf.tinyclaw.schema.ToolDefinition;
import com.lxf.tinyclaw.schema.ToolResult;

/**
 * 工具注册与分发。
 */
public interface Registry {

    void register(BaseTool tool);

    List<ToolDefinition> getAvailableTools();

    ToolResult execute(ToolCall toolCall);
}
