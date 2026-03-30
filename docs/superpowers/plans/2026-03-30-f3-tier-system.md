# F3: Tier 三級制（lite/std/pro）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Skill 執行與一般對話自動選擇最適合的 agent/model 等級，使用者不需手動切換。

**Architecture:** 新增 `TierRouter` 元件（`agent/tier` 套件），實作 6 級優先順序解析（關鍵字 > session /tier > skill-overrides > skill metadata > 自動分析 > 預設 std）。每級維護 agent+model fallback list，依序檢查 `isAvailable()`。`TierOptionsFactory` 根據 agentId 建構對應的 `AgentOptions` 子型別，在 `AgentClient.run(goalText, agentOptions)` 傳入以覆寫預設 model。`GrimoTuiRunner.processInput()` 整合 TierRouter 取代原本的 `agentRouter.route(null)` 流程。

**Tech Stack:** Java 25, Spring AI Community Agent Client 0.10.0-SNAPSHOT, Spring Shell 4.0, SnakeYAML, JUnit 5, AssertJ, Mockito

**SDK 驗證結果：**

| API | 狀態 | 來源 |
|-----|------|------|
| `AgentClient.run(String, AgentOptions)` | 已確認 | `AgentClient.java` — 內部建構 `Goal(goalText, null, agentOptions)` 再走 fluent chain |
| `Goal(String, Path, AgentOptions)` | 已確認 | `Goal.java` — 同時傳遞 workingDirectory(Path) + options(AgentOptions) |
| `AgentClientRequestSpec.workingDirectory(Path)` | 已確認 | fluent chain 設定 workingDir，**但無 `.options()` 方法** |
| `AgentOptions.getModel()` | 已確認 | `AgentOptions` 介面 — 所有子型別皆有 `model` 欄位 |
| `AgentOptions.getWorkingDirectory()` | 已確認 | 回傳 `String`（非 Path），fallback 優先順序：spec > Goal > defaultOptions > user.dir |
| `ClaudeAgentOptions.builder().model(m)` | 已確認 | Builder pattern，支援 model/yolo/timeout/maxBudgetUsd/fallbackModel |
| `GeminiAgentOptions.builder().model(m)` | 已確認 | Builder pattern，支援 model/yolo/timeout |
| `CodexAgentOptions.builder().model(m)` | 已確認 | Builder pattern，支援 model/fullAuto/approvalPolicy/timeout |
| `AgentModel.isAvailable()` | 已確認 | default method，Claude 實作嘗試建立 ClaudeSyncClient 偵測 CLI |
| `AgentModelRegistry.get(id)` | 已確認 | 回傳 `AgentModel` 或 `null`，搭配 `isAvailable()` 判斷可用性 |
| `McpServerCatalog` + builder pattern | 已確認 | `AgentClient.builder(model).mcpServerCatalog(catalog).build()` |

**設計說明 — per-request model 覆寫機制：**

目前 `AgentConfiguration` 在啟動時建立 `AgentModel`，model 寫死在 `defaultOptions` 裡。Tier 系統需要在執行時動態選擇 model（例如同一個 Claude agent 可能用 haiku/sonnet/opus）。

解法：不重建 `AgentModel`，改用 `Goal(goalText, workingDir, agentOptions)` 同時傳遞 workingDirectory 和 per-request options。SDK 內部 `DefaultAgentClient.run()` 的 options merging 是 simple override（goal options 存在就用 goal 的，不做 deep merge），因此 per-request options 必須包含所有必要欄位（model, yolo, timeout 等）。

**重要：`AgentClientRequestSpec` 沒有 `.options()` 方法**，只能透過 `Goal` 建構子或 `AgentClient.run(String, AgentOptions)` 傳遞 options。`run(String, AgentOptions)` 內部建構 `Goal(goalText, null, agentOptions)`，workingDirectory 為 null。因此使用 `defaultWorkingDirectory(projectDir)` 在 builder 層設定。

```
TierRouter.resolve(context)
  → TierSelection(agentId="claude", model="claude-haiku-4", tier=LITE)
    → AgentModel from registry (用來建 AgentClient)
    → TierOptionsFactory.build("claude", "claude-haiku-4")
      → ClaudeAgentOptions.builder().model("claude-haiku-4").yolo(true).timeout(10m).build()
    → client = AgentClient.builder(model)
        .defaultWorkingDirectory(projectDir)    // builder 層設 workingDir
        .mcpServerCatalog(catalog).build()
    → client.run(goalText, claudeOptions)       // per-request options 覆寫 model
```

**conversation vs skill-tier 區分：**
規格書定義 `conversation` config 區段，本計畫沿用既有的 `agents.default` / `agents.model` 作為 conversation 設定（功能等價，避免 breaking change）。未來可新增 `conversation` 區段作為 alias。

