# Grimo Interactive Shell 設計規格

## 概述

Grimo 的互動介面採用雙模式架構：**對話模式**（預設）與 **Grimoire 模式**（全螢幕 TUI）。對話模式以自然語言和 `/指令` 混合輸入為核心，Grimo 自動判斷意圖後路由到 agent、執行 skill 或建立 task。Grimoire 模式提供全螢幕監控面板，用 Tab 切換不同資訊類別。

## 設計目標

- 像 Claude Code CLI 一樣方便的對話式互動
- `/指令` 按 Tab 自動補全，降低操作負擔
- 自然語言輸入自動判斷意圖（路由 agent / 執行 skill / 建立 task）
- 全螢幕 Grimoire 模式用於監控 agent 活動、任務狀態等

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
- **意圖判斷**：透過 `AgentRouter` 將自然語言路由到適當的 agent
- **狀態管理**：各模組 registry（`AgentProviderRegistry`、`ChannelRegistry` 等）提供即時資料

---

## 對話模式（V1）

### 互動流程

```
grimo:> 每天早上九點檢查 API 健康狀態
🤖 [claude-cli] 已建立排程任務 task-20260322-a1b2c3d4
   Cron: 0 9 * * *
   描述: 檢查 API 健康狀態

grimo:> @claude-cli 分析這個錯誤 @logs/error.log
🤖 [claude-cli] 根據 error.log 分析...
   NullPointerException at line 42...

grimo:> /status
  Agents: 2 configured (1 available)
  Channels: 1 configured (1 enabled)
  Skills: 3 loaded

grimo:> /grimoire
[進入全螢幕 Grimoire 模式]
```

### V1 功能清單

#### 1. `/指令` Tab 補全

利用 Spring Shell JLine 的 `CommandCompleter`（已自動配置），所有 `@Command` 註冊的指令都可 Tab 補全。

**內建指令：**
- `/status` — 系統狀態總覽
- `/grimoire` — 進入全螢幕監控模式
- `/agent list` — 列出 agent
- `/agent use <id>` — 切換預設 agent
- `/task list` — 列出任務
- `/task create` — 建立任務
- `/skill list` — 列出 skill
- `/mcp list` — 列出 MCP 連線
- `/channel list` — 列出 channel
- `/exit` — 結束程式

#### 2. Status Bar

使用 Spring Shell 的 `PromptProvider` 在 prompt 中顯示關鍵資訊。

```
[claude-cli] grimo:>
```

Prompt 顯示目前使用的 agent。系統狀態（任務數、MCP 連線數）透過 `/status` 指令查看。

#### 3. 指定 Agent / LLM

兩種方式：
- **行內指定**：`@claude-cli 幫我分析...` — 本次對話使用指定 agent
- **切換預設**：`/agent use anthropic` — 後續對話都使用該 agent

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
3. **自然語言** → 路由到預設 agent，由 agent 判斷是否需要呼叫 skill 或建立 task

設計說明：V1 的意圖判斷相對簡單 — 自然語言全部送給 agent 處理，agent 自身的能力（透過 system prompt 中的 skill 定義）決定是否觸發 skill 或 task。不做本地 NLP 意圖分類。

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
║  claude-cli      CLI    🟢 ready     ║
║  anthropic       API    🟢 ready     ║
║  ollama          API    ⚫ offline   ║
║                                      ║
║  ACTIVE EXECUTIONS                   ║
║  ─────────────────────────────       ║
║  [claude-cli] 分析 error.log... 12s  ║
╚═══════════════════════════════════════╝
```

資料來源：`AgentProviderRegistry.listAll()` + agent 執行追蹤

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

---

## 參考資料

- [Spring Shell 4.0 TUI Documentation](https://docs.spring.io/spring-shell/reference/tui/index.html)
- [Spring Shell Custom Prompt](https://docs.spring.io/spring-shell/reference/customization/custom-prompt.html)
- [Spring Shell Commands - Reading Input](https://docs.spring.io/spring-shell/reference/commands/reading.html)
- [Spring Shell Commands - Writing Output](https://docs.spring.io/spring-shell/reference/commands/writing.html)
- [Spring Shell Building](https://docs.spring.io/spring-shell/reference/building.html)
- [Claude Code CLI Interactive Mode](https://code.claude.com/docs/en/interactive-mode)
- [Claude Code CLI Status Line](https://code.claude.com/docs/en/statusline)
