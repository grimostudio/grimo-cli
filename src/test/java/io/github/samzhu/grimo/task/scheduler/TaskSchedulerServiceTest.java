package io.github.samzhu.grimo.task.scheduler;

import io.github.samzhu.grimo.task.model.Task;
import io.github.samzhu.grimo.task.model.TaskStatus;
import io.github.samzhu.grimo.task.model.TaskType;
import io.github.samzhu.grimo.task.store.MarkdownTaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TaskSchedulerServiceTest {

    @TempDir
    Path tasksDir;

    MarkdownTaskStore store;
    ThreadPoolTaskScheduler scheduler;
    ApplicationEventPublisher publisher;
    TaskSchedulerService service;

    @BeforeEach
    void setUp() {
        store = new MarkdownTaskStore(tasksDir);
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        publisher = mock(ApplicationEventPublisher.class);
        service = new TaskSchedulerService(store, scheduler, publisher);
    }

    @Test
    void scheduleCronTaskShouldRegisterTask() {
        var task = cronTask("task-cron-1", "0 0 9 * * *");
        store.save(task);

        service.scheduleCron(task);

        assertThat(service.getScheduledTaskIds()).contains("task-cron-1");
    }

    @Test
    void cancelShouldRemoveScheduledTask() {
        var task = cronTask("task-cron-2", "0 0 9 * * *");
        store.save(task);
        service.scheduleCron(task);

        service.cancel("task-cron-2");

        assertThat(service.getScheduledTaskIds()).doesNotContain("task-cron-2");
    }

    @Test
    void restoreAllShouldScheduleExistingCronTasks() {
        store.save(cronTask("cron-a", "0 0 9 * * *"));
        store.save(cronTask("cron-b", "0 0 18 * * *"));
        store.save(immediateTask("imm-c"));

        service.restoreAll();

        assertThat(service.getScheduledTaskIds()).containsExactlyInAnyOrder("cron-a", "cron-b");
    }

    private Task cronTask(String id, String cron) {
        return new Task(id, TaskType.CRON, TaskStatus.PENDING, "Test cron",
            cron, null, "cli", Instant.now(), null, null, "# Test");
    }

    private Task immediateTask(String id) {
        return new Task(id, TaskType.IMMEDIATE, TaskStatus.PENDING, "Test immediate",
            null, null, "cli", Instant.now(), null, null, "# Test");
    }
}
