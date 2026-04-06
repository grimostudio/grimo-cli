# Config Lifecycle Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor GrimoConfig into a config bean with write-through persistence, remove unused features (tier-keywords, skill-overrides, agent-options), and add startup auto-fallback when default agent is not installed.

**Architecture:** GrimoConfig becomes a bean with in-memory fields loaded at construction time. Getters read fields directly (no file I/O). Setters update fields + write file (write-through). TierRouter simplified to only support sessionTier. Startup detects unavailable default agent and auto-switches.

**Tech Stack:** Java 25, Spring Boot 4.0.x, SnakeYAML, JUnit 5 + AssertJ

**Spec:** `docs/superpowers/specs/2026-04-06-config-lifecycle-design.md`

---

### File Structure

**Delete files:**

| File | Reason |
|------|--------|
| `src/main/java/io/github/samzhu/grimo/agent/tier/TierKeywordDetector.java` | Feature removed |
| `src/test/java/io/github/samzhu/grimo/agent/tier/TierKeywordDetectorTest.java` | Feature removed |

**Modified files:**

| File | Change |
|------|--------|
| `config/GrimoConfig.java` | Rewrite: bean fields + constructor loads/creates file + write-through setters; remove tierKeywords/skillOverrides/agentOption/tierModels methods |
| `home/GrimoHome.java` | Remove DEFAULT_CONFIG template; initialize() only creates directories |
| `GrimoStartupRunner.java` | Add auto-fallback after agent detection; store fallbackMessage |
| `TuiAdapter.java` | Display fallbackMessage on startup |
| `agent/AgentCommands.java` | Remove setAgentOption; remove getAgentOption; use grimoProperties.getDefaults() for model fallback |
| `agent/tier/TierRouter.java` | Simplify resolve(): remove keyword/skill-override branches; simplify Context to only sessionTier; walkFallbackList() only reads grimoProperties; resolveDefault() remove getAgentOption; remove fallbackToConversationDefault() |
| `agent/tier/TierCommands.java` | Remove /skill-tier command |
| `agent/tier/TierConfiguration.java` | Remove tierKeywordDetector bean |
| `ChatDispatcher.java` | Remove TierKeywordDetector dependency; dispatchTo() use config.getDefaultModel() instead of getAgentOption() |
| `agent/AgentConfiguration.java` | Replace `config.getAgentOption()` with `config.getDefaultModel()` (3 places: claude, gemini, codex agent specs) |
| `agent/DevModeRunner.java` | Remove `TierKeywordDetector` dependency |

**Test files to modify:**

| File | Change |
|------|--------|
| `src/test/java/io/github/samzhu/grimo/config/GrimoConfigTest.java` | Rewrite for bean pattern; remove tierKeywords/skillOverrides/tierModels tests |
| `src/test/java/io/github/samzhu/grimo/agent/tier/TierRouterTest.java` | Remove keyword/skill-override tests; update for simplified resolve() |
| `src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java` | Update for removed agent-options |
| `src/test/java/io/github/samzhu/grimo/agent/tier/TierCommandsTest.java` | Remove /skill-tier tests |
| `src/test/java/io/github/samzhu/grimo/agent/tier/TierIntegrationTest.java` | Remove keyword-related tests |

---

### Task 1: Rewrite GrimoConfig as config bean

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/config/GrimoConfig.java`
- Modify: `src/test/java/io/github/samzhu/grimo/config/GrimoConfigTest.java`

- [ ] **Step 1: Rewrite GrimoConfigTest for bean pattern**

Replace the entire test file. Key test cases:
- `constructorShouldCreateDefaultFileWhenMissing` — new GrimoConfig on non-existent path → file created with defaults
- `constructorShouldLoadExistingFile` — write yaml first, then construct → fields populated
- `getDefaultAgentShouldReadField` — construct with yaml → getDefaultAgent() returns value
- `setDefaultAgentShouldWriteThrough` — set → verify field changed + file updated
- `getMcpServersShouldReadField` — test MCP section loading
- `setMcpServerShouldWriteThrough` — add MCP → verify field + file
- `removeMcpServerShouldWriteThrough` — remove MCP → verify field + file
- `getSandboxModeShouldDefaultToLocal` — no sandbox in yaml → returns "local"
- Remove all tests for: `load()`, `getTierModels()`, `getSkillOverrides()`, `setSkillOverride()`, `getTierKeywords()`, `getAgentOption()`, `setAgentOption()`

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.config.GrimoConfigTest" -x nativeTest`
Expected: FAIL — constructor doesn't create default file yet

