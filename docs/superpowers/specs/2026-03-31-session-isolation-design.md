# Session Isolation — Plan Mode / Dev Mode 兩層權限設計

> Date: 2026-03-31
> Status: Draft
> Depends: Event-Driven TUI Refactor (Phase 1 ✅ Done)

## 問題

1. 主對話 agent 設 `yolo=true`，可以直接改程式碼 — 沒有安全網
2. 每條訊息建 worktree — 延遲高、複雜、不符合業界慣例（Claude Code / Scion / Codex 都是 session 級別）
3. Skill 觸發 dev 工作時沒有自動隔離機制

## 研究基礎

### 業界做法

| 工具 | 主對話隔離 | 開發隔離 |
|------|----------|---------|
| Claude Code | CWD（無隔離） | `--worktree` 或 subagent `isolation: worktree` |
| Codex CLI | OS sandbox（Seatbelt/bwrap） | per-thread worktree |
| Scion | N/A | per-agent container + worktree |
| Aider | CWD + auto-commit | N/A |

共識：**主對話不需 worktree**，人在場就是最好的隔離。Worktree 用於自動化/並行工作。

### SDK 權限 API（已驗證）

| Agent | 限制模式 | 全開模式 |
|-------|---------|---------|
| Claude | `disallowedTools(List.of("Edit","Write"))` | 無限制 + `yolo(true)` |
| Codex | `SandboxMode.READ_ONLY` + `ApprovalPolicy.SMART` | `DANGER_FULL_ACCESS` + `ApprovalPolicy.NEVER` |
| Gemini | `sandbox(true)` | `yolo(true)` |

### Skill 規格相容性

Agent Skills 標準（agentskills.io）沒有 `execution` 或 `isolation` 欄位。自訂屬性用 `metadata` map：

```yaml
metadata:
  grimo.execution: isolated    # 標準相容
```

Claude Code 的 `isolation: worktree` 是 subagent 欄位，不是 skill 欄位。Grimo 同理：skill 定義 workflow，orchestrator 決定 isolation。

## 設計

### 兩層模型

```
Plan Mode（主對話，預設）
  ├─ 工作目錄：CWD
  ├─ Agent 權限：禁止修改檔案
  │   Claude: disallowedTools=["Edit","Write","MultiEdit"]
  │   Codex:  SandboxMode.READ_ONLY
  │   Gemini: sandbox=true
  ├─ 能做：讀程式碼、搜尋、回答、寫 docs/ specs/ plans/
  └─ 不能做：修改 src/（工具層級禁止）

Dev Mode（開發，按需觸發）
  ├─ 工作目錄：git worktree（從 HEAD 分出）
  ├─ Agent 權限：全開
  │   Claude: yolo=true, 無工具限制
  │   Codex:  DANGER_FULL_ACCESS + ApprovalPolicy.NEVER
  │   Gemini: yolo=true
  ├─ 可選：Docker container
  └─ 完成後：diff summary → merge / PR / keep / discard
```

### Dev Mode 觸發方式

**1. Skill 自動觸發（不問使用者）：**

```yaml
# SKILL.md
---
name: subagent-driven-development
description: Execute plan by dispatching fresh subagent per task
metadata:
  grimo.execution: isolated
---
```

Grimo 讀到 `metadata.grimo.execution: isolated` → 自動建 worktree + 全開權限。

**2. 使用者手動觸發：**

```
/dev                    ← 進入 dev mode
/dev fix the auth bug   ← 進入 dev mode 並帶目標
```

### Orchestrator 邏輯

```java
// Skill 觸發
String execution = skill.metadata().getOrDefault("grimo.execution", "plan");
if ("isolated".equals(execution)) {
    enterDevMode(goal);  // 自動，不問
    return;
}

// 一般對話
dispatchInPlanMode(goal);  // 限制工具
```

