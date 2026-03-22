# Grimo Interactive Shell 設計規格

## 概述

Grimo 的互動介面採用雙模式架構：**對話模式**（預設）與 **Grimoire 模式**（全螢幕 TUI）。對話模式以自然語言和 `/指令` 混合輸入為核心，Grimo 自動判斷意圖後路由到 agent、執行 skill 或建立 task。Grimoire 模式提供全螢幕監控面板，用 Tab 切換不同資訊類別。

## 設計目標

- 像 Claude Code CLI 一樣方便的對話式互動
- `/指令` 按 Tab 自動補全，降低操作負擔
- 自然語言輸入自動判斷意圖（路由 agent / 執行 skill / 建立 task）
- 全螢幕 Grimoire 模式用於監控 agent 活動、任務狀態等
- 漸進式揭露（Progressive Disclosure）：簡單操作簡單做，進階功能按需展開

## 架構

### 雙模式切換

```
啟動 → 對話模式（預設）
         │
         ├─ 使用者輸入自然語言 → 意圖判斷 → agent/skill/task
         ├─ 使用者輸入 /指令 → 直接執行對應 command
         ├─ /grimoire → 進入 Grimoire 全螢幕模式
         │                  │
         │                  ├─ F1~F5 切換面板
         │                  ├─ q 或 Esc 返回對話模式
         │                  └─ 即時更新 agent/task/channel 狀態
         └─ /exit → 結束程式
```

### 技術堆疊

- **對話模式**：Spring Shell 4.0 `@Command` + JLine（tab 補全、歷史記錄、key event）
- **Grimoire 模式**：Spring Shell TUI（`AppView` + `MenuBarView` + `StatusBarView` + `ListView`）
- **意圖判斷**：透過設定的主要 agent/LLM 將自然語言路由到適當的處理
- **狀態管理**：各模組 registry（`AgentProviderRegistry`、`ChannelRegistry` 等）提供即時資料

---

## 指令設計：漸進式揭露

### 設計原則

採用業界最佳實踐的**漸進式揭露**（Progressive Disclosure）模式，參考 Taskwarrior、GitHub CLI、Docker CLI 的設計：

- **裸指令 = 最常用操作**：`/task` 直接等於 `/task list`，不用每次多打 `list`
- **缺少參數 = 互動式提問**：`/task create` 缺少描述時互動式詢問
- **完整參數 = 直接執行**：`/task create --desc "..." --cron "..."` 可用於自動化

