# 用 Spring Shell TerminalUI 重建互動介面

> 日期：2026-03-25
> 狀態：計畫中

## Context

多次嘗試用 JLine LineReader + Status API + fill-lines/ANSI positioning 實現 Claude Code 風格佈局均失敗。
根因：LineReader 的 prompt 機制和 Status 的 scroll region 無法精確控制佈局間距。

Spring Shell 4.0 內建 TerminalUI 框架（AppView + StatusBarView + InputView），
提供完整 TUI 渲染引擎，像 React/Ink 一樣 100% 控制終端佈局。

## 目標佈局

### 啟動初始狀態

```
┌──────────────────────────────────────────┐
│                                          │
│                                          │
│                                          │
│                                          │
│                                          │
│                                          │ ← content 區
│                                          │   （可滾動）
│   ✦     Grimo v0.0.1                    │
│ ▄████▄  claude-cli · unknown            │
│ █●██●█  ~/grimo-workspace               │
│ ██████  1 agent · 0 skill               │
│ ▀▄▀▀▄▀                                  │
├──────────────────────────────────────────┤
│ ❯ █                                     │ ← input 區（固定）
├──────────────────────────────────────────┤
│ claude-cli · unknown │ ~/grimo │ 1 agent │ ← status 區（固定）
│                                          │
│                                          │ ← 斜線指令選單區（隱藏）
│                                          │   （輸入 / 時動態出現）
│                                          │
└──────────────────────────────────────────┘
```

### 送出對話

```
┌──────────────────────────────────────────┐
│                                          │
│                                          │
│   ✦     Grimo v0.0.1                    │
│ ▄████▄  claude-cli · unknown            │ ← content 區
│ █●██●█  ~/grimo-workspace               │
│ ██████  1 agent · 0 skill               │
│ ▀▄▀▀▄▀                                  │
│                                          │
│ ❯ 你好                                   │
│                                          │
│ ✻ Thinking…                              │ ← 思考動畫（一行）
├──────────────────────────────────────────┤
│ ❯ █                                     │ ← input 區（固定）
├──────────────────────────────────────────┤
│ claude-cli · unknown │ ~/grimo │ 1 agent │ ← status 區（固定）
└──────────────────────────────────────────┘
```

### AI 回覆

```
┌──────────────────────────────────────────┐
│                                          │
│   ✦     Grimo v0.0.1                    │
│ ▄████▄  claude-cli · unknown            │
│ █●██●█  ~/grimo-workspace               │
│ ██████  1 agent · 0 skill               │ ← content 區
│ ▀▄▀▀▄▀                                  │
│                                          │
│ ❯ 你好                                   │
│                                          │
│ ⏺ 你好！有什麼我可以幫你的嗎              │
│                                          │
├──────────────────────────────────────────┤
│ ❯ █                                     │ ← input 區（固定）
├──────────────────────────────────────────┤
│ claude-cli · unknown │ ~/grimo │ 1 agent │ ← status 區（固定）
└──────────────────────────────────────────┘
```

### Content 區格式（仿 Claude Code）

```
│ ❯ 你好                                   │ ← 使用者輸入（❯ 前綴，深色背景行）
│                                          │
│ ⏺ 你好！有什麼我可以幫你的嗎              │ ← AI 回覆（⏺ 前綴，一般文字）
│                                          │
│ ❯ /brainstorming                         │ ← skill 呼叫（同樣 ❯ 格式）
│                                          │
│ ⏺ 你想要討論什麼功能或想法呢？請告訴我    │ ← skill 回覆（同樣 ⏺ 格式）
│   你想 brainstorm 的主題。                │
│                                          │
│ ❯ /agent list                            │ ← 命令呼叫（同樣 ❯ 格式）
│                                          │
│   claude-cli  ✓  available               │ ← 命令輸出（直接顯示）
```

**統一規則**：所有互動（對話、skill、命令）都在 content 區用相同格式呈現。
- 使用者輸入：`❯` 前綴 + 深色背景
- AI/系統回覆：`⏺` 前綴 + 一般文字
- 命令輸出：直接顯示（無前綴）

### 多次對話 歷史紀錄（banner 等同對話紀錄 被擠出視窗）

```
┌──────────────────────────────────────────┐
│ ██████  1 agent · 0 skill               │
│ ▀▄▀▀▄▀                                  │
│                                          │
│ ❯ 你好                                   │
│                                          │
│ ⏺ 你好！有什麼我可以幫你的嗎              │
│                                          │ ← content 區
│ ❯ 你是誰                                 │
│                                          │
│ ⏺ 我是 Claude Code，Anthropic 的 CLI    │
│   工具。我可以幫你進行軟體開發相關的工作。  │
│                                          │
├──────────────────────────────────────────┤
│ ❯ █                                     │ ← input 區（固定）
├──────────────────────────────────────────┤
│ claude-cli · unknown │ ~/grimo │ 1 agent │ ← status 區（固定）
└──────────────────────────────────────────┘
```

### 斜線命令選單（輸入 / 時）

```
┌──────────────────────────────────────────┐
│ ⏺ 你好！有什麼我可以幫你的嗎              │
│                                          │ ← content 區
│ ❯ 你是誰                                 │
│                                          │
│ ⏺ 我是 Claude Code，Anthropic 的 CLI    │
│ ┌──────────────────────────────────────┐ │
│ │  /agent        List all agents       │ │ ← modal overlay
│ │  /agent list   List all agents       │ │   覆蓋 content 底部
│ │  /agent model  Switch default model  │ │   input/status 不動
│ │  /agent use    Switch default agent  │ │
│ │  /channel      List channels         │ │
│ └──────────────────────────────────────┘ │
├──────────────────────────────────────────┤
│ ❯ /ag█                                  │ ← input 區（不動）
├──────────────────────────────────────────┤
│ claude-cli · unknown │ ~/grimo │ 1 agent │ ← status 區（不動）
└──────────────────────────────────────────┘
```