- [ ] **Step 3: Rewrite GrimoConfig implementation**

```java
package io.github.samzhu.grimo.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Config bean：啟動時讀檔填入欄位，getter 讀欄位，setter write-through。
 *
 * 設計說明：
 * - 建構子負責建立預設 config.yaml（若不存在）並載入到 bean 欄位
 * - GrimoHome.initialize() 只建目錄結構，不處理 config 內容
 * - getter 直接讀 bean 欄位（不讀檔）
 * - setter 更新 bean 欄位 + 寫檔（write-through）
 * - 所有 getter/setter 保持 synchronized（Virtual Thread 併發保護）
 * - event publish 由呼叫端負責（AgentCommands、McpCommands）
 */
public class GrimoConfig {

    private static final String DEFAULT_CONFIG = """
            agents:
              default: claude
              model: claude-sonnet-4-6
            sandbox:
              mode: local
            """;

    private final Path configFile;

    // --- bean fields ---
    private String defaultAgent;
    private String defaultModel;
    private Map<String, Map<String, Object>> mcpServers;
    private String sandboxMode;

    public GrimoConfig(Path configFile) {
        this.configFile = configFile;
        if (!Files.exists(configFile)) {
            createDefaultConfig();
        }
        loadFromFile();
    }

    // --- getters (read fields) ---

    public synchronized String getDefaultAgent() { return defaultAgent; }
    public synchronized String getDefaultModel() { return defaultModel; }
    public synchronized Map<String, Map<String, Object>> getMcpServers() {
        return mcpServers != null ? mcpServers : Map.of();
    }
    public synchronized String getSandboxMode() {
        return sandboxMode != null ? sandboxMode : "local";
    }

    // --- setters (write-through) ---

    public synchronized void setDefaultAgent(String agentId) {
        this.defaultAgent = agentId;
        save();
    }

    public synchronized void setDefaultModel(String model) {
        this.defaultModel = model;
        save();
    }

    @SuppressWarnings("unchecked")
    public synchronized void setMcpServer(String name, Map<String, Object> serverDef) {
        if (this.mcpServers == null) {
            this.mcpServers = new LinkedHashMap<>();
        }
        this.mcpServers.put(name, new LinkedHashMap<>(serverDef));
        save();
    }

    public synchronized boolean removeMcpServer(String name) {
        if (this.mcpServers == null || !this.mcpServers.containsKey(name)) {
            return false;
        }
        this.mcpServers.remove(name);
        save();
        return true;
    }

    // --- persistence ---

    private void createDefaultConfig() {
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, DEFAULT_CONFIG);
            Files.setPosixFilePermissions(configFile,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException e) {
            // Config creation failure should not block startup
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized void loadFromFile() {
        try (var reader = Files.newBufferedReader(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(reader);
            if (data == null) data = new LinkedHashMap<>();

            var agents = (Map<String, Object>) data.get("agents");
            if (agents != null) {
                this.defaultAgent = agents.get("default") != null ? agents.get("default").toString() : null;
                this.defaultModel = agents.get("model") != null ? agents.get("model").toString() : null;
            }

            var mcp = (Map<String, Map<String, Object>>) data.get("mcp");
            this.mcpServers = mcp != null ? new LinkedHashMap<>(mcp) : null;

            var sandbox = (Map<String, Object>) data.get("sandbox");
            if (sandbox != null) {
                this.sandboxMode = sandbox.get("mode") != null ? sandbox.get("mode").toString() : "local";
            } else {
                this.sandboxMode = "local";
            }
        } catch (IOException e) {
            // Fallback to defaults
            this.defaultAgent = "claude";
            this.defaultModel = "claude-sonnet-4-6";
            this.sandboxMode = "local";
        }
    }

    private synchronized void save() {
        var data = new LinkedHashMap<String, Object>();

        // agents
        var agents = new LinkedHashMap<String, Object>();
        if (defaultAgent != null) agents.put("default", defaultAgent);
        if (defaultModel != null) agents.put("model", defaultModel);
        if (!agents.isEmpty()) data.put("agents", agents);

        // sandbox
        var sandbox = new LinkedHashMap<String, Object>();
        sandbox.put("mode", sandboxMode != null ? sandboxMode : "local");
        data.put("sandbox", sandbox);

        // mcp (only if non-empty)
        if (mcpServers != null && !mcpServers.isEmpty()) {
            data.put("mcp", mcpServers);
        }

        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);

        try {
            Files.writeString(configFile, yaml.dump(data));
            Files.setPosixFilePermissions(configFile,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write config: " + configFile, e);
        }
    }
}
```

