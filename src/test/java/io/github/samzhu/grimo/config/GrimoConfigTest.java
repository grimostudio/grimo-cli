package io.github.samzhu.grimo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class GrimoConfigTest {

    @TempDir Path tempDir;

    @Test
    void constructorShouldCreateDefaultFileWhenMissing() {
        var configFile = tempDir.resolve("config.yaml");
        var config = new GrimoConfig(configFile);

        assertThat(configFile).exists();
        assertThat(config.getDefaultAgent()).isEqualTo("claude");
        assertThat(config.getDefaultModel()).isEqualTo("claude-sonnet-4-6");
        assertThat(config.getSandboxMode()).isEqualTo("local");
    }

    @Test
    void constructorShouldLoadExistingFile() throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            agents:
              default: gemini
              model: gemini-2.5-pro
            sandbox:
              mode: docker
            """);

        var config = new GrimoConfig(configFile);
        assertThat(config.getDefaultAgent()).isEqualTo("gemini");
        assertThat(config.getDefaultModel()).isEqualTo("gemini-2.5-pro");
        assertThat(config.getSandboxMode()).isEqualTo("docker");
    }

    @Test
    void setDefaultAgentShouldWriteThrough() {
        var configFile = tempDir.resolve("config.yaml");
        var config = new GrimoConfig(configFile);

        config.setDefaultAgent("codex");
        assertThat(config.getDefaultAgent()).isEqualTo("codex");

        // Verify file was updated
        var reloaded = new GrimoConfig(configFile);
        assertThat(reloaded.getDefaultAgent()).isEqualTo("codex");
    }

    @Test
    void setDefaultModelShouldWriteThrough() {
        var configFile = tempDir.resolve("config.yaml");
        var config = new GrimoConfig(configFile);

        config.setDefaultModel("gpt-5.4");
        assertThat(config.getDefaultModel()).isEqualTo("gpt-5.4");

        var reloaded = new GrimoConfig(configFile);
        assertThat(reloaded.getDefaultModel()).isEqualTo("gpt-5.4");
    }

    @Test
    void mcpServersShouldBeEmptyByDefault() {
        var config = new GrimoConfig(tempDir.resolve("config.yaml"));
        assertThat(config.getMcpServers()).isEmpty();
    }

    @Test
    void setMcpServerShouldWriteThrough() {
        var configFile = tempDir.resolve("config.yaml");
        var config = new GrimoConfig(configFile);

        config.setMcpServer("deepwiki", Map.of("type", "sse", "url", "https://mcp.deepwiki.com/sse"));

        assertThat(config.getMcpServers()).containsKey("deepwiki");
        var reloaded = new GrimoConfig(configFile);
        assertThat(reloaded.getMcpServers()).containsKey("deepwiki");
    }

    @Test
    void removeMcpServerShouldWriteThrough() {
        var configFile = tempDir.resolve("config.yaml");
        var config = new GrimoConfig(configFile);
        config.setMcpServer("test", Map.of("type", "sse", "url", "http://localhost"));

        boolean removed = config.removeMcpServer("test");
        assertThat(removed).isTrue();
        assertThat(config.getMcpServers()).doesNotContainKey("test");
    }

    @Test
    void removeMcpServerShouldReturnFalseWhenNotFound() {
        var config = new GrimoConfig(tempDir.resolve("config.yaml"));
        assertThat(config.removeMcpServer("nonexistent")).isFalse();
    }

    @Test
    void getSandboxModeShouldDefaultToLocal() {
        var config = new GrimoConfig(tempDir.resolve("config.yaml"));
        assertThat(config.getSandboxMode()).isEqualTo("local");
    }
}
