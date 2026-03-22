package io.github.samzhu.grimo.skill.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillLoaderTest {

    @TempDir
    Path skillsDir;

    @Test
    void loadShouldParseSkillMdFrontmatter() throws Exception {
        var skillDir = skillsDir.resolve("healthcheck");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: healthcheck
            description: Check service health status
            version: 1.0.0
            author: grimo-builtin
            executor: api
            triggers:
              - cron
              - command
            ---

            # Health Check

            Check the specified URL HTTP status.
            """);

        var loader = new SkillLoader(skillsDir);
        List<SkillDefinition> skills = loader.loadAll();

        assertThat(skills).hasSize(1);
        var skill = skills.getFirst();
        assertThat(skill.name()).isEqualTo("healthcheck");
        assertThat(skill.description()).isEqualTo("Check service health status");
        assertThat(skill.version()).isEqualTo("1.0.0");
        assertThat(skill.author()).isEqualTo("grimo-builtin");
        assertThat(skill.executor()).isEqualTo("api");
        assertThat(skill.triggers()).containsExactly("cron", "command");
        assertThat(skill.body()).contains("# Health Check");
    }

    @Test
    void loadShouldSkipDirectoriesWithoutSkillMd() throws Exception {
        Files.createDirectories(skillsDir.resolve("empty-dir"));
        Files.createDirectories(skillsDir.resolve("valid"));
        Files.writeString(skillsDir.resolve("valid/SKILL.md"), """
            ---
            name: valid
            description: A valid skill
            version: 1.0.0
            author: test
            executor: api
            triggers: []
            ---

            # Valid
            """);

        var loader = new SkillLoader(skillsDir);
        assertThat(loader.loadAll()).hasSize(1);
    }

    @Test
    void loadShouldReturnEmptyForEmptyDirectory() {
        var loader = new SkillLoader(skillsDir);
        assertThat(loader.loadAll()).isEmpty();
    }
}
