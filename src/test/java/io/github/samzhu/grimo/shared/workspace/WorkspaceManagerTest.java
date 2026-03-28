package io.github.samzhu.grimo.shared.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void initializeShouldCreateRequiredDirectories() {
        var manager = new WorkspaceManager(tempDir);
        manager.initialize();

        assertThat(tempDir.resolve("tasks")).isDirectory();
        assertThat(tempDir.resolve("skills")).isDirectory();
        assertThat(tempDir.resolve("conversations")).isDirectory();
        assertThat(tempDir.resolve("logs")).isDirectory();
        assertThat(tempDir.resolve("agents")).isDirectory();
        assertThat(tempDir.resolve("config.yaml")).isRegularFile();
        assertThat(tempDir.resolve("config.yaml")).content().contains("# MCP Server");
    }

    @Test
    void initializeShouldNotOverwriteExistingConfig() throws Exception {
        var manager = new WorkspaceManager(tempDir);
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "mcp:\n  deepwiki:\n    type: sse\n    url: https://mcp.deepwiki.com/sse\n");

        manager.initialize();

        assertThat(configFile).content().contains("deepwiki");
        assertThat(configFile).content().doesNotContain("# Grimo CLI");
    }

    @Test
    void shouldReturnCorrectSubPaths() {
        var manager = new WorkspaceManager(tempDir);

        assertThat(manager.tasksDir()).isEqualTo(tempDir.resolve("tasks"));
        assertThat(manager.skillsDir()).isEqualTo(tempDir.resolve("skills"));
        assertThat(manager.conversationsDir()).isEqualTo(tempDir.resolve("conversations"));
        assertThat(manager.logsDir()).isEqualTo(tempDir.resolve("logs"));
        assertThat(manager.configFile()).isEqualTo(tempDir.resolve("config.yaml"));
        assertThat(manager.agentsDir()).isEqualTo(tempDir.resolve("agents"));
    }

    @Test
    void isInitializedShouldReturnFalseForEmptyDir() {
        var manager = new WorkspaceManager(tempDir);
        assertThat(manager.isInitialized()).isFalse();
    }

    @Test
    void isInitializedShouldReturnTrueAfterInit() {
        var manager = new WorkspaceManager(tempDir);
        manager.initialize();
        assertThat(manager.isInitialized()).isTrue();
    }
}
