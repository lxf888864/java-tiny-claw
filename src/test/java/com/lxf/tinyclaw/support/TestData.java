package com.lxf.tinyclaw.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.lxf.tinyclaw.tools.BashTool;
import com.lxf.tinyclaw.tools.EditFileTool;
import com.lxf.tinyclaw.tools.ReadFileTool;
import com.lxf.tinyclaw.tools.Registry;
import com.lxf.tinyclaw.tools.RegistryImpl;
import com.lxf.tinyclaw.tools.WriteFileTool;

/** 读取 src/test/resources/data 下的测试数据，并提供常用的测试装配。 */
public final class TestData {

    private TestData() {
    }

    /** 读取 classpath 下的测试数据，例如 {@code read("plan/TODO.md")}。 */
    public static String read(String relativePath) {
        try (InputStream is = TestData.class.getResourceAsStream("/data/" + relativePath)) {
            if (is == null) {
                throw new IllegalArgumentException("找不到测试数据: data/" + relativePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 把测试数据复制到目标目录下（文件名可改）。 */
    public static Path copy(String relativePath, Path targetDir, String targetName) {
        try {
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(targetName);
            Files.writeString(target, read(relativePath), StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 与 Claw 中一致的工具注册表。 */
    public static Registry registry(String workDir) {
        Registry registry = new RegistryImpl();
        registry.register(new ReadFileTool(workDir));
        registry.register(new WriteFileTool(workDir));
        registry.register(new BashTool(workDir));
        registry.register(new EditFileTool(workDir));
        return registry;
    }

    /** 把字符串转成 JSON 字符串字面量（含引号），用于拼装工具参数。 */
    public static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
