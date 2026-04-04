# Model Catalog — CLI 模型清單 + Tier 對應表

> 兩份獨立資料：**CLI 模型清單**（各 CLI 能用的所有模型）+ **Tier 對應表**（推薦的複雜度 → 模型對應）。
> 放在 `application.yaml` 用 `@ConfigurationProperties` 綁定，開發者編譯時期維護。

## 目標

1. **完整模型清單** — 各 CLI 支援的模型全部列出，不替使用者決定能用哪些
2. **Tier 推薦對應** — 根據 benchmark 提供預設的複雜度 → 模型組合，零配置即可用
3. **使用者可覆寫** — `~/.grimo/config.yaml` 的 `tier-models` 優先於內建設定
4. **通用 tier 機制** — 不只 skill 用，龍蝦模式（自動任務執行）等 pipeline 的每個 step 都可依複雜度選模型
5. **開發者可維護** — benchmark 跑分隨模型列在一起，更新時有數據依據

## 設計原則

- 資料放 `application.yaml`，用 Spring Boot `@ConfigurationProperties(prefix = "grimo")` 綁定
- 兩份資料職責分離：`models`（全量模型清單）、`tier-models`（推薦對應）
- 不做 alias 層 — 各 CLI 本身已處理簡寫（Claude: `opus`、Gemini: `pro`）
- 模型 ID 必須是各 CLI 實際接受的值，避免執行失敗
- `skill-tiers` 更名為 `tier-models`（tier 機制不只給 skill 用）
- `~/.grimo/config.yaml` 的 `tier-models` per-tier 優先於 application.yaml

## 資料結構

### application.yaml 新增區段

```yaml
grimo:
  # === CLI 模型清單（各 CLI 支援的所有模型） ===
  # 不過濾 — CLI 能用的都列出來，使用者自行選擇
  # 來源：
  #   Claude — https://platform.claude.com/docs/en/docs/about-claude/models/overview
  #   Gemini — https://ai.google.dev/gemini-api/docs/models
  #            https://geminicli.com/docs/cli/model/
  #   Codex  — https://developers.openai.com/codex/models
  models:
    claude:
      - id: claude-opus-4-6
        swe-bench: "81.4%"
        gpqa: "88.5%"
        pricing: "$5/$25"
        context: "1M"
      - id: claude-sonnet-4-6
        swe-bench: "79.6%"
        gpqa: "74.1%"
        pricing: "$3/$15"
        context: "1M"
      - id: claude-haiku-4-5
        swe-bench: "73.3%"
        pricing: "$1/$5"
        context: "200K"
      - id: claude-sonnet-4-5
        pricing: "$3/$15"
        context: "200K"
      - id: claude-opus-4-5
        pricing: "$5/$25"
        context: "200K"

    gemini:
      - id: gemini-2.5-pro
        swe-bench: "63.2%"
        gpqa: "83.0%"
        pricing: "$1.25/$10"
        context: "1M"
      - id: gemini-2.5-flash
        swe-bench: "60.4%"
        gpqa: "82.8%"
        pricing: "$0.30/$2.50"
        context: "1M"
      - id: gemini-2.5-flash-lite
        pricing: "$0.10/$0.40"
        context: "1M"
      - id: gemini-3-pro-preview
        pricing: "$2/$12"
        context: "1M"
      - id: gemini-3-flash-preview
        pricing: "$0.50/$3"
        context: "1M"

    codex:
      - id: gpt-5.4
        pricing: "$2.50/$15"
        context: "1M"
      - id: gpt-5.4-mini
        pricing: "$0.75/$4.50"
        context: "400K"
      - id: gpt-5.3-codex
        pricing: "—"
        context: "—"
      - id: gpt-5-codex
        pricing: "—"
        context: "—"

  # === Agent 預設模型 ===
  # /agent-use <agent> 無指定 model 時使用
  defaults:
    claude: claude-sonnet-4-6
    gemini: gemini-2.5-pro
    codex: gpt-5.4

  # === Tier → Model 推薦對應（TierRouter 消費） ===
  # 每 tier 按優先順序排列，walk 時選第一個可用 agent
  # 命名 tier-models（非 skill-tiers）：tier 機制不只給 skill 用，
  # 龍蝦模式 pipeline 每個 step 都依複雜度選模型
  tier-models:
    lite:
      - agent: gemini
        model: gemini-2.5-flash-lite
      - agent: claude
        model: claude-haiku-4-5
      - agent: codex
        model: gpt-5.4-mini
    std:
      - agent: claude
        model: claude-sonnet-4-6
      - agent: gemini
        model: gemini-2.5-flash
      - agent: codex
        model: gpt-5.4-mini
    pro:
      - agent: claude
        model: claude-opus-4-6
      - agent: gemini
        model: gemini-2.5-pro
      - agent: codex
        model: gpt-5.4
```

### 分級依據

