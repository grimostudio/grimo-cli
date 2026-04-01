package io.github.samzhu.grimo.shared.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GrimoHomeTest {

    @TempDir
    Path tempDir;

    @Test
    void initializeShouldCreateRequiredDirectories() {
        var home = new GrimoHome(tempDir);
        home.initialize();

        assertThat(tempDir.resolve("tasks")).isDirectory();
        assertThat(tempDir.resolve("skills")).isDirectory();
        assertThat(tempDir.resolve("agents")).isDirectory();
        assertThat(tempDir.resolve("logs")).isDirectory();
        assertThat(tempDir.resolve("projects")).isDirectory();
        assertThat(tempDir.resolve("config.yaml")).isRegularFile();
    }

    @Test
    void initializeShouldNotCreateConversationsDir() {
        var home = new GrimoHome(tempDir);
        home.initialize();
        assertThat(tempDir.resolve("conversations")).doesNotExist();
    }

    @Test
    void initializeShouldNotOverwriteExistingConfig() throws Exception {
        var home = new GrimoHome(tempDir);
        Files.writeString(tempDir.resolve("config.yaml"), "custom: true\n");
        home.initialize();
        assertThat(tempDir.resolve("config.yaml")).content().contains("custom: true");
    }

    @Test
    void shouldReturnCorrectSubPaths() {
        var home = new GrimoHome(tempDir);
        assertThat(home.root()).isEqualTo(tempDir);
        assertThat(home.configFile()).isEqualTo(tempDir.resolve("config.yaml"));
        assertThat(home.skillsDir()).isEqualTo(tempDir.resolve("skills"));
        assertThat(home.tasksDir()).isEqualTo(tempDir.resolve("tasks"));
        assertThat(home.agentsDir()).isEqualTo(tempDir.resolve("agents"));
        assertThat(home.logsDir()).isEqualTo(tempDir.resolve("logs"));
        assertThat(home.projectsDir()).isEqualTo(tempDir.resolve("projects"));
    }

    @Test
    void isInitializedShouldReturnFalseForEmptyDir() {
        assertThat(new GrimoHome(tempDir).isInitialized()).isFalse();
    }

    @Test
    void isInitializedShouldReturnTrueAfterInit() {
        var home = new GrimoHome(tempDir);
        home.initialize();
        assertThat(home.isInitialized()).isTrue();
    }
}
