# Model Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 將散落在多處的硬編碼模型資訊統一到 `application.yaml`，用 `@ConfigurationProperties` 綁定，並將 `skill-tiers` 更名為 `tier-models`。

**Architecture:** 兩份資料分離 — `grimo.models`（各 CLI 全量模型清單）+ `grimo.tier-models`（推薦 tier → model 對應）。新增 `GrimoProperties`（built-in defaults from application.yaml）與既有 `GrimoConfig`（user overrides from ~/.grimo/config.yaml）職責分離。TierRouter 實作 per-tier fallback：user config > built-in。

**Tech Stack:** Java 25, Spring Boot 4.0.x `@ConfigurationProperties`, SnakeYAML (existing), JUnit 5 + AssertJ + Mockito

**SDK 查核結果（v0.11.0）：**
- Claude SDK: `--model` flag, **無驗證**, pass-through. 預設 `null`（CLI 自決）
- Gemini SDK: `-m` flag, **有驗證**（必須 `gemini-` 開頭）. 預設 `gemini-2.5-flash`
- Codex SDK: `--model` flag (before `exec`), **無驗證**, pass-through. 預設 `gpt-5-codex`
- 三個 SDK 都是 opaque string pass-through（Gemini 例外有 prefix 驗證）

**Spec:** `docs/superpowers/specs/2026-04-04-model-catalog-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `src/main/resources/application.yaml` | Modify | 新增 `grimo.models` / `grimo.defaults` / `grimo.tier-models` |
| `src/main/java/.../config/GrimoProperties.java` | **Create** | `@ConfigurationProperties(prefix="grimo")` — built-in model catalog |
| `src/main/java/.../config/GrimoConfig.java` | Modify | `getSkillTiers()` → `getTierModels()`，YAML key `skill-tiers` → `tier-models` |
| `src/main/java/.../agent/tier/TierRouter.java` | Modify | 注入 `GrimoProperties`，per-tier fallback |
| `src/main/java/.../agent/tier/TierConfiguration.java` | Modify | bean 加入 `GrimoProperties` 參數 |
| `src/main/java/.../agent/AgentCommands.java` | Modify | 刪除 `RECOMMENDED_MODELS` / `MODEL_ALIASES` / `resolveModel()`，改用 `GrimoProperties` |
| `src/main/java/.../agent/AgentConfiguration.java` | Modify | 三個 agent hardcoded default 改讀 `GrimoProperties` |
| `src/main/java/.../home/GrimoHome.java` | Modify | DEFAULT_CONFIG `skill-tiers` → `tier-models`，改為註解引導 |
| `src/main/java/.../TuiAdapter.java` | Modify | `RECOMMENDED_MODELS` → `GrimoProperties.getDefaults()` |
| `src/main/java/.../tui/TuiEventBridge.java` | Modify | 同上 |
| `src/main/java/.../tui/package-info.java` | Modify | 更新 Javadoc 引用 |
| `src/test/java/.../config/GrimoPropertiesTest.java` | **Create** | GrimoProperties 綁定測試 |
| `src/test/java/.../config/GrimoConfigTest.java` | Modify | `getSkillTiers` → `getTierModels` |
| `src/test/java/.../agent/tier/TierRouterTest.java` | Modify | `getSkillTiers` → `getTierModels` + GrimoProperties fallback |
| `src/test/java/.../agent/tier/TierIntegrationTest.java` | Modify | YAML key `skill-tiers` → `tier-models` |
| `src/test/java/.../agent/tier/TierCommandsTest.java` | Modify | YAML key `skill-tiers` → `tier-models` |
| `docs/glossary.md` | Modify | Tier 條目更新術語 |

---

### Task 1: GrimoProperties — @ConfigurationProperties 綁定

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/config/GrimoProperties.java`
- Modify: `src/main/resources/application.yaml`
- Create: `src/test/java/io/github/samzhu/grimo/config/GrimoPropertiesTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.github.samzhu.grimo.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = GrimoProperties.class)
@EnableConfigurationProperties(GrimoProperties.class)
@TestPropertySource(properties = {
    "grimo.defaults.claude=claude-sonnet-4-6",
    "grimo.defaults.gemini=gemini-2.5-pro",
    "grimo.defaults.codex=gpt-5.4"
})
class GrimoPropertiesTest {

    @Autowired
    private GrimoProperties props;

    @Test
    void defaultsShouldBindCorrectly() {
        assertThat(props.getDefaults()).containsEntry("claude", "claude-sonnet-4-6");
        assertThat(props.getDefaults()).containsEntry("gemini", "gemini-2.5-pro");
        assertThat(props.getDefaults()).containsEntry("codex", "gpt-5.4");
    }

    @Test
    void tierModelsShouldBindFromApplicationYaml() {
        // tier-models is defined in application.yaml, should auto-bind
        var tierModels = props.getTierModels();
        assertThat(tierModels).containsKeys("lite", "std", "pro");
        assertThat(tierModels.get("pro")).isNotEmpty();
        assertThat(tierModels.get("pro").getFirst().agent()).isEqualTo("claude");
        assertThat(tierModels.get("pro").getFirst().model()).isEqualTo("claude-opus-4-6");
    }

    @Test
    void modelsShouldBindFromApplicationYaml() {
        var models = props.getModels();
        assertThat(models).containsKeys("claude", "gemini", "codex");
        assertThat(models.get("claude")).isNotEmpty();
        assertThat(models.get("claude").getFirst().id()).isEqualTo("claude-opus-4-6");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.samzhu.grimo.config.GrimoPropertiesTest" -x nativeTest`
