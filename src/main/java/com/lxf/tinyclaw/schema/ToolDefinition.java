package com.lxf.tinyclaw.schema;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 可供模型理解的工具元信息。
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public final class ToolDefinition {

    String name;
    String description;
    String inputSchema;
}