**參考：**
- [Spring AI Community Agent Client](https://spring-ai-community.github.io/agent-client/) — AgentClient / AgentOptions API
- [MindStudio 3-Tier Router](https://www.mindstudio.ai/blog/set-up-ai-model-router-llm-stack-c2610) — 業界 3 級標準
- [RouteLLM](https://github.com/lm-sys/RouteLLM) — ML-based 路由研究
- [F3 Spec](../specs/2026-03-27-f3-tier-system.md) — 完整規格書

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `src/main/java/io/github/samzhu/grimo/agent/tier/Tier.java` | Tier 列舉（LITE/STD/PRO） |
| Create | `src/main/java/io/github/samzhu/grimo/agent/tier/TierSelection.java` | 路由結果 record（agentId, model, tier, source） |
| Create | `src/main/java/io/github/samzhu/grimo/agent/tier/TierConfiguration.java` | Spring `@Configuration`：建立 TierRouter, TierKeywordDetector, TierOptionsFactory, `AtomicReference<Tier>` beans |
| Create | `src/main/java/io/github/samzhu/grimo/agent/tier/TierKeywordDetector.java` | 從使用者輸入偵測 tier 關鍵字 |
| Create | `src/main/java/io/github/samzhu/grimo/agent/tier/TierRouter.java` | 6 級優先順序解析 + fallback 路由 |
| Create | `src/main/java/io/github/samzhu/grimo/agent/tier/TierOptionsFactory.java` | 依 agentId 建構 per-request AgentOptions |
| Create | `src/main/java/io/github/samzhu/grimo/agent/tier/TierCommands.java` | `/tier`, `/skill-tier` 指令 |
| Create | `src/main/java/io/github/samzhu/grimo/agent/tier/package-info.java` | Spring Modulith named interface |
| Create | `src/main/java/io/github/samzhu/grimo/skill/analyzer/SkillAnalyzer.java` | 安裝時用 lite agent 自動分析 Skill 複雜度 |
| Create | `src/main/java/io/github/samzhu/grimo/skill/analyzer/package-info.java` | Spring Modulith named interface |
| Create | `src/test/java/io/github/samzhu/grimo/agent/tier/TierKeywordDetectorTest.java` | 關鍵字偵測測試 |
| Create | `src/test/java/io/github/samzhu/grimo/agent/tier/TierRouterTest.java` | TierRouter 路由邏輯測試 |
| Create | `src/test/java/io/github/samzhu/grimo/agent/tier/TierOptionsFactoryTest.java` | Options 建構測試 |
| Create | `src/test/java/io/github/samzhu/grimo/agent/tier/TierCommandsTest.java` | 指令測試 |
| Create | `src/test/java/io/github/samzhu/grimo/skill/analyzer/SkillAnalyzerTest.java` | SkillAnalyzer 測試 |
| Create | `src/test/java/io/github/samzhu/grimo/GrimoStatusViewTest.java` | StatusView tier 渲染測試 |
| Modify | `src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java` | 新增 tier 相關讀寫方法 |
| Modify | `src/test/java/io/github/samzhu/grimo/shared/config/GrimoConfigTest.java` | 新增 tier config 測試 |
| Modify | `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` | 整合 TierRouter，session tier 狀態，Status Bar tier 顯示 |
| Modify | `src/main/java/io/github/samzhu/grimo/GrimoStatusView.java` | 支援 tier icon + label 渲染 |
| Modify | `src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java` | `/skill-install` 加入自動分析，`/skill-list` 顯示 tier |
| Modify | `docs/glossary.md` | 新增/更新 Tier 相關術語 |

**Not modified:**
- `build.gradle.kts` — 無新依賴（所有 SDK 已在 0.10.0-SNAPSHOT 中）
- `AgentConfiguration.java` — AgentModel 建立方式不變，tier 覆寫在 per-request options 層
- `AgentRouter.java` — 保留做 conversation fallback，TierRouter 在其上層
- `SkillDefinition.java` — `grimoTier()` 已存在，無需修改

---

### Task 1: Tier enum + TierSelection record

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/tier/Tier.java`
- Create: `src/main/java/io/github/samzhu/grimo/agent/tier/TierSelection.java`
- Create: `src/main/java/io/github/samzhu/grimo/agent/tier/package-info.java`

- [ ] **Step 1: Create Tier enum**

```java
package io.github.samzhu.grimo.agent.tier;

/**
 * Skill 執行的能力等級。
 *
 * 設計說明：
 * - 三級對應不同成本/能力：lite（快速便宜）、std（日常主力）、pro（深度推理）
 * - 對齊 MindStudio 3-Tier Router 業界標準（Fast/Standard/Premium）
 *
 * @see <a href="https://www.mindstudio.ai/blog/set-up-ai-model-router-llm-stack-c2610">MindStudio 3-Tier</a>
 */
public enum Tier {
    LITE("lite", "⚡"),
    STD("std", "⚙"),
    PRO("pro", "\uD83E\uDDE0");

    private final String value;
    private final String icon;

    Tier(String value, String icon) {
        this.value = value;
        this.icon = icon;
    }

    public String value() { return value; }
    public String icon() { return icon; }

    /**
     * 從字串解析 Tier，不區分大小寫。無效值回傳 STD。
     */
    public static Tier fromString(String s) {
        if (s == null) return STD;
        return switch (s.strip().toLowerCase()) {
            case "lite" -> LITE;
            case "pro" -> PRO;
            default -> STD;
        };
    }
}
```

- [ ] **Step 2: Create TierSelection record**

```java
package io.github.samzhu.grimo.agent.tier;

/**
 * TierRouter 的路由結果。
 *
 * @param agentId 選定的 agent ID（如 "claude", "gemini", "codex"）
 * @param model   選定的 model 名稱（如 "claude-haiku-4", "gemini-2.5-flash"）
 * @param tier    解析後的 Tier 等級
 * @param source  Tier 的來源（除錯/日誌用）
 */
public record TierSelection(
    String agentId,
    String model,
    Tier tier,
    String source
) {}
```

- [ ] **Step 3: Create package-info.java**

```java
@org.springframework.modulith.NamedInterface("tier")
package io.github.samzhu.grimo.agent.tier;
```

- [ ] **Step 4: Run build to verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/tier/
git commit -m "feat(f3): add Tier enum and TierSelection record"
```

---

### Task 2: TierConfig — 讀取 config.yaml tier 區段

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java`
- Modify: `src/test/java/io/github/samzhu/grimo/shared/config/GrimoConfigTest.java`

- [ ] **Step 1: Write the failing tests**

在 `GrimoConfigTest.java` 新增以下測試：

```java
@Test
void getSkillTiersShouldReturnFallbackListPerTier() throws IOException {
    var configFile = tempDir.resolve("config.yaml");
    Files.writeString(configFile, """
        skill-tiers:
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
    var tiers = config.getSkillTiers();

    assertThat(tiers).containsKey("lite");
    assertThat(tiers.get("lite")).hasSize(2);
    assertThat(tiers.get("lite").getFirst().get("agent")).isEqualTo("gemini");
    assertThat(tiers.get("lite").getFirst().get("model")).isEqualTo("gemini-2.5-flash");
    assertThat(tiers).containsKey("std");
    assertThat(tiers).containsKey("pro");
}

@Test
void getSkillTiersShouldReturnEmptyWhenNotConfigured() {
    var config = new GrimoConfig(tempDir.resolve("config.yaml"));
    assertThat(config.getSkillTiers()).isEmpty();
}

@Test
void getSkillOverridesShouldReturnOverridesMap() throws IOException {
    var configFile = tempDir.resolve("config.yaml");
    Files.writeString(configFile, """
        skill-overrides:
          deep-research:
            tier: pro
          tdd-workflow:
            agent: claude
            model: claude-opus-4
        """);

    var config = new GrimoConfig(configFile);
    var overrides = config.getSkillOverrides();

    assertThat(overrides).containsKey("deep-research");
    assertThat(overrides.get("deep-research").get("tier")).isEqualTo("pro");
    assertThat(overrides).containsKey("tdd-workflow");
    assertThat(overrides.get("tdd-workflow").get("agent")).isEqualTo("claude");
}

@Test
void getSkillOverridesShouldReturnEmptyWhenNotConfigured() {
    var config = new GrimoConfig(tempDir.resolve("config.yaml"));
    assertThat(config.getSkillOverrides()).isEmpty();
}

@Test
void getTierKeywordsShouldReturnKeywordsPerTier() throws IOException {
    var configFile = tempDir.resolve("config.yaml");
    Files.writeString(configFile, """
        tier-keywords:
          pro:
            - 仔細想
            - think hard
          lite:
            - 快速
            - quickly
        """);

    var config = new GrimoConfig(configFile);
    var keywords = config.getTierKeywords();

    assertThat(keywords).containsKey("pro");
    assertThat(keywords.get("pro")).contains("仔細想", "think hard");
    assertThat(keywords).containsKey("lite");
    assertThat(keywords.get("lite")).contains("快速", "quickly");
}

@Test
void getTierKeywordsShouldReturnEmptyWhenNotConfigured() {
    var config = new GrimoConfig(tempDir.resolve("config.yaml"));
    assertThat(config.getTierKeywords()).isEmpty();
}

@Test
void setSkillOverrideShouldPersist() {
    var configFile = tempDir.resolve("config.yaml");
    var config = new GrimoConfig(configFile);

    config.setSkillOverride("deep-research", Map.of("tier", "pro"));

    var reloaded = new GrimoConfig(configFile);
    var overrides = reloaded.getSkillOverrides();
    assertThat(overrides).containsKey("deep-research");
    assertThat(overrides.get("deep-research").get("tier")).isEqualTo("pro");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.config.GrimoConfigTest"`
Expected: FAIL — methods not found

- [ ] **Step 3: Implement GrimoConfig tier methods**

在 `GrimoConfig.java` 新增：

```java
/**
 * 取得 skill-tiers 設定：每個 tier 對應一個 agent+model fallback list。
 * 回傳格式：Map<tierName, List<Map<"agent"|"model", value>>>
 *
 * 設計說明：
 * - 每級可配多組 agent+model，按順序 fallback
 * - 回傳原始結構，TierRouter 負責解析與 isAvailable() 檢查
 */
@SuppressWarnings("unchecked")
public synchronized Map<String, List<Map<String, String>>> getSkillTiers() {
    var data = load();
    var tiers = (Map<String, List<Map<String, String>>>) data.get("skill-tiers");
    return tiers != null ? tiers : Map.of();
}

/**
 * 取得 skill-overrides 設定：per-skill 的 tier 或 agent+model 覆寫。
 * 回傳格式：Map<skillName, Map<"tier"|"agent"|"model", value>>
 *
 * 兩種形式：
 * - 形式 A：{ tier: "pro" } → 走 tier fallback
 * - 形式 B：{ agent: "claude", model: "claude-opus-4" } → 直接使用
 */
@SuppressWarnings("unchecked")
public synchronized Map<String, Map<String, String>> getSkillOverrides() {
    var data = load();
    var overrides = (Map<String, Map<String, String>>) data.get("skill-overrides");
    return overrides != null ? overrides : Map.of();
}

/**
 * 設定特定 skill 的 tier/agent/model 覆寫（寫入 skill-overrides 區段）。
 */
@SuppressWarnings("unchecked")
public synchronized void setSkillOverride(String skillName, Map<String, String> override) {
    var data = load();
    var overrides = (Map<String, Map<String, String>>)
            data.computeIfAbsent("skill-overrides", k -> new LinkedHashMap<>());
    overrides.put(skillName, new LinkedHashMap<>(override));
    save(data);
}

/**
 * 取得 tier-keywords 設定：每個 tier 對應的觸發關鍵字列表。
 * 回傳格式：Map<tierName, List<keyword>>
 */
@SuppressWarnings("unchecked")
public synchronized Map<String, List<String>> getTierKeywords() {
    var data = load();
    var keywords = (Map<String, List<String>>) data.get("tier-keywords");
    return keywords != null ? keywords : Map.of();
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.config.GrimoConfigTest"`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java \
        src/test/java/io/github/samzhu/grimo/shared/config/GrimoConfigTest.java
git commit -m "feat(f3): add tier config read/write methods to GrimoConfig"
```

---

### Task 3: TierKeywordDetector — 從使用者輸入偵測 tier 關鍵字

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/tier/TierKeywordDetector.java`
- Create: `src/test/java/io/github/samzhu/grimo/agent/tier/TierKeywordDetectorTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package io.github.samzhu.grimo.agent.tier;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TierKeywordDetectorTest {

    private final Map<String, List<String>> keywords = Map.of(
            "pro", List.of("仔細想", "深入分析", "think hard", "think deeply"),
            "lite", List.of("快速", "簡單說", "quickly", "briefly")
    );

    private final TierKeywordDetector detector = new TierKeywordDetector(keywords);

    @Test
    void detectProKeywordInChinese() {
        assertThat(detector.detect("仔細想 這段程式碼的問題")).hasValue(Tier.PRO);
    }

    @Test
    void detectLiteKeywordInEnglish() {
        assertThat(detector.detect("quickly check the test results")).hasValue(Tier.LITE);
    }

    @Test
    void detectNoKeyword() {
        assertThat(detector.detect("refactor the UserService class")).isEmpty();
    }

    @Test
    void detectReturnsHighestTierWhenMultipleMatch() {
        // pro > lite — 如果同時匹配多個，取最高等級
        assertThat(detector.detect("快速 但仔細想一下")).hasValue(Tier.PRO);
    }

    @Test
    void detectIsCaseInsensitive() {
        assertThat(detector.detect("THINK HARD about this")).hasValue(Tier.PRO);
    }

    @Test
    void detectWithEmptyKeywordsReturnsEmpty() {
        var emptyDetector = new TierKeywordDetector(Map.of());
        assertThat(emptyDetector.detect("仔細想")).isEmpty();
    }

    @Test
    void detectWithNullInputReturnsEmpty() {
        assertThat(detector.detect(null)).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.tier.TierKeywordDetectorTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Implement TierKeywordDetector**

```java
package io.github.samzhu.grimo.agent.tier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 從使用者輸入文字偵測 tier 關鍵字（per-turn 提升）。
 *
 * 設計說明：
 * - 關鍵字只影響該輪，不改 session 設定（類似 Claude Code 的 ultrathink 機制）
 * - 多個 tier 同時匹配時取最高等級（PRO > STD > LITE）
 * - 不區分大小寫（英文），中文直接子字串比對
 *
 * @see <a href="https://findskill.ai/blog/claude-ultrathink-extended-thinking/">Claude Code ultrathink</a>
 */
public class TierKeywordDetector {

    private final Map<String, List<String>> keywords;

    public TierKeywordDetector(Map<String, List<String>> keywords) {
        this.keywords = keywords;
    }

    /**
     * 偵測使用者輸入中的 tier 關鍵字。
     *
     * @param input 使用者輸入文字
     * @return 偵測到的 Tier，或 empty 表示無匹配
     */
    public Optional<Tier> detect(String input) {
        if (input == null || keywords.isEmpty()) {
            return Optional.empty();
        }
        String lower = input.toLowerCase();

        // 按 PRO > STD > LITE 順序檢查，回傳最高匹配
        for (Tier tier : List.of(Tier.PRO, Tier.STD, Tier.LITE)) {
            List<String> tierKeywords = keywords.getOrDefault(tier.value(), List.of());
            for (String keyword : tierKeywords) {
                if (lower.contains(keyword.toLowerCase())) {
                    return Optional.of(tier);
                }
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.tier.TierKeywordDetectorTest"`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/tier/TierKeywordDetector.java \
        src/test/java/io/github/samzhu/grimo/agent/tier/TierKeywordDetectorTest.java
git commit -m "feat(f3): add TierKeywordDetector for per-turn keyword elevation"
```

---

### Task 4: TierRouter — 核心路由邏輯

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/tier/TierRouter.java`
- Create: `src/test/java/io/github/samzhu/grimo/agent/tier/TierRouterTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package io.github.samzhu.grimo.agent.tier;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.model.AgentModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TierRouterTest {

    private AgentModelRegistry registry;
    private GrimoConfig config;
    private TierRouter router;

    @BeforeEach
    void setUp() {
        registry = new AgentModelRegistry();
        config = mock(GrimoConfig.class);

        // 預設 tier config
        when(config.getSkillTiers()).thenReturn(Map.of(
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

        router = new TierRouter(registry, config);
    }

    private void registerAgent(String id) {
        var model = mock(AgentModel.class);
        when(model.isAvailable()).thenReturn(true);
        registry.register(id, model);
    }

    @Test
    void resolveUsesSkillMetadataTier() {
        registerAgent("claude");
        var ctx = TierRouter.Context.builder()
                .skillTier("pro")
                .build();

        var selection = router.resolve(ctx);

        assertThat(selection.tier()).isEqualTo(Tier.PRO);
        assertThat(selection.agentId()).isEqualTo("claude");
        assertThat(selection.model()).isEqualTo("claude-opus-4");
        assertThat(selection.source()).isEqualTo("skill-metadata");
    }

    @Test
    void resolveDefaultsToStd() {
        registerAgent("claude");
        var ctx = TierRouter.Context.builder().build();

        var selection = router.resolve(ctx);

        assertThat(selection.tier()).isEqualTo(Tier.STD);
        assertThat(selection.source()).isEqualTo("default");
    }

    @Test
    void resolveSessionTierOverridesSkillMetadata() {
        registerAgent("claude");
        var ctx = TierRouter.Context.builder()
                .sessionTier(Tier.PRO)
                .skillTier("std")
                .build();

        var selection = router.resolve(ctx);

        assertThat(selection.tier()).isEqualTo(Tier.PRO);
        assertThat(selection.source()).isEqualTo("session");
    }

    @Test
    void resolveKeywordOverridesAll() {
        registerAgent("claude");
        var ctx = TierRouter.Context.builder()
                .keywordTier(Tier.PRO)
                .sessionTier(Tier.LITE)
                .skillTier("std")
                .build();

        var selection = router.resolve(ctx);

        assertThat(selection.tier()).isEqualTo(Tier.PRO);
        assertThat(selection.source()).isEqualTo("keyword");
    }

    @Test
    void resolveSkillOverrideTierWalksFallbackList() {
        registerAgent("gemini");
        // claude not registered → fallback to gemini
        when(config.getSkillOverrides()).thenReturn(Map.of(
                "deep-research", Map.of("tier", "pro")));

        var ctx = TierRouter.Context.builder()
                .skillName("deep-research")
                .build();

        var selection = router.resolve(ctx);

        assertThat(selection.tier()).isEqualTo(Tier.PRO);
        assertThat(selection.agentId()).isEqualTo("gemini");
        assertThat(selection.model()).isEqualTo("gemini-2.5-pro");
        assertThat(selection.source()).isEqualTo("skill-override");
    }

    @Test
    void resolveSkillOverrideDirectAgentBypassesTierList() {
        registerAgent("claude");
        when(config.getSkillOverrides()).thenReturn(Map.of(
                "tdd-workflow", Map.of("agent", "claude", "model", "claude-opus-4")));

        var ctx = TierRouter.Context.builder()
                .skillName("tdd-workflow")
                .build();

        var selection = router.resolve(ctx);

        assertThat(selection.agentId()).isEqualTo("claude");
        assertThat(selection.model()).isEqualTo("claude-opus-4");
        assertThat(selection.source()).isEqualTo("skill-override-direct");
    }

    @Test
    void resolveFallbackSkipsUnavailableAgent() {
        // claude not registered, gemini available
        registerAgent("gemini");

        var ctx = TierRouter.Context.builder()
                .skillTier("pro")
                .build();

        var selection = router.resolve(ctx);

        assertThat(selection.agentId()).isEqualTo("gemini");
        assertThat(selection.model()).isEqualTo("gemini-2.5-pro");
    }

    @Test
    void resolveThrowsWhenNoAgentAvailableForTier() {
        // no agents registered
        var ctx = TierRouter.Context.builder()
                .skillTier("pro")
                .build();

        assertThatThrownBy(() -> router.resolve(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pro");
    }

    @Test
    void resolveHandlesEmptyTierConfig() {
        registerAgent("claude");
        when(config.getSkillTiers()).thenReturn(Map.of());
        when(config.getDefaultAgent()).thenReturn("claude");
        when(config.getAgentOption("claude", "model")).thenReturn("claude-sonnet-4");

        router = new TierRouter(registry, config);
        var ctx = TierRouter.Context.builder().build();

        var selection = router.resolve(ctx);

        // 沒有 tier config 時 fallback 到 conversation default
        assertThat(selection.agentId()).isEqualTo("claude");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.tier.TierRouterTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Implement TierRouter**

```java
package io.github.samzhu.grimo.agent.tier;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Tier 路由器：根據 6 級優先順序解析 Tier，再 walk fallback list 選定 agent+model。
 *
 * 設計說明 — 6 級優先順序（高→低）：
 * 1. 使用者本輪關鍵字（keywordTier）     → per-turn，不持久
 * 2. 使用者 /tier 指令（sessionTier）     → session 級，持續到下次切換
 * 3. skill-overrides（config）            → 兩種形式（tier 或直接 agent+model）
 * 4. Skill metadata（grimo.tier）         → Skill 作者定義
 * 5. 安裝時自動分析結果（已寫入 metadata） → 等同 #4
 * 6. 預設 std
 *
 * Tier 確定後，walk skill-tiers.<tier> fallback list：
 * - 每個 entry 查 registry.get(agentId) → isAvailable()
 * - 第一個可用的就選定
 * - 全都不可用 → throw IllegalStateException
 *
 * @see <a href="https://www.mindstudio.ai/blog/set-up-ai-model-router-llm-stack-c2610">MindStudio 3-Tier</a>
 */
public class TierRouter {

    private static final Logger log = LoggerFactory.getLogger(TierRouter.class);

    private final AgentModelRegistry registry;
    private final GrimoConfig config;

    public TierRouter(AgentModelRegistry registry, GrimoConfig config) {
        this.registry = registry;
        this.config = config;
    }

    /**
     * 解析 tier 並選定 agent+model。
     *
     * @param ctx 包含本輪所有決策因素的 context
     * @return 選定的 agent+model+tier+source
     * @throws IllegalStateException 如果沒有可用 agent 符合該 tier
     */
    public TierSelection resolve(Context ctx) {
        // --- Priority 3: skill-overrides（形式 B：直接指定 agent+model）---
        if (ctx.skillName != null) {
            var overrides = config.getSkillOverrides();
            var override = overrides.get(ctx.skillName);
            if (override != null && override.containsKey("agent") && override.containsKey("model")) {
                String agentId = override.get("agent");
                String model = override.get("model");
                var agent = registry.get(agentId);
                if (agent != null && agent.isAvailable()) {
                    log.info("Tier resolved: skill-override-direct → {} / {}", agentId, model);
                    return new TierSelection(agentId, model, Tier.STD, "skill-override-direct");
                }
            }
        }

        // --- Resolve tier level (priorities 1-6) ---
        Tier tier;
        String source;

        if (ctx.keywordTier != null) {
            tier = ctx.keywordTier;
            source = "keyword";
        } else if (ctx.sessionTier != null) {
            tier = ctx.sessionTier;
            source = "session";
        } else if (ctx.skillName != null) {
            var overrides = config.getSkillOverrides();
            var override = overrides.get(ctx.skillName);
            if (override != null && override.containsKey("tier")) {
                tier = Tier.fromString(override.get("tier"));
                source = "skill-override";
            } else if (ctx.skillTier != null) {
                tier = Tier.fromString(ctx.skillTier);
                source = "skill-metadata";
            } else {
                tier = Tier.STD;
                source = "default";
            }
        } else if (ctx.skillTier != null) {
            tier = Tier.fromString(ctx.skillTier);
            source = "skill-metadata";
        } else {
            tier = Tier.STD;
            source = "default";
        }

        log.debug("Tier resolved: {} (source: {})", tier, source);

        // --- Walk fallback list for resolved tier ---
        return walkFallbackList(tier, source);
    }

    private TierSelection walkFallbackList(Tier tier, String source) {
        var tiers = config.getSkillTiers();
        List<Map<String, String>> fallbackList = tiers.get(tier.value());

        if (fallbackList != null && !fallbackList.isEmpty()) {
            for (var entry : fallbackList) {
                String agentId = entry.get("agent");
                String model = entry.get("model");
                var agent = registry.get(agentId);
                if (agent != null && agent.isAvailable()) {
                    log.info("Tier routing: {} → {} / {} (source: {})", tier, agentId, model, source);
                    return new TierSelection(agentId, model, tier, source);
                }
                log.debug("Tier fallback: {} / {} not available, trying next", agentId, model);
            }
            throw new IllegalStateException(
                    "沒有可用的 agent 符合 %s 等級。請確認至少一個 CLI agent 已安裝。".formatted(tier.value()));
        }

        // skill-tiers 未設定 → fallback to conversation default
        return fallbackToConversationDefault(tier, source);
    }

    private TierSelection fallbackToConversationDefault(Tier tier, String source) {
        String defaultAgent = config.getDefaultAgent();
        if (defaultAgent != null) {
            var agent = registry.get(defaultAgent);
            if (agent != null && agent.isAvailable()) {
                String model = config.getAgentOption(defaultAgent, "model");
                if (model == null) model = config.getDefaultModel();
                if (model == null) model = "unknown";
                log.info("Tier routing: no tier config, using conversation default: {} / {}", defaultAgent, model);
                return new TierSelection(defaultAgent, model, tier, source);
            }
        }

        // 最後 fallback：第一個可用 agent
        var available = registry.listAvailable();
        if (available.isEmpty()) {
            throw new IllegalStateException("No agents available. Install a CLI agent (claude, gemini, or codex).");
        }
        var first = available.entrySet().iterator().next();
        return new TierSelection(first.getKey(), "unknown", tier, "fallback-first-available");
    }

    /**
     * TierRouter 的輸入 context。使用 builder pattern 建構。
     */
    public static class Context {
        @Nullable final Tier keywordTier;
        @Nullable final Tier sessionTier;
        @Nullable final String skillName;
        @Nullable final String skillTier;

        private Context(Builder builder) {
            this.keywordTier = builder.keywordTier;
            this.sessionTier = builder.sessionTier;
            this.skillName = builder.skillName;
            this.skillTier = builder.skillTier;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Tier keywordTier;
            private Tier sessionTier;
            private String skillName;
            private String skillTier;

            public Builder keywordTier(Tier tier) { this.keywordTier = tier; return this; }
            public Builder sessionTier(Tier tier) { this.sessionTier = tier; return this; }
            public Builder skillName(String name) { this.skillName = name; return this; }
            public Builder skillTier(String tier) { this.skillTier = tier; return this; }

            public Context build() { return new Context(this); }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.tier.TierRouterTest"`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/tier/TierRouter.java \
        src/test/java/io/github/samzhu/grimo/agent/tier/TierRouterTest.java
git commit -m "feat(f3): add TierRouter with 6-level priority resolution and fallback"
```

---

### Task 5: TierOptionsFactory — 建構 per-request AgentOptions

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/tier/TierOptionsFactory.java`
- Create: `src/test/java/io/github/samzhu/grimo/agent/tier/TierOptionsFactoryTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package io.github.samzhu.grimo.agent.tier;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.claude.ClaudeAgentOptions;
import org.springaicommunity.agents.codex.CodexAgentOptions;
import org.springaicommunity.agents.gemini.GeminiAgentOptions;
import org.springaicommunity.agents.model.AgentOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TierOptionsFactoryTest {

    private final TierOptionsFactory factory = new TierOptionsFactory();

    @Test
    void buildClaudeOptions() {
        AgentOptions options = factory.build("claude", "claude-haiku-4");
        assertThat(options).isInstanceOf(ClaudeAgentOptions.class);
        assertThat(options.getModel()).isEqualTo("claude-haiku-4");
    }

    @Test
    void buildGeminiOptions() {
        AgentOptions options = factory.build("gemini", "gemini-2.5-flash");
        assertThat(options).isInstanceOf(GeminiAgentOptions.class);
        assertThat(options.getModel()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void buildCodexOptions() {
        AgentOptions options = factory.build("codex", "o4-mini");
        assertThat(options).isInstanceOf(CodexAgentOptions.class);
        assertThat(options.getModel()).isEqualTo("o4-mini");
    }

    @Test
    void buildUnknownAgentThrows() {
        assertThatThrownBy(() -> factory.build("unknown", "model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.tier.TierOptionsFactoryTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Implement TierOptionsFactory**

```java
package io.github.samzhu.grimo.agent.tier;

import org.springaicommunity.agents.claude.ClaudeAgentOptions;
import org.springaicommunity.agents.codex.CodexAgentOptions;
import org.springaicommunity.agents.gemini.GeminiAgentOptions;
import org.springaicommunity.agents.model.AgentOptions;
import org.springaicommunity.agents.codexsdk.types.ApprovalPolicy;

import java.time.Duration;

/**
 * 根據 agentId 建構對應的 per-request AgentOptions（含 tier 指定的 model）。
 *
 * 設計說明：
 * - 每個 CLI agent 需要不同的 AgentOptions 子型別（ClaudeAgentOptions / GeminiAgentOptions / CodexAgentOptions）
 * - 集中管理各 agent 的共用設定（yolo=true, timeout, fullAuto 等）
 * - 在 AgentClient.run(goalText, agentOptions) 傳入，覆寫 AgentModel 的 defaultOptions
 *
 * @see <a href="https://spring-ai-community.github.io/agent-client/">AgentClient API</a>
 */
public class TierOptionsFactory {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    /**
     * 建構指定 agent 的 AgentOptions，使用 tier 選定的 model。
     *
     * @param agentId agent ID（"claude", "gemini", "codex"）
     * @param model   tier 選定的 model 名稱
     * @return 對應的 AgentOptions 子型別
     * @throws IllegalArgumentException 如果 agentId 未知
     */
    public AgentOptions build(String agentId, String model) {
        return switch (agentId) {
            case "claude" -> ClaudeAgentOptions.builder()
                    .model(model)
                    .yolo(true)
                    .timeout(DEFAULT_TIMEOUT)
                    .build();
            case "gemini" -> GeminiAgentOptions.builder()
                    .model(model)
                    .yolo(true)
                    .timeout(DEFAULT_TIMEOUT)
                    .build();
            case "codex" -> CodexAgentOptions.builder()
                    .model(model)
                    .fullAuto(true)
                    .approvalPolicy(ApprovalPolicy.NEVER)
                    .timeout(DEFAULT_TIMEOUT)
                    .build();
            default -> throw new IllegalArgumentException("Unknown agent: " + agentId);
        };
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.tier.TierOptionsFactoryTest"`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/tier/TierOptionsFactory.java \
        src/test/java/io/github/samzhu/grimo/agent/tier/TierOptionsFactoryTest.java
git commit -m "feat(f3): add TierOptionsFactory for per-request model override"
```

---

### Task 5.5: TierConfiguration — Spring Bean 定義

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/tier/TierConfiguration.java`

所有 tier 相關 bean 集中在此 `@Configuration` 類別定義，解決 DI 問題。

- [ ] **Step 1: Create TierConfiguration**

```java
package io.github.samzhu.grimo.agent.tier;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Tier 系統的 Spring Bean 定義。
 *
 * 設計說明：
 * - TierRouter, TierKeywordDetector, TierOptionsFactory 都是 plain Java objects，
 *   透過 @Bean 工廠方法建立（Library over Starter 原則）
 * - sessionTier 用 AtomicReference 在 TierCommands 和 GrimoTuiRunner 間共享
 */
@Configuration
public class TierConfiguration {

    @Bean
    public TierRouter tierRouter(AgentModelRegistry registry, GrimoConfig config) {
        return new TierRouter(registry, config);
    }

    @Bean
    public TierKeywordDetector tierKeywordDetector(GrimoConfig config) {
        return new TierKeywordDetector(config.getTierKeywords());
    }

    @Bean
    public TierOptionsFactory tierOptionsFactory() {
        return new TierOptionsFactory();
    }

    /**
     * Session 級 tier 狀態：/tier 指令設定，持續到下次切換。
     * GrimoTuiRunner 和 TierCommands 共享此 reference。
     */
    @Bean
    public AtomicReference<Tier> sessionTier() {
        return new AtomicReference<>(null);
    }
}
```

- [ ] **Step 2: Run build to verify DI wiring**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/tier/TierConfiguration.java
git commit -m "feat(f3): add TierConfiguration for Spring bean definitions"
```

---

### Task 6: TierCommands — /tier 與 /skill-tier 指令

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/tier/TierCommands.java`
- Create: `src/test/java/io/github/samzhu/grimo/agent/tier/TierCommandsTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package io.github.samzhu.grimo.agent.tier;

import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TierCommandsTest {

    @TempDir
    Path tempDir;

    private GrimoConfig config;
    private TierCommands commands;
    // TierCommands 需要一個 session tier holder，用 AtomicReference<Tier> 模擬
    private java.util.concurrent.atomic.AtomicReference<Tier> sessionTierRef;

    @BeforeEach
    void setUp() throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            skill-tiers:
              lite:
                - agent: gemini
                  model: gemini-2.5-flash
              std:
                - agent: claude
                  model: claude-sonnet-4
              pro:
                - agent: claude
                  model: claude-opus-4
            """);
        config = new GrimoConfig(configFile);
        sessionTierRef = new java.util.concurrent.atomic.AtomicReference<>(null);
        commands = new TierCommands(config, sessionTierRef);
    }

    @Test
    void tierShowShouldDisplayCurrentTier() {
        String output = commands.tier(null);
        assertThat(output).contains("std"); // default
    }

    @Test
    void tierSetShouldUpdateSessionTier() {
        String output = commands.tier("pro");
        assertThat(output).contains("pro");
        assertThat(sessionTierRef.get()).isEqualTo(Tier.PRO);
    }

    @Test
    void tierSetInvalidShouldWarnUser() {
        String output = commands.tier("invalid");
        assertThat(output).contains("Unknown tier");
        assertThat(sessionTierRef.get()).isNull(); // unchanged
    }

    @Test
    void skillTierShouldSetOverride() {
        String output = commands.skillTier("deep-research", "pro");
        assertThat(output).contains("deep-research").contains("pro");

        var overrides = config.getSkillOverrides();
        assertThat(overrides).containsKey("deep-research");
        assertThat(overrides.get("deep-research").get("tier")).isEqualTo("pro");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.tier.TierCommandsTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Implement TierCommands**

```java
package io.github.samzhu.grimo.agent.tier;

import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.springframework.lang.Nullable;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tier 相關 CLI 指令。
 *
 * 設計說明：
 * - /tier：查看或切換 session tier（持續到下次切換）
 * - /skill-tier：覆寫特定 skill 的 tier（寫入 config 永久保存）
 * - session tier 存在 AtomicReference 中，GrimoTuiRunner 建立並注入
 */
@Component
public class TierCommands {

    private final GrimoConfig config;
    private final AtomicReference<Tier> sessionTier;

    public TierCommands(GrimoConfig config, AtomicReference<Tier> sessionTier) {
        this.config = config;
        this.sessionTier = sessionTier;
    }

    /**
     * 查看或設定 session tier。
     * /tier       → 顯示目前 tier
     * /tier pro   → 設定 session tier 為 pro
     */
    private static final java.util.Set<String> VALID_TIERS = java.util.Set.of("lite", "std", "pro");

    @Command(name = "tier", description = "View or set the session tier (lite/std/pro)")
    public String tier(@Nullable String level) {
        if (level == null || level.isBlank()) {
            Tier current = sessionTier.get();
            String tierName = current != null ? current.value() : "std (default)";
            return "Current session tier: " + tierName;
        }

        if (!VALID_TIERS.contains(level.strip().toLowerCase())) {
            return "Unknown tier: '" + level + "'. Valid values: lite, std, pro";
        }

        Tier newTier = Tier.fromString(level);
        sessionTier.set(newTier);
        return newTier.icon() + " Session tier set to: " + newTier.value();
    }

    /**
     * 覆寫特定 skill 的 tier（永久寫入 config.yaml）。
     * /skill-tier deep-research pro
     */
    @Command(name = "skill-tier", description = "Override tier for a specific skill")
    public String skillTier(String skillName, String tierLevel) {
        Tier tier = Tier.fromString(tierLevel);
        config.setSkillOverride(skillName, Map.of("tier", tier.value()));
        return "Skill '" + skillName + "' tier override set to: " + tier.value()
                + " (saved to config.yaml)";
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.tier.TierCommandsTest"`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/tier/TierCommands.java \
        src/test/java/io/github/samzhu/grimo/agent/tier/TierCommandsTest.java
git commit -m "feat(f3): add /tier and /skill-tier commands"
```

---

### Task 7: GrimoTuiRunner 整合 TierRouter

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`

這是 F3 的核心整合 task。修改 `processInput()` 的 AI 對話分支，用 TierRouter + TierOptionsFactory 取代原本的 `agentRouter.route(null)`。

- [ ] **Step 1: 新增 TierRouter 相關 field 和 constructor 參數**

在 `GrimoTuiRunner.java` 新增：

```java
// 現有 import 區新增：
import io.github.samzhu.grimo.agent.tier.Tier;
import io.github.samzhu.grimo.agent.tier.TierKeywordDetector;
import io.github.samzhu.grimo.agent.tier.TierOptionsFactory;
import io.github.samzhu.grimo.agent.tier.TierRouter;
import io.github.samzhu.grimo.agent.tier.TierSelection;
import java.util.concurrent.atomic.AtomicReference;

// 現有 field 區新增（注意：所有 TierConfiguration @Bean 透過 constructor injection）：
private final TierRouter tierRouter;
private final TierKeywordDetector tierKeywordDetector;
private final TierOptionsFactory tierOptionsFactory;
private final AtomicReference<Tier> sessionTier;  // 來自 TierConfiguration @Bean

/** 本輪 tier（顯示用）：每次 processInput 重設 */
private volatile TierSelection currentTierSelection;

// 將 statusView 從 run() 的 local variable 提升為 field（原本是 var statusView = new GrimoStatusView(...)）
private GrimoStatusView statusView;
// 快取 status bar 原始文字（agent 完成後恢復用）
private String originalStatusText;
```

在 constructor 新增參數（全部透過 Spring DI 注入，來自 `TierConfiguration` @Bean）：

```java
public GrimoTuiRunner(Terminal terminal,
                       // ... 既有參數 ...
                       TierRouter tierRouter,
                       TierKeywordDetector tierKeywordDetector,
                       TierOptionsFactory tierOptionsFactory,
                       AtomicReference<Tier> sessionTier) {
    // ... 既有初始化 ...
    this.tierRouter = tierRouter;
    this.tierKeywordDetector = tierKeywordDetector;
    this.tierOptionsFactory = tierOptionsFactory;
    this.sessionTier = sessionTier;
}
```

同時在 `run()` 方法中，將 `var statusView = new GrimoStatusView(statusText)` 改為：
```java
this.statusView = new GrimoStatusView(statusText);
this.originalStatusText = statusText;
```

- [ ] **Step 2: 修改 processInput() AI 對話分支**

將 `processInput()` 中 line 492 附近的：

```java
var model = agentRouter.route(null);
```

替換為 TierRouter 流程：

```java
// --- Tier 路由：決定用哪個 agent + model ---
var keywordTier = tierKeywordDetector.detect(text).orElse(null);
var tierCtx = TierRouter.Context.builder()
        .keywordTier(keywordTier)
        .sessionTier(sessionTier.get())
        .build();
var tierSelection = tierRouter.resolve(tierCtx);
currentTierSelection = tierSelection;

var model = agentModelRegistry.get(tierSelection.agentId());
if (model == null) {
    throw new IllegalStateException("Agent not found: " + tierSelection.agentId());
}
var tierOptions = tierOptionsFactory.build(tierSelection.agentId(), tierSelection.model());
```

然後修改 `AgentClient.builder` 區塊：

```java
// 設計說明：
// - defaultWorkingDirectory(projectDir) 在 builder 層設定 workingDir
// - client.run(text, tierOptions) 的 tierOptions 覆寫 defaultOptions 的 model
// - SDK 內部：run(text, opts) → Goal(text, null, opts) → determineWorkingDir 讀 builder default
// - AgentClientRequestSpec 沒有 .options() 方法，只能透過 run(String, AgentOptions) 傳入
var client = AgentClient.builder(model)
        .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
        .defaultMcpServers(mcpCatalogBuilder.getServerNames())
        .defaultWorkingDirectory(projectDir)
        .build();
var response = client.run(text, tierOptions);
```

- [ ] **Step 3: 更新 Status Bar 顯示 tier 資訊**

在 agent 啟動前更新 status bar：

```java
// 更新 status bar 顯示 tier
String tierLabel = tierSelection.tier().icon() + " " + tierSelection.tier().value();
if (keywordTier != null) {
    tierLabel += " (本輪)";
}
String newStatus = tierLabel + " · " + tierSelection.agentId() + " · "
        + tierSelection.model() + " │ " + workspacePath
        + " │ " + agentCount + " agent · " + skillCount + " skill · "
        + mcpCount + " mcp · " + taskCount + " task";
statusView.setStatusText(newStatus);
eventLoop.setDirty();
```

agent 完成後在 `finally` 區恢復原始 status（`statusView` 和 `originalStatusText` 已是 field）：

```java
// finally 區新增：恢復 status bar
currentTierSelection = null;
statusView.setTierDisplay(null, null);
statusView.setStatusText(originalStatusText);
```

- [ ] **Step 4: 驗證 DI wiring**

`AtomicReference<Tier> sessionTier` 已在 Task 5.5 的 `TierConfiguration` 中定義為 `@Bean`。
`TierRouter`, `TierKeywordDetector`, `TierOptionsFactory` 也在同一 `@Configuration` 中定義。
`GrimoTuiRunner` 和 `TierCommands` 都透過 constructor injection 注入這些 bean。

- [ ] **Step 5: Run full test suite**

Run: `./gradlew test`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java \
        src/main/java/io/github/samzhu/grimo/GrimoStatusView.java
git commit -m "feat(f3): integrate TierRouter into GrimoTuiRunner processInput flow"
```

---

### Task 8: GrimoStatusView 增強 — tier icon 渲染

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStatusView.java`

- [ ] **Step 1: 增強 render() 支援 tier icon 色彩**

目前 `GrimoStatusView` 統一用 gray（foreground 245）。加入 tier icon 彩色渲染：

```java
package io.github.samzhu.grimo;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.List;

/**
 * Status 區：顯示 agent/model/workspace/計數資訊（1 行）。
 *
 * 設計說明：
 * - 一般對話：灰色文字
 * - Skill 執行時：tier icon 用對應色彩（lite=green, std=gray, pro=yellow）
 */
public class GrimoStatusView {

    private static final AttributedStyle STATUS_STYLE = AttributedStyle.DEFAULT.foreground(245);
    private static final AttributedStyle LITE_STYLE = AttributedStyle.DEFAULT.foreground(2);   // green
    private static final AttributedStyle PRO_STYLE = AttributedStyle.DEFAULT.foreground(3);    // yellow

    private String statusText;
    private String tierIcon;   // null = 不顯示 tier icon
    private String tierStyle;  // "lite" / "std" / "pro"

    public GrimoStatusView(String statusText) {
        this.statusText = statusText;
    }

    public void setStatusText(String statusText) {
        this.statusText = statusText;
    }

    /**
     * 設定 tier 顯示資訊。傳 null 清除。
     */
    public void setTierDisplay(String icon, String style) {
        this.tierIcon = icon;
        this.tierStyle = style;
    }

    public List<AttributedString> render(int cols) {
        if (tierIcon != null && tierStyle != null) {
            var builder = new AttributedStringBuilder();
            var iconStyle = switch (tierStyle) {
                case "lite" -> LITE_STYLE;
                case "pro" -> PRO_STYLE;
                default -> STATUS_STYLE;
            };
            builder.styled(iconStyle, tierIcon + " ");
            builder.styled(STATUS_STYLE, statusText);
            String result = builder.toAttributedString().toAnsi();
            // 截斷至 cols
            var full = builder.toAttributedString();
            return List.of(full);
        }

        String text = statusText;
        if (text.length() > cols) {
            text = text.substring(0, cols);
        }
        return List.of(new AttributedString(text, STATUS_STYLE));
    }
}
```

- [ ] **Step 2: Write GrimoStatusView tests**

Create: `src/test/java/io/github/samzhu/grimo/GrimoStatusViewTest.java`

```java
package io.github.samzhu.grimo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrimoStatusViewTest {

    @Test
    void renderWithoutTierShouldShowPlainText() {
        var view = new GrimoStatusView("claude · sonnet-4 │ ~/project");
        var lines = view.render(80);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().toString()).contains("claude");
    }

    @Test
    void renderWithTierShouldIncludeIcon() {
        var view = new GrimoStatusView("pro · claude · opus-4 │ ~/project");
        view.setTierDisplay("\uD83E\uDDE0", "pro");
        var lines = view.render(80);
        assertThat(lines).hasSize(1);
        // tier icon 會被 prepend 到 status text 前
    }

    @Test
    void renderClearedTierShouldRevertToPlain() {
        var view = new GrimoStatusView("claude · sonnet-4");
        view.setTierDisplay("⚡", "lite");
        view.setTierDisplay(null, null); // clear
        var lines = view.render(80);
        assertThat(lines).hasSize(1);
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.GrimoStatusViewTest"`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoStatusView.java \
        src/test/java/io/github/samzhu/grimo/GrimoStatusViewTest.java
git commit -m "feat(f3): add tier icon and color to GrimoStatusView"
```

---

### Task 9: SkillAnalyzer — 安裝時自動分析 Skill 複雜度

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/skill/analyzer/SkillAnalyzer.java`
- Create: `src/main/java/io/github/samzhu/grimo/skill/analyzer/package-info.java`
- Create: `src/test/java/io/github/samzhu/grimo/skill/analyzer/SkillAnalyzerTest.java`
- Modify: `src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java`

- [ ] **Step 1: Write the failing tests**

設計說明：SkillAnalyzer 的核心邏輯是 JSON 解析，agent 呼叫是外部依賴。
測試策略：將 `parseResponse()` 設為 package-private 以便直接測試解析邏輯，避免 mock AgentClient 層。

```java
package io.github.samzhu.grimo.skill.analyzer;

import io.github.samzhu.grimo.agent.tier.Tier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillAnalyzerTest {

    @Test
    void parseResponseShouldExtractProTier() {
        var result = SkillAnalyzer.parseResponse("""
            { "tier": "pro", "reason": "多步驟研究流程" }
            """);
        assertThat(result.tier()).isEqualTo(Tier.PRO);
        assertThat(result.reason()).contains("多步驟");
    }

    @Test
    void parseResponseShouldExtractLiteTier() {
        var result = SkillAnalyzer.parseResponse("""
            { "tier": "lite", "reason": "simple query" }
            """);
        assertThat(result.tier()).isEqualTo(Tier.LITE);
    }

    @Test
    void parseResponseShouldReturnStdWhenInvalid() {
        var result = SkillAnalyzer.parseResponse("invalid response");
        assertThat(result.tier()).isEqualTo(Tier.STD);
    }

    @Test
    void parseResponseShouldReturnStdWhenNull() {
        var result = SkillAnalyzer.parseResponse(null);
        assertThat(result.tier()).isEqualTo(Tier.STD);
    }

    @Test
    void parseResponseShouldHandleJsonWithExtraText() {
        // agent 可能回傳額外說明文字
        var result = SkillAnalyzer.parseResponse("""
            Based on my analysis:
            { "tier": "pro", "reason": "cross-file refactoring needed" }
            Hope this helps!
            """);
        assertThat(result.tier()).isEqualTo(Tier.PRO);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.skill.analyzer.SkillAnalyzerTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Implement SkillAnalyzer**

```java
package io.github.samzhu.grimo.skill.analyzer;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.tier.Tier;
import io.github.samzhu.grimo.agent.tier.TierOptionsFactory;
import io.github.samzhu.grimo.agent.tier.TierRouter;
import io.github.samzhu.grimo.agent.tier.TierSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agents.client.AgentClient;
import org.springaicommunity.agents.model.AgentModel;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安裝 Skill 時用 lite 等級的 agent 自動分析 Skill 複雜度。
 *
 * 設計說明：
 * - 使用 lite tier 的 agent 分析（省成本）
 * - 送出標準 prompt，要求回傳 JSON { "tier": "...", "reason": "..." }
 * - 解析失敗時回傳 STD（安全預設）
 * - 如果 Skill 已標 grimo.tier → 跳過分析，尊重作者判斷
 */
public class SkillAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SkillAnalyzer.class);

    private static final String ANALYSIS_PROMPT = """
        分析以下 SKILL.md 的執行複雜度，判定為 lite / std / pro 三級之一：

        lite: 簡單查詢、格式轉換、單步操作
        std:  功能開發、bug 修復、單檔重構、一般 review
        pro:  多步驟工作流、跨檔案重構、深度分析、需要規劃能力

        SKILL.md 內容：
        %s

        回傳 JSON: { "tier": "pro", "reason": "多步驟研究流程，需要交叉比對多來源" }
        只回傳 JSON，不要其他文字。
        """;

    private static final Pattern TIER_PATTERN = Pattern.compile("\"tier\"\\s*:\\s*\"(lite|std|pro)\"");
    private static final Pattern REASON_PATTERN = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]+)\"");

    private final TierRouter tierRouter;
    private final TierOptionsFactory optionsFactory;
    private final AgentModelRegistry registry;
    private final Path workingDirectory;

    public SkillAnalyzer(TierRouter tierRouter, TierOptionsFactory optionsFactory,
                         AgentModelRegistry registry, Path workingDirectory) {
        this.tierRouter = tierRouter;
        this.optionsFactory = optionsFactory;
        this.registry = registry;
        this.workingDirectory = workingDirectory;
    }

    /**
     * 分析 Skill body，回傳建議的 Tier 和原因。
     */
    public AnalysisResult analyze(String skillBody) {
        try {
            // 用 lite tier 的 agent 分析
            var ctx = TierRouter.Context.builder().skillTier("lite").build();
            TierSelection selection = tierRouter.resolve(ctx);

            AgentModel agentModel = registry.get(selection.agentId());
            if (agentModel == null) {
                log.warn("No agent available for skill analysis, defaulting to std");
                return new AnalysisResult(Tier.STD, "No agent available for analysis");
            }

            var client = AgentClient.builder(agentModel)
                    .defaultWorkingDirectory(workingDirectory)
                    .build();
            var options = optionsFactory.build(selection.agentId(), selection.model());
            var response = client.run(ANALYSIS_PROMPT.formatted(skillBody), options);

            return parseResponse(response.getResult());
        } catch (Exception e) {
            log.warn("Skill analysis failed, defaulting to std: {}", e.getMessage());
            return new AnalysisResult(Tier.STD, "Analysis failed: " + e.getMessage());
        }
    }

    // package-private for testability — 核心解析邏輯不依賴 AgentClient
    static AnalysisResult parseResponse(String response) {
        if (response == null) {
            return new AnalysisResult(Tier.STD, "Empty response");
        }
        Matcher tierMatcher = TIER_PATTERN.matcher(response);
        if (tierMatcher.find()) {
            Tier tier = Tier.fromString(tierMatcher.group(1));
            String reason = "auto-analyzed";
            Matcher reasonMatcher = REASON_PATTERN.matcher(response);
            if (reasonMatcher.find()) {
                reason = reasonMatcher.group(1);
            }
            return new AnalysisResult(tier, reason);
        }
        return new AnalysisResult(Tier.STD, "Could not parse tier from response");
    }

    public record AnalysisResult(Tier tier, String reason) {}
}
```

- [ ] **Step 4: Create package-info.java**

```java
@org.springframework.modulith.NamedInterface("analyzer")
package io.github.samzhu.grimo.skill.analyzer;
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.skill.analyzer.SkillAnalyzerTest"`
Expected: ALL PASS

