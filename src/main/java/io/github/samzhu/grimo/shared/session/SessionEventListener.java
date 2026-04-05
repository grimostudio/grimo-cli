package io.github.samzhu.grimo.shared.session;

import io.github.samzhu.grimo.shared.event.AgentCallRecordedEvent;
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
 * - 注入 SessionManager 而非 SessionWriter；透過 sessionManager.getWriter() 取得 writer，
 *   確保 session 切換（resume）後仍寫入正確的 JSONL 檔案
 */
@Component
public class SessionEventListener {

    private final SessionManager sessionManager;

    public SessionEventListener(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @EventListener
    public void on(AgentCallRecordedEvent event) {
        sessionManager.getWriter().writeUserMessage(event.userGoal());
        sessionManager.getWriter().writeAssistantMessage(event.agentResult());
    }

    @EventListener
    public void on(DevModeEnteredEvent event) {
        sessionManager.getWriter().writeDispatchEntered(
            event.taskId(), event.agent(), event.model(),
            event.tier(), event.branchName(), event.goal());
    }

    @EventListener
    public void on(DevModeCompletedEvent event) {
        sessionManager.getWriter().writeDispatchCompleted(
            event.taskId(), event.agent(), event.model(),
            event.tier(), event.goal(),
            event.executionMode(), event.workDir(),
            event.branchName(), event.baseSha(), null,
            event.hasChanges(), event.commitCount(),
            event.diffStat(), event.durationMs(),
            event.result(), event.externalSessionPath());
    }
}
