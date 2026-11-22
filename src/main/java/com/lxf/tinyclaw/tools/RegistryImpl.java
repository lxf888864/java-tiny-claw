package com.lxf.tinyclaw.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.lxf.tinyclaw.schema.ToolCall;
import com.lxf.tinyclaw.schema.ToolDefinition;
import com.lxf.tinyclaw.schema.ToolResult;

public class RegistryImpl implements Registry {

    private final Map<String, BaseTool> toolsMap = new HashMap<>();

    @Override
    public void register(BaseTool tool) {
        toolsMap.put(tool.getName(), tool);
    }

    @Override
    public List<ToolDefinition> getAvailableTools() {
        List<ToolDefinition> tools = new ArrayList<>();
        toolsMap.forEach((key, value) -> {
            tools.add(value.getDefinition());
        });
        return tools;
    }

    @Override
    public ToolResult execute(ToolCall toolCall) {
        // 1. 路由查找，找到tool，分发请求
        BaseTool tool = toolsMap.get(toolCall.getName());
        if (tool == null) {
            return new ToolResult(toolCall.getId(), "系统中不存在工具：" + toolCall.getName(), true);
        }

        // 2. 执行工具
        ExecResult result;
        try {
            result = tool.execute(toolCall.getArguments());
        } catch (RuntimeException e) {
            // 兜底：任何工具内部未捕获的异常都转成错误结果交给模型，而不是让引擎崩溃
            return new ToolResult(toolCall.getId(), "工具执行异常：" + e.getMessage(), true);
        }

        // 3. 补上 toolCallId，isError 与工具层同向
        return new ToolResult(toolCall.getId(), result.output(), !result.isSuccess(), result.softFailure());
    }
}
