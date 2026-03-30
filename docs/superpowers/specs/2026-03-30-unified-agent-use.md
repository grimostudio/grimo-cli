# Unified `/agent-use` — 合併 Agent + Model 切換

> Date: 2026-03-30
> Status: Draft

## 問題

1. `/agent-use` 和 `/agent-model` 是兩個獨立指令，使用者容易忘記設模型，導致 status bar 顯示 `unknown`
2. `AgentConfiguration.java` 的預設模型過時（claude-sonnet-4-5、gemini-2.5-flash）
3. 切換 agent 後再切回來，不記得上次設定的模型

## 設計

### 合一指令

```
/agent-use claude          → 讀記憶 or 推薦預設
/agent-use claude opus     → 智慧匹配 + 存記憶
/agent-use gemini pro      → 智慧匹配 + 存記憶
```

刪除 `/agent-model` 指令。

### 推薦模型表

```java
Map.of(
    "claude", "claude-sonnet-4-6",
    "gemini", "gemini-2.5-pro",
    "codex",  "o4-mini"
)
```

來源：
- Claude Code: https://code.claude.com/docs/en/model-config — 預設 claude-sonnet-4-6
- Gemini CLI: defaultModelConfigs.ts — 預設 gemini-2.5-pro
- Codex CLI: README — 預設 o4-mini

### Per-agent 模型記憶

利用現有 `agent-options.<id>.model`：

```yaml
agent-options:
  claude:
    model: claude-opus-4-6    # 使用者手動設過
  gemini:
    model: gemini-2.5-pro     # 推薦預設（未手動設時不寫入）
```

解析順序：
1. `agent-options.<id>.model` 有值 → 使用（記憶）
2. 沒有 → 用推薦模型表

### `/agent-use` 邏輯

```
agent-use(agentId, modelHint?):
  1. 驗證 agentId 存在於 registry
  2. 解析 model:
     if modelHint != null:
       model = smartMatch(agentId, modelHint)  // "opus" → "claude-opus-4-6"
       setAgentOption(agentId, "model", model)  // 存記憶
     else:
       model = getAgentOption(agentId, "model") // 讀記憶
       if model == null:
         model = RECOMMENDED_MODELS.get(agentId) // 推薦預設
  3. setDefaultAgent(agentId)
  4. setDefaultModel(model)
  5. return "Switched to {agentId} · {model}"
```

### 智慧匹配

把使用者的簡寫對應到完整模型 ID：

```
"opus"    + claude → claude-opus-4-6
"sonnet"  + claude → claude-sonnet-4-6
"haiku"   + claude → claude-haiku-4-5
"pro"     + gemini → gemini-2.5-pro
"flash"   + gemini → gemini-2.5-flash
```

不匹配時直接當完整 model ID 使用（使用者可能輸入 `claude-opus-4-6`）。

### Status bar

現有邏輯已讀 `getDefaultModel()`，只要 `/agent-use` 正確設定 `default-model` 就會自動更新。

## 影響範圍

| 動作 | 檔案 | 變更 |
|------|------|------|
| Modify | `AgentCommands.java` | 改寫 `/agent-use`，加推薦表+智慧匹配，刪 `/agent-model` |
| Modify | `AgentConfiguration.java` | 更新預設模型字串 |
| Modify | `GrimoConfig.java` | 新增 `setAgentOption()` |
