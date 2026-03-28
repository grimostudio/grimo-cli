package io.github.samzhu.grimo.skill.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillLoaderTest {

    @TempDir
    Path skillsDir;

    // === 標準格式測試 ===

    @Test
    void loadShouldParseStandardFields() throws Exception {
        createSkillMd("multi-review", """
            ---
            name: multi-review
            description: Multi-agent parallel code review
            license: MIT
            compatibility: Requires Java 25+
            allowed-tools: Bash Read Grep
            metadata:
              grimo.tier: std
              grimo.author: grimo-team
              grimo.version: "1.0.0"
              grimo.execution: parallel
            ---

            # Multi Review

            Dispatch multiple agents for code review.
            """);

        var loader = new SkillLoader(skillsDir);
        List<SkillDefinition> skills = loader.loadAll();

        assertThat(skills).hasSize(1);
        var skill = skills.getFirst();
        assertThat(skill.name()).isEqualTo("multi-review");
        assertThat(skill.description()).isEqualTo("Multi-agent parallel code review");
        assertThat(skill.license()).isEqualTo("MIT");
        assertThat(skill.compatibility()).isEqualTo("Requires Java 25+");
        assertThat(skill.allowedTools()).containsExactly("Bash", "Read", "Grep");
        assertThat(skill.metadata()).containsEntry("grimo.tier", "std");
        assertThat(skill.metadata()).containsEntry("grimo.author", "grimo-team");
        assertThat(skill.grimoTier()).isEqualTo("std");
        assertThat(skill.grimoAuthor()).isEqualTo("grimo-team");
        assertThat(skill.grimoVersion()).isEqualTo("1.0.0");
        assertThat(skill.grimoExecution()).isEqualTo("parallel");
        assertThat(skill.body()).contains("# Multi Review");
    }

    @Test
    void loadShouldParseClaudeCodeExtensionFields() throws Exception {
        createSkillMd("brainstorming", """
            ---
            name: brainstorming
            description: Explore ideas before implementation
            model: claude-sonnet-4-5
            effort: high
            context: fork
            agent: task
            user-invocable: true
            disable-model-invocation: false
            argument-hint: "<topic>"
            paths:
              - "src/**/*.java"
              - "docs/**/*.md"
            shell: bash
            ---

            # Brainstorming

            Help turn ideas into designs.
            """);

        var loader = new SkillLoader(skillsDir);
        var skill = loader.loadAll().getFirst();

        assertThat(skill.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(skill.effort()).isEqualTo("high");
        assertThat(skill.context()).isEqualTo("fork");
        assertThat(skill.agent()).isEqualTo("task");
        assertThat(skill.userInvocable()).isTrue();
        assertThat(skill.disableModelInvocation()).isFalse();
        assertThat(skill.argumentHint()).isEqualTo("<topic>");
        assertThat(skill.paths()).containsExactly("src/**/*.java", "docs/**/*.md");
        assertThat(skill.shell()).isEqualTo("bash");
    }

    @Test
    void loadShouldHandleMinimalSkill() throws Exception {
        createSkillMd("simple", """
            ---
            name: simple
            description: A minimal skill
            ---

            Just do the thing.
            """);

        var loader = new SkillLoader(skillsDir);
        var skill = loader.loadAll().getFirst();

        assertThat(skill.name()).isEqualTo("simple");
        assertThat(skill.description()).isEqualTo("A minimal skill");
        assertThat(skill.license()).isNull();
        assertThat(skill.compatibility()).isNull();
        assertThat(skill.allowedTools()).isEmpty();
        assertThat(skill.metadata()).isEmpty();
        assertThat(skill.model()).isNull();
        assertThat(skill.effort()).isNull();
        assertThat(skill.userInvocable()).isNull();
        assertThat(skill.paths()).isEmpty();
        assertThat(skill.body()).contains("Just do the thing.");
    }

    // === 向後相容測試 ===

    @Test
    void loadShouldMigrateDeprecatedFieldsToMetadata() throws Exception {
        createSkillMd("legacy", """
            ---
            name: legacy
            description: Old format skill
            version: 2.0.0
            author: old-author
            executor: api
            triggers:
              - cron
              - command
            ---

            # Legacy Skill
            """);

        var loader = new SkillLoader(skillsDir);
        var skill = loader.loadAll().getFirst();

        // version/author 自動映射到 metadata
        assertThat(skill.grimoVersion()).isEqualTo("2.0.0");
        assertThat(skill.grimoAuthor()).isEqualTo("old-author");
        // executor/triggers 直接忽略（不存入任何欄位）
        assertThat(skill.metadata()).doesNotContainKey("executor");
        assertThat(skill.metadata()).doesNotContainKey("triggers");
        assertThat(skill.body()).contains("# Legacy Skill");
    }

    // === Forward compatibility 測試 ===

    @Test
    void loadShouldIgnoreUnknownFields() throws Exception {
        createSkillMd("future", """
            ---
            name: future
            description: Skill with unknown fields
            some-future-field: value
            another-field: 42
            ---

            # Future Skill
            """);

        var loader = new SkillLoader(skillsDir);
        var skill = loader.loadAll().getFirst();

        assertThat(skill.name()).isEqualTo("future");
        assertThat(skill.description()).isEqualTo("Skill with unknown fields");
        assertThat(skill.body()).contains("# Future Skill");
    }

    // === 既有行為保留 ===

    @Test
    void loadShouldSkipDirectoriesWithoutSkillMd() throws Exception {
        Files.createDirectories(skillsDir.resolve("empty-dir"));
        createSkillMd("valid", """
            ---
            name: valid
            description: A valid skill
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

    // === Helper ===

    private void createSkillMd(String dirName, String content) throws Exception {
        var dir = skillsDir.resolve(dirName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), content);
    }
}
