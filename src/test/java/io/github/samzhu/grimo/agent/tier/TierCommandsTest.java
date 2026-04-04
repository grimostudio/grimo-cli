package io.github.samzhu.grimo.agent.tier;

import io.github.samzhu.grimo.config.GrimoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TierCommandsTest {

    @TempDir
    Path tempDir;

    private GrimoConfig config;
    private TierCommands commands;
    private AtomicReference<Tier> sessionTierRef;

    @BeforeEach
    void setUp() throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            tier-models:
              lite:
                - agent: gemini
                  model: gemini-2.5-flash
              std:
                - agent: claude
                  model: claude-sonnet-4
              pro:
                - agent: claude
                  model: claude-opus-4
            """);
        config = new GrimoConfig(configFile);
        sessionTierRef = new AtomicReference<>(null);
        commands = new TierCommands(config, sessionTierRef);
    }

    @Test
    void tierShowShouldDisplayCurrentTier() {
        String output = commands.tier(null);
        assertThat(output).contains("std"); // default
    }

    @Test
    void tierSetShouldUpdateSessionTier() {
        String output = commands.tier("pro");
        assertThat(output).contains("pro");
        assertThat(sessionTierRef.get()).isEqualTo(Tier.PRO);
    }

    @Test
    void tierSetInvalidShouldWarnUser() {
        String output = commands.tier("invalid");
        assertThat(output).contains("Unknown tier");
        assertThat(sessionTierRef.get()).isNull(); // unchanged
    }

    @Test
    void skillTierShouldSetOverride() {
        String output = commands.skillTier("deep-research pro");
        assertThat(output).contains("deep-research").contains("pro");

        var overrides = config.getSkillOverrides();
        assertThat(overrides).containsKey("deep-research");
        assertThat(overrides.get("deep-research").get("tier")).isEqualTo("pro");
    }
}