Expected: FAIL — class not found

- [ ] **Step 3: Create GrimoProperties.java**

```java
package io.github.samzhu.grimo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 內建設定：從 application.yaml grimo.* 綁定。
 *
 * 設計說明：
 * - 與 GrimoConfig（讀 ~/.grimo/config.yaml 使用者設定）職責分離
 * - GrimoProperties = 開發者維護的 built-in defaults（模型清單、tier 對應、預設模型）
 * - GrimoConfig = 使用者的客製覆寫（tier-models、skill-overrides、agent-options）
 * - @ConfigurationProperties 由 Spring Boot 自動綁定，type-safe
 *
 * @see <a href="https://platform.claude.com/docs/en/docs/about-claude/models/overview">Claude Models</a>
 * @see <a href="https://ai.google.dev/gemini-api/docs/models">Gemini Models</a>
 * @see <a href="https://developers.openai.com/codex/models">Codex Models</a>
 */
@Component
@ConfigurationProperties(prefix = "grimo")
public class GrimoProperties {

    /** CLI 模型清單：agent → List<ModelEntry>。各 CLI 能用的所有模型，不過濾。 */
    private Map<String, List<ModelEntry>> models = Map.of();

    /** Agent 預設模型：agent → modelId。/agent-use 無指定 model 時使用。 */
    private Map<String, String> defaults = Map.of();

    /**
     * Tier → Model 推薦對應：tier → List<TierEntry>。
     * TierRouter 在 user config 沒設定時的 fallback。
     * 命名 tier-models（非 skill-tiers）：tier 機制通用於 skill、龍蝦模式等 pipeline。
     */
    private Map<String, List<TierEntry>> tierModels = Map.of();

    /**
     * CLI 可用模型資訊（benchmark、pricing 為開發者參考）。
     * 模型 ID 必須是各 CLI 實際接受的值。
     * 注意：Gemini SDK 驗證 model 必須以 "gemini-" 開頭。
     */
    public record ModelEntry(String id, String sweBench, String gpqa,
                             String pricing, String context) {}

    /** Tier fallback list 的單一條目。 */
    public record TierEntry(String agent, String model) {}

    public Map<String, List<ModelEntry>> getModels() { return models; }
    public void setModels(Map<String, List<ModelEntry>> models) { this.models = models; }

    public Map<String, String> getDefaults() { return defaults; }
    public void setDefaults(Map<String, String> defaults) { this.defaults = defaults; }

    public Map<String, List<TierEntry>> getTierModels() { return tierModels; }
    public void setTierModels(Map<String, List<TierEntry>> tierModels) { this.tierModels = tierModels; }
}
```

