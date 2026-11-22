package com.lxf.tinyclaw.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lxf.tinyclaw.context.PlanManager.TodoItem;
import com.lxf.tinyclaw.schema.Message;
import com.lxf.tinyclaw.schema.Role;
import com.lxf.tinyclaw.support.TestData;

/**
 * Plan Mode：PLAN.md / TODO.md 的解析与会话开场消息的构造。
 * 测试数据见 src/test/resources/data/plan/。
 */
class PlanManagerTest {

    private final PlanManager planManager = new PlanManager();

    @TempDir
    Path workDir;

    @Test
    @DisplayName("解析 TODO.md：识别 [ ] / [x] / [!] 三种标记，忽略非任务行")
    void parseTodoMarks() {
        TestData.copy("plan/TODO.md", workDir, "TODO.md");

        List<TodoItem> todos = planManager.readTodos(workDir);

        assertThat(todos).extracting(TodoItem::id).containsExactly("T1", "T2", "T3", "T4", "T5", "T6");
        assertThat(todos).extracting(TodoItem::mark).containsExactly('x', 'x', ' ', '!', ' ', ' ');
        // 支持 "*" 列表符号和中文冒号
        assertThat(todos.get(5).text()).isEqualTo("用 javac 编译并运行 3 个表达式验证");
    }

    @Test
    @DisplayName("未完成任务只包含 [ ]，阻塞的 [!] 不算待办")
    void pendingTodosExcludeDoneAndBlocked() {
        TestData.copy("plan/TODO.md", workDir, "TODO.md");

        assertThat(planManager.pendingTodos(workDir)).extracting(TodoItem::id).containsExactly("T3", "T5", "T6");
    }

    @Test
    @DisplayName("TODO.md 不存在时返回空列表，不抛异常")
    void missingTodoReturnsEmpty() {
        assertThat(planManager.readTodos(workDir)).isEmpty();
    }

    @Test
    @DisplayName("新会话（无计划文件）：在首条用户消息中追加规划指令，要求先写 PLAN.md 和 TODO.md")
    void bootstrapWithoutPlanFilesAsksModelToPlanFirst() {
        List<Message> messages = planManager.bootstrap(workDir, TestData.registry(workDir.toString()), "实现计算器");

        assertThat(messages).hasSize(1);
        Message first = messages.get(0);
        assertThat(first.getRole()).isEqualTo(Role.USER);
        assertThat(first.getContent())
                .startsWith("实现计算器")
                .contains("write_file 创建 PLAN.md")
                .contains("write_file 创建 TODO.md");
    }

    @Test
    @DisplayName("已有计划文件：引擎主动 read_file，注入钉住的调用与结果，并指出第一个未完成任务 T3")
    void bootstrapWithExistingPlanFilesReadsThemAndPointsToFirstPending() {
        TestData.copy("plan/PLAN.md", workDir, "PLAN.md");
        TestData.copy("plan/TODO.md", workDir, "TODO.md");

        List<Message> messages = planManager.bootstrap(workDir, TestData.registry(workDir.toString()), "继续");

        // 顺序：user(任务) → assistant(2 个 read_file 调用) → 2 个工具结果 → user(进度指引)
        assertThat(messages).extracting(Message::getRole)
                .containsExactly(Role.USER, Role.ASSISTANT, Role.USER, Role.USER, Role.USER);

        Message reads = messages.get(1);
        assertThat(reads.getToolCalls()).extracting(c -> c.getName()).containsOnly("read_file");
        assertThat(reads.getToolCalls()).allMatch(c -> c.getId().startsWith(PlanManager.PINNED_ID_PREFIX));

        assertThat(messages.get(2).getContent()).contains("递归下降解析");          // PLAN.md 的真实内容
        assertThat(messages.get(3).getContent()).contains("- [ ] T3");             // TODO.md 的真实内容
        assertThat(messages.get(2).getToolCallId()).isEqualTo(reads.getToolCalls().get(0).getId());

        assertThat(messages.get(4).getContent())
                .contains("当前进度 2/6")
                .contains("- [ ] T3: 实现递归下降 Parser");
    }

    @Test
    @DisplayName("所有任务都已完成：提示模型如有新需求则追加步骤，否则直接汇报")
    void bootstrapWhenAllDone() throws Exception {
        TestData.copy("plan/PLAN.md", workDir, "PLAN.md");
        Files.writeString(workDir.resolve("TODO.md"), "- [x] T1: a\n- [!] T2: b —— 阻塞\n");

        List<Message> messages = planManager.bootstrap(workDir, TestData.registry(workDir.toString()), "还有吗");

        assertThat(messages.get(messages.size() - 1).getContent()).contains("没有未完成的任务");
    }

    @Test
    @DisplayName("只有 PLAN.md 没有 TODO.md：读取 PLAN.md 并要求补写 TODO.md")
    void bootstrapWithOnlyPlan() {
        TestData.copy("plan/PLAN.md", workDir, "PLAN.md");

        List<Message> messages = planManager.bootstrap(workDir, TestData.registry(workDir.toString()), "继续");

        assertThat(messages.get(1).getToolCalls()).hasSize(1);
        assertThat(messages.get(messages.size() - 1).getContent()).contains("TODO.md 缺失");
    }
}
