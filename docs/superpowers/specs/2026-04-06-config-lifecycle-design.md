# Config 生命週期重構設計

> Date: 2026-04-06
> Status: Draft
> Depends: Agent Routing Fix (✅ Done)

## 問題

1. `GrimoConfig` 每次 getter 都讀檔（`load()` 在每個方法裡被呼叫），TierRouter 每次 dispatch 都讀
2. Config.yaml 首次啟動時寫入全註解模板，等於沒設定，`getDefaultAgent()` 回傳 null
3. Status bar 和 dispatch 可能使用不同 agent（啟動時 config 沒設定 default agent）
4. `tier-keywords`、`skill-overrides`、`agent-options` 增加了不必要的複雜度

## 目標

1. GrimoConfig 改為 config bean — 啟動時讀檔填入欄位，getter 讀欄位，setter write-through
2. 首次啟動寫入真實預設值（`agents.default: claude`、`agents.model: claude-sonnet-4-6`、`sandbox.mode: local`）
3. 啟動時偵測 default agent 是否已安裝，未安裝則自動切換到可用 agent 並更新 config
4. 移除 `tier-keywords`、`skill-overrides`、`agent-options`，簡化 TierRouter

## 設計

### GrimoConfig Bean

```java
public class GrimoConfig {
    private final Path configFile;

    // --- agents ---
    private String defaultAgent;                           // agents.default
    private String defaultModel;                           // agents.model

    // --- mcp ---
    private Map<String, Map<String, Object>> mcpServers;   // mcp.*

    // --- sandbox ---
    private String sandboxMode;                            // sandbox.mode
}
```

### 生命週期

**預設 config 的建立由 `GrimoConfig` 建構子負責**（不在 `GrimoHome`）。`GrimoHome.initialize()` 只建目錄結構，不處理 config 內容。

```
建構（Path configFile）
  → configFile 存在？
    → 否 → 寫入預設值檔案（見下方模板）
  → 讀檔 → 填入 bean 欄位
  → 欄位為 null → 填入 fallback 預設值

getter
  → return this.field（直接 field access，synchronized）

setter（如 setDefaultAgent）
  → synchronized: this.field = newValue → save()
  → event publish 由呼叫端負責（AgentCommands、McpCommands）
```

**Thread safety**：所有 getter/setter 保持 `synchronized`（與現行一致）。bean 欄位在 Virtual Thread 併發下透過 synchronized 保護一致性。

### 首次啟動預設 config.yaml

```yaml
agents:
  default: claude
  model: claude-sonnet-4-6
sandbox:
  mode: local
```

MCP 區段只在使用者 `/mcp-add` 時才出現。`tier-models` 走 `application.yaml` 內建值，不寫入使用者 config。

### 啟動流程與 Auto-Fallback

```
GrimoStartupRunner (Order HIGHEST_PRECEDENCE + 1)
  ① GrimoHome.initialize()
    → 只建目錄結構（不處理 config 內容）
  ② GrimoConfig 建構（@Bean）
    → config.yaml 不存在 → 寫入預設值
    → 讀 config.yaml → 填入 bean 欄位
  ③ AgentModelFactory.detectAndRegister()（Virtual Threads）
    → 偵測已安裝的 CLI agents → 註冊到 AgentModelRegistry
  ④ 檢查 config.defaultAgent 是否已安裝
    → 已安裝 → 不動
    → 未安裝（isAvailable() = false）→ 自動切換：
        a. 走 `grimoProperties.getTierModels().get("std")` fallback list 找第一個可用（內建值，非 user config）
        b. config.setDefaultAgent(newAgent)
        c. config.setDefaultModel(newModel)
        d. 存 fallbackMessage 供 TUI 顯示
        e. log.warn("claude not installed, switched to gemini/flash")
  ⑤ 其餘初始化（Skill、MCP、Task、Sandbox）

TuiAdapter (Order HIGHEST_PRECEDENCE + 2)
  → 讀 config bean → 顯示 status bar（已是正確的 agent）
  → 若有 fallbackMessage → contentView 顯示提示
```

**區分「沒安裝」和「安裝了但執行失敗」**：
- **沒安裝**（`isAvailable() = false`）→ APP 主動切換，優化體驗
- **安裝了但 runtime 失敗** → 報錯讓使用者決定（`/agent-use` 切換）

### 移除項目