**斜線指令選單行為**：
- 輸入 `空格 + /` 或行首 `/` 時出現（modal overlay 覆蓋 content 底部，最多 5 項）
- 隨輸入即時過濾（`/ag` → 只顯示 agent 相關命令）
- `↑/↓` 方向鍵選擇
- `Tab` 或 `Enter` 填入選中命令到 input
- `Esc` 或清空 `/` 取消，列表消失
- 不輸入 `/` 時列表隱藏，status 下方無內容

**斜線指令選單配色**：
- 選中項：**系統標誌色**（steel blue, ANSI 256 色碼 67 `#5F87AF`）整行文字變色
- 未選中項：**預設文字色**（terminal default，不變灰）
- 不使用 ❯ 標記，純粹用字色區分

```
  /agent        List all agents           ← 預設色（未選中）
  /agent list   List all agents           ← 標誌色 #5F87AF（選中）
  /agent model  Switch default model      ← 預設色
  /agent use    Switch default provider   ← 預設色
  /channel      List channels             ← 預設色
```

**系統標誌色統一**：
- 色碼：ANSI 256 color 67（`\033[38;5;67m`，近似 `#5F87AF` steel blue）
- 用途：選中斜線指令 文字色、吉祥物 logo 色、品牌強調色
- 取代現有 cyan（ANSI 37, `#00AFAF`）作為品牌主色
- 影響檔案：`BannerRenderer.java`、`StartupAnimationRenderer.java`、`GrimoSlashCommandListView.java`

## 斜線指令命名規範（Slash Command Naming）

對齊 Claude Code，所有斜線指令使用 **kebab-case**，扁平結構（無子命令）。

| 類型 | 舊格式 | 新格式（kebab-case） |
|------|--------|---------------------|
| 應用命令 | `/agent list` | `/agent-list` |
| 應用命令 | `/agent model` | `/agent-model` |
| 應用命令 | `/agent use` | `/agent-use` |
| 應用命令 | `/task create` | `/task-create` |
| 應用命令 | `/mcp list` | `/mcp-list` |
| Skills | `/brainstorming` | `/brainstorming`（已符合） |
| 系統命令 | `/exit` | `/exit`（已符合） |

**規則**：
- 全小寫 + 連字號（kebab-case），最長 64 字元
- 無空格、無子命令層級
- 影響：`@Command` 註解的 name 屬性、`SlashStrippingCommandParser`、`GrimoCommandCompleter`

## 操作設計

### 對話操作

1. 使用者在 input 區輸入文字，按 Enter 送出
2. 輸入內容移至 content 區顯示（`❯ 使用者輸入`，深色背景）
3. input 區恢復為 1 行空白狀態
4. AI 回覆以 streaming 方式逐字顯示在 content 區（`⏺ AI 回覆`）
5. Streaming 完成後，input 區重新接受輸入

**Streaming 支援**：依據使用的 agent/API 能力，支援 streaming（逐字）和非 streaming（整段）兩種模式。

### Input 區多行輸入

- 預設 1 行高
- **Shift + Enter**：換行（不送出），input 區高度自動增長
- 文字超出寬度：自動 wrap 到下一行，input 高度自動增長
- **Enter**：送出輸入，input 恢復為 1 行空白
- GridView row 高度動態調整：送出後 `gridView.setRowSize(0, 1, 1)` + `ui.redraw()`

```
一般狀態（1 行）：
├──────────────────────────────────┤
│ ❯ 你好█                         │ ← 1 行
├──────────────────────────────────┤

多行狀態（Shift+Enter 或 wrap）：
├──────────────────────────────────┤
│ ❯ 這是第一行                     │ ← 動態增長
│   這是第二行                      │
│   這是第三行█                     │
├──────────────────────────────────┤
```

### 斜線指令操作（Slash Command）

**涵蓋範圍**：所有 `/` 開頭的指令統一稱為「斜線指令」，包括：
- Skills：`/brainstorming`、`/writing-plans` 等
- 應用命令：`/agent-list`、`/task-create`、`/mcp-list` 等（kebab-case）
- 系統命令：`/exit`、`/help`、`/clear` 等

**觸發條件**：輸入 `空格 + /` 或行首 `/` 時觸發斜線指令選單

**操作流程**：
```
1. 使用者輸入文字：  dsdfijso █
2. 輸入空格 + /：   dsdfijso /█        ← 斜線指令選單出現（modal overlay）
3. 繼續輸入過濾：    dsdfijso /bra█     ← 選單即時過濾
4. ↑/↓ 選擇：      游標移動，選中項變標誌色
5. Tab 確認：       dsdfijso /brainstorming █  ← 填入 + 空格 + 選單關閉
```

**填入後的 input 呈現**（仿 Claude Code）：
```
❯ dsdfijso /brainstorming dsmsmgsdmg█
           ^^^^^^^^^^^^^^
           標誌色（#5F87AF）顯示
           前後自動加空格
```

**斜線指令名稱在 input 中以標誌色顯示**，其他文字用預設色。
前後保持空格分隔。

### 斜線指令執行

所有斜線指令共用同一個選單。選取或直接輸入後按 Enter 執行：
- 應用命令（`/agent list` 等）→ `CommandParser.parse()` → `CommandExecutor.execute()` → 輸出到 content 區
- Skills（`/brainstorming` 等）→ 由 SkillRegistry 處理 → AI 對話模式
- 系統命令（`/exit`）→ 直接執行（結束 TUI）

### 退出操作

- 輸入 `/exit` + Enter → 結束 TUI 事件迴圈，應用程式退出
- Ctrl+D → 同 `/exit`
- Ctrl+C → 清空目前 input 內容（不退出）

