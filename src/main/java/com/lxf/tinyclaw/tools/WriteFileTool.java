package com.lxf.tinyclaw.tools;

import static com.openai.core.ObjectMappers.jsonMapper;

import java.io.IOException;
import java.nio.file.Files;

import com.lxf.tinyclaw.schema.ToolDefinition;
import com.lxf.tinyclaw.util.PathGuard;
import com.fasterxml.jackson.core.JsonProcessingException;

public class WriteFileTool implements BaseTool {

    private final String name;
    private final String description;
    private final String workDir;
    private final String inputSchema;

    public WriteFileTool(String workDir) {
        this.name = "write_file";
        this.description = "创建或覆盖写入一个文件。如果目录不存在会自动创建。请提供相对于工作区的相对路径。";
        this.workDir = workDir;
        this.inputSchema = """
                            {
                                "type": "object",
                                "properties":
                                {
                                    "path":
                                    {
                                        "type": "string",
                                        "description": "要写入的文件路径，如 cmd/claw/main.java"
                                    },
                                    "content":
                                    {
                                        "type": "string",
                                        "description": "要写入的完整文件内容"
                                    }
                                },
                                "required":
                                [
                                    "path",
                                    "content"
                                ]
                            }
                """;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public ToolDefinition getDefinition() {
        return new ToolDefinition(this.name, this.description, this.inputSchema);
    }

    @Override
    public ExecResult execute(String arguments) {
        WriteFileArgs input;
        try {
            input = jsonMapper().readValue(arguments, WriteFileArgs.class);
        } catch (JsonProcessingException e) {
            return new ExecResult("参数解析失败：" + e.getMessage(), false);
        }

        if (input.path() == null || input.path().isBlank()) {
            return new ExecResult("路径为空，未写入任何内容。", false);
        }

        // 【安全防线】：限制在 WorkDir 下执行，防止大模型修改系统级文件
        java.nio.file.Path fullPath;
        try {
            fullPath = PathGuard.resolveInside(this.workDir, input.path());
        } catch (SecurityException e) {
            return new ExecResult(e.getMessage(), false);
        }

        // 自动创建缺失的父级目录
        try {
            PathGuard.createDirectories(fullPath.getParent());
        } catch (IOException e) {
            return new ExecResult("创建父级目录失败:" + e.getMessage(), false);
        }

        // 写入文件内容，权限设置为 644（仅 POSIX 文件系统）
        try {
            Files.writeString(fullPath, input.content());
            PathGuard.setDefaultFilePermissions(fullPath);
        } catch (Exception e) {
            return new ExecResult("写入文件失败:" + e.getMessage(), false);
        }

        return new ExecResult("成功写入文件" + input.path(), true);
    }

    private record WriteFileArgs(String path, String content) {
    }
}
