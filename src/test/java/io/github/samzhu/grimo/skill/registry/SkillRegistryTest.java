package io.github.samzhu.grimo.skill.registry;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        registry.register(testSkill("x", "old", "old body"));
        registry.register(testSkill("x", "new", "new body"));

        assertThat(registry.get("x").get().description()).isEqualTo("new");
    }

    private SkillDefinition sampleSkill(String name) {
        return testSkill(name, "Test skill", "# Test");
    }

    private SkillDefinition testSkill(String name, String description, String body) {
        return new SkillDefinition(
            name, description, null, null, List.of(), Map.of(),
            null, null, null, null, null, null, null, List.of(), null,
            body
        );
    }
}
