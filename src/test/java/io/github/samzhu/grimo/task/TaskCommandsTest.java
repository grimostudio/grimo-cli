package io.github.samzhu.grimo.task;

import io.github.samzhu.grimo.task.model.Task;
import io.github.samzhu.grimo.task.model.TaskStatus;
import io.github.samzhu.grimo.task.model.TaskType;
import io.github.samzhu.grimo.task.scheduler.TaskSchedulerService;
import io.github.samzhu.grimo.task.store.MarkdownTaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TaskCommandsTest {

    @TempDir
    Path tasksDir;

    MarkdownTaskStore store;
    TaskSchedulerService schedulerService;
    TaskCommands commands;

    @BeforeEach
    void setUp() {
        store = new MarkdownTaskStore(tasksDir);
        schedulerService = mock(TaskSchedulerService.class);
        commands = new TaskCommands(store, schedulerService);
    }

    @Test
    void createShouldSaveTaskAndReturnConfirmation() {
        String result = commands.create("Check API health", "0 0 9 * * *");

        assertThat(result).contains("Task created");
        assertThat(store.loadAll()).hasSize(1);
    }

    @Test
    void createCronShouldScheduleTask() {
        commands.create("Check API health", "0 0 9 * * *");

        verify(schedulerService).scheduleCron(any(Task.class));
    }

    @Test
    void createImmediateShouldNotSchedule() {
        commands.create("One-time task", null);

        verify(schedulerService, never()).scheduleCron(any(Task.class));
    }

    @Test
    void listShouldShowTasks() {
        store.save(sampleTask("task-001", "Task A"));
        store.save(sampleTask("task-002", "Task B"));

        String result = commands.list();

        assertThat(result).contains("task-001");
        assertThat(result).contains("task-002");
    }

    @Test
    void listShouldShowEmptyMessage() {
        String result = commands.list();

        assertThat(result).contains("No tasks found");
    }

    @Test
    void showShouldDisplayTaskDetails() {
        store.save(sampleTask("task-001", "Check API"));

        String result = commands.show("task-001");

        assertThat(result).contains("task-001");
        assertThat(result).contains("Check API");
    }

    @Test
    void showShouldReturnNotFoundForMissing() {
        String result = commands.show("nonexistent");

        assertThat(result).contains("Task not found");
    }

    @Test
    void cancelShouldUpdateStatusAndCancelSchedule() {
        store.save(sampleTask("task-001", "To cancel"));

        String result = commands.cancel("task-001");

        assertThat(result).contains("cancelled");
        verify(schedulerService).cancel("task-001");
    }

    @Test
    void cancelShouldReturnNotFoundForMissing() {
        String result = commands.cancel("nonexistent");

        assertThat(result).contains("Task not found");
    }

    private Task sampleTask(String id, String desc) {
        return new Task(id, TaskType.CRON, TaskStatus.PENDING, desc,
            "0 9 * * *", null, "cli", Instant.now(), null, null, "# " + desc);
    }
}
