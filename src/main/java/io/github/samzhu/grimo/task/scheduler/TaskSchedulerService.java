package io.github.samzhu.grimo.task.scheduler;

import io.github.samzhu.grimo.shared.event.TaskExecutionEvent;
import io.github.samzhu.grimo.task.model.Task;
import io.github.samzhu.grimo.task.model.TaskType;
import io.github.samzhu.grimo.task.store.MarkdownTaskStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 排程服務，負責管理 cron 類型任務的排程與取消。
 *
 * 設計說明：
 * - 使用 Spring TaskScheduler 搭配 CronTrigger 註冊 cron 排程
 * - 以 ConcurrentHashMap 追蹤已排程任務的 ScheduledFuture，支援動態取消
 * - restoreAll() 在應用啟動時從 MarkdownTaskStore 載入所有 cron 任務並重新排程
 * - 任務觸發時透過 Spring ApplicationEventPublisher 發送 TaskExecutionEvent，
 *   由其他模組（如 agent）監聽處理，實現跨模組解耦
 */
public class TaskSchedulerService {

    private final MarkdownTaskStore store;
    private final TaskScheduler scheduler;
    private final ApplicationEventPublisher publisher;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public TaskSchedulerService(MarkdownTaskStore store, TaskScheduler scheduler,
                                 ApplicationEventPublisher publisher) {
        this.store = store;
        this.scheduler = scheduler;
        this.publisher = publisher;
    }

    /**
     * 將指定的 cron 任務註冊到排程器。
     * 任務觸發時會發送 TaskExecutionEvent 領域事件。
     */
    public void scheduleCron(Task task) {
        var future = scheduler.schedule(
            () -> executeTask(task),
            new CronTrigger(task.cron())
        );
        scheduledTasks.put(task.id(), future);
    }

    /**
     * 取消指定 taskId 的排程任務。
     * 使用 cancel(false) 不中斷正在執行的任務，僅阻止下次觸發。
     */
    public void cancel(String taskId) {
        var future = scheduledTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 取得目前所有已排程任務的 id 集合（唯讀快照）。
     */
    public Set<String> getScheduledTaskIds() {
        return Set.copyOf(scheduledTasks.keySet());
    }

    /**
     * 從 MarkdownTaskStore 載入所有 cron 類型任務並重新排程。
     * 通常在應用啟動時呼叫，恢復先前已排定的 cron 任務。
     */
    public void restoreAll() {
        store.loadAll().stream()
            .filter(t -> t.type() == TaskType.CRON)
            .filter(t -> t.cron() != null)
            .forEach(this::scheduleCron);
    }

    private void executeTask(Task task) {
        publisher.publishEvent(new TaskExecutionEvent(
            task.id(), task.description(), Instant.now()
        ));
    }
}