### AI 回覆 Streaming

```
content 區顯示：
│ ❯ 你好                                   │
│                                          │
│ ⏺ 你好！有什麼我可以│                      │ ← streaming 中（逐字出現）
│   ✻ Thinking…                            │ ← 或顯示思考動畫
```

Streaming 期間 input 區仍可見但不接受輸入（或顯示等待狀態）。

### Content 區滾動（滑鼠/觸控板）

啟用 JLine mouse tracking，Content 區支援滾動瀏覽歷史對話：

```java
// 啟用滑鼠追蹤（含滾輪事件）
terminal.trackMouse(Terminal.MouseTracking.Any);

// 訂閱滾動事件（MouseEvent 用 has(int) 判斷滾輪方向）
// 滾輪上=button 4, 滾輪下=button 5（xterm mouse protocol）
eventLoop.mouseEvents()
    .subscribe(e -> {
        if (e.has(64))  contentView.scrollUp(3);    // WheelUp
        if (e.has(65))  contentView.scrollDown(3);   // WheelDown
        ui.redraw();
    });
```

> **SDK 備註**：`MouseEvent` 沒有 `isWheelUp()` 等便利方法，需用 `has(int)` + xterm mouse button 常數。
> 實作時需驗證 button 64/65 是否正確對應滾輪事件。

**滾動行為**：
- 滑鼠滾輪 / Mac 觸控板兩指上下 → 滾動 Content 區
- 滾到底部時自動鎖定（新內容進來自動跟隨）
- 滾動中途有新內容 → 不自動跳到底部（避免打斷閱讀）
- 使用者主動滾到最底部 → 恢復自動跟隨
- 退出 TUI 前 `terminal.trackMouse(null)` 關閉追蹤

**GrimoContentView 滾動 API**：
```java
int scrollOffset = 0;           // 當前滾動偏移
boolean autoFollow = true;      // 是否自動跟隨最新內容

void scrollUp(int lines)   { scrollOffset = Math.max(0, scrollOffset - lines); autoFollow = false; }
void scrollDown(int lines)  { scrollOffset = Math.min(maxOffset(), scrollOffset + lines);
                              if (scrollOffset >= maxOffset()) autoFollow = true; }
void appendLine(...)        { lines.add(...); if (autoFollow) scrollOffset = maxOffset(); }
```

### Session 對話存檔

每次 TUI session 的對話紀錄持久化到 `~/grimo-workspace/conversations/`。

**檔案格式**：JSONL（JSON Lines），每行一個 JSON 物件，append-only。
對齊 Claude Code 的 session 檔案設計。

**檔案路徑**（對齊 Claude Code 結構）：
```
~/grimo-workspace/
  └─ projects/
      └─ <encoded-cwd>/           ← 工作目錄路徑，非字母數字換 -
          └─ sessions/
              └─ <session-uuid>.jsonl
```
範例：工作目錄 `/Users/samzhu/my-project`
→ `~/grimo-workspace/projects/-Users-samzhu-my-project/sessions/a1b2c3d4-e5f6-7890.jsonl`

**JSONL 每行格式**（對齊 Claude Code）：
```jsonl
{"type":"system","sessionId":"a1b2c3d4","timestamp":"2026-03-25T10:30:00Z","uuid":"msg-001","message":{"role":"system","content":"...system prompt + tool definitions..."},"cwd":"/Users/samzhu/my-project","version":"0.0.1"}
{"type":"user","sessionId":"a1b2c3d4","timestamp":"2026-03-25T10:30:05Z","uuid":"msg-002","parentUuid":"msg-001","message":{"role":"user","content":"你好"}}
{"type":"assistant","sessionId":"a1b2c3d4","timestamp":"2026-03-25T10:30:08Z","uuid":"msg-003","parentUuid":"msg-002","message":{"role":"assistant","content":"你好！有什麼我可以幫你的嗎？"}}
{"type":"user","sessionId":"a1b2c3d4","timestamp":"2026-03-25T10:31:00Z","uuid":"msg-004","parentUuid":"msg-003","message":{"role":"user","content":"/agent list"}}
{"type":"command","sessionId":"a1b2c3d4","timestamp":"2026-03-25T10:31:01Z","uuid":"msg-005","parentUuid":"msg-004","message":{"role":"assistant","content":"claude-cli  ✓  available"},"command":"agent list"}
```

**每行共通欄位**：
| 欄位 | 說明 |
|------|------|
| `type` | 訊息類型：`system`、`user`、`assistant`、`command`、`tool_use`、`tool_result` |
| `sessionId` | Session UUID（與檔名一致，resume 時用於識別） |
| `timestamp` | ISO 8601 時間戳 |
| `uuid` | 訊息唯一 ID |
| `parentUuid` | 父訊息 ID（支援對話樹結構） |
| `message` | `{ "role": "...", "content": "..." }`（對齊 LLM API 格式） |
| `cwd` | 工作目錄（首行 system 訊息必填，後續可省略） |
| `version` | Grimo 版本（首行 system 訊息必填） |

**Message types**：
| type | 說明 |
|------|------|
| `system` | Session 首行：完整 system prompt + agent/model 設定 |
| `user` | 使用者輸入（對話或斜線指令） |
| `assistant` | AI 回覆（streaming 完成後寫入完整文字） |
| `command` | 斜線指令執行結果 |
| `tool_use` | Agent 工具呼叫（未來擴展） |
| `tool_result` | 工具執行結果（未來擴展） |

**寫入時機**：
- Session 啟動 → 寫入 `system`（含 prompt、agent、model、cwd、version）
- 使用者按 Enter → 立即寫入 `user`
- AI 回覆完成 → 寫入 `assistant`
- 命令執行完成 → 寫入 `command`