| 移除項目 | 檔案 | 原因 |
|---------|------|------|
| `TierKeywordDetector.java` | 刪除整個檔案 | tier-keywords 功能移除 |
| `TierKeywordDetectorTest.java` | 刪除 | 對應測試 |
| `TierConfiguration.tierKeywordDetector()` bean | 刪除方法 | 無消費者 |
| `TierRouter.Context.keywordTier` | 刪除欄位 + builder method | 無來源 |
| `TierRouter.Context.skillName` | 刪除欄位 + builder method | skill-overrides 移除 |
| `TierRouter.Context.skillTier` | 刪除欄位 + builder method | skill-overrides 移除 |
| `TierRouter.resolve()` 的 keyword/skill-override 分支 | 刪除對應邏輯 | 無來源 |
| `GrimoConfig.getTierKeywords()` | 刪除方法 | 無消費者 |
| `GrimoConfig.getSkillOverrides()` / `setSkillOverride()` | 刪除方法 | 功能移除 |
| `GrimoConfig.getAgentOption()` / `setAgentOption()` | 刪除方法 | 改用 `agents.model` 統一 |
| `GrimoConfig.getTierModels()` | 刪除方法 | tier-models 只從 application.yaml 讀取 |
| `TierCommands./skill-tier` 指令 | 刪除 | skill-overrides 移除 |
| `GrimoHome` 的 tier-keywords 模板 | 刪除 | 不再寫入 |

### TierRouter 簡化後

```java
public TierSelection resolve(Context ctx) {
    Tier tier;
    String source;

    if (ctx.sessionTier != null) {
        tier = ctx.sessionTier;
        source = "session";
    } else {
        tier = Tier.STD;
        source = "default";
    }

    return walkFallbackList(tier, source);
}
```

`Context` 只保留 `sessionTier`：

```java
public static class Context {
    @Nullable final Tier sessionTier;

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Tier sessionTier;
        public Builder sessionTier(Tier tier) { this.sessionTier = tier; return this; }
        public Context build() { return new Context(this); }
    }
}
```

`resolveDefault()` 保持不變（已在 agent routing fix 中加入）。

### AgentCommands 簡化

```java
// /agent-use 不再寫 agent-options，直接寫 agents.model
config.setDefaultAgent(agentId);
config.setDefaultModel(model);
eventPublisher.publishEvent(new AgentSwitchedEvent(agentId, model));
```

**Per-agent model memory 移除**：原本切換 `claude → opus`，再切 `gemini`，再切回 `claude` 會記住 `opus`。移除 `agent-options` 後此行為消失 — 切換 agent 時 model 統一寫入 `agents.model`，不再 per-agent 記憶。這是有意的簡化：減少使用者困惑（「為什麼切回 claude 變成 opus 不是 sonnet？」），且使用者可明確指定 `/agent-use claude opus`。

## 影響範圍

### 修改檔案

| 檔案 | 變更 |
|------|------|
| `config/GrimoConfig.java` | 重構：bean 欄位 + 建構時讀入 + write-through；移除 tierKeywords/skillOverrides/agentOption/tierModels 方法 |
| `home/GrimoHome.java` | 修改：預設 config 模板改為真實值 |
| `GrimoStartupRunner.java` | 修改：agent 偵測後加 auto-fallback 邏輯；存 fallbackMessage |
| `TuiAdapter.java` | 修改：啟動時顯示 fallbackMessage |
| `agent/AgentCommands.java` | 簡化：移除 setAgentOption，直接寫 agents.model |
| `agent/tier/TierRouter.java` | 簡化：移除 keyword/skill-override 邏輯；Context 只保留 sessionTier；`walkFallbackList()` 移除 `config.getTierModels()` 讀取，只從 `grimoProperties.getTierModels()` 讀取；`resolveDefault()` 移除 `getAgentOption()` 呼叫 |
| `agent/tier/TierCommands.java` | 移除 `/skill-tier` 指令 |
| `agent/tier/TierConfiguration.java` | 移除 tierKeywordDetector bean |
| `ChatDispatcher.java` | 移除 tierKeywordDetector 依賴；`dispatchTo()` 移除 `getAgentOption()` 呼叫，改用 `config.getDefaultModel()` |

### 刪除檔案

| 檔案 | 原因 |
|------|------|
| `agent/tier/TierKeywordDetector.java` | 功能移除 |
| 對應測試檔案 | 跟隨刪除 |

## 測試

| 測試 | 覆蓋 |
|------|------|
| `GrimoConfigTest` | bean 載入 / write-through / 檔案不存在時建立預設值 / save 後重讀一致 |
| `TierRouterTest`（修改） | 移除 keyword/skill-override 測試；保留 sessionTier + default + resolveDefault 測試 |
| `GrimoStartupRunner integration` | auto-fallback：default agent 未安裝時自動切換 |

## Glossary 更新

| 術語 | 變更 |
|------|------|
| GrimoConfig | 更新：config bean，啟動時讀檔填入欄位，setter write-through（記憶體 + 檔案） |
| TierKeywordDetector | 移除 |
| skill-overrides | 移除 |
| agent-options | 移除 |
