package com.lxf.tinyclaw.schema;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 模型请求的一次工具调用
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public final class ToolCall {

    String id;
    String name;
    String arguments;
}
