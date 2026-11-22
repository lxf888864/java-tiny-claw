package com.lxf.tinyclaw.tools;

import static com.lxf.tinyclaw.support.TestData.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lxf.tinyclaw.schema.ToolCall;
import com.lxf.tinyclaw.schema.ToolResult;
import com.lxf.tinyclaw.support.TestData;
import com.lxf.tinyclaw.util.PathGuard;

/** 文件类工具：工作区越界防护，以及在 Windows 上 edit_file 勾选 TODO 的能力。 */
class FileToolsTest {

    @TempDir
    Path workDir;

    private ToolResult exec(String tool, String args) {
        return TestData.registry(workDir.toString()).execute(new ToolCall("t", tool, args));
    }

    @Test
    @DisplayName("PathGuard：工作区内的路径正常解析")
    void pathInsideWorkspace() {
        Path p = PathGuard.resolveInside(workDir.toString(), "src/../TODO.md");
        assertThat(p).isEqualTo(workDir.toAbsolutePath().normalize().resolve("TODO.md"));
    }

    @Test
    @DisplayName("PathGuard：../ 越界和绝对路径都会被拒绝")
    void pathOutsideWorkspaceRejected() {
        assertThatThrownBy(() -> PathGuard.resolveInside(workDir.toString(), "../../etc/passwd"))
                .isInstanceOf(SecurityException.class).hasMessageContaining("路径越界");
        String outside = workDir.getParent().resolve("secret.txt").toAbsolutePath().toString();
        assertThatThrownBy(() -> PathGuard.resolveInside(workDir.toString(), outside))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("read_file / write_file / edit_file 遇到越界路径返回错误结果，而不是读写文件")
    void toolsRejectEscapingPaths() {
        assertThat(exec("read_file", "{\"path\":\"../x.txt\"}").isError()).isTrue();
        assertThat(exec("write_file", "{\"path\":\"../x.txt\",\"content\":\"hack\"}").isError()).isTrue();
        assertThat(exec("edit_file", "{\"path\":\"../x.txt\",\"oldText\":\"a\",\"newText\":\"b\"}").isError()).isTrue();
        assertThat(workDir.getParent().resolve("x.txt")).doesNotExist();
    }

    @Test
    @DisplayName("write_file 自动创建多级目录（在 Windows 上也能成功）")
    void writeFileCreatesParentDirs() throws Exception {
        ToolResult r = exec("write_file", "{\"path\":\"a/b/c.txt\",\"content\":" + json("你好\n世界") + "}");

        assertThat(r.isError()).as(r.getOutput()).isFalse();
        assertThat(Files.readString(workDir.resolve("a/b/c.txt"))).isEqualTo("你好\n世界");
    }

    @Test
    @DisplayName("edit_file 把 TODO 中的 '- [ ] T3' 标记为 '- [x] T3'，返回成功（修复前在 Windows 上会误报失败）")
    void editFileMarksTodoDone() throws Exception {
        TestData.copy("plan/TODO.md", workDir, "TODO.md");

        ToolResult r = exec("edit_file", "{\"path\":\"TODO.md\",\"oldText\":\"- [ ] T3\",\"newText\":\"- [x] T3\"}");

        assertThat(r.isError()).as(r.getOutput()).isFalse();
        assertThat(Files.readString(workDir.resolve("TODO.md")))
                .contains("- [x] T3: 实现递归下降 Parser")
                .contains("- [ ] T5");
    }

    @Test
    @DisplayName("edit_file：oldText 匹配多处时报错并提示提供更多上下文（这正是 TODO 要带编号的原因）")
    void editFileAmbiguousMatch() {
        TestData.copy("plan/TODO.md", workDir, "TODO.md");

        ToolResult r = exec("edit_file", "{\"path\":\"TODO.md\",\"oldText\":\"- [ ]\",\"newText\":\"- [x]\"}");

        assertThat(r.isError()).isTrue();
        assertThat(r.getOutput()).contains("匹配到了");
    }

    @Test
    @DisplayName("工具参数不是合法 JSON：返回错误结果，引擎不会崩溃")
    void invalidJsonArguments() {
        ToolResult r = exec("read_file", "not json");

        assertThat(r.isError()).isTrue();
        assertThat(r.getOutput()).contains("参数解析失败");
    }

    @Test
    @DisplayName("调用不存在的工具：返回错误结果")
    void unknownTool() {
        assertThat(exec("rm_rf", "{}").getOutput()).contains("不存在工具");
    }
}
