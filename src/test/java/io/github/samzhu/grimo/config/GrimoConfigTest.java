package io.github.samzhu.grimo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    void getDefaultAgentShouldReturnConfiguredValue() throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            agents:
              default: anthropic
              model: claude-sonnet-4
            """);

        var config = new GrimoConfig(configFile);
        assertThat(config.getDefaultAgent()).isEqualTo("anthropic");
        assertThat(config.getDefaultModel()).isEqualTo("claude-sonnet-4");
    }

    @Test
    void getDefaultAgentShouldReturnNullWhenNotConfigured() {
        var config = new GrimoConfig(tempDir.resolve("config.yaml"));
        assertThat(config.getDefaultAgent()).isNull();
        assertThat(config.getDefaultModel()).isNull();
    }

    @Test
    void setDefaultAgentShouldPersist() {
        var configFile = tempDir.resolve("config.yaml");
        var config = new GrimoConfig(configFile);

        config.setDefaultAgent("openai");
        config.setDefaultModel("gpt-4o");

        var reloaded = new GrimoConfig(configFile);
        assertThat(reloaded.getDefaultAgent()).isEqualTo("openai");
        assertThat(reloaded.getDefaultModel()).isEqualTo("gpt-4o");
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

    @Test
    void setMcpServerShouldPersistSseServer() {
        var configFile = tempDir.resolve("config.yaml");
        var config = new GrimoConfig(configFile);

        config.setMcpServer("deepwiki", Map.of(
                "type", "sse",
                "url", "https://mcp.deepwiki.com/sse"
        ));

        var reloaded = new GrimoConfig(configFile);
        var servers = reloaded.getMcpServers();
        assertThat(servers).containsKey("deepwiki");
        assertThat(servers.get("deepwiki").get("type")).isEqualTo("sse");
        assertThat(servers.get("deepwiki").get("url")).isEqualTo("https://mcp.deepwiki.com/sse");
    }

    @Test
    void setMcpServerShouldCreateMcpSectionWhenMissing() {
        var configFile = tempDir.resolve("config.yaml");
        var config = new GrimoConfig(configFile);

        config.setMcpServer("test-server", Map.of("type", "http", "url", "http://localhost/mcp"));

        assertThat(configFile).exists();
        var servers = new GrimoConfig(configFile).getMcpServers();
        assertThat(servers).containsKey("test-server");
    }

    @Test
    void removeMcpServerShouldDeleteFromConfig() throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            mcp:
              deepwiki:
                type: sse
                url: https://mcp.deepwiki.com/sse
              filesystem:
                type: stdio
                command: npx
            """);

        var config = new GrimoConfig(configFile);
        boolean removed = config.removeMcpServer("deepwiki");

        assertThat(removed).isTrue();
        var servers = new GrimoConfig(configFile).getMcpServers();
        assertThat(servers).doesNotContainKey("deepwiki");
        assertThat(servers).containsKey("filesystem");
    }

    @Test
    void getSandboxModeShouldReturnConfiguredMode() throws Exception {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            sandbox:
              mode: docker
            """);
        var config = new GrimoConfig(configFile);
        assertThat(config.getSandboxMode()).isEqualTo("docker");
    }

    @Test
    void getSandboxModeShouldReturnLocalWhenNotConfigured() throws Exception {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "agents:\n  default: claude\n");
        var config = new GrimoConfig(configFile);
        assertThat(config.getSandboxMode()).isEqualTo("local");
    }

    @Test
    void removeMcpServerShouldReturnFalseWhenNotFound() {
        var configFile = tempDir.resolve("config.yaml");
        var config = new GrimoConfig(configFile);

        boolean removed = config.removeMcpServer("nonexistent");

        assertThat(removed).isFalse();
    }

    @Test
    void getTierModelsShouldReturnFallbackListPerTier() throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            tier-models:
              lite:
                - agent: gemini
                  model: gemini-2.5-flash
                - agent: claude
                  model: claude-haiku-4
              std:
                - agent: claude
                  model: claude-sonnet-4
              pro:
                - agent: claude
                  model: claude-opus-4
            """);

        var config = new GrimoConfig(configFile);
        var tiers = config.getTierModels();

        assertThat(tiers).containsKey("lite");
        assertThat(tiers.get("lite")).hasSize(2);
        assertThat(tiers.get("lite").getFirst().get("agent")).isEqualTo("gemini");
        assertThat(tiers.get("lite").getFirst().get("model")).isEqualTo("gemini-2.5-flash");
        assertThat(tiers).containsKey("std");
        assertThat(tiers).containsKey("pro");
    }

    @Test
    void getTierModelsShouldReturnEmptyWhenNotConfigured() {
        var config = new GrimoConfig(tempDir.resolve("config.yaml"));
        assertThat(config.getTierModels()).isEmpty();
    }

    @Test
    void getSkillOverridesShouldReturnOverridesMap() throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            skill-overrides:
              deep-research:
                tier: pro
              tdd-workflow:
                agent: claude
                model: claude-opus-4
            """);

        var config = new GrimoConfig(configFile);
        var overrides = config.getSkillOverrides();

        assertThat(overrides).containsKey("deep-research");
        assertThat(overrides.get("deep-research").get("tier")).isEqualTo("pro");
        assertThat(overrides).containsKey("tdd-workflow");
        assertThat(overrides.get("tdd-workflow").get("agent")).isEqualTo("claude");
    }

    @Test
    void getSkillOverridesShouldReturnEmptyWhenNotConfigured() {
        var config = new GrimoConfig(tempDir.resolve("config.yaml"));
        assertThat(config.getSkillOverrides()).isEmpty();
    }

    @Test
    void getTierKeywordsShouldReturnKeywordsPerTier() throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            tier-keywords:
              pro:
                - 仔細想
                - think hard
              lite:
                - 快速
                - quickly
            """);

        var config = new GrimoConfig(configFile);
        var keywords = config.getTierKeywords();

        assertThat(keywords).containsKey("pro");
        assertThat(keywords.get("pro")).contains("仔細想", "think hard");
        assertThat(keywords).containsKey("lite");
        assertThat(keywords.get("lite")).contains("快速", "quickly");
    }

    @Test
    void getTierKeywordsShouldReturnEmptyWhenNotConfigured() {
        var config = new GrimoConfig(tempDir.resolve("config.yaml"));
        assertThat(config.getTierKeywords()).isEmpty();
    }

    @Test
    void setSkillOverrideShouldPersist() {
        var configFile = tempDir.resolve("config.yaml");
        var config = new GrimoConfig(configFile);

        config.setSkillOverride("deep-research", Map.of("tier", "pro"));

        var reloaded = new GrimoConfig(configFile);
        var overrides = reloaded.getSkillOverrides();
        assertThat(overrides).containsKey("deep-research");
        assertThat(overrides.get("deep-research").get("tier")).isEqualTo("pro");
    }
}
