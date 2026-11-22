package com.lxf.tinyclaw.tools;

/**
 * 工具层执行结果，给人看。Registry 再翻成 {@code ToolResult} 给模型。
 *
 * @param output      输出文本
 * @param isSuccess   工具本身是否成功执行（参数错误、IO 失败等为 false，会作为 isError 回传模型）
 * @param softFailure 工具执行了，但动作本身失败（如 bash 退出码非 0）。不算工具报错，
 *                    但会作为失败信号提供给 Reminder 做死循环检测
 */
public record ExecResult(String output, boolean isSuccess, boolean softFailure) {

    public ExecResult(String output, boolean isSuccess) {
        this(output, isSuccess, false);
    }
}
