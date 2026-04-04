# Model Catalog — Tier 對應表 + Benchmark 參考

> 單一 `model-catalog.yaml` 資源檔作為 tier → model 對應表的 single source of truth，取代散落在多處的硬編碼。

## 目標

1. **Single Source of Truth** — 開發者維護一份 `model-catalog.yaml`，tier fallback、預設模型、別名全部從此衍生
2. **零配置 Tier Routing** — 使用者不碰 config.yaml 也有合理的 tier → model 對應
3. **使用者可覆寫** — config.yaml 的 `skill-tiers` / `skill-overrides` 優先於 catalog
4. **開發者可維護** — benchmark 跑分當註解，更新模型時有數據依據
5. **MVP 先做內建 Resource** — 未來可演進為 remote fetch + local cache（hybrid）

## 設計原則

- Catalog 是編譯時期資源，開發者根據最新 benchmark 定期更新
- 程式消費 `skill-tiers`、`defaults`、`aliases` 三個區段；benchmark 純註解不解析
- Config.yaml 有設 skill-tiers → 用 config（尊重客製）；沒設 → fallback 到 catalog
- 不改 Tier enum 結構，不改 TierKeywordDetector，不改 GrimoConfig

## 資料結構

檔案位置：`src/main/resources/model-catalog.yaml`

```yaml
# model-catalog.yaml — 開發者維護，編譯時期打包
# 最後更新：2026-04-04
# 依據：SWE-bench Verified、GPQA Diamond、pricing（各官方公告）

# === Tier → Model 對應表（TierRouter 消費） ===
# 每個 tier 按優先順序排列，TierRouter walk 時選第一個可用 agent
skill-tiers:
  lite:
    - agent: gemini
      model: gemini-2.5-flash-lite    # $0.10/$0.40
    - agent: claude
      model: claude-haiku-4-5         # SWE 73%, $1/$5
    - agent: codex
      model: gpt-4.1-nano             # $0.10/$0.40
  std:
    - agent: claude
      model: claude-sonnet-4-6        # SWE 80%, $3/$15
    - agent: gemini
      model: gemini-2.5-flash         # SWE 60%, $0.30/$2.50
    - agent: codex
      model: o4-mini                  # SWE 68%, $1.10/$4.40
  pro:
    - agent: claude
      model: claude-opus-4-6          # SWE 81%, $5/$25
    - agent: gemini
      model: gemini-2.5-pro           # SWE 63%, $1.25/$10
    - agent: codex
      model: gpt-5.4                  # $2.50/$15

# === Agent 預設模型（/agent-use 無指定 model 時） ===
defaults:
  claude: claude-sonnet-4-6
  gemini: gemini-2.5-pro
  codex: o4-mini

# === 模型別名（簡寫 → 完整 ID） ===
aliases:
  claude:
    opus: claude-opus-4-6
    sonnet: claude-sonnet-4-6
    haiku: claude-haiku-4-5
  gemini:
    pro: gemini-2.5-pro
    flash: gemini-2.5-flash
    flash-lite: gemini-2.5-flash-lite
  codex:
    gpt-5.4: gpt-5.4
    gpt-5.4-mini: gpt-5.4-mini
    o4-mini: o4-mini
    o3: o3
    gpt-4.1: gpt-4.1
    gpt-4.1-mini: gpt-4.1-mini
    gpt-4.1-nano: gpt-4.1-nano

# === Benchmark 參考（開發者維護依據，程式不消費） ===
# 來源：
#   Claude — https://platform.claude.com/docs/en/docs/about-claude/models/overview
#   Gemini — https://ai.google.dev/gemini-api/docs/models
#   OpenAI — https://developers.openai.com/api/docs/models
#
# 模型                    SWE-bench  GPQA   AIME'24  $/M(in/out)  Context
# ─────────────────────── ───────── ────── ──────── ──────────── ───────
# claude-opus-4-6          81.4%    88.5%    —       $5/$25       1M
# claude-sonnet-4-6        79.6%    74.1%    —       $3/$15       1M
# claude-haiku-4-5         73.3%     —       —       $1/$5        200K
# gemini-2.5-pro           63.2%    83.0%   92.0%    $1.25/$10    1M
# gemini-2.5-flash         60.4%    82.8%   88.0%    $0.30/$2.50  1M
# gemini-2.5-flash-lite     —        —       —       $0.10/$0.40  1M
# gpt-5.4                   —        —       —       $2.50/$15    1M
# gpt-5.4-mini              —        —       —       $0.75/$4.50  400K
# o4-mini                  68.1%    81.4%   92.7%    $1.10/$4.40  200K
# o3                       69.1%    83.3%   88.9%    $2/$8        200K
# gpt-4.1                   —        —       —       $2/$8        1M
# gpt-4.1-mini              —        —       —       $0.15/$0.60  1M
# gpt-4.1-nano              —        —       —       $0.10/$0.40  1M
```

