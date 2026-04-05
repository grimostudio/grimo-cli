package io.github.samzhu.grimo.shared.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SessionManagerTest {

    @TempDir Path tempDir;
    ApplicationEventPublisher publisher;
    SessionManager manager;

    @BeforeEach
    void setUp() {
        publisher = mock(ApplicationEventPublisher.class);
        manager = new SessionManager(tempDir, publisher, "claude", "opus");
    }

    @Test
    void startNewSessionShouldCreateJsonlAndIndex() throws Exception {
        manager.startNewSession("main", "/test", "1.0");

        assertThat(manager.getWriter().getSessionFile()).exists();
        var indexFile = tempDir.resolve("sessions-index.json");
        assertThat(indexFile).exists();

        var sessions = manager.listSessions();
        assertThat(sessions).hasSize(1);
        assertThat(sessions.getFirst().agent()).isEqualTo("claude");
        assertThat(sessions.getFirst().gitBranch()).isEqualTo("main");
    }

    @Test
    void onMessageWrittenShouldUpdateIndex() throws Exception {
        manager.startNewSession("main", "/test", "1.0");
        manager.onMessageWritten("user", "hello world");

        var sessions = manager.listSessions();
        assertThat(sessions.getFirst().messageCount()).isEqualTo(1);
        assertThat(sessions.getFirst().firstUserMessage()).isEqualTo("hello world");
    }

    @Test
    void firstUserMessageShouldTruncateAt80Chars() throws Exception {
        manager.startNewSession("main", "/test", "1.0");
        String longMsg = "a".repeat(100);
        manager.onMessageWritten("user", longMsg);

        var sessions = manager.listSessions();
        assertThat(sessions.getFirst().firstUserMessage()).hasSize(80);
    }

    @Test
    void resumeSessionShouldSwitchWriter() throws Exception {
        // Create first session
        manager.startNewSession("main", "/test", "1.0");
        String firstId = manager.getCurrentInfo().sessionId();
        manager.getWriter().writeUserMessage("first session msg");
        manager.onMessageWritten("user", "first session msg");

        // Create second session
        manager.startNewSession("main", "/test", "1.0");
        String secondId = manager.getCurrentInfo().sessionId();

        // Resume first
        manager.resumeSession(firstId);
        assertThat(manager.getCurrentInfo().sessionId()).isEqualTo(firstId);
        verify(publisher).publishEvent((Object) any());
    }

    @Test
    void resumeNonExistentSessionShouldThrow() {
        manager.startNewSession("main", "/test", "1.0");
        assertThatThrownBy(() -> manager.resumeSession("nonexistent"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void continueLastSessionShouldResumeLatest() throws Exception {
        manager.startNewSession("main", "/test", "1.0");
        String firstId = manager.getCurrentInfo().sessionId();
        manager.getWriter().writeUserMessage("msg1");
        manager.onMessageWritten("user", "msg1");

        // Simulate restart: new manager with same dataDir
        var manager2 = new SessionManager(tempDir, publisher, "claude", "opus");
        boolean resumed = manager2.continueLastSession();

        assertThat(resumed).isTrue();
        assertThat(manager2.getCurrentInfo().sessionId()).isEqualTo(firstId);
    }

    @Test
    void continueLastWithNoHistoryShouldReturnFalse() {
        boolean resumed = manager.continueLastSession();
        assertThat(resumed).isFalse();
    }

    @Test
    void indexShouldSurviveCrashRecovery() throws Exception {
        manager.startNewSession("main", "/test", "1.0");
        manager.getWriter().writeUserMessage("test");
        manager.onMessageWritten("user", "test");

        // Delete index to simulate crash
        Files.deleteIfExists(tempDir.resolve("sessions-index.json"));

        // New manager should rebuild
        var manager2 = new SessionManager(tempDir, publisher, "claude", "opus");
        var sessions = manager2.listSessions();
        assertThat(sessions).hasSize(1);
    }
}