- [ ] **Step 6: Modify SkillCommands — install 加入自動分析**

在 `SkillCommands.java` 的 `install()` 方法中，在 `registry.register(skill)` 之前加入自動分析：

```java
// 在 var skill = loader.load(skillMd); 之後：
// 自動分析 tier（如果 Skill 沒有標 grimo.tier）
if (skill.grimoTier().equals("std") && skill.metadata().get("grimo.tier") == null) {
    try {
        var analysisResult = skillAnalyzer.analyze(skill.body());
        // 回寫 tier 到 SKILL.md frontmatter
        // 設計說明：用字串操作在 frontmatter 末尾（--- 之前）插入 grimo.tier
        String content = Files.readString(skillMd);
        int secondDash = content.indexOf("---", content.indexOf("---") + 3);
        if (secondDash > 0) {
            String withTier = content.substring(0, secondDash)
                    + "  grimo.tier: " + analysisResult.tier().value() + "\n"
                    + content.substring(secondDash);
            Files.writeString(skillMd, withTier);
            // 重新載入 skill 以反映新 metadata
            skill = loader.load(skillMd);
        }
    } catch (Exception e) {
        // 分析失敗不影響安裝，使用預設 std
    }
}
```

完整修改需在 `SkillCommands` constructor 注入 `SkillAnalyzer`，並新增寫入 metadata 的邏輯。

