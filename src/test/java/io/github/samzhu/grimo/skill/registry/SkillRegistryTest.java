package io.github.samzhu.grimo.skill.registry;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRegistryTest {

    SkillRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
    }

    @Test
    void registerAndGetSkill() {
        var skill = sampleSkill("healthcheck");
        registry.register(skill);

        assertThat(registry.get("healthcheck")).isPresent();
        assertThat(registry.get("healthcheck").get().name()).isEqualTo("healthcheck");
    }

    @Test
    void removeShouldUnregisterSkill() {
        registry.register(sampleSkill("to-remove"));
        registry.remove("to-remove");

        assertThat(registry.get("to-remove")).isEmpty();
    }

    @Test
    void listAllShouldReturnAllSkills() {
        registry.register(sampleSkill("a"));
        registry.register(sampleSkill("b"));

        assertThat(registry.listAll()).hasSize(2);
    }

    @Test
    void registerShouldOverwriteExisting() {
        registry.register(new SkillDefinition("x", "old", "1.0", "a", "api", List.of(), "old body"));
        registry.register(new SkillDefinition("x", "new", "2.0", "a", "api", List.of(), "new body"));

        assertThat(registry.get("x").get().description()).isEqualTo("new");
    }

    private SkillDefinition sampleSkill(String name) {
        return new SkillDefinition(name, "Test skill", "1.0.0", "test", "api", List.of(), "# Test");
    }
}
