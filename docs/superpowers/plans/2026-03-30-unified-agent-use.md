# Unified `/agent-use` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/agent-use claude` auto-picks the recommended model, remembers per-agent model overrides, and replaces the separate `/agent-model` command.

**Architecture:** Add `setAgentOption()` to GrimoConfig for per-agent model persistence. Rewrite `AgentCommands` with a recommended model map and smart alias matching. Update `AgentConfiguration` default model strings to match current CLI defaults.

**Tech Stack:** Java 25, Spring Shell `@Command`, SnakeYAML (GrimoConfig), JUnit 5 + AssertJ

**Spec:** `docs/superpowers/specs/2026-03-30-unified-agent-use.md`

**References:**
- Claude Code default: `claude-sonnet-4-6` — https://code.claude.com/docs/en/model-config
- Gemini CLI default: `gemini-2.5-pro` — https://github.com/google-gemini/gemini-cli
- Codex CLI default: `o4-mini` — https://github.com/openai/codex

---

## File Structure

| Action | File | Change |
|--------|------|--------|
| Modify | `src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java` | Add `setAgentOption()` |
| Modify | `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java` | Rewrite `/agent-use`, add recommended models + smart match, delete `/agent-model` |
| Modify | `src/main/java/io/github/samzhu/grimo/agent/AgentConfiguration.java` | Update default model strings |
| Create | `src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java` | Unit tests for new `/agent-use` logic |

---

### Task 1: Add GrimoConfig.setAgentOption + update default models

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java:78-87`
- Modify: `src/main/java/io/github/samzhu/grimo/agent/AgentConfiguration.java:51,70,95`

- [ ] **Step 1: Add setAgentOption to GrimoConfig**

Add after `getAgentOption()` method (line 87) in `GrimoConfig.java`:

```java
/**
 * 設定指定 agent 的選項值（寫入 agent-options.<agentId>.<key>）。
 * 用於 per-agent 模型記憶：使用者切換模型後，下次切回同一 agent 自動恢復。
 */
@SuppressWarnings("unchecked")
public synchronized void setAgentOption(String agentId, String key, String value) {
    var data = load();
    var agentOptions = (Map<String, Object>) data.computeIfAbsent("agent-options", k -> new LinkedHashMap<>());
    var agentSection = (Map<String, Object>) agentOptions.computeIfAbsent(agentId, k -> new LinkedHashMap<>());
    agentSection.put(key, value);
    save(data);
}
```

- [ ] **Step 2: Update AgentConfiguration default models**

In `AgentConfiguration.java`, change three default model strings:

Line 51: `if (model == null) model = "claude-sonnet-4-5";` → `if (model == null) model = "claude-sonnet-4-6";`

Line 70: `if (model == null) model = "gemini-2.5-flash";` → `if (model == null) model = "gemini-2.5-pro";`

Line 95 stays: `if (model == null) model = "o4-mini";` (already correct)

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java \
       src/main/java/io/github/samzhu/grimo/agent/AgentConfiguration.java
git commit -m "feat: add GrimoConfig.setAgentOption + update default models to latest"
```

---

