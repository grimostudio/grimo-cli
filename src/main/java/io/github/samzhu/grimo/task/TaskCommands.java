package io.github.samzhu.grimo.task;

import io.github.samzhu.grimo.task.model.Task;
import io.github.samzhu.grimo.task.model.TaskStatus;
import io.github.samzhu.grimo.task.model.TaskType;
import io.github.samzhu.grimo.task.scheduler.TaskSchedulerService;
import io.github.samzhu.grimo.task.store.MarkdownTaskStore;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Spring Shell CLI commands for task management (create/list/show/cancel).
 *
 * 設計說明：
 * - 提供 task create / task list / task show / task cancel 四個子命令
 * - create 支援可選的 cron 參數，有 cron 時自動排程；無 cron 則為 IMMEDIATE 類型
 * - 透過 MarkdownTaskStore 持久化任務至 ~/grimo-workspace/tasks/ 目錄
 * - 透過 TaskSchedulerService 管理 cron 排程的註冊與取消
 * - Task ID 格式為 task-{yyyyMMdd}-{8碼UUID}，確保唯一且可排序
 *
 * Uses Spring Shell 4.0 @Command annotation model (replaces legacy @ShellComponent/@ShellMethod).
 * Reference: https://docs.spring.io/spring-shell/reference/api/org/springframework/shell/core/command/annotation/Option.html
 */
@Component
public class TaskCommands {

    private final MarkdownTaskStore store;
    private final TaskSchedulerService schedulerService;

    public TaskCommands(MarkdownTaskStore store, TaskSchedulerService schedulerService) {
        this.store = store;
        this.schedulerService = schedulerService;
    }

    /**
     * 建立新任務。若提供 cron 表達式則自動排程為 CRON 類型任務，否則為 IMMEDIATE 類型。
     *
     * @param description 任務描述
     * @param cron        可選的 cron 表達式（Spring 6 欄位格式，如 "0 0 9 * * *"）
     * @return 建立確認訊息，包含生成的 task ID
     */
    @Command(name = {"task", "create"}, description = "Create a new task")
    public String create(
            @Option(longName = "description", shortName = 'd', description = "Task description", required = true)
            String description,
            @Option(longName = "cron", shortName = 'c', description = "Cron expression for scheduled tasks")
            String cron) {

        // 若 cron 為空字串則視為 null（Spring Shell 預設值為空字串）
        String effectiveCron = (cron != null && !cron.isEmpty()) ? cron : null;
        TaskType type = effectiveCron != null ? TaskType.CRON : TaskType.IMMEDIATE;

        String id = "task-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                     + "-" + UUID.randomUUID().toString().substring(0, 8);

        var task = new Task(id, type, TaskStatus.PENDING, description,
            effectiveCron, null, "cli", Instant.now(), null, null, "# " + description);

        store.save(task);

        if (type == TaskType.CRON) {
            schedulerService.scheduleCron(task);
        }

        return "Task created: " + id;
    }

    /**
     * 裸指令別名：輸入 /task 等同 /task list，符合漸進式揭露原則。
     * Smart Default: bare 'task' command delegates to 'task list'.
     */
    @Command(name = "task", description = "List all tasks (alias for 'task list')")
    public String taskDefault() {
        return list();
    }

    /**
     * 列出所有任務，以表格格式顯示 ID、類型、狀態與描述。
     */
    @Command(name = {"task", "list"}, description = "List all tasks")
    public String list() {
        var tasks = store.loadAll();
        if (tasks.isEmpty()) return "No tasks found.";

        var sb = new StringBuilder();
        sb.append(String.format("  %-25s %-10s %-10s %s%n", "ID", "TYPE", "STATUS", "DESCRIPTION"));
        for (Task t : tasks) {
            sb.append(String.format("  %-25s %-10s %-10s %s%n",
                t.id(), t.type(), t.status(), t.description()));
        }
        return sb.toString();
    }

    /**
     * 顯示指定任務的詳細資訊，包含排程、執行時間與 Markdown 內容。
     *
     * @param taskId 任務 ID
     */
    @Command(name = {"task", "show"}, description = "Show task details")
    public String show(
            @Option(longName = "id", description = "Task ID", required = true)
            String taskId) {
        return store.load(taskId)
            .map(t -> String.format("""
                ID:          %s
                Type:        %s
                Status:      %s
                Cron:        %s
                Channel:     %s
                Created:     %s
                Last Run:    %s
                Next Run:    %s

                %s""",
                t.id(), t.type(), t.status(),
                t.cron() != null ? t.cron() : "N/A",
                t.channel(), t.created(),
                t.lastRun() != null ? t.lastRun() : "N/A",
                t.nextRun() != null ? t.nextRun() : "N/A",
                t.body()))
            .orElse("Task not found: " + taskId);
    }

    /**
     * 取消指定任務，將狀態更新為 CANCELLED 並從排程器移除。
     *
     * @param taskId 任務 ID
     */
    @Command(name = {"task", "cancel"}, description = "Cancel a task")
    public String cancel(
            @Option(longName = "id", description = "Task ID", required = true)
            String taskId) {
        return store.load(taskId).map(task -> {
            store.save(task.withStatus(TaskStatus.CANCELLED));
            schedulerService.cancel(taskId);
            return "Task cancelled: " + taskId;
        }).orElse("Task not found: " + taskId);
    }
}