- [ ] **Step 7: Modify SkillCommands — skill-list 顯示 tier**

修改 `list()` 方法的表頭和格式：

```java
sb.append(String.format("  %-15s %-6s %-10s %-15s %-8s%n",
        "NAME", "TIER", "VERSION", "AUTHOR", "STATUS"));
for (SkillDefinition s : skills) {
    sb.append(String.format("  %-15s %-6s %-10s %-15s %-8s%n",
        s.name(), s.grimoTier(), s.grimoVersion(), s.grimoAuthor(), "loaded"));
}
```

- [ ] **Step 8: Run full test suite**

Run: `./gradlew test`
Expected: ALL PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/skill/analyzer/ \
        src/test/java/io/github/samzhu/grimo/skill/analyzer/ \
        src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java
git commit -m "feat(f3): add SkillAnalyzer for auto tier detection on skill install"
```

---

### Task 10: Glossary 更新

**Files:**
- Modify: `docs/glossary.md`

- [ ] **Step 1: 更新調度系統術語表**

在 `docs/glossary.md` 的「調度系統術語」表格中，更新 Tier 條目並新增相關元件：

```markdown
| **TierRouter** | Tier Router | 解析 tier 來源（6 級優先順序：關鍵字 > session /tier > skill-overrides > skill metadata > 自動分析 > 預設 std），查 fallback list（每級多組 agent+model，依序 isAvailable()），回傳 `TierSelection(agentId, model, tier, source)`。 |
| **TierOptionsFactory** | Tier Options Factory | 根據 agentId 建構對應的 per-request `AgentOptions` 子型別（ClaudeAgentOptions / GeminiAgentOptions / CodexAgentOptions），含 tier 選定的 model。在 `AgentClient.run(goalText, agentOptions)` 傳入以覆寫 defaultOptions。 |
| **TierKeywordDetector** | Tier Keyword Detector | 從使用者輸入偵測 tier 關鍵字（如「仔細想」→ pro）。只影響該輪，不改 session 設定。多 tier 同時匹配取最高（PRO > STD > LITE）。 |
| **SkillAnalyzer** | Skill Analyzer | 安裝 Skill 時用 lite tier agent 自動分析 Skill body 複雜度，判定 tier 並寫入 metadata。已標 grimo.tier 的 Skill 跳過分析。 |
```

在「Agent 技術元件對應」表格新增：

```markdown
| Tier 路由 | `TierRouter` | 6 級優先順序 → fallback list → `AgentModel.isAvailable()` |
| Tier 選項 | `TierOptionsFactory` | `AgentClient.run(goal, agentOptions)` per-request model 覆寫 |
| Tier 偵測 | `TierKeywordDetector` | config.yaml `tier-keywords` 字串比對 |
| Tier 指令 | `TierCommands` | Spring Shell @Command: `/tier`, `/skill-tier` |
| Skill 分析 | `SkillAnalyzer` | AgentClient + lite tier → JSON 回應解析 |
```

- [ ] **Step 2: Commit**

```bash
git add docs/glossary.md
git commit -m "docs: update glossary with F3 Tier system terminology"
```

---

### Task 11: 整合測試 + config.yaml 預設範本

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/workspace/WorkspaceManager.java` — DEFAULT_CONFIG 加入 tier 範本