- [ ] **Step 4: Run GrimoConfigTest only (full build will fail until Tasks 2-4 remove callers of deleted methods)**

Run: `./gradlew test --tests "io.github.samzhu.grimo.config.GrimoConfigTest" -x nativeTest`
Expected: GrimoConfigTest PASS. Note: full `./gradlew test` will have compilation errors in other files that still reference removed methods — these are fixed in Tasks 2-4.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/config/GrimoConfig.java \
        src/test/java/io/github/samzhu/grimo/config/GrimoConfigTest.java
git commit -m "refactor(config): rewrite GrimoConfig as bean with write-through persistence"
```

---

### Task 2: Remove TierKeywordDetector and simplify TierConfiguration

**Files:**
- Delete: `src/main/java/io/github/samzhu/grimo/agent/tier/TierKeywordDetector.java`
- Delete: `src/test/java/io/github/samzhu/grimo/agent/tier/TierKeywordDetectorTest.java`
- Modify: `src/main/java/io/github/samzhu/grimo/agent/tier/TierConfiguration.java`
- Modify: `src/main/java/io/github/samzhu/grimo/ChatDispatcher.java`

- [ ] **Step 1: Delete TierKeywordDetector and its test**

```bash
rm src/main/java/io/github/samzhu/grimo/agent/tier/TierKeywordDetector.java
rm src/test/java/io/github/samzhu/grimo/agent/tier/TierKeywordDetectorTest.java
```

- [ ] **Step 2: Remove tierKeywordDetector bean from TierConfiguration**

In `TierConfiguration.java`, delete lines 27-30 (the `@Bean tierKeywordDetector` method). Remove the `GrimoConfig` import if no longer used.

- [ ] **Step 3: Remove TierKeywordDetector from ChatDispatcher and DevModeRunner**

In `DevModeRunner.java`:
- Remove `TierKeywordDetector` import
- Remove `tierKeywordDetector` field and constructor parameter
- Remove any usage of `tierKeywordDetector` in the class

In `ChatDispatcher.java`:
- Remove `TierKeywordDetector` import
- Remove `tierKeywordDetector` field
- Remove `tierKeywordDetector` from constructor parameter
- In `resolveTier()`, remove `var keywordTier = tierKeywordDetector.detect(userInput).orElse(null);` and remove `.keywordTier(keywordTier)` from Context builder

After change, `resolveTier()` becomes:
```java
private TierSelection resolveTier(String userInput) {
    var tierCtx = TierRouter.Context.builder()
            .sessionTier(sessionTier.get())
            .build();
    var tierSelection = tierRouter.resolve(tierCtx);
    // ... validation and logging unchanged
    return tierSelection;
}
```

- [ ] **Step 4: Run full tests**

Run: `./gradlew test -x nativeTest`
Expected: Compilation errors in tests that reference keyword-related code

- [ ] **Step 5: Fix test compilation — remove keyword-related test code**

In `TierIntegrationTest.java` — remove any test that references `TierKeywordDetector` or keyword functionality.
In `TierRouterTest.java` — remove tests that set `keywordTier` on Context.

- [ ] **Step 6: Run tests again**

Run: `./gradlew test -x nativeTest`
Expected: PASS (except pre-existing TierOptionsFactoryTest failure)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(tier): remove TierKeywordDetector and simplify TierConfiguration"
```

---