**Resume 功能**：
- `grimo --resume` → 列出最近 sessions，選擇後載入對話紀錄到 Content 區
- `grimo --resume <session-id>` → 直接恢復指定 session
- 恢復時讀取 JSONL，重建 Content 區的對話紀錄，保持完整 context

### Terminal Resize

- TerminalUI 框架自動處理 resize 事件
- GridView 重新計算各區域大小
- Content 區 flex 適應新高度
- Input + Status 固定行高不變

### Status 動態更新（Spring Modulith Event）

斜線指令（如 `/agent-use`）執行後可能改變 agent/model。
透過 Spring Modulith domain event 解耦，命令層不需知道 UI 存在。

**事件流**：
```
AgentCommands                  EventPublisher              GrimoTuiRunner
     │                               │                         │
     │── /agent-use claude-cli ──────│                         │
     │   registry.setDefault(...)    │                         │
     │── publish(AgentChangedEvent)──→                         │
     │                               │── @ApplicationModuleListener
     │                               │   onAgentChanged(event) │
     │                               │   → statusBar.setItems()│
     │                               │   → ui.redraw()         │
```

**Domain Events**（新增到 `shared/` 模組）：
```java
public record AgentChangedEvent(String agentId, String model) {}
public record ResourceCountChangedEvent(int agents, int skills, int mcps, int tasks) {}
```

**TUI 端監聽**：
```java
@ApplicationModuleListener
void onAgentChanged(AgentChangedEvent event) {
    statusBarView.setItems(buildStatusItems(event.agentId(), event.model(), ...));
    ui.redraw();
}
```

**優點**：
- 命令層與 UI 層完全解耦
- 多監聽者可同時反應（Status 更新、Session JSONL 寫入）
- 符合項目既有的 Spring Modulith event 模式（`IncomingMessageEvent`、`OutgoingMessageEvent`）

### 錯誤處理

- 命令不存在：content 區顯示 `⚠ Unknown command: xxx`
- 執行失敗：content 區顯示 `⚠ Error: error message`
- Agent/API 連線失敗：content 區顯示錯誤，不中斷 TUI

## 架構設計

```
GrimoTuiRunner (ApplicationRunner)
│
├─ 啟動流程（偵測 agents/skills/MCP/tasks、組裝 banner）
│
└─ TerminalUI.run()  ← 阻塞式事件迴圈，取代 shellRunner.run()
    │
    └─ GridView (根佈局)
        ├─ contentView (row 0, flex)：banner + 對話紀錄（可滾動）
        ├─ inputView (row 1, 1行)：❯ 輸入行（固定）
        └─ statusBarView (row 2, 1行)：狀態資訊（固定）

    TerminalUI.setModal(slashCommandDialog)  ← 浮動 overlay，覆蓋 content 底部
        └─ slashCommandListView：斜線命令候選（最多 5 項，定位在 input 區正上方）
```

### 事件流

```
用戶輸入 → EventLoop.keyEvents()
  │
  ├─ 斜線指令選單模式（選單可見時）
  │   ├─ 普通字元 → 更新 input + 過濾選單
  │   ├─ ↑/↓ → 移動選中項（變標誌色）
  │   ├─ Tab → 填入選中斜線指令 到 input（前後加空格，標誌色顯示）+ 關閉選單
  │   ├─ Enter → 填入選中斜線指令 + 關閉選單
  │   ├─ Esc → 關閉選單，保留已輸入文字
  │   └─ Backspace → 刪字，如果 / 被刪除則關閉選單
  │
  └─ 一般模式（選單不可見時）
      ├─ 普通字元 → 更新 input
      │   └─ 偵測到「空格 + /」→ 開啟 斜線指令選單
      ├─ Enter → 送出輸入
      │   ├─ 含 /xxx token → 解析為命令 → CommandExecutor
      │   └─ 純文字 → 送給 AI agent 對話
      ├─ ↑/↓ → 歷史瀏覽
      ├─ Ctrl+C → 清空 input
      └─ Ctrl+D → 退出（同 /exit）
```

## 啟動流程循序圖

```
GrimoTuiRunner                              TerminalUI        GridView/Views
     │                                           │                │
     │─── run() ─────────────────────────────────│                │
     │                                           │                │
     │── workspace.init() ───────────────────────│                │
     │── agentDetector.detect() ─────────────────│                │
     │── skillLoader.loadAll() ──────────────────│                │
     │── mcpClient.connect() ────────────────────│                │
     │── taskScheduler.restore() ────────────────│                │
     │                                           │                │
     │── new TerminalUIBuilder(terminal).build()─┤                │
     │                                           │                │
     │── new GrimoContentView(bannerText) ───────│────────────────┤
     │── new GrimoInputView() ───────────────────│────────────────┤
     │── new StatusBarView(statusItems) ─────────│────────────────┤
     │── new GrimoSlashCommandListView(cmds) ────│────────────────┤
     │── new GridView(content, input, status) ───│────────────────┤
     │── new DialogView(slashCommandListView) ───│  (modal, 備用)  │
     │                                           │                │
     │── ui.setRoot(gridView, true) ─────────────┤                │
     │── registerInputHandlers() ────────────────┤                │
     │── sessionWriter.writeSystemMessage() ─────│  (JSONL 首行)   │
     │                                           │                │
     │── ui.run() ───────────────────────────────┤── 渲染初始畫面 ─┤
     │   (blocking event loop)                   │                │
     │   ← 等待使用者輸入 ─────────────────────────┤                │
```

## 命令執行流程循序圖

