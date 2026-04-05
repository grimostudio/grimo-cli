package io.github.samzhu.grimo.command;

import io.github.samzhu.grimo.shared.session.SessionManager;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Session 管理指令。
 *
 * 設計說明：
 * - /session-resume 是 TUI 專屬攔截（在 TuiKeyHandler 處理，開 overlay），不在此註冊
 * - /session-info 走正常指令管線（InputPort → CommandDispatcher → 這裡）
 * - 放在 command/ package 而非 shared/session/，遵循 shared/ 不依賴功能模組規則
 */
@Component
public class SessionCommands {

    private final SessionManager sessionManager;

    public SessionCommands(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Command(name = "session-info", description = "Show current session info")
    public String info() {
        var info = sessionManager.getCurrentInfo();
        if (info == null) return "No active session.";

        String elapsed = formatDuration(Duration.between(info.startedAt(), Instant.now()));
        return String.format("""
                Session: %s%s
                Started: %s (%s ago)
                Messages: %d
                Agent: %s / %s""",
                info.sessionId(),
                info.resumed() ? " ↩" : "",
                info.startedAt(), elapsed,
                info.messageCount(),
                info.agent(), info.model());
    }

    private String formatDuration(Duration d) {
        if (d.toDays() > 0) return d.toDays() + "d";
        if (d.toHours() > 0) return d.toHours() + "h";
        if (d.toMinutes() > 0) return d.toMinutes() + "m";
        return d.toSeconds() + "s";
    }
}