- [ ] **Step 1: 確認 WorkspaceManager 的 DEFAULT_CONFIG**

讀取 `WorkspaceManager.java`，找到 DEFAULT_CONFIG 定義，加入預設的 tier config：

```yaml
# Skill 三級對應表（每級多選項，按優先順序 fallback）
skill-tiers:
  lite:
    - agent: gemini
      model: gemini-2.5-flash
    - agent: claude
      model: claude-haiku-4
    - agent: codex
      model: o4-mini
  std:
    - agent: claude
      model: claude-sonnet-4
    - agent: gemini
      model: gemini-2.5-pro
    - agent: codex
      model: o4-mini
  pro:
    - agent: claude
      model: claude-opus-4
    - agent: gemini
      model: gemini-2.5-pro
    - agent: codex
      model: o3

# Tier 關鍵字觸發（per-turn）
tier-keywords:
  pro:
    - 仔細想
    - 深入分析
    - 好好想
    - think hard
    - think deeply
  lite:
    - 快速
    - 簡單說
    - 大概看一下
    - quickly
    - briefly
```

- [ ] **Step 2: 驗證 Spring Modulith 模組邊界**

`skill.analyzer` 套件依賴 `agent.tier`（跨模組）。確認 `@NamedInterface("tier")` 在 `agent.tier.package-info.java` 上正確宣告，使此依賴合法。