```
EventLoop     InputView     GrimoInputHandler   CommandParser   CommandExecutor   ContentView
    │              │                │                  │               │               │
    │─ keyEvent ──→│                │                  │               │               │
    │  (Enter)     │                │                  │               │               │
    │              │─ getInputText()→                  │               │               │
    │              │  "/agent list" │                  │               │               │
    │              │                │                  │               │               │
    │              │                │── parse("agent list") ──────────→│               │
    │              │                │  ← ParsedInput ─┤               │               │
    │              │                │                  │               │               │
    │              │                │── execute(ctx) ──│───────────────→               │
    │              │                │                  │  ← ExitStatus │               │
    │              │                │                  │               │               │
    │              │                │── appendOutput(result) ─────────│───────────────→│
    │              │                │                  │               │               │
    │              │← clear() ──────│                  │               │               │
    │              │                │── ui.redraw() ──→│               │               │
```

## 斜線命令選單流程循序圖

```
EventLoop     InputView     GrimoInputHandler    SkillListView     CommandRegistry
    │              │                │                  │                  │
    │─ keyEvent ──→│                │                  │                  │
    │  ('/')       │                │                  │                  │
    │              │─ getText()────→│                  │                  │
    │              │  "/"           │                  │                  │
    │              │                │── getCommands()──│──────────────────→
    │              │                │  ← all commands  │                  │
    │              │                │── show(commands)─→│                  │
    │              │                │  顯示 5 項        │                  │
    │              │                │                  │                  │
    │─ keyEvent ──→│                │                  │                  │
    │  ('a','g')   │                │                  │                  │
    │              │─ getText()────→│                  │                  │
    │              │  "/ag"         │                  │                  │
    │              │                │── filter("ag")──→│                  │
    │              │                │  過濾 → 4 項      │                  │
    │              │                │                  │                  │
    │─ keyEvent ──→│                │                  │                  │
    │  (↓ arrow)   │                │                  │                  │
    │              │                │── moveDown()────→│                  │
    │              │                │  selectedIndex++ │                  │
    │              │                │                  │                  │
    │─ keyEvent ──→│                │                  │                  │
    │  (Tab/Enter) │                │                  │                  │
    │              │                │── getSelected()─→│                  │
    │              │                │  ← "/agent list" │                  │
    │              │← setText("/agent list") ─────────│                  │
    │              │                │── hide()────────→│                  │
    │              │                │  列表消失          │                  │
```

## 檔案變更

### 新建

| 檔案 | 說明 |
|------|------|
| `GrimoAppView.java` | GridView 子類，定義三區佈局（content + input + status） |
| `GrimoContentView.java` | 上方內容區：顯示 banner + 對話紀錄，支援滾動 |
| `GrimoInputView.java` | 自建輸入元件（取代 InputView）：支援 getText/setText/游標移動/刪除 |
| `GrimoInputHandler.java` | 輸入處理：Enter 執行、Tab 補全、↑↓ 選擇/歷史、/ 斜線選單 |
| `GrimoSlashCommandListView.java` | 斜線命令候選列表：modal overlay，最多 5 項、即時過濾、↑↓ 選擇 |

### 修改

| 檔案 | 變更 |
|------|------|
| `GrimoTuiRunner.java` | 改用 `TerminalUI.run()` 取代 `shellRunner.run()`，移除動畫相關 |
| 所有 `@Command` 類別 | 命令名改為 kebab-case（`agent list` → `agent-list`） |

### 移除/停用

| 檔案 | 原因 |
|------|------|
| `GrimoPromptProvider.java` | TerminalUI 不使用 LineReader，PromptProvider 不再需要 |
| `StatusLineRenderer.java` | 被 StatusBarView 取代 |
| `StartupAnimationRenderer.java` | 動畫功能移除 |
| `SlashMenuRenderer.java` | 已刪除（被 GrimoSlashCommandListView 取代） |
| 手動 ANSI scroll region 程式碼 | 被 TerminalUI 佈局取代 |
| fill-lines 程式碼 | 不再需要 |

### 保留不動

| 檔案 | 原因 |
|------|------|
| `GrimoCommandCompleter.java` | 補全候選來源（SkillListView 取候選用） |
| `SlashStrippingCommandParser.java` | 命令解析（去 / 前綴） |
| `BannerRenderer.java` | banner 內容產生 |
| `StartupAnimationRenderer.java` | 開場動畫 |
| 所有 `@Command` 類別 | 命令邏輯不變 |

## 實作步驟

### Step 1: GridView 三區佈局 + Modal overlay

```java
// 根佈局：三區固定（斜線指令選單用 modal，不佔 GridView row）
GridView root = new GridView();
root.setRowSize(0, 1, 1);  // content(flex), input(1行), status(1行)
// addItem 簽名：(View, row, col, rowSpan, colSpan, layer, zIndex)
root.addItem(contentView, 0, 0, 1, 1, 0, 0);
root.addItem(grimoInputView, 1, 0, 1, 1, 0, 0);   // 自建，用 setDrawFunction
root.addItem(statusBarView, 2, 0, 1, 1, 0, 0);

// 斜線指令選單：modal overlay，定位在 input 正上方
var slashCommandDialog = new DialogView(slashCommandListView);
slashCommandDialog.setRect(0, terminal.getHeight() - 3 - 5, terminal.getWidth(), 5);
```

> **SDK 備註**：`GridView.addItem` 需 7 參數（含 layer, zIndex）。
> `BoxView.draw()` 是 final，自訂渲染用 `setDrawFunction(BiFunction<Screen, Rectangle, Rectangle>)`。

- Row 0：flex（佔滿剩餘空間）→ content
- Row 1：固定 1 行 → GrimoInputView（自建，支援 setText）
- Row 2：固定 1 行 → status
- 斜線指令選單：`ui.setModal(slashCommandDialog)` 覆蓋 content 底部，input/status 不動

BoxView 的 `setShowBorder(true)` 在 input 區上下自動繪製分隔線。