- [ ] **Step 4: Add model data to application.yaml**

在 `src/main/resources/application.yaml` 最後加上完整的 `grimo:` 區段（見 spec 的 application.yaml 區段，完整 YAML 內容）。

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.samzhu.grimo.config.GrimoPropertiesTest" -x nativeTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/config/GrimoProperties.java \
        src/main/resources/application.yaml \
        src/test/java/io/github/samzhu/grimo/config/GrimoPropertiesTest.java
git commit -m "feat: add GrimoProperties — model catalog in application.yaml with @ConfigurationProperties"
```

---

### Task 2: GrimoConfig rename — `getSkillTiers()` → `getTierModels()`

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/config/GrimoConfig.java:159-163`
- Modify: `src/test/java/io/github/samzhu/grimo/config/GrimoConfigTest.java:181-212`

- [ ] **Step 1: Write the failing test — add `getTierModels` test**

在 `GrimoConfigTest.java` 中，把 `getSkillTiersShouldReturnFallbackListPerTier`（:181）和 `getSkillTiersShouldReturnEmptyWhenNotConfigured`（:210）中的：
- 方法名 `getSkillTiers` → `getTierModels`
- YAML key `skill-tiers:` → `tier-models:`

```java
@Test
void getTierModelsShouldReturnFallbackListPerTier() throws IOException {
    var configFile = tempDir.resolve("config.yaml");
    Files.writeString(configFile, """
        tier-models:
          lite:
            - agent: gemini
              model: gemini-2.5-flash
            - agent: claude
              model: claude-haiku-4
          std:
            - agent: claude
              model: claude-sonnet-4
          pro:
            - agent: claude
              model: claude-opus-4
        """);

    var config = new GrimoConfig(configFile);
    var tiers = config.getTierModels();

    assertThat(tiers).containsKey("lite");
    assertThat(tiers.get("lite")).hasSize(2);
    assertThat(tiers.get("lite").getFirst().get("agent")).isEqualTo("gemini");
    assertThat(tiers.get("lite").getFirst().get("model")).isEqualTo("gemini-2.5-flash");
    assertThat(tiers).containsKey("std");
    assertThat(tiers).containsKey("pro");
}

@Test
void getTierModelsShouldReturnEmptyWhenNotConfigured() {
    var config = new GrimoConfig(tempDir.resolve("config.yaml"));
    assertThat(config.getTierModels()).isEmpty();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.samzhu.grimo.config.GrimoConfigTest" -x nativeTest`
Expected: FAIL — `getTierModels()` not found

- [ ] **Step 3: Rename method in GrimoConfig.java**

`GrimoConfig.java:159-163` — rename method + YAML key：

```java
/**
 * 取得 tier-models 設定：每個 tier 對應一個 agent+model fallback list。
 * 回傳格式：Map<tierName, List<Map<"agent"|"model", value>>>
 *
 * 設計說明：
 * - 更名自 getSkillTiers — tier 機制通用，不只 skill 用
 * - 每級可配多組 agent+model，按順序 fallback
 * - 回傳原始結構，TierRouter 負責解析與 isAvailable() 檢查
 */
@SuppressWarnings("unchecked")
public synchronized Map<String, List<Map<String, String>>> getTierModels() {
    var data = load();
    var tiers = (Map<String, List<Map<String, String>>>) data.get("tier-models");
    return tiers != null ? tiers : Map.of();
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.samzhu.grimo.config.GrimoConfigTest" -x nativeTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/config/GrimoConfig.java \
        src/test/java/io/github/samzhu/grimo/config/GrimoConfigTest.java
git commit -m "refactor: rename getSkillTiers → getTierModels — tier system is general-purpose"
```

---

