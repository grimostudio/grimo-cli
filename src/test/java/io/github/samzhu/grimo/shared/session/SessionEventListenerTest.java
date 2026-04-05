package io.github.samzhu.grimo.shared.session;

import io.github.samzhu.grimo.shared.event.DevModeEnteredEvent;
import io.github.samzhu.grimo.shared.event.DevModeCompletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SessionEventListenerTest {

    @TempDir
    Path tempDir;

    @Test
    void onDevModeEnteredShouldWriteDispatchEntry() throws Exception {
        var dataDir = tempDir.resolve("project");
        Files.createDirectories(dataDir);
        var publisher = mock(ApplicationEventPublisher.class);
        var sessionManager = new SessionManager(dataDir, publisher, "claude", "claude-sonnet-4-6");
        sessionManager.startNewSession("main", "/test", "1.0");
        var writer = sessionManager.getWriter();
        var listener = new SessionEventListener(sessionManager);

        listener.on(new DevModeEnteredEvent(
            "task1", "claude", "claude-sonnet-4-6", "std",
            "fix bug", "grimo/task1", "/tmp/wt"));

        assertThat(Files.readString(writer.getSessionFile()))
            .contains("dispatch-entered")
            .contains("task1");
    }

    @Test
    void onDevModeCompletedShouldWriteMetaJson() throws Exception {
        var dataDir = tempDir.resolve("project");
        Files.createDirectories(dataDir);
        var publisher = mock(ApplicationEventPublisher.class);
        var sessionManager = new SessionManager(dataDir, publisher, "claude", "claude-sonnet-4-6");
        sessionManager.startNewSession("main", "/test", "1.0");
        var writer = sessionManager.getWriter();
        var listener = new SessionEventListener(sessionManager);

        listener.on(new DevModeCompletedEvent(
            "task1", "claude", "claude-sonnet-4-6", "std",
            "fix bug", "worktree", "/tmp/wt", "grimo/task1", "sha123",
            2, "3 files", 30000, true, "Done", null));

        assertThat(Files.readString(writer.getSessionFile()))
            .contains("dispatch-completed");
        assertThat(writer.dispatchesDir().resolve("task1.meta.json")).exists();
    }
}
