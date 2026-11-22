package com.lxf.tinyclaw.tools;

import com.lxf.tinyclaw.schema.ToolDefinition;

public interface BaseTool {

    String getName();

    ToolDefinition getDefinition();

    ExecResult execute(String arguments);
}