| Tier | 定位 | 選模型標準 | 適用場景 |
|------|------|-----------|---------|
| **LITE ⚡** | 低延遲、低成本 | pricing < $1/M input | 翻譯、摘要、格式化、快速查詢 |
| **STD ⚙** | 品質/成本平衡 | SWE-bench > 60% | 日常 coding、debug、code review |
| **PRO 🧠** | 品質優先 | 該 provider 最強模型 | 架構設計、複雜演算法、深度推理 |

### 兩份資料的關係

```
grimo.models（全量 CLI 模型清單）──┐
                                  ├─→ 分類矩陣
grimo.tier-models（推薦對應）────┘

消費場景：
1. TierRouter       → 查 tier-models，自動選模型
2. /agent-use       → 查 defaults，取預設模型
3. AgentConfiguration → 查 defaults，建立 AgentModel 時的預設
4. 使用者參考        → 看 models 完整清單，自行決定用哪個
5. 龍蝦模式（未來）  → 每個 step 查 tier-models，依複雜度派工
```

## 程式碼變更

### 1. GrimoProperties.java（新增）

```java
package io.github.samzhu.grimo.config;

/**
 * 內建設定：從 application.yaml grimo.* 綁定。
 *
 * 設計說明：
 * - 與 GrimoConfig（讀 ~/.grimo/config.yaml 使用者設定）職責分離
 * - GrimoProperties = 開發者維護的 built-in defaults
 * - GrimoConfig = 使用者的客製覆寫
 * - @ConfigurationProperties 由 Spring Boot 自動綁定，type-safe
 */
@Component
@ConfigurationProperties(prefix = "grimo")
public class GrimoProperties {

    /** CLI 模型清單：agent → List<ModelEntry> */
    private Map<String, List<ModelEntry>> models;

    /** Agent 預設模型：agent → modelId */
    private Map<String, String> defaults;

    /** Tier 對應表：tier → List<TierEntry> */
    private Map<String, List<TierEntry>> tierModels;

    public record ModelEntry(String id, String sweBench, String gpqa,
                             String pricing, String context) {}

    public record TierEntry(String agent, String model) {}

    // getters/setters（Spring Boot binding 需要）
}
```

### 2. TierRouter.java（改）

```java
// 注入 GrimoProperties（新增）
private final GrimoProperties grimoProperties;

// skill-tiers → tier-models 更名
// per-tier fallback：user config > built-in
private TierSelection walkFallbackList(Tier tier, String source) {
    // 優先：使用者 config.yaml 的 tier-models
    var configTiers = config.getTierModels();  // renamed from getSkillTiers
    List<Map<String, String>> fallbackList = configTiers.get(tier.value());

    if (fallbackList == null || fallbackList.isEmpty()) {
        // fallback：application.yaml 的 grimo.tier-models
        var builtIn = grimoProperties.getTierModels();
        var entries = builtIn.get(tier.value());
        if (entries != null) {
            fallbackList = entries.stream()
                .map(e -> Map.of("agent", e.agent(), "model", e.model()))
                .toList();
        }
    }
    // ... 原有 walk 邏輯不變
}
```

### 3. AgentCommands.java（改）

```java
// 刪除硬編碼
// - 刪除 static final RECOMMENDED_MODELS
// - 刪除 static final MODEL_ALIASES
// - 刪除 resolveAlias() 方法

// 注入 GrimoProperties
private final GrimoProperties grimoProperties;

// 取預設模型：
// 原本：RECOMMENDED_MODELS.getOrDefault(id, "")
// 改為：grimoProperties.getDefaults().getOrDefault(id, "unknown")

// 模型解析：使用者輸入的 model hint 直接 passthrough 給 CLI
// CLI 自己處理 alias（opus → claude-opus-4-6 等）
```

### 4. AgentConfiguration.java（改）

```java
// 三個 agent 的 hardcoded default 改讀 properties
private final GrimoProperties grimoProperties;

// Claude — 原本：if (model == null) model = "claude-sonnet-4-6";
if (model == null) model = grimoProperties.getDefaults().get("claude");

// Gemini — 原本：if (model == null) model = "gemini-2.5-pro";
if (model == null) model = grimoProperties.getDefaults().get("gemini");

// Codex — 原本：if (model == null) model = "o4-mini";
if (model == null) model = grimoProperties.getDefaults().get("codex");
```

### 5. GrimoConfig.java（改）

```java
// 方法更名：語意從 skill 專用改為通用
// getSkillTiers() → getTierModels()
// 讀的 config.yaml key 也從 skill-tiers → tier-models

public synchronized Map<String, List<Map<String, String>>> getTierModels() {
    var data = load();
    var tiers = (Map<String, List<Map<String, String>>>) data.get("tier-models");
    return tiers != null ? tiers : Map.of();
}
```

### 6. GrimoHome.java（改）

DEFAULT_CONFIG 更名 + 改為註解引導：

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

### 7. 其他檔案引用更新