### Step 2: GrimoContentView — banner + 對話區

基於 BoxView + 內部 `List<AttributedString> lines` 實作滾動 + 底部對齊：
- 維護 `List<AttributedString> lines`（所有內容行）
- **底部對齊渲染**：`draw()` 時計算 `startRow = viewHeight - lines.size()`，內容靠底部顯示
  - 內容少於 view 高度 → 上方留空，內容在底部（如啟動時 banner 在底部）
  - 內容超過 view 高度 → 只渲染最後 N 行（自動滾動到底部）
- 新內容加在 list 末尾，autoFollow 模式下自動滾到底部
- 滑鼠滾輪可手動瀏覽歷史（見 Content 區滾動章節）

```
啟動時（5 行 banner，view 高度 20）：
  startRow = 20 - 5 = 15
  row 0-14: 空白
  row 15-19: banner 5 行    ← 底部對齊
```

```java
public class GrimoContentView extends BoxView {
    private final List<AttributedString> lines = new ArrayList<>();

    public void appendUserInput(String text) {
        // ❯ 前綴 + 深色背景
        lines.add(styledLine("❯ " + text, DARK_BG));
        lines.add(AttributedString.EMPTY);  // 空行
    }

    public void appendAiReply(String text) {
        // ⏺ 前綴 + 一般文字
        lines.add(styledLine("⏺ " + text, DEFAULT));
        lines.add(AttributedString.EMPTY);
    }

    public void appendCommandOutput(String text) {
        // 直接顯示（無前綴）
        for (String line : text.split("\n")) {
            lines.add(new AttributedString("  " + line));
        }
    }
}
```

**命令輸出重導向**：
`@Command` 方法透過 `CommandContext.outputWriter()` 輸出。
自建 `ContentPrintWriter extends PrintWriter`，將寫入的文字轉發到 `contentView.appendCommandOutput()`。

```java
var outputWriter = new ContentPrintWriter(contentView, ui);
var ctx = new CommandContext(parsedInput, commandRegistry, outputWriter, inputReader);
commandExecutor.execute(ctx);
```

### Step 3: GrimoSlashCommandListView — 斜線命令候選

基於 ListView 或 BoxView：
- 接收命令列表 `List<MenuItem>`（name + description）
- `filter(String prefix)` → 過濾並顯示匹配項（最多 5 項）
- `moveUp()` / `moveDown()` → 移動 ❯ 選中標記
- `getSelected()` → 回傳選中命令名
- `show()` / `hide()` → 控制可見性（GridView row 高度 0↔5）

### Step 4: 輸入處理 — 命令執行 + 斜線選單整合

事件處理偽碼（使用自建 GrimoInputView）：
```java
eventLoop.keyEvents().subscribe(event -> {
    int key = event.key();

    if (ui.getModal() != null) {
        // === 斜線指令選單模式（modal 可見）===
        switch (key) {
            case UP    -> slashCommandListView.moveUp();
            case DOWN  -> slashCommandListView.moveDown();
            case TAB, ENTER -> {
                // 填入選中斜線指令：前後加空格，斜線指令名以標誌色標記
                grimoInputView.insertSlashCommand(slashCommandListView.getSelected());
                ui.setModal(null);  // 關閉 modal
            }
            case ESC   -> ui.setModal(null);  // 關閉，保留已輸入文字
            case BACKSPACE -> {
                grimoInputView.deleteChar();
                String slashToken = grimoInputView.getCurrentSlashToken();
                if (slashToken == null) ui.setModal(null);
                else slashCommandListView.filter(slashToken.substring(1));
            }
            default    -> {
                grimoInputView.insertChar((char) key);
                String slashToken = grimoInputView.getCurrentSlashToken();
                if (slashToken != null) slashCommandListView.filter(slashToken.substring(1));
            }
        }
    } else {
        // === 一般模式 ===
        switch (key) {
            case ENTER -> {
                String text = grimoInputView.getText();
                contentView.appendUserInput(text);  // 顯示 ❯ 文字 到 content
                grimoInputView.clear();
                processInput(text);  // 命令或對話
            }
            case UP    -> historyPrev();
            case DOWN  -> historyNext();
            default    -> {
                grimoInputView.insertChar((char) key);
                // 偵測「空格 + /」或「行首 /」→ 開啟 斜線指令選單
                if (grimoInputView.shouldOpenSlashMenu()) {
                    slashCommandListView.filterAll();
                    ui.setModal(slashCommandDialog);
                }
            }
        }
    }
    ui.redraw();
});
```

**GrimoInputView 關鍵方法**：
- `getText()` / `setText(String)` — 取得/設定文字
- `insertChar(char)` / `deleteChar()` — 字元操作
- `insertSlashCommand(String skillName)` — 插入 斜線指令名（前後加空格，標誌色）
- `getCurrentSlashToken()` — 取得游標所在的 `/xxx` token（null = 非 skill 輸入中）
- `shouldOpenSlashMenu()` — 偵測是否剛輸入「空格+/」或「行首/」
- `clear()` — 清空
- `getAttributedText()` — 回傳含標誌色的 AttributedString（渲染用）

### Step 5: GrimoTuiRunner 整合

```java
@Override
public void run(ApplicationArguments args) throws Exception {
    // 1-4: 啟動流程（workspace、動畫、偵測、banner）

    // 5: 建構 TUI
    TerminalUI ui = new TerminalUIBuilder(terminal).build();

    var contentView = new GrimoContentView(bannerText);
    var grimoInputView = new GrimoInputView();  // 自建，支援 setText
    var statusBar = new StatusBarView(statusItems);
    var slashCommandListView = new GrimoSlashCommandListView(menuItems);

    // 三區 GridView（斜線指令選單用 modal）
    var root = buildLayout(contentView, grimoInputView, statusBar);
    ui.setRoot(root, true);

    // 斜線指令選單 modal（定位在 input 正上方）
    var slashCommandDialog = new DialogView(slashCommandListView);

    // 6: 註冊事件處理
    registerInputHandlers(ui, grimoInputView, contentView, statusBar,
                           slashCommandListView, slashCommandDialog);

    // 7: 啟動 TUI 事件迴圈（阻塞）
    ui.run();
}
```

