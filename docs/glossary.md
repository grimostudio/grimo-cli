# Grimo CLI 術語表（Glossary）

> 統一專案中使用的名詞定義，搭配佈局示意圖標註。

## 佈局總覽

```
┌──────────────────────────────────────────────────┐
│                                                  │
│   ✦     Grimo v0.0.1                            │
│ ▄████▄  claude-cli · unknown                    │
│ █●██●█  ~/grimo-workspace                       │  ← Content 區
│ ██████  1 agent · 0 skill                       │
│ ▀▄▀▀▄▀                                          │
│                                                  │
│ ❯ 你好                                           │  ← 使用者輸入紀錄
│                                                  │
│ ⏺ 你好！有什麼我可以幫你的嗎                      │  ← AI 回覆紀錄
│                                                  │
│ ┌──────────────────────────────────────────────┐ │
│ │  /agent        List all agents               │ │  ← 斜線指令選單
│ │  /agent list   List all agents               │ │    （Modal Overlay）
│ │  /agent model  Switch default model          │ │
│ └──────────────────────────────────────────────┘ │
├──────────────────────────────────────────────────┤  ← 分隔線
│ ❯ /ag█                                          │  ← Input 區
├──────────────────────────────────────────────────┤  ← 分隔線
│ claude-cli · unknown │ ~/grimo │ 1 agent · 0 mcp│  ← Status 區
└──────────────────────────────────────────────────┘
```

## 佈局區域

| 名詞 | 英文 | 說明 |
|------|------|------|
| **Content 區** | Content Area | 佔據畫面上方大部分空間的可滾動區域。啟動時顯示 Banner，之後累積使用者輸入紀錄、AI 回覆、命令輸出。內容往上滾動，舊內容（包含 Banner）最終被推出畫面。 |
| **Input 區** | Input Area | 固定在底部的單行輸入框，顯示 `❯` 提示符號。使用者在此輸入文字或斜線指令。**永遠不移動**。 |
| **Status 區** | Status Bar | 固定在最底行的狀態列，顯示 agent、model、workspace、資源計數。**永遠不移動**。 |
| **斜線指令選單** | Slash Command Menu | 輸入 `/` 時以 Modal Overlay 浮動出現在 Input 區正上方（覆蓋 Content 區底部）。最多 5 項，即時過濾，↑↓ 選擇。選取或取消後消失。**不影響 Input 區和 Status 區位置**。 |
| **分隔線** | Separator | Input 區上下的水平線（`─`），視覺上區隔 Content、Input、Status 三個區域。 |

## 互動元素

| 名詞 | 英文 | 說明 |
|------|------|------|
| **斜線指令** | Slash Command | 所有以 `/` 開頭的指令的統稱。包含 Skills（`/brainstorming`）、應用命令（`/agent list`）、系統命令（`/exit`）。觸發條件：行首 `/` 或 `空格 + /`。 |
| **使用者輸入紀錄** | User Input Record | Content 區中的使用者輸入歷史，以 `❯` 前綴 + 深色背景行顯示。 |
| **AI 回覆紀錄** | AI Reply Record | Content 區中的 AI 回應，以 `⏺` 前綴 + 一般文字顯示。 |
| **命令輸出** | Command Output | 斜線指令執行後的結果，直接顯示在 Content 區（無前綴）。 |
| **Banner** | Banner | 啟動時顯示的吉祥物 + 版本 + 環境資訊（5 行），位於 Content 區底部。隨對話累積逐漸被推出畫面。 |

## 樣式

| 名詞 | 英文 | 說明 |
|------|------|------|
| **系統標誌色** | Brand Accent Color | Steel blue，ANSI 256 color 67（`#5F87AF`）。用於：吉祥物 Logo、選中的斜線指令文字、Input 區中已填入的斜線指令名稱。 |
| **預設文字色** | Default Text Color | Terminal 預設前景色。用於：未選中的斜線指令、一般文字。 |

## Session 與歷史

| 名詞 | 英文 | 說明 |
|------|------|------|
| **Session** | Session | 一次 TUI 啟動到結束的對話歷程。以 UUID 識別，存為 JSONL 檔案。可透過 `--resume` 恢復。 |
| **Session 檔案** | Session File | `~/.grimo/projects/<encoded-cwd>/sessions/<session-uuid>.jsonl`。對齊 Claude Code 結構。每行一個 JSON 物件（含 uuid、parentUuid 支援對話樹），append-only。 |
| **滾動** | Scroll | Content 區支援滑鼠滾輪 / Mac 觸控板兩指滾動，瀏覽已消失的歷史對話。自動跟隨模式：在底部時新內容自動顯示，滾動中途不自動跳轉。 |

## Domain Events（Spring Modulith）

| 名詞 | 英文 | 說明 |
|------|------|------|
| **AgentSwitchedEvent** | Agent Switched Event | `/agent-use` 執行後由 AgentCommands 發布。TUI 的 @EventListener 接收後自動刷新 status bar。 |
| **McpCatalogChangedEvent** | MCP Catalog Changed Event | MCP server 新增/移除後發布。TUI 自動更新 mcp count。 |