### Task 3: Simplify TierRouter — remove skill-overrides, simplify Context

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/tier/TierRouter.java`
- Modify: `src/test/java/io/github/samzhu/grimo/agent/tier/TierRouterTest.java`

- [ ] **Step 1: Simplify TierRouter.resolve()**

Replace the current `resolve()` with the simplified version from spec:

```java
public TierSelection resolve(Context ctx) {
    Tier tier;
    String source;

    if (ctx.sessionTier != null) {
        tier = ctx.sessionTier;
        source = "session";
    } else {
        String explicitDefault = config.getDefaultAgent();
        if (explicitDefault != null) {
            var agent = registry.get(explicitDefault);
            if (agent != null && agent.isAvailable()) {
                String model = config.getDefaultModel();
                if (model == null) model = grimoProperties.getDefaults()
                        .getOrDefault(explicitDefault, "unknown");
                return new TierSelection(explicitDefault, model, Tier.STD, "user-default");
            }
            throw new IllegalStateException(
                "%s is not available. Run '/agent-use' to switch agent.".formatted(explicitDefault));
        }
        tier = Tier.STD;
        source = "default";
    }

    log.debug("Tier resolved: {} (source: {})", tier, source);
    return walkFallbackList(tier, source);
}
```

- [ ] **Step 2: Simplify resolveDefault()**

Remove `getAgentOption()` call:

```java
public TierSelection resolveDefault() {
    String explicitDefault = config.getDefaultAgent();
    if (explicitDefault != null) {
        var agent = registry.get(explicitDefault);
        if (agent != null && agent.isAvailable()) {
            String model = config.getDefaultModel();
            if (model == null) model = grimoProperties.getDefaults()
                    .getOrDefault(explicitDefault, "unknown");
            return new TierSelection(explicitDefault, model, Tier.STD, "user-default");
        }
        throw new IllegalStateException(
            "%s is not available. Run '/agent-use' to switch agent.".formatted(explicitDefault));
    }
    return resolve(Context.builder().build());
}
```

- [ ] **Step 3: Simplify walkFallbackList() — only read grimoProperties**

Remove `config.getTierModels()` call. The method becomes:

```java
private TierSelection walkFallbackList(Tier tier, String source) {
    var builtIn = grimoProperties.getTierModels();
    var entries = builtIn.get(tier.value());

    if (entries != null && !entries.isEmpty()) {
        for (var entry : entries) {
            String agentId = entry.agent();
            String model = entry.model();
            var agent = registry.get(agentId);
            if (agent != null && agent.isAvailable()) {
                log.info("Tier routing: {} → {} / {} (source: {})", tier, agentId, model, source);
                return new TierSelection(agentId, model, tier, source);
            }
            log.debug("Tier fallback: {} / {} not available, trying next", agentId, model);
        }
    }

    // No agent available for this tier
    var available = registry.listAvailable();
    if (available.isEmpty()) {
        throw new IllegalStateException("No agents available. Install a CLI agent (claude, gemini, or codex).");
    }
    var first = available.entrySet().iterator().next();
    return new TierSelection(first.getKey(), "unknown", tier, "fallback-first-available");
}
```

- [ ] **Step 4: Remove fallbackToConversationDefault() method entirely**

It's no longer needed — the logic is handled in `resolve()` before reaching `walkFallbackList()`.

- [ ] **Step 5: Simplify Context — only sessionTier**

```java
public static class Context {
    @Nullable final Tier sessionTier;

    private Context(Builder builder) {
        this.sessionTier = builder.sessionTier;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Tier sessionTier;
        public Builder sessionTier(Tier tier) { this.sessionTier = tier; return this; }
        public Context build() { return new Context(this); }
    }
}
```

- [ ] **Step 6: Remove GrimoConfig dependency from TierRouter constructor**

After removing `config.getTierModels()`, `config.getSkillOverrides()`, `config.getTierKeywords()`, check if `config` is still used. It IS still used by `resolve()` for `config.getDefaultAgent()` and `config.getDefaultModel()`. Keep the dependency.

- [ ] **Step 7: Update TierRouterTest**

Remove tests that reference `keywordTier`, `skillName`, `skillTier`, `config.getTierModels()`. Update remaining tests to use simplified Context (only `sessionTier`).

- [ ] **Step 8: Run tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.tier.TierRouterTest" -x nativeTest`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/tier/TierRouter.java \
        src/test/java/io/github/samzhu/grimo/agent/tier/TierRouterTest.java
git commit -m "refactor(tier): simplify TierRouter — remove skill-overrides, keyword, simplify Context"
```

---

### Task 4: Simplify AgentCommands and TierCommands

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java`
- Modify: `src/main/java/io/github/samzhu/grimo/agent/tier/TierCommands.java`
- Modify: `src/main/java/io/github/samzhu/grimo/ChatDispatcher.java`
- Modify: `src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java`
- Modify: `src/test/java/io/github/samzhu/grimo/agent/tier/TierCommandsTest.java`

- [ ] **Step 1: Simplify AgentCommands.use()**

Remove all `config.getAgentOption()` / `config.setAgentOption()` calls.