### Task 3: TierRouter — per-tier fallback + GrimoProperties 注入

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/tier/TierRouter.java:30-40,93-113`
- Modify: `src/main/java/io/github/samzhu/grimo/agent/tier/TierConfiguration.java:21-24`
- Modify: `src/test/java/io/github/samzhu/grimo/agent/tier/TierRouterTest.java:17-41,127-138`

- [ ] **Step 1: Update TierRouterTest — rename + add fallback test**

`TierRouterTest.java` changes：
- `setUp()` :27 — `config.getSkillTiers()` → `config.getTierModels()`
- :130 — 同上
- :40 — constructor 加 `GrimoProperties`（mock）
- 新增測試：config 某 tier 為空時 fallback 到 GrimoProperties

```java
// setUp() 新增
private GrimoProperties grimoProperties;

@BeforeEach
void setUp() {
    registry = new AgentModelRegistry();
    config = mock(GrimoConfig.class);
    grimoProperties = mock(GrimoProperties.class);

    when(config.getTierModels()).thenReturn(Map.of(
            "lite", List.of(
                    Map.of("agent", "gemini", "model", "gemini-2.5-flash"),
                    Map.of("agent", "claude", "model", "claude-haiku-4")),
            "std", List.of(
                    Map.of("agent", "claude", "model", "claude-sonnet-4")),
            "pro", List.of(
                    Map.of("agent", "claude", "model", "claude-opus-4"),
                    Map.of("agent", "gemini", "model", "gemini-2.5-pro"))
    ));
    when(config.getSkillOverrides()).thenReturn(Map.of());
    when(config.getTierKeywords()).thenReturn(Map.of());
    when(grimoProperties.getTierModels()).thenReturn(Map.of()); // default empty

    router = new TierRouter(registry, config, grimoProperties);
}

// 新增測試
@Test
void resolveFallsBackToGrimoPropertiesWhenConfigTierEmpty() {
    registerAgent("claude");
    // config has no "pro" tier
    when(config.getTierModels()).thenReturn(Map.of(
            "std", List.of(Map.of("agent", "claude", "model", "claude-sonnet-4"))
    ));
    // built-in has "pro" tier
    when(grimoProperties.getTierModels()).thenReturn(Map.of(
            "pro", List.of(new GrimoProperties.TierEntry("claude", "claude-opus-4"))
    ));
    router = new TierRouter(registry, config, grimoProperties);

    var ctx = TierRouter.Context.builder().skillTier("pro").build();
    var selection = router.resolve(ctx);
    assertThat(selection.agentId()).isEqualTo("claude");
    assertThat(selection.model()).isEqualTo("claude-opus-4");
}
```

Also update `:130` (`resolveHandlesEmptyTierConfig`)：
```java
when(config.getTierModels()).thenReturn(Map.of());
// ... rest stays same
router = new TierRouter(registry, config, grimoProperties);
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.tier.TierRouterTest" -x nativeTest`
Expected: FAIL — constructor mismatch

- [ ] **Step 3: Update TierRouter.java**

`TierRouter.java` changes：
- Constructor: 加入 `GrimoProperties grimoProperties` 第三參數
- `walkFallbackList()` :93-110：per-tier fallback 邏輯

```java
private final AgentModelRegistry registry;
private final GrimoConfig config;
private final GrimoProperties grimoProperties;

public TierRouter(AgentModelRegistry registry, GrimoConfig config, GrimoProperties grimoProperties) {
    this.registry = registry;
    this.config = config;
    this.grimoProperties = grimoProperties;
}
```

`walkFallbackList()` :93 改為：

```java
private TierSelection walkFallbackList(Tier tier, String source) {
    // per-tier fallback：user config > built-in
    var configTiers = config.getTierModels();
    List<Map<String, String>> fallbackList = configTiers.get(tier.value());

    if (fallbackList == null || fallbackList.isEmpty()) {
        // 該 tier 在 user config 沒設定 → fallback 到 built-in (application.yaml)
        var builtIn = grimoProperties.getTierModels();
        var entries = builtIn.get(tier.value());
        if (entries != null) {
            fallbackList = entries.stream()
                .map(e -> Map.of("agent", e.agent(), "model", e.model()))
                .toList();
        }
    }

    if (fallbackList != null && !fallbackList.isEmpty()) {
        // ... 原有 walk 邏輯（registry.get + isAvailable check）不變
```