### 欄位說明

| 區段 | 消費者 | 說明 |
|------|--------|------|
| `skill-tiers` | `ModelCatalog` → `TierRouter` | 每 tier 的 agent+model fallback list，按優先順序 |
| `defaults` | `ModelCatalog` → `AgentCommands` | 各 agent 的推薦預設模型 |
| `aliases` | `ModelCatalog` → `AgentCommands` | 簡寫 → 完整 model ID |
| 註解 benchmark | 開發者 | 跑分數據，更新 tier 對應時的依據 |

### Tier 分級依據

| Tier | 定位 | 選模型標準 |
|------|------|-----------|
| **LITE ⚡** | 低延遲、低成本優先 | pricing < $1/M input，能完成簡單任務即可 |
| **STD ⚙** | 品質/成本平衡 | SWE-bench > 60%，pricing 合理（$1-3/M input） |
| **PRO 🧠** | 品質優先 | SWE-bench > 70% 或該 provider 最強模型 |

## 變更

### 1. ModelCatalog.java（新增）

```java
package io.github.samzhu.grimo.agent.catalog;

/**
 * Model Catalog：從 classpath:model-catalog.yaml 載入 tier → model 對應表。
 *
 * 設計說明：
 * - Single source of truth — tier fallback、預設模型、別名全從此衍生
 * - 啟動時載入一次，不可變（immutable）
 * - TierRouter、AgentCommands 注入使用
 * - 未來演進：可換成 remote fetch + local cache
 */
@Component
public class ModelCatalog {

    // 從 classpath:model-catalog.yaml 載���（SnakeYAML，Spring Boot 已內建）
    // @PostConstruct 載入一次，不可變
    private final Map<String, List<Map<String, String>>> skillTiers;
    private final Map<String, String> defaults;
    private final Map<String, Map<String, String>> aliases;

    @PostConstruct
    void init() {
        // ResourceLoader.getResource("classpath:model-catalog.yaml")
        // SnakeYAML 解析（與 GrimoConfig 同一 library）
    }

    /** 指定 tier 的 fallback list（TierRouter ���費） */
    public Map<String, List<Map<String, String>>> getSkillTiers();

    /** Agent 預設模型（AgentCommands、AgentConfiguration 消費） */
    public String getDefaultModel(String agentId);

    /** 別名解析：找不到則 passthrough 原始 hint（AgentCommands 消費） */
    public String resolveAlias(String agentId, String alias);
}
```

### 2. TierRouter.java（改）

```java
// 現有：只查 config
private TierSelection walkFallbackList(Tier tier, String source) {
    var tiers = config.getSkillTiers();
    // ...
}

// 改為：per-tier fallback — config 該 tier 有值就用，沒有才查 catalog
private TierSelection walkFallbackList(Tier tier, String source) {
    var configTiers = config.getSkillTiers();
    List<Map<String, String>> fallbackList = configTiers.get(tier.value());
    if (fallbackList == null || fallbackList.isEmpty()) {
        // 該 tier 在 config 沒設定 → fallback 到 catalog
        fallbackList = modelCatalog.getSkillTiers().get(tier.value());
    }
    // ... 原有 walk 邏輯不變
}
// 注意：per-tier fallback 支援使用者只客製部分 tier（如只覆寫 pro），
// 其餘 tier 仍由 catalog 提供。
```

### 3. AgentCommands.java（改）

```java
// 刪除 static 硬編碼
// - public static final Map<String, String> RECOMMENDED_MODELS = Map.of(...)
// - static final Map<String, String> MODEL_ALIASES = Map.ofEntries(...)

// 改為注入 ModelCatalog
private final ModelCatalog modelCatalog;

// 原本：RECOMMENDED_MODELS.getOrDefault(id, "")
// 改為：modelCatalog.getDefaultModel(id)

// 原本：MODEL_ALIASES.getOrDefault(aliasKey, hint)
// 改為：modelCatalog.resolveAlias(agentId, hint)
```

### 4. AgentConfiguration.java（改）

三個 agent 的預設 model 都改讀 catalog：

```java
// Claude — 原本：if (model == null) model = "claude-sonnet-4-6";
if (model == null) model = modelCatalog.getDefaultModel("claude");

// Gemini — 原本：if (model == null) model = "gemini-2.5-pro";
if (model == null) model = modelCatalog.getDefaultModel("gemini");

// Codex — 原本：if (model == null) model = "o4-mini";
if (model == null) model = modelCatalog.getDefaultModel("codex");
```

