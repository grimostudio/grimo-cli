package io.github.samzhu.grimo.shared.session;

import io.github.samzhu.grimo.shared.event.DevModeEnteredEvent;
import io.github.samzhu.grimo.shared.event.DevModeCompletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 監聽 DevMode 事件，寫入 dispatch 紀錄到 session。
 *
 * 設計說明：
 * - 遵循 event-driven 架構：agent/ 模組不直接依賴 shared/session/
 * - DevModeRunner 只負責發布事件，SessionEventListener 負責持久化
 * - 使用 @EventListener（非 @ApplicationModuleListener）因為 CLI app 沒有 DB transaction
 */
@Component
public class SessionEventListener {

    private final SessionWriter sessionWriter;

    public SessionEventListener(SessionWriter sessionWriter) {
        this.sessionWriter = sessionWriter;
    }

    @EventListener
    public void on(DevModeEnteredEvent event) {
        sessionWriter.writeDispatchEntered(
            event.taskId(), event.agent(), event.model(),
            event.tier(), event.branchName(), event.goal());
    }

    @EventListener
    public void on(DevModeCompletedEvent event) {
        sessionWriter.writeDispatchCompleted(
            event.taskId(), event.agent(), event.model(),
            event.tier(), event.goal(),
            event.executionMode(), event.workDir(),
            event.branchName(), event.baseSha(), null,
            event.hasChanges(), event.commitCount(),
            event.diffStat(), event.durationMs(),
            event.result(), event.externalSessionPath());
    }
}