### Step 6: 自製 History

由於不使用 LineReader，需自行實作：
- `List<String> history` + `int historyIndex`
- ↑ 鍵：`historyIndex--`，顯示前一筆
- ↓ 鍵：`historyIndex++`，顯示下一筆
- Enter 時加入 history

## 分階段實施

### Phase 1: 基礎佈局 + 輸入（Day 1）
- GridView 三區佈局（content + GrimoInputView + status）
- GrimoInputView（getText/setText/insertChar/deleteChar/多行支援）
- GrimoContentView（lines list + 底部對齊渲染 + 滾動）
- TerminalUI 啟動 + banner 顯示在 contentView
- StatusBarView 顯示狀態資訊
- 基本鍵盤事件（字元輸入、Enter、Shift+Enter 換行、Ctrl+C）
- **驗收**：啟動佈局正確、input/status 固定底部、可打字、banner 底部對齊

### Phase 2: 命令執行 + 命名重構（Day 2）
- 所有 `@Command` 改為 kebab-case 命名
- Enter → CommandParser.parse() → CommandExecutor.execute()
- ContentPrintWriter 命令輸出重導向到 contentView
- 錯誤處理（CommandNotFoundException、CommandExecutionException）
- `/exit` + Ctrl+D 退出
- **驗收**：`/agent-list`、`/help`、`/exit` 等命令可執行

### Phase 3: 斜線指令選單（Day 3）
- GrimoSlashCommandListView 實作（modal overlay，定位 input 上方）
- 行首 `/` 或 `空格+/` 觸發 → 即時過濾 → ↑↓ 選擇（標誌色）→ Tab/Enter 填入
- 最多 5 項，選中項以標誌色（#5F87AF）顯示
- 填入後斜線指令名在 input 中以標誌色渲染
- **驗收**：斜線指令選單完整互動

### Phase 4: 歷史 + 滾動（Day 4）
- ↑/↓ 歷史瀏覽（非斜線選單模式時）
- 滑鼠/觸控板滾動 Content 區（trackMouse + mouseEvents）
- autoFollow 邏輯（滾動中途不跳、滾到底部恢復跟隨）
- **驗收**：歷史瀏覽 + 滾動正常

### Phase 5: Session 存檔 + 清理（Day 5）
- SessionWriter：JSONL append-only 寫入
- 啟動時寫 system 訊息，每次互動寫 user/assistant/command
- 移除舊程式碼（StatusLineRenderer、GrimoPromptProvider、StartupAnimationRenderer、ANSI scroll region、fill-lines）
- 更新測試
- **驗收**：./gradlew build 通過、session JSONL 正確寫入

## API 驗證結果（2026-03-25 已驗證，javap 確認）

| API | 狀態 | 實際簽名 / 說明 |
|-----|------|----------------|
| `TerminalUIBuilder(Terminal).build()` | ✅ | 也接受 `TerminalUICustomizer...` |
| `TerminalUI.setRoot(View, boolean)` | ✅ | fullscreen 模式 |
| `TerminalUI.run()` | ✅ | 阻塞式事件迴圈 |
| `TerminalUI.setModal(View)` | ✅ | overlay 不影響底層佈局 |
| `TerminalUI.configure(View)` | ✅ | 對 view 套用預設（theme 等），在 setRoot 前呼叫 |
| `TerminalUI.getEventLoop()` | ✅ | 回傳 `EventLoop`（Reactor Flux） |
| `TerminalUI.redraw()` | ✅ | 手動觸發重繪 |
| `GridView.setRowSize(int...)` | ✅ | 支援 flex(0) + fixed(N) |
| `GridView.addItem(...)` | ⚠️ | **7 參數**：`(View, row, col, rowSpan, colSpan, layer, zIndex)` |
| `BoxView.draw()` | ⚠️ | **final 不可 override**，改用 `setDrawFunction(BiFunction<Screen, Rectangle, Rectangle>)` |
| `BoxView.setShowBorder(boolean)` | ✅ | 分隔線 |
| `Screen.writerBuilder()` | ✅ | `.color(int).style(int).build()` → `Writer.text(String, x, y)` |
| `InputView.getInputText()` | ✅ | 可取得輸入文字 |
| `InputView.setText()` | ❌ | **不存在，需自建 GrimoInputView** |
| `StatusBarView(StatusItem[])` | ✅ | `StatusItem.of(title)` / `StatusItem.of(title, action, hotKey, primary, priority)` |
| `eventLoop.keyEvents()` | ✅ | `Flux<KeyEvent>`，`KeyEvent.key()` / `hasCtrl()` / `isKey(int)` |
| `eventLoop.mouseEvents()` | ✅ | `Flux<MouseEvent>`，`MouseEvent.has(int)` 判斷滾輪（無 isWheelUp） |
| `DialogView(View, ButtonView...)` | ✅ | `setRect(x, y, w, h)` 定位 |
| `ListView<T>` | ⚠️ | ItemStyle 只有 `NOCHECK`/`CHECKED`/`RADIO`，無 highlight 樣式 |

### 關鍵設計決策（根據 SDK 驗證修正）

**自訂渲染用 `setDrawFunction()` 而非 override `draw()`**：
`BoxView.draw()` 是 final。GrimoContentView 和 GrimoInputView 用 `setDrawFunction(BiFunction<Screen, Rectangle, Rectangle>)` 注入自訂繪製邏輯。