### 5. GrimoHome.java（改）

DEFAULT_CONFIG 的 `skill-tiers` 區段改為註解引導：

```yaml
# Skill Tier 對應表
# 預設由內建 model-catalog 提供（零配置即可用）
# 如需客製，取消註解並調整：
#skill-tiers:
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

## 流程

```
Skill 安裝
  → SkillAnalyzer 用 lite agent 分析
  → 寫入 skill metadata: grimo.tier=pro

使用者觸發 Skill
  → TierRouter.resolve()
    → 6 級優先順序解析出 tier=pro
    → config.getSkillTiers().get("pro") 有值？
       ├─ 有 → 用使用者自訂 fallback list（尊重客製）
       └─ 該 tier 沒設 → modelCatalog.getSkillTiers().get("pro")
          → pro: [claude-opus-4-6, gemini-2.5-pro, gpt-5.4]
    → walk fallback list
    → 選第一個 registry.isAvailable() 的 agent
    → 回傳 TierSelection(claude, claude-opus-4-6, PRO, "catalog")

使用者覆寫
  → /skill-tier my-skill lite
  → 寫入 config.yaml skill-overrides.my-skill.tier=lite
  → 下次走 priority 3（skill-override），不查 catalog
```

## 不改的部分

| 元件 | 理由 |
|------|------|
| `Tier.java` | Enum 結構不變，benchmark 參考放 catalog YAML 註解 |
| `TierKeywordDetector` | 關鍵字偵測邏輯不涉及模型對應 |
| `TierSelection` | Record 結構不變 |
| `GrimoConfig` | 讀寫 config.yaml 邏輯不變，只是 skill-tiers 可能為空 |
| `TierCommands` | /tier 和 /skill-tier 指令不變 |

## 影響範圍

| 檔案 | 變更類型 |
|------|---------|
| `model-catalog.yaml` | 新�� — `src/main/resources/` classpath resource |
| `agent/catalog/ModelCatalog.java` | 新增 — SnakeYAML 載入 + 查詢 API |
| `agent/tier/TierRouter.java` | 修改 — 注入 ModelCatalog，per-tier fallback ��輯 |
| `agent/tier/TierConfiguration.java` | 修改 — `tierRouter()` bean 加入 ModelCatalog 參數 |
| `agent/AgentCommands.java` | 修改 — 刪除 `RECOMMENDED_MODELS`/`MODEL_ALIASES` 硬編碼，改用 ModelCatalog |
| `agent/AgentConfiguration.java` | 修改 — 三個 agent 預設 model 改讀 catalog |
| `home/GrimoHome.java` | 修改 — DEFAULT_CONFIG skill-tiers 改為註解引導 |
| `TuiAdapter.java` | 修改 — `AgentCommands.RECOMMENDED_MODELS` 引用改為 `ModelCatalog.getDefaultModel()` |
| `tui/TuiEventBridge.java` | 修改 — 同上，移除對 `AgentCommands.RECOMMENDED_MODELS` 的引用 |

## 測試

| 案例 | 驗證 |
|------|------|
| ModelCatalog 載入 | YAML 正確解析，三個區段都有值 |
| TierRouter config 優先 | config 有 skill-tiers → 用 config，不查 catalog |
| TierRouter catalog fallback | config ��� skill-tiers ��� 用 catalog |
| TierRouter per-tier fallback | config 只設 pro tier → pro 用 config，lite/std 用 catalog |
| AgentCommands 預設模型 | `getDefaultModel("claude")` = `claude-sonnet-4-6` |
| AgentCommands 別名解析 | `resolveAlias("claude", "opus")` = `claude-opus-4-6` |
| 未知別名 passthrough | `resolveAlias("claude", "some-full-id")` = `some-full-id` |

## 驗收標準

1. `./gradlew build` 通過
2. 新裝 Grimo（無 config.yaml skill-tiers）→ tier routing 正常運作
3. 手動配 config.yaml skill-tiers → 優先使用自訂設定
4. `AgentCommands` 不再有 `static final Map` 硬編碼
5. 更新模型只需改 `model-catalog.yaml` 一處

## 未來演進（不在此 MVP）

- **Remote Fetch**：啟動時嘗試從 GitHub raw URL 拉最新 catalog，失敗則用內建版本
- **`/models` 指令**：TUI overlay 顯示可用模型 + benchmark
- **自動 benchmark 更新**：CI job 定期抓各官方 benchmark 更新 catalog
