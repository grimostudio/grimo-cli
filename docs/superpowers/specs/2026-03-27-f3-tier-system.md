# F3: Tier 三級制 — lite / std / pro

> Date: 2026-03-27
> Status: Draft
> Phase: 2（成本最佳化）
> Parent: [PRD](2026-03-27-grimo-orchestration-platform-prd.md)

## 問題

目前 Grimo 只有一個 default agent/model 設定。所有 Skill 不分複雜度都用同一個 agent，造成：
- 簡單 Skill 浪費昂貴 token（用 Opus 做查詢）
- 複雜 Skill 用便宜模型品質不足（用 Flash 做重構）

## 目標

Skill 執行時自動選擇最適合的 agent/model 等級，使用者不需要手動切換。

## 設計

### 核心概念

**Tier 是給 Skill 執行用的，一般對話用使用者自訂的 conversation 設定。**

| | 一般對話 | Skill 執行 |
|---|---|---|
| 決定權 | 使用者自訂（conversation config） | Skill metadata + Tier 對應表 |
| 指令 | `/agent-use`、`/agent-model` | `/tier`、`/skill-tier` |

### 三級定義

| 等級 | 定位 | 典型場景 |
|------|------|---------|
| `lite` | 快速、便宜 | 查詢、格式轉換、簡單問答 |
| `std` | 日常主力 | 功能開發、bug 修復、單檔重構 |
| `pro` | 深度推理 | 架構設計、大型重構、深度研究 |

### config.yaml 結構

```yaml
# 一般對話設定（使用者偏好）
conversation:
  agent: claude-code
  model: claude-sonnet-4

# Skill 三級對應表（每級多選項，按優先順序 fallback）
skill-tiers:
  lite:
    - agent: gemini-cli
      model: gemini-2.5-flash
    - agent: claude-code
      model: claude-haiku-4
    - agent: codex-cli
      model: o4-mini

  std:
    - agent: claude-code
      model: claude-sonnet-4
    - agent: gemini-cli
      model: gemini-2.5-pro
    - agent: codex-cli
      model: o4-mini

  pro:
    - agent: claude-code
      model: claude-opus-4
    - agent: gemini-cli
      model: gemini-2.5-pro
    - agent: codex-cli
      model: o3

# Per-Skill 覆蓋（可選）
skill-overrides:
  deep-research:
    tier: pro
  tdd-workflow:
    agent: claude-code
    model: claude-opus-4
```

### Fallback 邏輯

```
Skill 標記 tier = pro

查 skill-tiers.pro:
  1. claude-code / opus → isAvailable()? → ✓ 用這個
  2. gemini-cli / pro   → (跳過，第一個就可用)
  3. codex-cli / o3     → (跳過)

如果環境只有 Gemini CLI:
  1. claude-code / opus → isAvailable()? → ✗ 沒裝
  2. gemini-cli / pro   → isAvailable()? → ✓ 用這個
  3. codex-cli / o3     → (跳過)

全都不可用 → 報錯：「沒有可用的 agent 符合 pro 等級」
```

### Tier 解析優先順序

```
Skill 執行時，Tier 從哪來（高優先→低優先）：

1. 使用者本輪關鍵字     「仔細想 /deep-research xxx」  → pro
2. 使用者 /tier 指令    /tier pro                      → pro（session 級）
3. skill-overrides      兩種形式（見下方說明）
4. Skill metadata       metadata.grimo.tier = pro      → pro
5. 安裝時自動分析結果   (寫入 metadata)                 → pro
6. 預設                                                → std
```

**skill-overrides 有兩種形式：**

```yaml
skill-overrides:
  # 形式 A：指定 tier（走 tier fallback 邏輯）
  deep-research:
    tier: std

  # 形式 B：直接指定 agent + model（跳過 tier 解析）
  tdd-workflow:
    agent: claude-code
    model: claude-opus-4
```

形式 B 優先於形式 A。當 `agent` + `model` 都有指定時，直接使用該組合，不查 `skill-tiers` fallback list。

### 安裝時自動分析

使用者安裝 Skill 時，如果 Skill 沒有標 `grimo.tier`，Grimo 自動用 lite 等級的 agent 分析一次：

```
❯ /skill-install https://github.com/xxx/grimo-skill-deep-research

  ✓ 下載完成
  ⚙ 分析 Skill 需求...
  ✓ 判定等級: pro（原因：多步驟研究、交叉比對、長篇報告）
  ✓ 已寫入 metadata: grimo.tier = pro
```

**分析 prompt（送給 lite 等級的 agent）：**

```
分析以下 SKILL.md 的執行複雜度，判定為 lite / std / pro 三級之一：

lite: 簡單查詢、格式轉換、單步操作
std:  功能開發、bug 修復、單檔重構、一般 review
pro:  多步驟工作流、跨檔案重構、深度分析、需要規劃能力

SKILL.md 內容：
{skill body}

回傳 JSON: { "tier": "pro", "reason": "多步驟研究流程，需要交叉比對多來源" }
```

如果 Skill 已標了 `grimo.tier` → 跳過分析，尊重作者判斷。

### 關鍵字觸發（per-turn）

```yaml
# config.yaml
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

關鍵字只影響**該輪**，不改 session 設定。類似 Claude Code 的 `ultrathink`。

### 指令

| 指令 | 效果 | 持續性 |
|------|------|--------|
| `/tier` | 互動式查看/選擇 | 持續到下次切換 |
| `/tier lite/std/pro` | 切換 session tier | 持續到下次切換 |
| `/skill-tier <skill> <tier>` | 覆蓋特定 skill | 寫入 config 永久 |

### Status Bar 顯示

```
一般對話：
  ⚙ claude-code · sonnet-4 · ~/project

Skill 執行時顯示 tier：
  ⚡ lite · gemini-cli · flash · ~/project
  ⚙ std · claude-code · sonnet-4 · ~/project
  🧠 pro · claude-code · opus-4 · ~/project

關鍵字提升（短暫）：
  🧠 pro (本輪) · claude-code · opus-4 · ~/project
```

## 新增元件

| 元件 | 職責 |
|------|------|
| `TierRouter` | 解析 tier 來源（關鍵字/session/skill/config），查 fallback list，回傳 agent+model |
| `TierConfig` | 讀取 config.yaml 的 `skill-tiers`、`skill-overrides`、`tier-keywords` |
| `SkillAnalyzer` | 安裝時用 lite agent 分析 Skill 複雜度 |
| `TierCommands` | `/tier`、`/skill-tier` 指令 |

## 影響範圍

| 檔案 | 變更 |
|------|------|
| `GrimoConfig.java` | 新增 tier 相關讀取方法 |
| `GrimoTuiRunner.java` | processInput 加入 TierRouter 判斷 |
| `SkillCommands.java` | 新增 `/skill-tier` 指令 |
| `GrimoStatusView.java` | 顯示 tier 資訊 |
| `config.yaml` | 新增 `skill-tiers`、`tier-keywords` section |

## 參考

- [MindStudio: 3-Tier Model Router](https://www.mindstudio.ai/blog/set-up-ai-model-router-llm-stack-c2610) — 業界 3 級標準（Fast/Standard/Premium）
- [RouteLLM](https://github.com/lm-sys/RouteLLM) — 2 級 ML-based 路由（學術研究）
- [Claude Code effort levels](https://code.claude.com/docs/en/model-config) — low/medium/high/max
- [Claude Code ultrathink keyword](https://findskill.ai/blog/claude-ultrathink-extended-thinking/) — per-turn 關鍵字觸發