```java
// Plan Mode options builder
private AgentOptions buildPlanOptions(String agentId) {
    return switch (agentId) {
        case "claude" -> ClaudeAgentOptions.builder()
                .yolo(true)  // 非互動式必須 yolo
                .disallowedTools(List.of("Edit", "Write", "MultiEdit"))
                .model(model).timeout(timeout).build();
        case "codex" -> CodexAgentOptions.builder()
                .sandboxMode(SandboxMode.READ_ONLY)
                .approvalPolicy(ApprovalPolicy.NEVER)
                .model(model).timeout(timeout).build();
        case "gemini" -> GeminiAgentOptions.builder()
                .yolo(false).model(model).timeout(timeout).build();
        default -> throw new IllegalArgumentException("Unknown agent: " + agentId);
    };
}

// Dev Mode options builder
private AgentOptions buildDevOptions(String agentId) {
    return switch (agentId) {
        case "claude" -> ClaudeAgentOptions.builder()
                .yolo(true)
                .model(model).timeout(timeout).build();
        case "codex" -> CodexAgentOptions.builder()
                .sandboxMode(SandboxMode.DANGER_FULL_ACCESS)
                .approvalPolicy(ApprovalPolicy.NEVER)
                .fullAuto(true)
                .model(model).timeout(timeout).build();
        case "gemini" -> GeminiAgentOptions.builder()
                .yolo(true).model(model).timeout(timeout).build();
        default -> throw new IllegalArgumentException("Unknown agent: " + agentId);
    };
}
```

### Dev Mode 生命週期

```
觸發（skill 自動 or /dev 手動）
  → 記錄 baseSha = git rev-parse HEAD
  → git worktree add /tmp/grimo-dev-<id> -b grimo/dev-<id>
  → provision skills 到 worktree
  → agent dispatch（worktree 為 workingDirectory，全開權限）
  → agent 完成
  → diff summary：
      Branch: grimo/dev-<id>
      3 files changed (+42 -8)
      → [m]erge  [p]r  [k]eep  [d]iscard
  → 使用者選擇
  → cleanup worktree
```

### 需移除的現有邏輯

| 移除 | 原因 |
|------|------|
| Per-message worktree 建立/清理 | 改為 Dev Mode 按需觸發 |
| Smart cleanup（判斷是否有變更） | Dev Mode 完成後統一處理 |
| `displayDiffSummary` per-message | 改為 Dev Mode 完成後一次性顯示 |
| `refreshStatusBar` 手動呼叫 | 已改為 event-driven（Phase 1 ✅） |

### Skill 分類對照

| Skill | grimo.execution | Mode |
|-------|----------------|------|
| brainstorming | （無，預設 plan） | Plan — 寫 spec |
| writing-plans | （無，預設 plan） | Plan — 寫 plan |
| subagent-driven-development | `isolated` | Dev — 自動建 worktree |
| executing-plans | `isolated` | Dev — 自動建 worktree |
| 未來：code-review | （無） | Plan — 只讀程式碼 |
| 未來：refactoring | `isolated` | Dev — 改程式碼 |

## 影響範圍

| 動作 | 檔案 |
|------|------|
| Create | `agent/AgentOptionsFactory.java` — Plan/Dev mode options builder |
| Create | `shared/event/DevModeEnteredEvent.java` |
| Create | `shared/event/DevModeCompletedEvent.java` |
| Modify | `GrimoTuiRunner.java` — 移除 per-message worktree，加 Dev Mode 流程 |
| Modify | `AgentConfiguration.java` — 支援 Plan/Dev 兩組 options |
| Modify | `skill/loader/SkillDefinition.java` — 讀取 metadata.grimo.execution |
| Modify | Grimo 管理的 skill SKILL.md — 加 metadata.grimo.execution |

## 實作順序

```
Phase A：Plan Mode 基礎
  → AgentOptionsFactory（Plan/Dev options builder）
  → 主對話改用 Plan Mode options（disallowedTools）
  → 驗證：主對話 agent 不能修改檔案

Phase B：Dev Mode 實作 ✅ Done
  → /dev 指令
  → Skill metadata.grimo.execution 自動觸發
  → Worktree 生命週期（建立 → 工作 → diff → merge options → cleanup）

Phase C：移除 per-message worktree ✅ Done
  → 刪除現有 per-message 邏輯
  → 清理 smart cleanup、displayDiffSummary
```
