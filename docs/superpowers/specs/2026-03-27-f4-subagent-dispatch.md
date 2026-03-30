# F4: Sub-Agent 調度系統

> Date: 2026-03-27
> Status: Draft
> Phase: 3（核心差異化）
> Parent: [PRD](2026-03-27-grimo-orchestration-platform-prd.md)
> Depends: F2-d (WorkspaceProvisioner), F2-e (Worktree Isolation), F3 (Tier System)

## 問題

目前 Grimo 一次只能跟一個 agent 對話。無法：
- 平行派遣多個 agent 從不同角度分析同一問題
- 分工交付：一個 agent 規劃，另一個執行
- 臨時叫另一個 agent 來幫忙

## 目標

讓 Grimo 能調度多個 CLI agent 作為 sub-agent，支援平行和循序兩種模式，同時保持 context 隔離。

## 核心原則

### Grimo 是調度者，不是中間人

```
✓ Grimo 做的事：
  - 決定派哪個 agent
  - 組合 goal（含必要 context）
  - 啟動 agent（AgentClient API）
  - 收集結果、顯示給使用者

✗ Grimo 不做的事：
  - 控制 agent 內部用什麼 skill
  - 攔截 agent 的工具呼叫
  - 管理 agent 內部的 MCP 連線
  - 坐在 agent 之間當訊息路由器
```

### Context 隔離

每個 sub-agent 獨立 context，不共享對話歷史：

```
主對話（使用者 ←→ 主 agent）
  │
  │ 只傳遞：明確的 goal + 必要檔案/diff
  │ 不傳遞：主對話歷史、其他 agent 的推理過程
  │
  ├─ Sub-Agent A（獨立 context）
  │   看不到主對話、看不到 B
  │   → 回傳摘要結果
  │
  └─ Sub-Agent B（獨立 context）
      看不到主對話、看不到 A
      → 回傳摘要結果
```

**為什麼隔離：**
- Context rot：context 越長 LLM 準確度越低（Anthropic 研究）
- 獨立判斷：reviewer 看到 author 的推理 → 客觀性被污染
- 垃圾隔離：sub-agent 中間過程可能 50K token，父只需要 2K 摘要
- 業界共識：Claude Code、Codex、OpenCode 全部用隔離 context

> "Share memory by communicating, don't communicate by sharing memory." — Go 並發原則

## 兩種觸發方式

### A. Grimo Skill 定義的調度

Grimo Skill（`~/.grimo/skills/`）是**調度指令**，定義要派誰、怎麼分工：

```yaml
# ~/.grimo/skills/multi-review/SKILL.md
---
name: multi-review
description: 多 agent 平行程式碼審查
metadata:
  grimo.tier: std
  grimo.subagents: '[{"name":"logic-review","goal":"Review {target} for logic correctness, null safety, race conditions, and edge cases","tier":"std"},{"name":"arch-review","goal":"Review {target} for architecture, SOLID principles, and module boundaries","tier":"std"}]'
  grimo.execution: parallel
---

## 工作流程
1. 取得目標：使用者指定的檔案、目錄、或 git diff
2. 平行派遣 sub-agent，各自獨立 review
3. 收集結果，分區顯示每個 sub-agent 的發現
4. 產出合併報告到 .grimo/reviews/{timestamp}.md
```

**Sequential 模式（分工交付）：**

```yaml
# ~/.grimo/skills/plan-execute/SKILL.md
---
name: plan-execute
description: 高階模型規劃，低成本模型執行
metadata:
  grimo.tier: pro
  grimo.subagents: '[{"name":"planner","goal":"分析以下需求，產出 Markdown 格式的結構化執行計劃，每步驟一個明確可執行的 goal：{input}","tier":"pro"},{"name":"executor","goal":"根據以下計劃逐步執行。計劃內容：{previous.result}","tier":"lite"}]'
  grimo.execution: sequential
---

## 工作流程
1. 派遣 planner 產出計劃
2. 顯示計劃給使用者審閱
3. 使用者確認後，派遣 executor 執行
```

**`{previous.result}`** — Grimo 把上一步的摘要結果塞入下一步的 goal。不是共享 context，是組合 goal 字串。

### B. @mention 臨時派遣

使用者在對話中臨時叫另一個 agent：

```
❯ @gemini 幫我看一下這段 code

  → Grimo 建立 gemini-cli sub-agent
  → goal = 使用者的文字（不含主對話歷史）
  → working directory = 當前工作目錄
  → 獨立 context
  → 結果回到主對話顯示（摘要）
```

```
❯ @claude-code @gemini 同時 review src/auth/

  → Grimo 平行建立 2 個 sub-agent
  → 各自獨立 review
  → 結果分區顯示
```

## Sub-Agent 定義檔（可選）

使用者可以預定義 sub-agent 角色，供 Skill 引用：