Reference:
- [Command Line Interface Guidelines (clig.dev)](https://clig.dev/)
- [Taskwarrior Syntax](https://taskwarrior.org/docs/syntax/)
- [GitHub CLI Manual](https://cli.github.com/manual/)

### Smart Defaults 規則

所有指令統一套用：

1. **裸指令 = list**：`/xxx` 等同 `/xxx list`，顯示該類別的列表
2. **缺少必要參數 = 互動式提問**：`/xxx create` 缺少參數時互動式詢問（if TTY）
3. **完整參數 = 直接執行**：`/xxx create --flag value` 無需互動，可用於自動化

### 指令對照表

#### /task — 任務管理

| 輸入 | 行為 |
|------|------|
| `/task` | 顯示任務列表 |
| `/task create` | 互動式建立任務（提問描述、cron 等） |
| `/task create --desc "檢查 API" --cron "0 9 * * *"` | 直接建立 |
| `/task show <id>` | 顯示任務詳情 |
| `/task cancel <id>` | 取消任務 |

#### /agent — Agent 管理

| 輸入 | 行為 |
|------|------|
| `/agent` | 顯示 agent 列表及狀態 |
| `/agent use <id>` | 切換預設 agent |
| `/agent model <name>` | 切換預設模型 |
| `/agent add` | 互動式新增 agent（選擇 type、輸入 API key 等） |
| `/agent add --type api --id openai --key sk-...` | 直接新增 |
| `/agent remove <id>` | 移除 agent |

#### /mcp — MCP 連線管理

| 輸入 | 行為 |
|------|------|
| `/mcp` | 顯示 MCP 連線列表及工具數 |
| `/mcp add` | 互動式新增連線（選擇 transport、輸入 command 等） |
| `/mcp add --name github --transport stdio --command "npx @modelcontextprotocol/server-github"` | 直接新增 |
| `/mcp remove <name>` | 移除連線 |

#### /skill — Skill 管理

| 輸入 | 行為 |
|------|------|
| `/skill` | 顯示已載入 skill 列表 |
| `/skill install <url>` | 從 Git repo 安裝 skill |
| `/skill remove <name>` | 移除 skill |

#### /channel — Channel 管理

| 輸入 | 行為 |
|------|------|
| `/channel` | 顯示 channel 列表及狀態 |
| `/channel add` | 互動式新增 channel（選擇 type、輸入 token 等） |
| `/channel add --type telegram --token "..."` | 直接新增 |
| `/channel remove <type>` | 移除 channel |

#### 全域指令

| 輸入 | 行為 |
|------|------|
| `/config` | 進入互動式設定（預設 agent、model、workspace 等） |
| `/status` | 系統狀態總覽 |
| `/grimoire` | 進入全螢幕 Grimoire 監控模式 |
| `/exit` | 結束程式 |
| 自然語言 | 路由到預設 agent 處理 |

---

## Agent / LLM 設定管理

### 設計說明

使用者需要在啟動時或運行中選擇哪個 agent/LLM 和哪個模型作為主要的意圖判斷和對話 router。此設定存在 workspace 的 `config.yaml` 中，也可透過 `/config` 互動式修改。

### config.yaml 格式

```yaml
agents:
  default: anthropic          # 預設使用的 agent ID
  model: claude-sonnet-4      # 預設模型（agent 支援時使用）
```

### 設定方式

#### 1. 互動式設定（`/config`）

```
grimo:> /config

  Grimo Configuration
  ───────────────────
  1. Default Agent
     Current: anthropic
     Available: [claude-cli, anthropic, openai, ollama]
     Select: █

  2. Default Model
     Current: claude-sonnet-4
     Available: [claude-sonnet-4, claude-haiku-4-5, gpt-4o]
     Select: █

  3. Workspace Path
     Current: ~/grimo-workspace

  [Save] [Cancel]
```

設計說明：`/config` 使用 Spring Shell TUI 的 `ComponentFlow`（互動式表單流程）實作，
提供選單式選擇，不需要記住 agent ID 或模型名稱。

#### 2. 指令式設定

```
grimo:> /agent use anthropic
✅ Default agent switched to: anthropic

grimo:> /agent model claude-sonnet-4
✅ Default model switched to: claude-sonnet-4
```

#### 3. 行內臨時指定

```
grimo:> @claude-cli 幫我分析這個錯誤
→ 本次使用 claude-cli，不改變預設設定

grimo:> @openai 翻譯這段文字
→ 本次使用 openai
```

### 啟動時的 Agent 選擇邏輯

```
啟動 → 讀取 config.yaml 的 agents.default
         │
         ├─ 有設定且 agent 可用 → 使用該 agent
         ├─ 有設定但 agent 不可用 → 警告，fallback 到自動偵測
         └─ 未設定 → AgentRouter 自動選擇（偏好 CLI > API）
```

設計說明：第一次啟動時如果沒有任何 agent 可用（沒有 API key、沒有 CLI 工具），
Grimo 仍可運作（`/指令` 都可用），但自然語言對話功能不可用，
prompt 會提示使用者設定 agent：`[no agent] grimo:>`。

---

## 對話模式（V1）

### 互動流程

```
[anthropic] grimo:> 每天早上九點檢查 API 健康狀態
🤖 [anthropic/claude-sonnet-4] 已建立排程任務 task-20260322-a1b2c3d4
   Cron: 0 9 * * *
   描述: 檢查 API 健康狀態

[anthropic] grimo:> @claude-cli 分析這個錯誤 @logs/error.log
🤖 [claude-cli] 根據 error.log 分析...
   NullPointerException at line 42...

[anthropic] grimo:> /task
  ID                    TYPE       STATUS     DESCRIPTION
  task-0322-a1b2c3d4    CRON       PENDING    檢查 API 健康狀態

[anthropic] grimo:> /config
[進入互動式設定]

[anthropic] grimo:> /grimoire
[進入全螢幕 Grimoire 模式]
```

### V1 功能清單

#### 1. `/指令` Tab 補全

利用 Spring Shell JLine 的 `CommandCompleter`（已自動配置），所有 `@Command` 註冊的指令都可 Tab 補全。

裸指令（如 `/task`、`/agent`）預設執行 list 操作，遵循漸進式揭露原則。

#### 2. Status Bar（Prompt）

使用 Spring Shell 的 `PromptProvider` 在 prompt 中顯示目前使用的 agent 和模型。

```
[anthropic/claude-sonnet-4] grimo:>
```

無 agent 時顯示：
```
[no agent] grimo:>
```

#### 3. 指定 Agent / LLM

三種方式（從持久到臨時）：

| 方式 | 範圍 | 範例 |
|------|------|------|
| `/config` | 持久化到 config.yaml | 互動式選擇 |
| `/agent use <id>` | 本次 session | `/agent use openai` |
| `@<agent-id> ...` | 本次訊息 | `@claude-cli 分析...` |

設計說明：`@` 前綴同時用於 agent 指定和檔案引用，透過以下規則區分：
- `@<agent-id>` — 如果名稱在 `AgentProviderRegistry` 中存在，視為 agent
- `@<path>` — 否則視為檔案路徑

#### 4. Esc 中斷

監聽 JLine key event，按 Esc 時：
- 如果 agent 正在回覆 → 中斷回覆，保留已收到的內容
- 如果在一般 prompt → 清除目前輸入行

實作方式：agent 呼叫使用 `CompletableFuture`，Esc 時呼叫 `future.cancel(true)`。

#### 5. `@` 檔案引用

自訂 JLine `Completer`，在使用者輸入 `@` 時：
- 掃描當前目錄和 workspace 目錄的檔案
- Tab 補全檔案路徑
- 送出時讀取檔案內容，注入到 agent 的 prompt 中

```
grimo:> 分析這個設定 @config.yaml
→ 讀取 config.yaml 內容，附加到 prompt 送給 agent
```

#### 6. 對話歷史

JLine 內建功能，已自動配置：
- 上下鍵翻閱之前的輸入
- 歷史記錄存在 `grimo.log`（由 `JLineShellAutoConfiguration` 自動配置）

### 意圖判斷設計

使用者輸入分為三類，依序判斷：

1. **`/` 開頭** → 直接路由到對應的 Spring Shell `@Command`
2. **`@agent-id` 開頭** → 使用指定 agent 處理後續文字
3. **自然語言** → 路由到預設 agent（config.yaml 的 `agents.default`），由 agent 判斷是否需要呼叫 skill 或建立 task

設計說明：V1 的意圖判斷相對簡單 — 自然語言全部送給預設 agent 處理，agent 自身的能力（透過 system prompt 中的 skill 定義）決定是否觸發 skill 或 task。不做本地 NLP 意圖分類。主要 agent/LLM 的選擇透過 `/config` 或 config.yaml 設定。

---

## Grimoire 模式（V1）

### 佈局

```
┌─────────────────────────────────────┐
│ [F1:Agents] [F2:Tasks] [F3:Channels]│ ← MenuBarView
│ [F4:MCP] [F5:System]               │
├─────────────────────────────────────┤
│                                     │
│   目前 Tab 的完整內容               │
│   （一次只看一個面板，空間充足）    │
│                                     │
├─────────────────────────────────────┤
│ 🟢 claude-cli | Tasks: 3 | MCP: 2  │ ← StatusBarView
└─────────────────────────────────────┘
```

按 `q` 或 `Esc` 返回對話模式。

### 面板內容

#### F1: Agents

```
╔═══════════════════════════════════════╗
║  AGENTS                              ║
╠═══════════════════════════════════════╣
║  ID              TYPE   STATUS       ║
║  ─────────────── ────── ──────       ║
║  ★ anthropic     API    🟢 ready     ║
║    claude-cli    CLI    🟢 ready     ║
║    ollama        API    ⚫ offline   ║
║                                      ║
║  DEFAULT: anthropic / claude-sonnet-4║
║                                      ║
║  ACTIVE EXECUTIONS                   ║
║  ─────────────────────────────       ║
║  [claude-cli] 分析 error.log... 12s  ║
╚═══════════════════════════════════════╝
```

★ 標記預設 agent。顯示目前使用的模型。

資料來源：`AgentProviderRegistry.listAll()` + `GrimoConfig` + agent 執行追蹤

#### F2: Tasks

```
╔═══════════════════════════════════════╗
║  SCHEDULED TASKS                     ║
╠═══════════════════════════════════════╣
║  ID                    STATUS  CRON  ║
║  ──────────────────── ─────── ────── ║
║  task-0322-a1b2  🟢 PENDING  0 9 ** ║
║  task-0321-c3d4  ✅ COMPLETED       ║
║  task-0320-e5f6  ❌ FAILED          ║
║                                      ║
║  EXECUTION HISTORY (last 5)          ║
║  ─────────────────────────────       ║
║  2026-03-22 09:00 — API 200 OK      ║
║  2026-03-21 09:00 — API timeout     ║
╚═══════════════════════════════════════╝
```

資料來源：`MarkdownTaskStore.loadAll()` + `TaskSchedulerService.getScheduledTaskIds()`

#### F3: Channels

```
╔═══════════════════════════════════════╗
║  CHANNELS                            ║
╠═══════════════════════════════════════╣
║  CHANNEL     STATUS                  ║
║  ─────────── ──────                  ║
║  telegram    🟢 enabled              ║
║  line        ⚫ disabled             ║
║                                      ║
║  RECENT MESSAGES                     ║
║  ─────────────────────────────       ║
║  [telegram] @user1: 查天氣  10:30   ║
║  [telegram] @user2: 設提醒  10:28   ║
╚═══════════════════════════════════════╝
```

資料來源：`ChannelRegistry.listAll()` + 訊息事件日誌

#### F4: MCP

```
╔═══════════════════════════════════════╗
║  MCP CONNECTIONS                     ║
╠═══════════════════════════════════════╣
║  NAME        TRANSPORT  TOOLS        ║
║  ─────────── ───────── ─────         ║
║  github      stdio      12           ║
║  filesystem  stdio       8           ║
║                                      ║
║  AVAILABLE TOOLS (github)            ║
║  ─────────────────────────────       ║
║  create_issue, list_repos, ...       ║
╚═══════════════════════════════════════╝
```

資料來源：`McpClientRegistry.listAll()`

#### F5: System

```
╔═══════════════════════════════════════╗
║  SYSTEM STATUS                       ║
╠═══════════════════════════════════════╣
║  Uptime:     2h 15m                  ║
║  Memory:     256MB / 512MB           ║
║  Workspace:  ~/grimo-workspace       ║
║  Skills:     3 loaded                ║
║  Tasks:      2 scheduled             ║
║                                      ║
║  CONVERSATION SUMMARY                ║
║  ─────────────────────────────       ║
║  Messages: 12 (session)              ║
║  Agent calls: 5                      ║
║  Last activity: 2 min ago            ║
╚═══════════════════════════════════════╝
```

### 技術實作

Grimoire 模式透過一個 `@Command` 觸發：

```java
@Command(name = "grimoire", description = "Open Grimoire full-screen monitoring")
public void grimoire() {
    TerminalUI ui = terminalUIBuilder.build();

    // 建立 MenuBarView + 各面板 ListView + StatusBarView
    // 組合成 AppView
    // ui.setRoot(appView, true)
    // ui.run()  ← 阻塞直到使用者按 q/Esc
}
```

使用 Spring Shell TUI 元件：
- `AppView`：整體佈局（menu + main + status）
- `MenuBarView`：F1~F5 tab 切換
- `ListView`：各面板的內容列表
- `StatusBarView`：底部狀態列
- `EventLoop`：key event 監聽（q/Esc 退出）

---

## V2 延後功能

- 串流回覆（AI 回應即時顯示）
- Markdown 渲染（語法高亮、程式碼區塊）
- 工具呼叫顯示（agent 正在呼叫什麼工具）
- 自訂 Prompt 樣式（顏色、圖示）
- Grimoire 面板即時自動刷新
- 對話 session 持久化與恢復
- `/config` 互動式設定使用 ComponentFlow TUI

---

## 參考資料

- [Spring Shell 4.0 TUI Documentation](https://docs.spring.io/spring-shell/reference/tui/index.html)
- [Spring Shell Custom Prompt](https://docs.spring.io/spring-shell/reference/customization/custom-prompt.html)
- [Spring Shell Commands - Reading Input](https://docs.spring.io/spring-shell/reference/commands/reading.html)
- [Spring Shell Commands - Writing Output](https://docs.spring.io/spring-shell/reference/commands/writing.html)
- [Spring Shell Building](https://docs.spring.io/spring-shell/reference/building.html)
- [Claude Code CLI Interactive Mode](https://code.claude.com/docs/en/interactive-mode)
- [Claude Code CLI Status Line](https://code.claude.com/docs/en/statusline)
- [Command Line Interface Guidelines (clig.dev)](https://clig.dev/)
- [Taskwarrior Syntax — Progressive Disclosure](https://taskwarrior.org/docs/syntax/)
- [GitHub CLI Manual](https://cli.github.com/manual/)
- [Lazygit Turns 5 — TUI Design Philosophy](https://jesseduffield.com/Lazygit-5-Years-On/)
- [Progressive Disclosure of Agent Tools](https://github.com/musistudio/claude-code-router/blob/main/blog/en/progressive-disclosure-of-agent-tools-from-the-perspective-of-cli-tool-style.md)