同步更新所有 Javadoc 中 `skill-tiers` → `tier-models`。

- [ ] **Step 4: Update TierConfiguration.java**

`TierConfiguration.java:21-24` — 加入 GrimoProperties 參數：

```java
@Bean
public TierRouter tierRouter(AgentModelRegistry registry, GrimoConfig config, GrimoProperties grimoProperties) {
    return new TierRouter(registry, config, grimoProperties);
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.tier.TierRouterTest" -x nativeTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/tier/TierRouter.java \
        src/main/java/io/github/samzhu/grimo/agent/tier/TierConfiguration.java \
        src/test/java/io/github/samzhu/grimo/agent/tier/TierRouterTest.java
git commit -m "feat: TierRouter per-tier fallback — user config > built-in GrimoProperties"
```

---

### Task 4: AgentCommands — 刪除硬編碼，改用 GrimoProperties

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java:35-53,83,122,137-140`

- [ ] **Step 1: Update AgentCommands.java**

1. 刪除 `RECOMMENDED_MODELS` (:35-39)
2. 刪除 `MODEL_ALIASES` (:45-53)
3. 刪除 `resolveModel()` 方法 (:137-140)
4. 新增 `GrimoProperties` 注入
5. `list()` :83 — `RECOMMENDED_MODELS.getOrDefault(id, "")` → `grimoProperties.getDefaults().getOrDefault(id, "")`
6. `use()` :122 — `RECOMMENDED_MODELS.getOrDefault(agentId, "unknown")` → `grimoProperties.getDefaults().getOrDefault(agentId, "unknown")`
7. `use()` model 解析 — 不做 alias，直接 passthrough hint 給 CLI（各 CLI 自己處理 alias）

```java
// use() 中的 model 解析改為：
if (modelHint != null && !modelHint.isBlank()) {
    model = modelHint;  // passthrough — CLI 自行解析 alias
    config.setAgentOption(agentId, "model", model);
} else {
    model = config.getAgentOption(agentId, "model");
    if (model == null) {
        model = grimoProperties.getDefaults().getOrDefault(agentId, "unknown");
    }
}
```

- [ ] **Step 2: Write test for AgentCommands with GrimoProperties**

新增或更新 `AgentCommandsTest.java`，驗證 list() 和 use() 正確讀取 GrimoProperties：

```java
@Test
void listShouldShowDefaultModelFromProperties() {
    when(grimoProperties.getDefaults()).thenReturn(Map.of("claude", "claude-sonnet-4-6"));
    // register mock agent...
    String result = commands.list();
    assertThat(result).contains("claude-sonnet-4-6");
}

@Test
void useShouldPassthroughModelHintDirectly() {
    // "opus" should be passed directly, no alias resolution
    when(registry.get("claude")).thenReturn(mockAvailableAgent);
    String result = commands.use("claude opus");
    assertThat(result).contains("opus"); // passthrough, not "claude-opus-4-6"
}

