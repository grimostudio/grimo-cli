package io.github.samzhu.grimo.task.store;

import io.github.samzhu.grimo.task.model.Task;
import io.github.samzhu.grimo.task.model.TaskStatus;
import io.github.samzhu.grimo.task.model.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownTaskStoreTest {

    @TempDir
    Path tasksDir;

    MarkdownTaskStore store;

    @BeforeEach
    void setUp() {
        store = new MarkdownTaskStore(tasksDir);
    }

    @Test
    void saveShouldCreateMarkdownFile() {
        var task = sampleTask("task-001", TaskType.CRON, "Check API health");
        store.save(task);

        assertThat(tasksDir.resolve("task-001.md")).exists();
    }

    @Test
    void saveShouldWriteYamlFrontmatter() throws Exception {
        var task = sampleTask("task-002", TaskType.CRON, "Check API health");
        store.save(task);

        String content = Files.readString(tasksDir.resolve("task-002.md"));
        assertThat(content).startsWith("---");
        assertThat(content).contains("id: task-002");
        assertThat(content).contains("type: cron");
        assertThat(content).contains("status: pending");
    }

    @Test
    void loadShouldParseMarkdownFile() {
        var task = sampleTask("task-003", TaskType.IMMEDIATE, "Quick task");
        store.save(task);

        var loaded = store.load("task-003");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().id()).isEqualTo("task-003");
        assertThat(loaded.get().description()).isEqualTo("Quick task");
        assertThat(loaded.get().type()).isEqualTo(TaskType.IMMEDIATE);
    }

    @Test
    void loadAllShouldReturnAllTasks() {
        store.save(sampleTask("task-a", TaskType.CRON, "Task A"));
        store.save(sampleTask("task-b", TaskType.IMMEDIATE, "Task B"));

        var tasks = store.loadAll();

        assertThat(tasks).hasSize(2);
    }

    @Test
    void appendExecutionLogShouldAddToBody() throws Exception {
        var task = sampleTask("task-004", TaskType.CRON, "Monitored task");
        store.save(task);

        store.appendExecutionLog("task-004", "2026-03-22 09:00", "API responded 200 OK");

        String content = Files.readString(tasksDir.resolve("task-004.md"));
        assertThat(content).contains("### 2026-03-22 09:00");
        assertThat(content).contains("API responded 200 OK");
    }

    @Test
    void deleteShouldRemoveFile() {
        store.save(sampleTask("task-005", TaskType.IMMEDIATE, "To delete"));
        store.delete("task-005");

        assertThat(tasksDir.resolve("task-005.md")).doesNotExist();
    }

    private Task sampleTask(String id, TaskType type, String description) {
        return new Task(
            id, type, TaskStatus.PENDING, description,
            type == TaskType.CRON ? "0 9 * * *" : null,
            null, "cli",
            Instant.parse("2026-03-22T02:30:00Z"),
            null, null,
            "# " + description
        );
    }
}