```java
var contentView = new BoxView();
contentView.setDrawFunction((screen, rect) -> {
    var writer = screen.writerBuilder().color(67).build();  // 標誌色
    int startRow = Math.max(0, rect.height() - lines.size());
    for (int i = 0; i < Math.min(lines.size(), rect.height()); i++) {
        writer.text(lines.get(i), rect.x(), rect.y() + startRow + i);
    }
    return rect;
});
```

**斜線指令選單用 `BoxView` + `setDrawFunction()` 而非 `ListView`**：
ListView 的 ItemStyle 不支援純色彩高亮。改用 BoxView 自訂渲染，選中項用標誌色（ANSI 67），未選中用預設色。

**斜線指令選單用 Modal overlay（不影響佈局）**：
`ui.setModal(slashCommandDialog)` 在 **input 正上方**浮動出現（覆蓋 content 底部），
input/status 完全不移動。清除用 `ui.setModal(null)`。Modal 會攔截所有鍵盤事件，需手動路由。

**自建 GrimoInputView 取代 InputView**：
Spring Shell 的 InputView 沒有 `setText()`。用 BoxView + setDrawFunction 自建，
支援 `getText()`/`setText()`/游標移動/刪除/多行(Shift+Enter)/斜線指令標誌色渲染。

## 風險與緩解

| 風險 | 緩解 |
|------|------|
| InputView 不支援 cursor movement | 自行處理 ←→ 鍵、Home/End、Ctrl+A/E |
| 命令輸出重導向 | CommandContext 的 outputWriter 指向 contentView |
| GridView row 高度無法動態調整 | 改用 BoxView 手動控制 slashCommandListView 的可見性 |
| TerminalUI.run() 阻塞主執行緒 | 這是設計如此，ApplicationRunner 就是阻塞到結束 |
| 大量重構 | 分 4 Phase 實作，每 Phase 可獨立測試 |

## 已知框架問題

### Spring Shell TerminalUI Mouse Event Parsing 未完成

**問題**：`TerminalUI.run()` 內部啟用 mouse tracking（SGR mode 1006），但 `EventLoop` 從未呼叫 `reader.readMouseEvent()` 解析 SGR sequences。導致 raw bytes（如 `[<64;42;7M`）逐字元漏到 `keyEvents()` 成為亂碼。`mouseEvents()` 永遠不會觸發。

**根因**：EventLoop 的 input 讀取流程缺少 SGR mouse sequence 偵測和解析步驟。API 存在（`eventLoop.mouseEvents()`）但底層實作未完成。

**影響**：無法使用觸控板/滑鼠滾動 content 區。

**目前解法**：啟動後用 daemon thread 發送 ANSI disable sequences 關閉所有 mouse tracking modes。Content 區翻頁改用 Ctrl+U（上）/ Ctrl+D（下）鍵盤操作。

**參考**：
- [Spring Shell Discussion #760 — Terminal application view framework planning](https://github.com/spring-projects/spring-shell/discussions/760)
- [Spring Shell Issue #1045 — No mouse support in new windows terminal](https://github.com/spring-projects/spring-shell/issues/1045)
- [JLine Mouse Support Documentation](https://jline.org/docs/advanced/mouse-support/)

**未來方案**：
- 等待 Spring Shell 修復 EventLoop 的 mouse event parsing
- 或自建 SGR state machine 在 keyEvents 中解析 mouse sequences（已驗證可行，但因 mouse tracking 已關閉故未採用）
- 或遷移至 JLine raw Terminal + 自建 event loop（完全控制 mouse 處理）

### Spring Shell InputView 缺少 setText()

**問題**：`InputView.getInputText()` 可取得文字，但無 `setText()` 方法。無法程式化設定輸入內容（歷史瀏覽、斜線指令填入）。

**解法**：自建 `GrimoInputView`（extends BoxView + setDrawFunction）實作完整輸入元件。

### Spring Shell BoxView.draw() 是 final

**問題**：`BoxView.draw()` 標記為 `final`，無法 override。

**解法**：使用 `setDrawFunction(BiFunction<Screen, Rectangle, Rectangle>)` 注入自訂繪製邏輯。

## 技術參考

- [AppView :: Spring Shell](https://docs.spring.io/spring-shell/reference/tui/views/app.html)
- [StatusBarView :: Spring Shell](https://docs.spring.io/spring-shell/reference/tui/views/statusbar.html)
- [InputView :: Spring Shell](https://docs.spring.io/spring-shell/reference/tui/views/input.html)
- [GridView :: Spring Shell](https://docs.spring.io/spring-shell/reference/tui/views/grid.html)
- [ListView :: Spring Shell](https://docs.spring.io/spring-shell/reference/tui/views/list.html)
- [TerminalUI :: Spring Shell](https://docs.spring.io/spring-shell/reference/tui/intro/terminalui.html)
- [EventLoop :: Spring Shell](https://docs.spring.io/spring-shell/reference/tui/events/eventloop.html)

## 驗證

```bash
./gradlew test    # 單元測試
./run.sh          # 手動驗證
```

1. Banner 完整顯示在 content 區底部
2. Input 區（❯）和 status 區永遠固定在底部，不跳動
3. 對話紀錄在 content 區往上滾動，banner 最終被推出
4. 輸入 `/` → status 下方出現最多 5 項候選
5. ↑/↓ 方向鍵選擇候選（❯ 標記移動）
6. Tab/Enter 填入選中命令、Esc 取消
7. 隨輸入即時過濾候選（`/ag` → agent 相關）
8. 不輸入 `/` 時 ↑/↓ 瀏覽歷史
9. Enter 執行命令，輸出在 content 區
10. Ctrl+C 清空 / Ctrl+D 退出
