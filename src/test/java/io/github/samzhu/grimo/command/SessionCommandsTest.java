package io.github.samzhu.grimo.command;

import io.github.samzhu.grimo.shared.session.SessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SessionCommandsTest {

    @TempDir Path tempDir;

    @Test
    void sessionInfoShouldReturnFormattedInfo() {
        var publisher = mock(ApplicationEventPublisher.class);
        var manager = new SessionManager(tempDir, publisher, "claude", "opus");
        manager.startNewSession("main", "/test", "1.0");
        manager.onMessageWritten("user", "test");

        var commands = new SessionCommands(manager);
        String result = commands.info();

        assertThat(result).contains(manager.getCurrentInfo().sessionId());
        assertThat(result).contains("claude");
        assertThat(result).contains("1"); // messageCount
    }

    @Test
    void sessionInfoWithNoSessionShouldReturnMessage() {
        var publisher = mock(ApplicationEventPublisher.class);
        var manager = new SessionManager(tempDir, publisher, "claude", "opus");

        var commands = new SessionCommands(manager);
        String result = commands.info();

        assertThat(result).contains("No active session");
    }
}
