package io.github.samzhu.grimo.shared.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GrimoConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void loadShouldReturnEmptyMapWhenFileDoesNotExist() {
        var config = new GrimoConfig(tempDir.resolve("config.yaml"));
        assertThat(config.load()).isEmpty();
    }

    @Test
    void loadShouldParseYamlFile() throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            agents:
              default: claude-cli
            channels:
              telegram:
                enabled: true
            """);

        var config = new GrimoConfig(configFile);
        Map<String, Object> data = config.load();

        assertThat(data).containsKey("agents");
        assertThat(data).containsKey("channels");
    }

    @Test
    void saveShouldWriteYamlFile() {
        var configFile = tempDir.resolve("config.yaml");
        var config = new GrimoConfig(configFile);

        config.save(Map.of("agents", Map.of("default", "anthropic")));

        assertThat(configFile).exists();
        assertThat(configFile).content().contains("default: anthropic");
    }

    @Test
    void shouldSetFilePermissionsTo600() throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        var config = new GrimoConfig(configFile);

        config.save(Map.of("test", "data"));

        // On POSIX systems, verify permissions
        var perms = Files.getPosixFilePermissions(configFile);
        assertThat(perms).hasSize(2); // OWNER_READ, OWNER_WRITE
    }
}