@Test
void useShouldFallbackToPropertiesDefault() {
    when(grimoProperties.getDefaults()).thenReturn(Map.of("codex", "gpt-5.4"));
    when(registry.get("codex")).thenReturn(mockAvailableAgent);
    when(config.getAgentOption("codex", "model")).thenReturn(null);
    String result = commands.use("codex");
    assertThat(result).contains("gpt-5.4");
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.AgentCommandsTest" -x nativeTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java \
        src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java
git commit -m "refactor: AgentCommands — remove RECOMMENDED_MODELS/MODEL_ALIASES, use GrimoProperties"
```

---

### Task 5: AgentConfiguration — hardcoded defaults 改讀 GrimoProperties

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/AgentConfiguration.java:46-119`

- [ ] **Step 1: Update AgentConfiguration.java**

注入 `GrimoProperties`，三處 hardcoded default 改讀：

```java
@Bean
public AgentModelFactory agentModelFactory(AgentModelRegistry registry, GrimoConfig config,
                                           GrimoProperties grimoProperties) {
    var specs = List.of(
        // Claude — :51 原本 "claude-sonnet-4-6"
        new AgentModelFactory.AgentSpec("claude", "cli", "Claude Code CLI", workingDirectory -> {
            String model = config.getAgentOption("claude", "model");
            if (model == null) model = grimoProperties.getDefaults().getOrDefault("claude", "claude-sonnet-4-6");
            // ... rest unchanged
        }),
        // Gemini — :70 原本 "gemini-2.5-pro"
        new AgentModelFactory.AgentSpec("gemini", "cli", "Gemini CLI", workingDirectory -> {
            String model = config.getAgentOption("gemini", "model");
            if (model == null) model = grimoProperties.getDefaults().getOrDefault("gemini", "gemini-2.5-pro");
            // ... rest unchanged
        }),
        // Codex — :95 原本 "o4-mini"，修正為 gpt-5.4
        new AgentModelFactory.AgentSpec("codex", "cli", "Codex CLI", workingDirectory -> {
            String model = config.getAgentOption("codex", "model");
            if (model == null) model = grimoProperties.getDefaults().getOrDefault("codex", "gpt-5.4");
            // ... rest unchanged
        })
    );
```

- [ ] **Step 2: Verify build + run existing agent tests**

Run: `./gradlew compileJava && ./gradlew test --tests "io.github.samzhu.grimo.agent.*" -x nativeTest`
Expected: PASS

Note: Codex default 從 `o4-mini` 改為 `gpt-5.4`（application.yaml 的 `grimo.defaults.codex`）。
SDK 預設是 `gpt-5-codex`，但 Grimo 選擇 `gpt-5.4` 作為推薦預設（更新的模型）。
實機驗證：`codex --model gpt-5.4 exec "echo test"` 確認 CLI 接受。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/AgentConfiguration.java
git commit -m "refactor: AgentConfiguration — read default models from GrimoProperties, fix codex o4-mini → gpt-5.4"
```

---

### Task 6: TUI 層 — RECOMMENDED_MODELS 引用遷移

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/TuiAdapter.java:104-109,148,257-269`
- Modify: `src/main/java/io/github/samzhu/grimo/tui/TuiEventBridge.java:2-3,48-57,178-180`
- Modify: `src/main/java/io/github/samzhu/grimo/tui/package-info.java:11`

- [ ] **Step 1: Update TuiAdapter.java**

1. Constructor: 注入 `GrimoProperties grimoProperties`
2. :148 — `AgentCommands.RECOMMENDED_MODELS.getOrDefault(agentId, "unknown")` → `grimoProperties.getDefaults().getOrDefault(agentId, "unknown")`
3. `buildModelItems()` :257-272 — `AgentCommands.RECOMMENDED_MODELS.get(agentId)` → `grimoProperties.getDefaults().get(agentId)`
   注意：此方法目前只顯示「推薦」和「上次使用」兩個選項。MVP 維持現有行為，
   只替換資料來源。未來 `/models` 指令可利用 `grimoProperties.getModels()` 顯示完整清單。
4. 移除 `import io.github.samzhu.grimo.agent.AgentCommands;`（如果不再需要）

- [ ] **Step 2: Update TuiEventBridge.java**

1. Constructor: 注入 `GrimoProperties grimoProperties`
2. :180 — `AgentCommands.RECOMMENDED_MODELS.getOrDefault(agentId, "unknown")` → `grimoProperties.getDefaults().getOrDefault(agentId, "unknown")`
3. 移除 `import io.github.samzhu.grimo.agent.AgentCommands;`

- [ ] **Step 3: Update package-info.java**

:11 — `AgentCommands.RECOMMENDED_MODELS 常數` → `GrimoProperties.getDefaults() 設定`

- [ ] **Step 4: Verify build**

Run: `./gradlew compileJava`
Expected: PASS — no remaining references to `AgentCommands.RECOMMENDED_MODELS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/TuiAdapter.java \
        src/main/java/io/github/samzhu/grimo/tui/TuiEventBridge.java \
        src/main/java/io/github/samzhu/grimo/tui/package-info.java
git commit -m "refactor: TUI layer — migrate RECOMMENDED_MODELS to GrimoProperties.getDefaults()"
```

---

### Task 7: GrimoHome + test YAML — `skill-tiers` → `tier-models`

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/home/GrimoHome.java:64-141`
- Modify: `src/test/java/io/github/samzhu/grimo/agent/tier/TierIntegrationTest.java:51,279-283`
- Modify: `src/test/java/io/github/samzhu/grimo/agent/tier/TierCommandsTest.java:28`

- [ ] **Step 1: Update GrimoHome.java DEFAULT_CONFIG**

`GrimoHome.java:104` — `skill-tiers:` 區段改為 `tier-models:` 並改為註解引導：

```yaml
# Tier 對應表
# 預設由內建 application.yaml 提供（零配置即可用）
# 如需客製，取消註解並調整（per-tier 覆寫，未設定的 tier 用內建值）：
#tier-models:
#  lite:
#    - agent: gemini
#      model: gemini-2.5-flash-lite
#  std:
#    - agent: claude
#      model: claude-sonnet-4-6
#  pro:
#    - agent: claude
#      model: claude-opus-4-6
```

- [ ] **Step 2: Update test YAML — all occurrences of `skill-tiers:` → `tier-models:`**

Files with YAML string `skill-tiers:` to rename：
- `TierIntegrationTest.java:51` → `tier-models:`
- `TierIntegrationTest.java:279-283` → 更新註解
- `TierCommandsTest.java:28` → `tier-models:`

- [ ] **Step 3: Run affected tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.tier.*" -x nativeTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/home/GrimoHome.java \
        src/test/java/io/github/samzhu/grimo/agent/tier/TierIntegrationTest.java \
        src/test/java/io/github/samzhu/grimo/agent/tier/TierCommandsTest.java
git commit -m "refactor: skill-tiers → tier-models — general-purpose tier naming"
```

---

### Task 8: Glossary + full build verification

**Files:**
- Modify: `docs/glossary.md:104,111`

- [ ] **Step 1: Update glossary**

`docs/glossary.md:104` — Tier 定義中如果提到 skill-tiers 要改：
```
| **Tier** | Tier | 任務執行的能力等級。三級：`lite`（快速便宜）、`std`（日常主力）、`pro`（深度推理）。每級對應一個 agent+model fallback list，定義於 `application.yaml` `grimo.tier-models`，使用者可在 `config.yaml` `tier-models` 覆寫。 |
```

`docs/glossary.md:111` — TierRouter 定義更新：
```
| **TierRouter** | Tier Router | 解析 tier 來源（6 級優先順序），查 fallback list（per-tier：user config `tier-models` > built-in `grimo.tier-models`），回傳 `TierSelection(agentId, model, tier, source)`。 |
```

- [ ] **Step 2: Full build**

Run: `./gradlew build -x nativeTest -x nativeCompile`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify no remaining hardcoded references**

Run: `grep -r "RECOMMENDED_MODELS\|MODEL_ALIASES\|getSkillTiers\|skill-tiers" src/`
Expected: No matches (only in docs/specs)

- [ ] **Step 4: Commit**

```bash
git add docs/glossary.md
git commit -m "docs: update glossary — tier-models naming, GrimoProperties reference"
```

---

## Verification Checklist

After all tasks:

1. `./gradlew build -x nativeTest -x nativeCompile` — PASS
2. `grep -r "RECOMMENDED_MODELS" src/` — no matches
3. `grep -r "MODEL_ALIASES" src/` — no matches
4. `grep -r "getSkillTiers" src/` — no matches
5. `grep -r "skill-tiers" src/main/` — no matches (test/docs OK if in comments)
6. `application.yaml` contains `grimo.models`, `grimo.defaults`, `grimo.tier-models`
7. Codex default = `gpt-5.4`（非 `o4-mini`）