```yaml
# ~/.grimo/agents/logic-reviewer.md
---
name: logic-reviewer
description: 審查邏輯正確性與潛在 bug
tier: std
preferred-agent: claude-code
---

你是邏輯審查員。專注於：
1. 空指標、邊界條件、競態條件
2. 錯誤處理是否完整
3. 邏輯分支是否涵蓋所有情況

不要管架構風格問題，那是 arch-reviewer 的職責。
```

**`preferred-agent`**：偏好用哪個 CLI agent。如果環境沒裝，按 tier fallback list 選擇。

**`tier`**：這個 sub-agent 需要什麼等級的模型。

**Markdown body**：成為 goal 的一部分，補充 agent 的行為指引。

### Sub-Agent 定義的解析優先順序

```
Skill 引用 sub-agent "logic-reviewer"：

1. ~/.grimo/agents/logic-reviewer.md → 有定義檔，用定義檔的設定
2. 沒有定義檔 → 用 Skill metadata 中的 inline 設定（name/goal/tier）
```

## 執行流程

### Parallel 模式

每個 sub-agent 在獨立 git worktree 中工作（F2-e），避免並行修改衝突：

```
使用者：/multi-review src/auth/

Grimo 讀 Skill metadata:
  subagents: [logic-review, arch-review]
  execution: parallel

  ┌──────────────────────────────────┐
  │ Sub-Agent 1                      │  Virtual Thread 1
  │ logic-review                     │
  │ tier: std → claude / sonnet      │
  │                                  │
  │ Worktree: /tmp/grimo-aaa/       │  ← WorkspaceProvisioner.provision()
  │ Branch:   grimo/logic-review-aaa │
  │ Skills:   .agents/skills/ ✓     │
  │                                  │
  │ goal = "Review src/auth/ for     │
  │         logic bugs"              │
  └────────────┬─────────────────────┘
               │
               │ 平行（各自獨立 worktree，不衝突）
               │
  ┌────────────▼─────────────────────┐
  │ Sub-Agent 2                      │  Virtual Thread 2
  │ arch-review                      │
  │ tier: std → gemini / pro         │
  │                                  │
  │ Worktree: /tmp/grimo-bbb/       │  ← WorkspaceProvisioner.provision()
  │ Branch:   grimo/arch-review-bbb  │
  │ Skills:   .agents/skills/ ✓     │
  │                                  │
  │ goal = "Review src/auth/ for     │
  │         architecture"            │
  └────────────┬─────────────────────┘
               │
               ▼ CompletableFuture.allOf()
  ┌──────────────────────────────────┐
  │ 收集結果 + 清理 worktrees        │
  │                                  │
  │ ━━ logic-review (branch: aaa) ━━│
  │ 找到 3 個問題...                 │
  │                                  │
  │ ━━ arch-review (branch: bbb) ━━━│
  │ 建議 2 個改善...                 │
  │                                  │
  │ Branches:                        │
  │   → git merge grimo/logic-review-aaa│
  │   → git merge grimo/arch-review-bbb │
  └──────────────────────────────────┘
```

### Sequential 模式

Sequential 模式中，後續 agent 的 worktree 基於前一步的 branch（繼承修改）：

```
使用者：/plan-execute 重構 auth module

Phase 1:
  planner sub-agent (pro tier → opus)
  Worktree: /tmp/grimo-ccc/ on branch grimo/planner-ccc (from HEAD)
  goal = "分析需求，產出計劃：重構 auth module"
  → 結果：「3 步驟計劃：1. 抽取 interface 2. 實作 3. 補測試」
  → cleanup worktree（分支保留）

  [使用者審閱：Enter 執行 / e 編輯 / q 取消]

Phase 2:
  executor sub-agent (lite tier → flash)
  Worktree: /tmp/grimo-ddd/ on branch grimo/executor-ddd (from grimo/planner-ccc)
  goal = "根據計劃執行。計劃：{previous.result}"
  → 結果：「已完成所有步驟」
  → cleanup worktree（分支保留）

最終：
  → git merge grimo/executor-ddd（包含 planner + executor 的所有變更）
```

**`from grimo/planner-ccc`**：executor 的 worktree 基於 planner 的 branch 建立，自然繼承前一步的修改。

## 新增元件

放在 `agent/` 模組下（sub-agent 調度是 agent 模組的延伸，不另建頂層模組）：

| 元件 | 位置 | 職責 |
|------|------|------|
| `SubAgentDispatcher` | `agent/dispatch/` | 建立 AgentClient、啟動 Virtual Thread、收集結果 |
| `SubAgentDefinitionLoader` | `agent/dispatch/` | 載入 `~/.grimo/agents/*.md` 定義檔 |
| `SubAgentResult` | `agent/dispatch/` | 封裝 sub-agent 回傳的摘要結果 |
| `OrchestrationSkillParser` | `agent/dispatch/` | 解析 Skill metadata 中的 `grimo.subagents`、`grimo.execution` |

### SubAgentDispatcher 核心 API

