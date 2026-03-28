# F2: SkillDefinition 對齊 Agent Skills 標準 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor SkillDefinition record to be compatible with the Agent Skills open standard (agentskills.io) and Claude Code extension fields, enabling third-party skills to load without errors.

**Architecture:** Replace the 7-field SkillDefinition record with a new structure containing: Agent Skills standard fields (name, description, license, compatibility, allowedTools, metadata), Claude Code extension fields (model, effort, context, agent, userInvocable, disableModelInvocation, argumentHint, paths, shell), and body. SkillLoader parses all known fields from YAML frontmatter with forward compatibility (unknown fields ignored, deprecated fields auto-mapped to metadata with WARN log). No SDK integration needed — AgentClient has no skill API; Grimo skills are dispatch instructions, not CLI agent skills.

**Tech Stack:** Java 25, SnakeYAML (already in classpath via Spring Boot), JUnit 5, AssertJ

**SDK Verification:**
- `spring-ai-community/agent-client` 0.10.0-SNAPSHOT: **No skill API** — AgentClient.Builder has no `skillCatalog()` or `skills()` method. Confirmed via GitHub source inspection.
- `spring-ai-community/spring-ai-agent-utils`: Has `SkillsTool` (Spring AI ToolCallback) for ChatClient integration, but Grimo uses AgentClient (CLI subprocess), not ChatClient. Not applicable to F2.
- Agent Skills standard: [agentskills.io/specification](https://agentskills.io/specification) — SKILL.md frontmatter with `name`, `description`, `license`, `compatibility`, `allowed-tools`, `metadata` fields.

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `src/main/java/io/github/samzhu/grimo/skill/loader/SkillDefinition.java` | Record restructure: 7 fields → standard + Claude Code + metadata + body |
| Modify | `src/main/java/io/github/samzhu/grimo/skill/loader/SkillLoader.java` | Parse new YAML fields, backward compat for deprecated fields |
| Modify | `src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java` | Display version/author from metadata instead of direct fields |
| Modify | `src/test/java/io/github/samzhu/grimo/skill/loader/SkillLoaderTest.java` | Tests for new format + backward compat + forward compat |
| Modify | `src/test/java/io/github/samzhu/grimo/skill/registry/SkillRegistryTest.java` | Update SkillDefinition constructor calls |
| Modify | `src/test/java/io/github/samzhu/grimo/skill/SkillCommandsTest.java` | Update constructor calls + metadata-based display |
| Modify | `src/test/java/io/github/samzhu/grimo/GrimoCommandCompleterTest.java` | Update SkillDefinition constructor calls |
| Modify | `docs/glossary.md` | Update Grimo Skill / Agent Skill definitions |

**Not modified** (confirmed no impact):
- `GrimoTuiRunner.java` — uses `skill.name()` and `skill.description()` only (unchanged)
- `GrimoCommandCompleter.java` — uses `skill.name()` and `skill.description()` only (unchanged)
- `SkillRegistry.java` — generic `ConcurrentHashMap<String, SkillDefinition>`, no field access
- `GrimoStartupRunner.java` — creates `SkillLoader` and `SkillRegistry` beans, no SkillDefinition usage

---

### Task 1: Refactor SkillDefinition record

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/skill/loader/SkillDefinition.java`

- [ ] **Step 1: Replace SkillDefinition record**

```java
package io.github.samzhu.grimo.skill.loader;

import java.util.List;
import java.util.Map;

/**
 * Skill 定義資料模型，對齊 Agent Skills 開放標準（agentskills.io/specification）。
 *
 * 設計說明：
 * - 標準欄位：name, description, license, compatibility, allowedTools, metadata
 * - Claude Code 擴充欄位：model, effort, context, agent, userInvocable, disableModelInvocation, argumentHint, paths, shell
 * - Grimo 擴充放在 metadata map 裡（grimo.tier, grimo.subagents, grimo.execution 等）
 * - 第三方 Skill（為 Claude Code / Gemini CLI 撰寫的）安裝到 Grimo 不會解析失敗
 *
 * @see <a href="https://agentskills.io/specification">Agent Skills Specification</a>
 * @see <a href="https://code.claude.com/docs/en/skills">Claude Code Skills Documentation</a>
 */
public record SkillDefinition(
    // Agent Skills 標準欄位
    String name,
    String description,
    String license,
    String compatibility,
    List<String> allowedTools,
    Map<String, String> metadata,
    // Claude Code 擴充欄位
    String model,
    String effort,
    String context,
    String agent,
    Boolean userInvocable,
    Boolean disableModelInvocation,
    String argumentHint,
    List<String> paths,
    String shell,
    // Body（Markdown 內容）
    String body
) {
    /**
     * Grimo 擴充：建議的執行等級（lite/std/pro）。
     * 預設 "std"。用於 F3 Tier 系統路由。
     */
    public String grimoTier() {
        return metadata().getOrDefault("grimo.tier", "std");
    }

    /**
     * Grimo 擴充：調度方式（parallel/sequential）。
     * 空字串表示單一 agent 執行。用於 F4 Sub-Agent 調度。
     */
    public String grimoExecution() {
        return metadata().getOrDefault("grimo.execution", "");
    }

    /**
     * Grimo 擴充：作者。從 metadata 讀取，向後相容舊格式。
     */
    public String grimoAuthor() {
        return metadata().getOrDefault("grimo.author", "");
    }

    /**
     * Grimo 擴充：版本。從 metadata 讀取，向後相容舊格式。
     */
    public String grimoVersion() {
        return metadata().getOrDefault("grimo.version", "");
    }

    /**
     * Grimo 擴充：sub-agent 列表（JSON 字串）。
     * 用於 F4 Sub-Agent 調度。回傳原始 JSON 字串，由調度層解析。
     */
    public String grimoSubagents() {
        return metadata().getOrDefault("grimo.subagents", "");
    }
}
```

- [ ] **Step 2: Verify compilation fails (expected — dependents reference old fields)**

Run: `./gradlew compileJava 2>&1 | head -30`
Expected: Compilation errors in SkillLoader, SkillCommands, and test files referencing old constructor

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/skill/loader/SkillDefinition.java
git commit -m "refactor: restructure SkillDefinition to align with Agent Skills standard (F2)

Break: intentional — SkillLoader, SkillCommands, and tests will be updated in next commits."
```

---

### Task 2: Rewrite SkillLoader for new format + backward compatibility

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/skill/loader/SkillLoader.java`
- Modify: `src/test/java/io/github/samzhu/grimo/skill/loader/SkillLoaderTest.java`

- [ ] **Step 1: Write the failing tests**

Replace `src/test/java/io/github/samzhu/grimo/skill/loader/SkillLoaderTest.java`:

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.skill.loader.SkillLoaderTest" 2>&1 | tail -20`
Expected: FAIL — SkillLoader.parseSkillMd() still uses old 7-param constructor

- [ ] **Step 3: Rewrite SkillLoader.parseSkillMd()**

Replace `src/main/java/io/github/samzhu/grimo/skill/loader/SkillLoader.java`:

```java
package io.github.samzhu.grimo.skill.loader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 從 workspace skills 目錄載入 SKILL.md，解析為 SkillDefinition。
 *
 * 設計說明：
 * - 對齊 Agent Skills 開放標準（agentskills.io/specification）的 SKILL.md 格式
 * - 支援 Claude Code 擴充欄位（model, effort, context, agent 等）
 * - 向後相容：舊格式的 version/author 自動映射到 metadata，triggers/executor 忽略並 WARN
 * - Forward compatibility：未知欄位直接忽略，不報錯
 *
 * @see <a href="https://agentskills.io/specification">Agent Skills Specification</a>
 */
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private final Path skillsDir;

    public SkillLoader(Path skillsDir) {
        this.skillsDir = skillsDir;
    }

    /**
     * Scans all subdirectories under skillsDir, loading any SKILL.md found.
     */
    public List<SkillDefinition> loadAll() {
        if (!Files.isDirectory(skillsDir)) return List.of();

        var skills = new ArrayList<SkillDefinition>();
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory)
                .forEach(dir -> {
                    Path skillMd = dir.resolve("SKILL.md");
                    if (Files.exists(skillMd)) {
                        skills.add(parseSkillMd(skillMd));
                    }
                });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan skills directory", e);
        }
        return skills;
    }

    /**
     * Loads a single SKILL.md file.
     */
    public SkillDefinition load(Path skillMdPath) {
        return parseSkillMd(skillMdPath);
    }

    /**
     * 解析 SKILL.md：YAML frontmatter + Markdown body。
     *
     * 設計說明：
     * - 標準欄位直接提取（name, description, license, compatibility, allowed-tools, metadata）
     * - Claude Code 欄位直接提取（model, effort, context, agent, user-invocable 等）
     * - 舊格式 version/author → metadata 自動遷移 + WARN log
     * - 舊格式 triggers/executor → 忽略 + WARN log
     * - 未知欄位 → 靜默忽略（forward compatibility）
     *
     * @see <a href="https://agentskills.io/specification">Agent Skills Specification — Frontmatter</a>
     */
    @SuppressWarnings("unchecked")
    private SkillDefinition parseSkillMd(Path path) {
        try {
            String content = Files.readString(path);
            String[] parts = content.split("---", 3);
            if (parts.length < 3) {
                throw new IllegalArgumentException("Invalid SKILL.md: missing frontmatter in " + path);
            }

            Yaml yaml = new Yaml();
            Map<String, Object> fm = yaml.load(parts[1]);
            if (fm == null) fm = Map.of();
            String body = parts[2].strip();

            // --- Agent Skills 標準欄位 ---
            String name = getString(fm, "name");
            String description = getString(fm, "description");
            String license = getString(fm, "license");
            String compatibility = getString(fm, "compatibility");

            // allowed-tools: 空格分隔字串 → List<String>
            List<String> allowedTools = List.of();
            Object rawAllowedTools = fm.get("allowed-tools");
            if (rawAllowedTools instanceof String s && !s.isBlank()) {
                allowedTools = Arrays.asList(s.split("\\s+"));
            }

            // metadata: Map<String, Object> → Map<String, String>
            Map<String, String> metadata = new LinkedHashMap<>();
            Object rawMetadata = fm.get("metadata");
            if (rawMetadata instanceof Map<?, ?> m) {
                m.forEach((k, v) -> metadata.put(k.toString(), v != null ? v.toString() : ""));
            }

            // --- 向後相容：舊格式 version/author 自動遷移到 metadata ---
            if (fm.containsKey("version") && !metadata.containsKey("grimo.version")) {
                metadata.put("grimo.version", fm.get("version").toString());
                log.warn("SKILL.md '{}': 'version' is deprecated, use metadata.grimo.version instead", name);
            }
            if (fm.containsKey("author") && !metadata.containsKey("grimo.author")) {
                metadata.put("grimo.author", fm.get("author").toString());
                log.warn("SKILL.md '{}': 'author' is deprecated, use metadata.grimo.author instead", name);
            }
            if (fm.containsKey("triggers")) {
                log.warn("SKILL.md '{}': 'triggers' is deprecated and ignored", name);
            }
            if (fm.containsKey("executor")) {
                log.warn("SKILL.md '{}': 'executor' is deprecated and ignored", name);
            }

            // --- Claude Code 擴充欄位 ---
            String model = getString(fm, "model");
            String effort = getString(fm, "effort");
            String context = getString(fm, "context");
            String agent = getString(fm, "agent");
            Boolean userInvocable = getBoolean(fm, "user-invocable");
            Boolean disableModelInvocation = getBoolean(fm, "disable-model-invocation");
            String argumentHint = getString(fm, "argument-hint");

            List<String> paths = List.of();
            Object rawPaths = fm.get("paths");
            if (rawPaths instanceof List<?> list) {
                paths = list.stream().map(Object::toString).toList();
            }

            String shell = getString(fm, "shell");

            return new SkillDefinition(
                name, description, license, compatibility, allowedTools, metadata,
                model, effort, context, agent, userInvocable, disableModelInvocation, argumentHint, paths, shell,
                body
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read SKILL.md: " + path, e);
        }
    }

    private static String getString(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        return v != null ? v.toString() : null;
    }

    private static Boolean getBoolean(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.valueOf(s);
        return null;
    }
}
```

- [ ] **Step 4: Run SkillLoader tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.skill.loader.SkillLoaderTest" 2>&1 | tail -20`
Expected: All 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/skill/loader/SkillLoader.java \
        src/test/java/io/github/samzhu/grimo/skill/loader/SkillLoaderTest.java
git commit -m "feat: rewrite SkillLoader for Agent Skills standard + backward compat (F2)"
```

---

### Task 3: Update SkillCommands display

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java`
- Modify: `src/test/java/io/github/samzhu/grimo/skill/SkillCommandsTest.java`

- [ ] **Step 1: Write the failing tests**

Replace `src/test/java/io/github/samzhu/grimo/skill/SkillCommandsTest.java`:

```java
package io.github.samzhu.grimo.skill;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
        registry.register(new SkillDefinition(
            "multi-review", "Multi-agent code review", "MIT", null,
            List.of(), Map.of("grimo.version", "1.0.0", "grimo.author", "grimo-team"),
            null, null, null, null, null, null, null, List.of(), null,
            "# Multi Review"
        ));

        String output = commands.list();

        assertThat(output).contains("multi-review");
        assertThat(output).contains("1.0.0");
        assertThat(output).contains("grimo-team");
        assertThat(output).contains("loaded");
    }

    @Test
    void listShouldShowEmptyMessage() {
        String output = commands.list();
        assertThat(output).contains("No skills loaded");
    }

    @Test
    void listShouldHandleMissingVersionAndAuthor() {
        registry.register(new SkillDefinition(
            "minimal", "A minimal skill", null, null,
            List.of(), Map.of(),
            null, null, null, null, null, null, null, List.of(), null,
            "# Minimal"
        ));

        String output = commands.list();

        assertThat(output).contains("minimal");
        // version/author 欄位顯示空字串（不是 null）
        assertThat(output).doesNotContain("null");
    }

    @Test
    void removeShouldUnregisterSkill() {
        registry.register(new SkillDefinition(
            "test", "Test", null, null,
            List.of(), Map.of(),
            null, null, null, null, null, null, null, List.of(), null,
            "# Test"
        ));

        String output = commands.remove("test");

        assertThat(output).contains("removed");
        assertThat(registry.get("test")).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.skill.SkillCommandsTest" 2>&1 | tail -20`
Expected: FAIL — SkillCommands.list() still calls `s.version()` and `s.author()`

- [ ] **Step 3: Update SkillCommands.list() to use metadata**

In `src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java`, replace the `list()` method:

```java
    /**
     * 列出所有已載入的 skills。
     * kebab-case 扁平命令：/skill-list
     *
     * 設計說明：
     * - version/author 從 metadata 讀取（grimo.version, grimo.author）
     * - 對齊 Agent Skills 標準，自訂欄位放在 metadata 裡
     */
    @Command(name = "skill-list", description = "List all loaded skills")
    public String list() {
        var skills = registry.listAll();
        if (skills.isEmpty()) return "No skills loaded.";

        var sb = new StringBuilder();
        sb.append(String.format("  %-20s %-10s %-15s %-8s%n", "NAME", "VERSION", "AUTHOR", "STATUS"));
        for (SkillDefinition s : skills) {
            sb.append(String.format("  %-20s %-10s %-15s %-8s%n",
                s.name(), s.grimoVersion(), s.grimoAuthor(), "loaded"));
        }
        return sb.toString();
    }
```

Note: NAME column width changed from 15 to 20 to accommodate longer standard-format names like `multi-review`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.skill.SkillCommandsTest" 2>&1 | tail -20`
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java \
        src/test/java/io/github/samzhu/grimo/skill/SkillCommandsTest.java
git commit -m "feat: update skill-list display to read version/author from metadata (F2)"
```

---

### Task 4: Update SkillRegistry and GrimoCommandCompleter tests

**Files:**
- Modify: `src/test/java/io/github/samzhu/grimo/skill/registry/SkillRegistryTest.java`
- Modify: `src/test/java/io/github/samzhu/grimo/GrimoCommandCompleterTest.java`

- [ ] **Step 1: Update SkillRegistryTest**

Replace `src/test/java/io/github/samzhu/grimo/skill/registry/SkillRegistryTest.java`:

```java
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
        registry.register(sampleSkill("x", "old"));
        registry.register(sampleSkill("x", "new"));

        assertThat(registry.get("x").get().description()).isEqualTo("new");
    }

    private SkillDefinition sampleSkill(String name) {
        return sampleSkill(name, "Test skill");
    }

    private SkillDefinition sampleSkill(String name, String description) {
        return new SkillDefinition(
            name, description, null, null,
            List.of(), Map.of(),
            null, null, null, null, null, null, null, List.of(), null,
            "# Test"
        );
    }
}
```

- [ ] **Step 2: Update GrimoCommandCompleterTest**

Replace `src/test/java/io/github/samzhu/grimo/GrimoCommandCompleterTest.java`:

```java
package io.github.samzhu.grimo;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.shell.core.command.CommandRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrimoCommandCompleterTest {

    SkillRegistry skillRegistry;
    CommandRegistry commandRegistry;
    GrimoCommandCompleter completer;

    @BeforeEach
    void setUp() {
        skillRegistry = new SkillRegistry();
        commandRegistry = mock(CommandRegistry.class);
        when(commandRegistry.getCommandsByPrefix(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(List.of());
        completer = new GrimoCommandCompleter(commandRegistry, skillRegistry);
    }

    @Test
    void slashPrefixShouldProvideSkillCandidatesWithSlashInValue() {
        skillRegistry.register(new SkillDefinition(
            "healthcheck", "Check health", null, null,
            List.of(), Map.of("grimo.version", "1.0.0", "grimo.author", "grimo-builtin"),
            null, null, null, null, null, null, null, List.of(), null,
            "# HC"
        ));
        var candidates = new ArrayList<Candidate>();
        var line = mockParsedLine("/", List.of("/"));
        completer.complete(mock(LineReader.class), line, candidates);
        assertThat(candidates).anyMatch(c -> c.value().equals("/skill healthcheck"));
    }

    @Test
    void slashPrefixShouldReturnAllCandidatesForJLineFiltering() {
        skillRegistry.register(new SkillDefinition(
            "healthcheck", "Check health", null, null,
            List.of(), Map.of(),
            null, null, null, null, null, null, null, List.of(), null,
            "# HC"
        ));
        var candidates = new ArrayList<Candidate>();
        var line = mockParsedLine("/hea", List.of("/hea"));
        completer.complete(mock(LineReader.class), line, candidates);
        assertThat(candidates).anyMatch(c -> c.value().startsWith("/skill"));
    }

    @Test
    void nonSlashInputShouldDelegateToParent() {
        var candidates = new ArrayList<Candidate>();
        var line = mockParsedLine("stat", List.of("stat"));
        completer.complete(mock(LineReader.class), line, candidates);
    }

    private ParsedLine mockParsedLine(String line, List<String> words) {
        var parsed = mock(ParsedLine.class);
        when(parsed.line()).thenReturn(line);
        when(parsed.words()).thenReturn(words);
        when(parsed.word()).thenReturn(words.isEmpty() ? "" : words.getLast());
        when(parsed.wordIndex()).thenReturn(0);
        when(parsed.wordCursor()).thenReturn(line.length());
        return parsed;
    }
}
```

- [ ] **Step 3: Run all tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.skill.registry.SkillRegistryTest" --tests "io.github.samzhu.grimo.GrimoCommandCompleterTest" 2>&1 | tail -20`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add src/test/java/io/github/samzhu/grimo/skill/registry/SkillRegistryTest.java \
        src/test/java/io/github/samzhu/grimo/GrimoCommandCompleterTest.java
git commit -m "test: update SkillRegistry and CommandCompleter tests for new SkillDefinition (F2)"
```

---

### Task 5: Full build verification + glossary update

**Files:**
- Modify: `docs/glossary.md`

- [ ] **Step 1: Run full build to verify no remaining compilation errors**

Run: `./gradlew build 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL — all compilation passes, all tests pass

If there are remaining compilation errors (e.g. in `SkillCommands.install()` still using old `SkillLoader` API), fix them. The `install()` method creates a new `SkillLoader` instance — it should work since `SkillLoader.load()` returns the new SkillDefinition format. No code change expected here since `SkillLoader.load()` signature is unchanged (`load(Path) → SkillDefinition`).

- [ ] **Step 2: Update glossary.md**

In `docs/glossary.md`, update the Grimo Skill and Agent Skill entries in the 調度系統術語 section to reflect the standard alignment:

Replace the existing Grimo Skill row:

```
| **Grimo Skill** | Grimo Skill | 放在 `~/.grimo/skills/` 的 SKILL.md，定義 Grimo 的調度指令（派誰、怎麼分工）。不是給 CLI agent 的執行指令。 |
```

With:

```
| **Grimo Skill** | Grimo Skill | 放在 `~/.grimo/skills/` 的 SKILL.md，格式對齊 Agent Skills 開放標準（[agentskills.io](https://agentskills.io/specification)）。定義 Grimo 的調度指令（派誰、怎麼分工）。Grimo 擴充欄位放在 `metadata` map 裡（`grimo.tier`、`grimo.subagents`、`grimo.execution`）。第三方 Skill 直接安裝不會解析失敗。 |
```

- [ ] **Step 3: Update F2 spec status from Draft to Done**

In `docs/superpowers/specs/2026-03-27-f2-skill-standard-compatibility.md`, change:

```
> Status: Draft
```

To:

```
> Status: Done
```

- [ ] **Step 4: Commit**

```bash
git add docs/glossary.md \
        docs/superpowers/specs/2026-03-27-f2-skill-standard-compatibility.md
git commit -m "docs: update glossary and mark F2 spec as done"
```