```java
@Command(name = "agent-use", description = "Switch agent (auto-picks model)")
public String use(String rawArgs) {
    if (rawArgs == null || rawArgs.isBlank()) {
        return "Usage: /agent-use <agent> [model]\nExample: /agent-use claude opus";
    }
    String[] parts = rawArgs.trim().split("\\s+", 2);
    String agentId = parts[0];
    String modelHint = parts.length > 1 ? parts[1] : null;

    if (registry.get(agentId) == null) {
        return "Agent not found: " + agentId + ". Run '/agent-list' to see available agents.";
    }

    // model: user hint or built-in default
    String model;
    if (modelHint != null && !modelHint.isBlank()) {
        model = modelHint;
    } else {
        model = grimoProperties.getDefaults().getOrDefault(agentId, "unknown");
    }

    config.setDefaultAgent(agentId);
    config.setDefaultModel(model);
    eventPublisher.publishEvent(new AgentSwitchedEvent(agentId, model));

    return "Switched to " + agentId + " · " + model;
}
```

Also simplify `list()` — replace `config.getAgentOption(id, "model")` with `grimoProperties.getDefaults().getOrDefault(id, "")`.

- [ ] **Step 2: Remove /skill-tier from TierCommands**

Delete the entire `skillTier()` method (lines 57-78). Remove the `GrimoConfig` constructor dependency if it's only used by `skillTier()`. Check if `/tier` still needs `config` — it doesn't (only uses `sessionTier`).

After cleanup, TierCommands constructor only needs `AtomicReference<Tier> sessionTier`.

- [ ] **Step 3: Update AgentConfiguration — replace getAgentOption() with getDefaultModel()**

In `src/main/java/io/github/samzhu/grimo/agent/AgentConfiguration.java`, replace all 3 occurrences of `config.getAgentOption("<agent>", "model")` (lines 51, 70, 95) with `config.getDefaultModel()`:

```java
// Before:
String model = config.getAgentOption("claude", "model");
if (model == null) model = grimoProperties.getDefaults().getOrDefault("claude", "claude-sonnet-4-6");

// After:
String model = config.getDefaultModel();
if (model == null) model = grimoProperties.getDefaults().getOrDefault("claude", "claude-sonnet-4-6");
```

Same pattern for gemini (line 70) and codex (line 95).

- [ ] **Step 4: Update ChatDispatcher.dispatchTo()**

Replace `grimoConfig.getAgentOption(agentId, "model")` with `grimoConfig.getDefaultModel()`:

```java
var configModel = grimoConfig.getDefaultModel();
```

- [ ] **Step 5: Update test files**

- `AgentCommandsTest.java` — remove references to `getAgentOption`/`setAgentOption`
- `TierCommandsTest.java` — remove `/skill-tier` tests

- [ ] **Step 6: Run tests**

Run: `./gradlew test -x nativeTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java \
        src/main/java/io/github/samzhu/grimo/agent/tier/TierCommands.java \
        src/main/java/io/github/samzhu/grimo/ChatDispatcher.java \
        src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java \
        src/test/java/io/github/samzhu/grimo/agent/tier/TierCommandsTest.java
git commit -m "refactor: simplify AgentCommands and TierCommands — remove agent-options and skill-tier"
```

---

### Task 5: Update GrimoHome — remove DEFAULT_CONFIG template

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/home/GrimoHome.java`

- [ ] **Step 1: Remove DEFAULT_CONFIG and config file creation from GrimoHome**

In `GrimoHome.java`:
- Delete the `DEFAULT_CONFIG` string constant (lines 64-131)
- In `initialize()`, remove the config file creation logic (the block that checks `!Files.exists(config)` and writes `DEFAULT_CONFIG`)
- Keep the directory creation logic (`tasksDir`, `skillsDir`, `agentsDir`, `logsDir`, `projectsDir`)
- `configFile()` method stays — it still returns the path, used by GrimoConfig

- [ ] **Step 2: Run tests**

Run: `./gradlew test -x nativeTest`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/home/GrimoHome.java
git commit -m "refactor(home): remove DEFAULT_CONFIG template — GrimoConfig handles its own defaults"
```

---