```java
public class SubAgentDispatcher {

    private final WorkspaceProvisioner workspaceProvisioner;
    private final SkillRegistry skillRegistry;

    /**
     * 平行派遣多個 sub-agent，等待全部完成。
     * 每個 sub-agent 在獨立的 git worktree + Virtual Thread 上執行。
     * WorkspaceProvisioner 為每個 sub-agent 建立獨立 worktree + provision skills。
     */
    public List<SubAgentResult> dispatchParallel(
        List<SubAgentSpec> specs,
        Path projectDir,
        McpServerCatalog mcpCatalog
    );

    /**
     * 循序派遣 sub-agent，每步在獨立 worktree 執行。
     * 後續 agent 的 worktree 基於前一步的 branch（繼承修改）。
     * 支援 user-gate：在步驟間暫停等待使用者確認。
     */
    public List<SubAgentResult> dispatchSequential(
        List<SubAgentSpec> specs,
        Path projectDir,
        McpServerCatalog mcpCatalog,
        boolean userGateBetweenSteps
    );
}
```

**Worktree 管理：** SubAgentDispatcher 內部呼叫 `workspaceProvisioner.provision()` 為每個 sub-agent 建立獨立 worktree，完成後 `cleanup()`。呼叫者不需要處理 worktree 生命週期。

## @mention 路由規則

```
使用者輸入解析：

1. @agent-id xxx      → 派遣指定 agent 作為 sub-agent
2. @agent-a @agent-b  → 平行派遣多個
3. /skill-name        → 讀 Skill metadata 決定調度方式
4. 關鍵字觸發         → 觸發預設 Skill（如「多角度」→ multi-review）
5. 普通文字           → 送主 agent（一般對話）
```

## 使用者體驗

### Parallel 結果顯示

```
❯ /multi-review src/auth/

⏺ 派遣 2 個 sub-agent...
  ● Worktree(grimo/logic-review-aaa)
    └ Isolated workspace created
  ⟳ logic-review (claude-code / sonnet)
  ● Worktree(grimo/arch-review-bbb)
    └ Isolated workspace created
  ⟳ arch-review (gemini-cli / pro)

━━━ logic-review 完成 (12s) ━━━
找到 3 個問題：
1. AuthController.java:42 — null check missing
2. TokenService.java:78 — race condition
3. SessionManager.java:15 — unclosed resource
  Branch: grimo/logic-review-aaa (1 commit)

━━━ arch-review 完成 (8s) ━━━
建議 2 個改善：
1. AuthController 違反 SRP，建議拆分
2. TokenService 應透過 interface 注入
  Branch: grimo/arch-review-bbb (0 commits)

Branches:
  → git merge grimo/logic-review-aaa
```

### Sequential 結果顯示

```
❯ /plan-execute 重構 auth module

● Worktree(grimo/planner-ccc)
  └ Isolated workspace created
⏺ Phase 1: 規劃中 (claude-code / opus)...

━━━ 計劃 ━━━
1. 抽取 TokenService interface
2. 實作 JwtTokenService
3. 修改 AuthController 注入
4. 補寫單元測試

[Enter] 執行  [e] 編輯  [q] 取消

❯ [Enter]

● Worktree(grimo/executor-ddd) from grimo/planner-ccc
  └ Isolated workspace created
⏺ Phase 2: 執行中 (gemini-cli / flash)...

━━━ 執行結果 ━━━
✓ 已完成 4 個步驟
  Modified: 3 files, Added: 2 files
  Branch: grimo/executor-ddd (5 commits)
  → git merge grimo/executor-ddd
```

## 影響範圍

| 檔案 | 變更 |
|------|------|
| `GrimoTuiRunner.java` | processInput 加入 @mention 解析、Skill 調度判斷；移除 `agentRunning` 單一限制，改為多 agent 追蹤 |
| `GrimoContentView.java` | 分區顯示 sub-agent 結果 + branch info |
| `GrimoInputView.java` | @mention 自動補全 |
| `WorkspaceProvisioner.java` | 已由 F2-e 擴充 worktree 支援，F4 直接使用 |
| 新增 `agent/dispatch/` package | SubAgentDispatcher（注入 WorkspaceProvisioner）、DefinitionLoader、SkillParser、SubAgentResult |

## 參考

- [F2-e: Git Worktree Isolation](2026-03-30-f2e-worktree-isolation.md) — WorkspaceProvisioner worktree 基礎設施
- [Google Scion](https://github.com/GoogleCloudPlatform/scion) — 每個 agent 一個 container + git worktree + credentials
- [Claude Code Sub-Agents](https://code.claude.com/docs/en/sub-agents) — 隔離 context、@mention、Agent tool
- [Claude Code Agent Teams](https://code.claude.com/docs/en/agent-teams) — 訊息傳遞、共享 task list
- [Codex CLI Sub-Agents](https://developers.openai.com/codex/subagents) — TOML 定義、max_threads
- [OpenCode Agents](https://opencode.ai/docs/agents/) — 跨 provider、獨立 session
- [Anthropic: Context Engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) — 最小必要 context
