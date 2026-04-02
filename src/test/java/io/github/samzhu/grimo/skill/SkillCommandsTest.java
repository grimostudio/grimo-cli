package io.github.samzhu.grimo.skill;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SkillCommandsTest {

    @TempDir
    Path skillsDir;

    SkillRegistry registry;
    SkillCommands commands;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
        commands = new SkillCommands(registry, skillsDir, mock(ApplicationEventPublisher.class));
    }

    @Test
    void listShouldShowRegisteredSkills() {
        registry.register(new SkillDefinition(
            "healthcheck", "Check health", null, null, List.of(),
            Map.of("grimo.version", "1.0.0", "grimo.author", "grimo-builtin"),
            null, null, null, null, null, null, null, List.of(), null,
            "# HC"
        ));

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
        registry.register(new SkillDefinition(
            "test", "Test", null, null, List.of(), Map.of(),
            null, null, null, null, null, null, null, List.of(), null,
            "# Test"
        ));

        String output = commands.remove("test");

        assertThat(output).contains("removed");
        assertThat(registry.get("test")).isEmpty();
    }
}