執行既有的 Modulith 結構測試：
Run: `./gradlew test --tests "io.github.samzhu.grimo.ModulithStructureTest"`
Expected: PASS — 無非法跨模組依賴

- [ ] **Step 3: Run full test suite**

Run: `./gradlew test`
Expected: ALL PASS

- [ ] **Step 4: Run application manually to verify**

Run: `./gradlew bootRun`
- 輸入 `/tier` → 應顯示 "Current session tier: std (default)"
- 輸入 `/tier pro` → 應顯示 "🧠 Session tier set to: pro"
- 輸入 `/tier` → 應顯示 "Current session tier: pro"
- 輸入 `/skill-list` → 應顯示 TIER 欄位

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/workspace/WorkspaceManager.java
git commit -m "feat(f3): add default tier config to workspace initialization"
```

---

## Sequence Diagram — Tier 路由流程

```
User                GrimoTuiRunner          TierKeywordDetector    TierRouter             TierOptionsFactory    AgentClient
 │                       │                        │                     │                        │                  │
 │  "仔細想 重構這段"     │                        │                     │                        │                  │
 │──────────────────────>│                        │                     │                        │                  │
 │                       │  detect("仔細想 重構")  │                     │                        │                  │
 │                       │───────────────────────>│                     │                        │                  │
 │                       │  Optional(PRO)         │                     │                        │                  │
 │                       │<───────────────────────│                     │                        │                  │
 │                       │                        │                     │                        │                  │
 │                       │  resolve(Context{keyword=PRO, session=null}) │                        │                  │
 │                       │──────────────────────────────────────────────>│                        │                  │
 │                       │                        │  walk pro list      │                        │                  │
 │                       │                        │  claude available?  │                        │                  │
 │                       │                        │  → yes              │                        │                  │
 │                       │  TierSelection(claude, opus-4, PRO, keyword) │                        │                  │
 │                       │<─────────────────────────────────────────────│                        │                  │
 │                       │                        │                     │                        │                  │
 │                       │  build("claude", "claude-opus-4")            │                        │                  │
 │                       │──────────────────────────────────────────────────────────────────────>│                  │
 │                       │  ClaudeAgentOptions(model=opus-4, yolo=true) │                        │                  │
 │                       │<────────────────────────────────────────────────────────────────────-│                  │
 │                       │                        │                     │                        │                  │
 │                       │  client.run("仔細想 重構這段", claudeOptions) │                        │                  │
 │                       │────────────────────────────────────────────────────────────────────────────────────────>│
 │                       │                        │                     │                        │    opus-4 處理   │
 │                       │  AgentClientResponse   │                     │                        │                  │
 │                       │<──────────────────────────────────────────────────────────────────────────────────────-│
 │  顯示結果 + Status Bar│                        │                     │                        │                  │
 │  🧠 pro (本輪) · claude · opus-4              │                     │                        │                  │
 │<──────────────────────│                        │                     │                        │                  │
```

---

## 驗收標準

1. `/tier` 顯示目前 session tier
2. `/tier pro` 切換 session tier，後續 agent 呼叫使用 pro 級 agent+model
3. `/skill-tier deep-research pro` 寫入 config.yaml，永久覆寫該 skill 的 tier
4. 輸入「仔細想 xxx」→ Status Bar 顯示 `🧠 pro (本輪)`，該輪使用 pro agent
5. 輸入「快速 xxx」→ Status Bar 顯示 `⚡ lite`，該輪使用 lite agent
6. `/skill-list` 顯示每個 skill 的 tier 欄位
7. `/skill-install` 安裝未標 tier 的 skill 時，自動分析並寫入 metadata
8. Tier fallback：如果首選 agent 不可用，自動嘗試下一個
9. 無 skill-tiers config 時 → 退回 conversation default agent（向後相容）
10. 所有既有測試通過 + 新增測試全部通過