### Task 2: Rewrite AgentCommands with unified /agent-use

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java`
- Create: `src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java`

- [ ] **Step 1: Write failing tests**

Create `src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java`:

```java
package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agents.model.AgentModel;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCommandsTest {

    @TempDir Path tempDir;
    private GrimoConfig config;
    private AgentModelRegistry registry;
    private AgentCommands commands;

    @BeforeEach
    void setUp() throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "agents:\n  default: claude\n");
        config = new GrimoConfig(configFile);
        registry = new AgentModelRegistry();

        // Register mock agents
        var claudeModel = mock(AgentModel.class);
        when(claudeModel.isAvailable()).thenReturn(true);
        registry.register("claude", claudeModel);

        var geminiModel = mock(AgentModel.class);
        when(geminiModel.isAvailable()).thenReturn(true);
        registry.register("gemini", geminiModel);

        var codexModel = mock(AgentModel.class);
        when(codexModel.isAvailable()).thenReturn(true);
        registry.register("codex", codexModel);

        commands = new AgentCommands(registry, config);
    }

    @Test
    void useShouldSetRecommendedModelWhenNoModelSpecified() {
        String result = commands.use("claude");
        assertThat(result).contains("claude").contains("claude-sonnet-4-6");
        assertThat(config.getDefaultAgent()).isEqualTo("claude");
        assertThat(config.getDefaultModel()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void useShouldSetRecommendedModelForGemini() {
        String result = commands.use("gemini");
        assertThat(result).contains("gemini").contains("gemini-2.5-pro");
        assertThat(config.getDefaultModel()).isEqualTo("gemini-2.5-pro");
    }

    @Test
    void useShouldSetRecommendedModelForCodex() {
        String result = commands.use("codex");
        assertThat(result).contains("codex").contains("o4-mini");
        assertThat(config.getDefaultModel()).isEqualTo("o4-mini");
    }

    @Test
    void useShouldSmartMatchAlias() {
        String result = commands.use("claude opus");
        assertThat(result).contains("claude-opus-4-6");
        assertThat(config.getDefaultModel()).isEqualTo("claude-opus-4-6");
        // 記憶寫入 agent-options
        assertThat(config.getAgentOption("claude", "model")).isEqualTo("claude-opus-4-6");
    }

    @Test
    void useShouldSmartMatchGeminiFlash() {
        String result = commands.use("gemini flash");
        assertThat(result).contains("gemini-2.5-flash");
        assertThat(config.getAgentOption("gemini", "model")).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void useShouldRememberModelAcrossSwitches() {
        // 設定 claude 用 opus
        commands.use("claude opus");
        assertThat(config.getDefaultModel()).isEqualTo("claude-opus-4-6");

        // 切到 gemini
        commands.use("gemini");
        assertThat(config.getDefaultModel()).isEqualTo("gemini-2.5-pro");

        // 切回 claude — 應該記住 opus
        commands.use("claude");
        assertThat(config.getDefaultModel()).isEqualTo("claude-opus-4-6");
    }

    @Test
    void useShouldAcceptFullModelId() {
        String result = commands.use("claude claude-opus-4-6");
        assertThat(result).contains("claude-opus-4-6");
        assertThat(config.getDefaultModel()).isEqualTo("claude-opus-4-6");
    }

    @Test
    void useShouldRejectUnknownAgent() {
        String result = commands.use("unknown");
        assertThat(result).contains("not found");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.AgentCommandsTest" 2>&1 | tail -10`
Expected: FAIL — current `use()` method signature doesn't match

- [ ] **Step 3: Rewrite AgentCommands.java**

Replace the entire file `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java`:

```java
package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Agent 管理指令：統一的 /agent-use 處理 agent + model 切換。
 *
 * 設計說明：
 * - 合併原本的 /agent-use 和 /agent-model 為單一指令
 * - 懶人原則：只指定 agent → 自動帶推薦模型或記憶的模型
 * - Per-agent 模型記憶：切到 opus 後換 gemini 再換回 claude，記住 opus
 * - 智慧匹配：簡寫 "opus" → "claude-opus-4-6"
 *
 * @see <a href="https://code.claude.com/docs/en/model-config">Claude Code Model Config</a>
 * @see <a href="https://github.com/google-gemini/gemini-cli">Gemini CLI</a>
 * @see <a href="https://github.com/openai/codex">Codex CLI</a>
 */
@Component
public class AgentCommands {

    private final AgentModelRegistry registry;
    private final GrimoConfig config;

    /**
     * 各 CLI agent 的推薦預設模型（對齊各 CLI 官方預設）。
     */
    static final Map<String, String> RECOMMENDED_MODELS = Map.of(
            "claude", "claude-sonnet-4-6",
            "gemini", "gemini-2.5-pro",
            "codex", "o4-mini"
    );

    /**
     * 簡寫 → 完整模型 ID 對應表。
     * key 格式：agentId + ":" + alias
     */
    static final Map<String, String> MODEL_ALIASES = Map.ofEntries(
            Map.entry("claude:opus", "claude-opus-4-6"),
            Map.entry("claude:sonnet", "claude-sonnet-4-6"),
            Map.entry("claude:haiku", "claude-haiku-4-5"),
            Map.entry("gemini:pro", "gemini-2.5-pro"),
            Map.entry("gemini:flash", "gemini-2.5-flash"),
            Map.entry("codex:o4-mini", "o4-mini"),
            Map.entry("codex:o3", "o3")
    );

    public AgentCommands(AgentModelRegistry registry, GrimoConfig config) {
        this.registry = registry;
        this.config = config;
    }

    @Command(name = "agent-list", description = "List all configured agents")
    public String list() {
        var models = registry.listAll();
        if (models.isEmpty()) {
            return "No agents available. Install a CLI agent (claude, gemini, or codex).";
        }

        var sb = new StringBuilder();
        sb.append(String.format("  %-15s %-15s%n", "ID", "STATUS"));
        for (var entry : models.entrySet()) {
            String status = entry.getValue().isAvailable() ? "ready" : "not available";
            sb.append(String.format("  %-15s %-15s%n", entry.getKey(), status));
        }
        return sb.toString();
    }

    /**
     * 統一切換 agent + model。
     *
     * 用法：
     *   /agent-use claude          → claude + 記憶模型 or 推薦預設
     *   /agent-use claude opus     → claude + claude-opus-4-6（智慧匹配 + 存記憶）
     *   /agent-use gemini flash    → gemini + gemini-2.5-flash
     *
     * @param input agent ID，可選空格後接 model hint
     */
    @Command(name = "agent-use", description = "Switch agent (auto-picks model)")
    public String use(String input) {
        String[] parts = input.trim().split("\\s+", 2);
        String agentId = parts[0];
        String modelHint = parts.length > 1 ? parts[1] : null;

        // 驗證 agent 存在
        if (registry.get(agentId) == null) {
            return "Agent not found: " + agentId + ". Run '/agent-list' to see available agents.";
        }

        // 解析 model
        String model;
        if (modelHint != null) {
            model = resolveModel(agentId, modelHint);
            config.setAgentOption(agentId, "model", model);
        } else {
            // 讀記憶，沒有就用推薦預設
            model = config.getAgentOption(agentId, "model");
            if (model == null) {
                model = RECOMMENDED_MODELS.getOrDefault(agentId, "unknown");
            }
        }

        config.setDefaultAgent(agentId);
        config.setDefaultModel(model);

        return "Switched to " + agentId + " \u00b7 " + model;
    }

    /**
     * 智慧匹配：簡寫 alias → 完整模型 ID。
     * 先查 alias 表，不匹配則直接當完整 model ID。
     */
    private String resolveModel(String agentId, String hint) {
        String aliasKey = agentId + ":" + hint.toLowerCase();
        return MODEL_ALIASES.getOrDefault(aliasKey, hint);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.AgentCommandsTest" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL — all 8 tests pass

- [ ] **Step 5: Compile full project**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java \
       src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java
git commit -m "feat: unified /agent-use with recommended models, smart match, and per-agent memory"
```

---

### Task 3: Manual Verification

- [ ] **Step 1: Build and run**

```bash
./run.sh
```

- [ ] **Step 2: Test default model**

```
/agent-use claude
```

Expected output: `Switched to claude · claude-sonnet-4-6`
Status bar: `claude · claude-sonnet-4-6`

- [ ] **Step 3: Test smart match + memory**

```
/agent-use claude opus
```

Expected: `Switched to claude · claude-opus-4-6`

```
/agent-use gemini
```

Expected: `Switched to gemini · gemini-2.5-pro`

```
/agent-use claude
```

Expected: `Switched to claude · claude-opus-4-6` (remembered opus)

- [ ] **Step 4: Verify /agent-model is removed**

```
/agent-model something
```

Expected: Error or not found (command no longer exists)