### Task 6: Add startup auto-fallback in GrimoStartupRunner

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`
- Modify: `src/main/java/io/github/samzhu/grimo/TuiAdapter.java`

- [ ] **Step 1: Add fallbackMessage field and auto-fallback logic**

In `GrimoStartupRunner`, add a bean-scoped field to hold the fallback message, or use a simple `AtomicReference<String>` bean.

Add `@Bean` for fallback message:
```java
@Bean
public java.util.concurrent.atomic.AtomicReference<String> startupFallbackMessage() {
    return new java.util.concurrent.atomic.AtomicReference<>(null);
}
```

In `startupInitRunner()`, after agent detection (Step 2, around line 224), add:

```java
// Step 2.5: Check if default agent is installed, auto-fallback if not
String defaultAgent = grimoConfig.getDefaultAgent();
if (defaultAgent != null) {
    var agentModel = agentModelRegistry.get(defaultAgent);
    if (agentModel == null || !agentModel.isAvailable()) {
        // Walk tier-models.std from built-in defaults to find first available
        var stdEntries = grimoProperties.getTierModels().get("std");
        String newAgent = null;
        String newModel = null;
        if (stdEntries != null) {
            for (var entry : stdEntries) {
                var candidate = agentModelRegistry.get(entry.agent());
                if (candidate != null && candidate.isAvailable()) {
                    newAgent = entry.agent();
                    newModel = entry.model();
                    break;
                }
            }
        }
        if (newAgent != null) {
            grimoConfig.setDefaultAgent(newAgent);
            grimoConfig.setDefaultModel(newModel);
            String msg = "⚠ " + defaultAgent + " not installed, using " + newAgent + " · " + newModel;
            log.warn(msg);
            startupFallbackMessage.set(msg);
        } else {
            log.error("No agents available. Install a CLI agent (claude, gemini, or codex).");
        }
    }
}
```

Inject `startupFallbackMessage`, `grimoConfig`, `grimoProperties` into the `startupInitRunner` method params.

- [ ] **Step 2: Display fallbackMessage in TuiAdapter**

In `TuiAdapter.java`:
- Inject `AtomicReference<String> startupFallbackMessage` via constructor
- In `run()`, after contentView is constructed and banner is set, check fallback:

```java
// Display startup fallback message if agent was auto-switched
String fallbackMsg = startupFallbackMessage.get();
if (fallbackMsg != null) {
    contentView.appendLine(new org.jline.utils.AttributedString(fallbackMsg,
            org.jline.utils.AttributedStyle.DEFAULT.foreground(3))); // yellow
}
```

- [ ] **Step 3: Run full tests**

Run: `./gradlew test -x nativeTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java \
        src/main/java/io/github/samzhu/grimo/TuiAdapter.java
git commit -m "feat(config): add startup auto-fallback when default agent is not installed"
```

---

### Task 7: Clean up remaining references and integration tests

**Files:**
- Modify: `src/test/java/io/github/samzhu/grimo/agent/tier/TierIntegrationTest.java`
- Modify: `src/main/java/io/github/samzhu/grimo/agent/DevModeRunner.java` (if uses getAgentOption)
- Modify: `docs/glossary.md`

- [ ] **Step 1: Fix TierIntegrationTest**

Remove any references to `TierKeywordDetector`, `keywordTier`, `skillName`, `skillTier`, `config.getTierModels()`, `config.getSkillOverrides()`.

- [ ] **Step 2: Update glossary**

In `docs/glossary.md`:
- Update GrimoConfig entry to reflect bean pattern
- Remove TierKeywordDetector entry
- Remove skill-overrides references
- Remove agent-options references

- [ ] **Step 3: Run full test suite**

Run: `./gradlew test -x nativeTest`
Expected: ALL PASS (except pre-existing TierOptionsFactoryTest)

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: clean up remaining tier-keyword, skill-override, agent-option references"
```

---

### Task 8: Integration smoke test

- [ ] **Step 1: Build**

Run: `./gradlew build -x nativeCompile -x nativeTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify config.yaml defaults**

Delete `~/.grimo/config.yaml` (backup first), run `./gradlew bootRun`, verify new config.yaml contains:
```yaml
agents:
  default: claude
  model: claude-sonnet-4-6
sandbox:
  mode: local
```

- [ ] **Step 3: Verify auto-fallback**

If claude is not installed: TUI should show `⚠ claude not installed, using gemini · gemini-2.5-flash` and config.yaml should be updated.

- [ ] **Step 4: Verify /agent-use**

Type `/agent-use gemini flash` → status bar should update → config.yaml should update

- [ ] **Step 5: Verify /session-info still works**

Type `/session-info` → should show current session info with correct agent
