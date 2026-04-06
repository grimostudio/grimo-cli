package io.github.samzhu.grimo.home;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        // Note: config.yaml is now created by GrimoConfig constructor, not by GrimoHome.initialize()
    }

    @Test
    void initializeShouldNotCreateConversationsDir() {
        var home = new GrimoHome(tempDir);
        home.initialize();
        assertThat(tempDir.resolve("conversations")).doesNotExist();
    }

    @Test
    void initializeShouldNotCreateConfigFile() {
        // GrimoHome.initialize() no longer creates config.yaml — GrimoConfig handles its own defaults
        var home = new GrimoHome(tempDir);
        home.initialize();
        assertThat(tempDir.resolve("config.yaml")).doesNotExist();
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
