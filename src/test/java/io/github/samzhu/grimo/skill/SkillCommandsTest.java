package io.github.samzhu.grimo.skill;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillCommandsTest {

    @TempDir
    Path skillsDir;

    SkillRegistry registry;
    SkillCommands commands;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
        commands = new SkillCommands(registry, skillsDir);
    }

    @Test
    void listShouldShowRegisteredSkills() {
        registry.register(new SkillDefinition("healthcheck", "Check health", "1.0.0",
            "grimo-builtin", "api", List.of("cron"), "# HC"));

        String output = commands.list();

        assertThat(output).contains("healthcheck");
        assertThat(output).contains("1.0.0");
        assertThat(output).contains("loaded");
    }

    @Test
    void listShouldShowEmptyMessage() {
        String output = commands.list();
        assertThat(output).contains("No skills loaded");
    }

    @Test
    void removeShouldUnregisterSkill() {
        registry.register(new SkillDefinition("test", "Test", "1.0.0",
            "test", "api", List.of(), "# Test"));

        String output = commands.remove("test");

        assertThat(output).contains("removed");
        assertThat(registry.get("test")).isEmpty();
    }
}