| 檔案 | 變更 |
|------|------|
| `TierConfiguration.java` | `tierRouter()` bean 加入 `GrimoProperties` 參數 |
| `TuiAdapter.java` | `AgentCommands.RECOMMENDED_MODELS` → `grimoProperties.getDefaults()` |
| `TuiEventBridge.java` | 同上 |
| `TierCommands.java` | 如有引用 `skill-tiers` 字串需更名 |
| 所有引用 `getSkillTiers()` 的地方 | → `getTierModels()` |

## 流程

```
Skill 安裝 / 龍蝦模式 step 分派
  → 決定 tier（keyword / session / skill-metadata / 自動分析 / default）

TierRouter.resolve(tier=pro)
  → config.getTierModels().get("pro") 有值？
     ├─ 有 → 用使用者自訂 fallback list（尊重客製）
     └─ 沒有 → grimoProperties.getTierModels().get("pro")
        → [claude-opus-4-6, gemini-2.5-pro, gpt-5.4]
  → walk fallback list
  → 選第一個 registry.isAvailable() 的 agent
  → 回傳 TierSelection(claude, claude-opus-4-6, PRO, "built-in")

使用者覆寫
  → /skill-tier my-skill lite   （per-skill 覆寫 tier）
  → /agent-use claude opus      （直接指定，CLI 解析 alias）
  → 手動編輯 config.yaml tier-models（per-tier 覆寫）
```

## 不改的部分

| 元件 | 理由 |
|------|------|
| `Tier.java` | Enum 結構不變 |
| `TierKeywordDetector` | 關鍵字偵測不涉及模型對應 |
| `TierSelection` | Record 結構不變 |

## 影響範圍

| 檔案 | 變更類型 |
|------|---------|
| `application.yaml` | 修改 — 新增 `grimo.models` / `grimo.defaults` / `grimo.tier-models` |
| `config/GrimoProperties.java` | 新增 — `@ConfigurationProperties(prefix = "grimo")` |
| `config/GrimoConfig.java` | 修改 — `getSkillTiers()` → `getTierModels()`，YAML key 更名 |
| `agent/tier/TierRouter.java` | 修改 — 注入 GrimoProperties，per-tier fallback |
| `agent/tier/TierConfiguration.java` | 修改 — bean 加入 GrimoProperties 參數 |
| `agent/AgentCommands.java` | 修改 — 刪除 `RECOMMENDED_MODELS` / `MODEL_ALIASES`，改用 GrimoProperties |
| `agent/AgentConfiguration.java` | 修改 — hardcoded defaults 改讀 GrimoProperties |
| `home/GrimoHome.java` | 修改 — DEFAULT_CONFIG `skill-tiers` → `tier-models`，改為註解 |
| `TuiAdapter.java` | 修改 — RECOMMENDED_MODELS 引用改為 GrimoProperties |
| `tui/TuiEventBridge.java` | 修改 — 同上 |
| `agent/tier/TierRouterTest.java` | 修改 — `getSkillTiers()` → `getTierModels()` + 注入 GrimoProperties |
| `config/GrimoConfigTest.java` | 修改 — 測試方法更名對應 |

## 測試

| 案例 | 驗證 |
|------|------|
| GrimoProperties 綁定 | models、defaults、tierModels 正確載入 |
| TierRouter config 優先 | config 有 tier-models → 用 config |
| TierRouter built-in fallback | config 無 tier-models → 用 GrimoProperties |
| TierRouter per-tier fallback | config 只設 pro → pro 用 config，lite/std 用 built-in |
| AgentCommands 預設模型 | defaults.get("claude") = "claude-sonnet-4-6" |
| AgentCommands model passthrough | 使用者輸入 "opus" 直接傳給 CLI，不做 alias 轉換 |
| Codex 預設模型修正 | defaults.get("codex") = "gpt-5.4"（非 o4-mini） |
| Codex model ID 驗證 | 實作時需 `codex --model gpt-5.4 exec "echo test"` 驗證 CLI 接受 |

## 驗收標準

1. `./gradlew build` 通過
2. 新裝 Grimo（無 config.yaml tier-models）→ tier routing 正常
3. 手動配 config.yaml tier-models → 優先使用自訂
4. `AgentCommands` 不再有 `static final Map` 硬編碼
5. Codex 預設模型為 `gpt-5.4`（修正 o4-mini 問題）
6. 模型 ID 全部對應各 CLI 實際支援值

## 未來演進（不在此 MVP）

- **Remote Fetch**：啟動時從 GitHub 拉最新 catalog，失敗用內建版本
- **`/models` 指令**：TUI overlay 顯示完整模型清單 + benchmark
- **龍蝦模式**：pipeline 每個 step 依 tier 自動選模型
  - 參考 OpenClaw Lobster 的 per-step model selection + sub-lobster loop 設計
  - 參考 RouteLLM / LLMRouter 的 complexity-based routing
