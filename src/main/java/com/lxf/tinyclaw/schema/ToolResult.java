package com.lxf.tinyclaw.schema;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 工具本地执行后的结果。
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public final class ToolResult {

    String toolCallId;
    String output;
    boolean isError;
    // 工具执行了但动作失败（如 bash 退出码非 0），供 Reminder 判断是否陷入失败循环
    boolean softFailure;

    public ToolResult(String toolCallId, String output, boolean isError) {
        this(toolCallId, output, isError, false);
    }

    /** 对 Reminder 而言的"失败"：工具报错或动作失败。 */
    public boolean isFailure() {
        return isError || softFailure;
    }
}