已有的 events：`IncomingMessageEvent`、`OutgoingMessageEvent`、`TaskExecutionEvent`

## 技術元件對應

| 佈局區域 | 實作元件 | 底層技術 |
|----------|----------|----------|
| Content 區 | `GrimoContentView` | 純 Java + `AttributedString`，render() 回傳行列表 |
| Input 區 | `GrimoInputView` | 純 Java + `AttributedString`，斜線指令以品牌色渲染 |
| Status 區 | `GrimoStatusView` | 純 Java + `AttributedString` |
| 斜線指令選單 | `GrimoSlashMenuView` | 純 Java + `AttributedString`，overlay 到 content 底部 |
| 分隔線 | `GrimoInputView.render()` | `─` 字元 + gray 色（ANSI 245） |
| 畫面組合 | `GrimoScreen` | JLine `Display`（diff-based 渲染，不閃爍） |
| 事件迴圈 | `GrimoEventLoop` | JLine `BindingReader` + `KeyMap`（雙執行緒 Tmux 模式） |
| 滑鼠滾輪 | `GrimoEventLoop` | JLine `MouseEvent.Button.WheelUp/WheelDown` |

## 調度系統術語

| 名詞 | 英文 | 說明 |
|------|------|------|
| **Sub-agent** | Sub-agent | Grimo 派遣的獨立 CLI agent 實例。擁有獨立 context，不共享主對話歷史。接收明確的 goal，完成後回傳摘要結果。程式碼中使用 `SubAgent`（CamelCase），metadata key 使用 `subagents`（無連字號）。 |
| **Tier** | Tier | Skill 執行的能力等級。三級：`lite`（快速便宜）、`std`（日常主力）、`pro`（深度推理）。每級對應一個 agent+model fallback list。 |
| **Grimo Skill** | Grimo Skill | 放在 `~/.grimo/skills/` 的 SKILL.md，格式對齊 Agent Skills 開放標準（[agentskills.io](https://agentskills.io/specification)）。定義 Grimo 的調度指令（派誰、怎麼分工）。Grimo 擴充欄位放在 `metadata` map 裡（`grimo.tier`、`grimo.subagents`、`grimo.execution`）。第三方 Skill 直接安裝不會解析失敗。 |
| **Agent Skill** | Agent Skill | 各 CLI agent 自己的 skill（如 `.claude/skills/`、`.gemini/agents/`）。由 agent 自己讀取和執行，Grimo 不介入。 |
| **Portable MCP** | Portable MCP | Spring AI Community Agent Client 的 MCP 轉換機制。在 `config.yaml` 統一定義 MCP server（stdio/sse/http），SDK 自動轉成各 CLI agent 的原生格式（Claude: `--mcp-config` JSON、Gemini: settings.json、Codex: 原生格式）。Grimo 不需處理轉換邏輯。 |
| **McpServerCatalog** | MCP Server Catalog | 所有 MCP server 定義的 immutable 集合。由 `McpCatalogBuilder` 從 `config.yaml` 建構，傳入 `AgentClient.Builder.mcpServerCatalog()` 後由 SDK 處理分發。 |
| **WorkspaceProvisioner** | Workspace Provisioner | 派遣 agent 前將 Grimo 管理的 Skill symlink 到工作目錄 `.agents/skills/`（跨 agent 標準路徑）。CLI agent（Claude/Gemini/Codex）原生發現 skill，Progressive Disclosure 自然運作。Grimo 的環境準備層，不同於 SDK 的 `LocalSandbox`（agent 執行隔離層）。 |
| **Sandbox** | Sandbox | Agent 執行環境。Local 模式直接使用工作目錄（symlink skill）；Docker/E2B 模式使用隔離容器（Phase B/C）。由 `SandboxDetector` 偵測可用後端，`WorkspaceProvisioner` 負責環境配置。 |
| **TierRouter** | Tier Router | 解析 tier 來源（6 級優先順序：關鍵字 > session /tier > skill-overrides > skill metadata > 自動分析 > 預設 std），查 fallback list（每級多組 agent+model，依序 isAvailable()），回傳 `TierSelection(agentId, model, tier, source)`。 |
| **TierOptionsFactory** | Tier Options Factory | 根據 agentId 建構對應的 per-request `AgentOptions` 子型別（ClaudeAgentOptions / GeminiAgentOptions / CodexAgentOptions），含 tier 選定的 model。在 `AgentClient.run(goalText, agentOptions)` 傳入以覆寫 defaultOptions。 |
| **TierKeywordDetector** | Tier Keyword Detector | 從使用者輸入偵測 tier 關鍵字（如「仔細想」→ pro）。只影響該輪，不改 session 設定。多 tier 同時匹配取最高（PRO > STD > LITE）。 |
| **SkillAnalyzer** | Skill Analyzer | 安裝 Skill 時用 lite tier agent 自動分析 Skill body 複雜度，判定 tier 並寫入 metadata。已標 grimo.tier 的 Skill 跳過分析。 |
| **Plan Mode** | Plan Mode | 主對話預設模式。Agent 可讀程式碼、寫 docs，但禁止修改 src/。Claude 用 disallowedTools，Codex 用 ApprovalPolicy.SMART。 |
| **Dev Mode** | Dev Mode | 開發模式。Agent 全開（yolo=true），搭配 worktree 隔離。由 skill metadata.grimo.execution=isolated 自動觸發，或使用者 /dev 指令。 |
| **ExecutionMode** | Execution Mode | `TierOptionsFactory.ExecutionMode` enum: PLAN（限制）/ DEV（全開）。決定 agent 的工具權限等級。 |
| **Worktree** | Worktree | 每次 agent 派遣時建立的獨立 git worktree。Agent 在 worktree 中工作，完成後使用者決定是否 merge。非 git 目錄 fallback 到 CWD（現有行為）。 |
| **WorktreeInfo** | Worktree Info | `WorktreeInfo(workDir, branchName, baseSha, provisionedSkills, isWorktree)` record。記錄 worktree 的工作目錄、分支名稱、建立時的 HEAD SHA、已配置 skill。 |
| **GitHelper** | Git Helper | Git CLI 操作工具。封裝 worktree 建立/移除、diff、auto-commit 等操作。使用 ProcessBuilder 執行 git 指令。 |

## Agent 技術元件對應

| 元件 | 實作 | 底層技術 |
|------|------|----------|
| Agent 統一抽象 | `AgentModel` | Spring AI Community Agent Client 0.10.0-SNAPSHOT（Library 模式） |
| Agent 偵測 | `AgentModelFactory` | 各 SDK `isAvailable()` + Virtual Thread 並行偵測 |
| Agent 註冊 | `AgentModelRegistry` | `ConcurrentHashMap<String, AgentModel>`（runtime 動態增刪） |
| Agent 路由 | `AgentRouter` | config default → 第一個可用 |
| Agent 呼叫 | `AgentClient.builder(model).mcpServerCatalog().build().goal().run()` | CLI subprocess（claude / gemini / codex） |
| Agent 配置 | `AgentConfiguration` | `@Configuration` + `AgentSpec` per CLI agent |
| Advisor: Session | `GrimoSessionAdvisor` | `AgentCallAdvisor`（around-advice，記錄 goal/result） |
| Advisor: Validation | `GoalValidationAdvisor` | `AgentCallAdvisor`（阻擋危險操作） |
| MCP 定義 | `McpCatalogBuilder` | `McpServerCatalog`（Portable MCP，config.yaml → AgentClient.Builder） |
| 非阻塞對話 | `GrimoTuiRunner` | Virtual Thread + `eventLoop.setDirty()` 觸發重繪 |
| Tier 路由 | `TierRouter` | 6 級優先順序 → fallback list → `AgentModel.isAvailable()` |
| Tier 選項 | `TierOptionsFactory` | `AgentClient.run(goal, agentOptions)` per-request model 覆寫 |
| Tier 偵測 | `TierKeywordDetector` | config.yaml `tier-keywords` 字串比對 |
| Tier 指令 | `TierCommands` | Spring Shell @Command: `/tier`, `/skill-tier` |
| Skill 分析 | `SkillAnalyzer` | AgentClient + lite tier → JSON 回應解析 |
| Worktree 隔離 | `WorkspaceProvisioner` + `GitHelper` | `git worktree add/remove` via ProcessBuilder |

## TUI 框架術語

| 名詞 | 英文 | 說明 |
|------|------|------|
| **DisplayWidth** | Display Width | 封裝 JLine WCWidth 的字串寬度計算工具。CJK=2, ASCII=1。提供 padRight/padLeft/center/truncate/wrap 操作。 |
| **TuiComponent** | TUI Component | TUI 元件介面。`render(int width)` 回傳 `List<AttributedString>`，每行保證 columnLength == width。元件不管高度，容器負責捲動。 |
| **Layout** | Layout | 佈局切分計算。Slot.Fixed(n) 固定值，Slot.Fill() 填滿剩餘。支援 gap 間距。 |
| **TuiTable** | TUI Table | 寬度感知的表格 Builder。用 Layout.horizontal 計算欄寬，DisplayWidth.padRight 對齊。 |
| **TuiStatusBar** | TUI Status Bar | 單行狀態列元件。truncate 感知 CJK，保證精確 width。 |
| **TuiSelector** | TUI Selector | 可捲動選擇器。渲染 selected/unselected 項目列表，每行保證精確寬度。用於 slash menu、agent 選擇。 |
| **TuiMessage** | TUI Message | 對話訊息格式化。inline 模式（2 行 icon+detail）和 block 模式（多行 wrap + role icon）。借鑑 OpenCode InlineTool/BlockTool。 |
