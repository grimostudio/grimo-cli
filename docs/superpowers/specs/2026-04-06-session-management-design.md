# Session 管理功能設計

> Date: 2026-04-06
> Status: Draft
> Depends: Event-Driven TUI (✅ Done), SessionWriter (✅ Done)
> Parent: [F5: Session 管理](2026-03-27-f5-session-management.md)

## 問題

SessionWriter 已能記錄對話到 JSONL，但缺乏 session 生命週期管理：無法列出歷史 session、無法 resume 舊 session、無 session 索引供快速查詢。每次啟動 Grimo 都是全新對話，先前 context 完全消失。

## 研究基礎

### 業界 Session 機制比較

| 產品 | Resume 策略 | 儲存格式 | Picker |
|------|-----------|---------|--------|
| Claude Code | 完整還原 JSONL（`--continue` / `--resume`） | JSONL + sessions-index.json | 互動式 picker |
| Codex CLI | 完整 event stream replay（`codex resume`） | .jsonl.zst + SQLite index | 互動式 picker（分頁、排序） |
| Gemini CLI | 完整還原（`--resume` / `/resume`） | 按 project hash 分目錄 | 互動式 browser + 命名存檔點 |
| Aider | 摘要式 resume（`--restore-chat-history`，opt-in） | Markdown + input history | 無 picker |
| Cursor | `@Past Chats` 按需讀取 | SQLite JSON blobs | 無 picker |

來源：
- [Claude Code Compaction & Session](https://okhlopkov.com/claude-code-compaction-explained/)
- [Codex CLI Session Management](https://deepwiki.com/openai/codex/3.3-session-management-and-persistence)
- [Gemini CLI Session Management](https://geminicli.com/docs/cli/session-management/)
- [Aider Chat History](https://aider.chat/docs/config/options.html)

### 設計決策

**完整還原，不做摘要。** Grimo 是 orchestrator，JSONL 紀錄的是 Grimo 自身的對話歷史（user input、AI response、command output、dispatch record）。Context 壓縮是外部 agent CLI 的責任，不是 Grimo 的。Resume 就是載入原始紀錄。

## 目標

1. Session 生命週期管理：啟動建立、即時索引、resume 切換、退出自然結束
2. 互動式 session picker：TUI overlay + 啟動時 terminal picker
3. 啟動參數：`--continue`（最近 session）、`--resume`（picker 或指定 ID）
4. 完整 replay：resume 時將歷史訊息渲染到 contentView

### De-scope（明確排除）

- **`/session export`**：原 F5 spec 的匯出功能，留待後續迭代
- **自動清理**：`maxAge` / `maxCount` 機制不做，session 累積由使用者手動管理
- **Context compaction / summarization**：壓縮是外部 agent CLI 的責任，不在此 scope

## 設計

### 元件架構

```
shared/session/
  ├── SessionManager.java       （新）生命週期管理 + index 維護
  ├── SessionWriter.java        （既有，微調）純 JSONL I/O
  ├── SessionIndex.java         （新）index 資料模型
  ├── SessionMessage.java       （新）JSONL 訊息讀取模型
  ├── SessionEventListener.java （既有，不變）
  └── package-info.java

command/
  └── SessionCommands.java      （新）/session-resume、/session-info
```

> **`SessionCommands` 放在 `command/` 而非 `shared/session/`**：遵循 CLAUDE.md 「`shared/` 不依賴功能模組」規則。其他 Commands 也各自在功能包或 root（`SkillCommands` 在 `skill/`、`TaskCommands` 在 `task/`）。Session commands 與 UI/指令系統耦合，不屬於基礎設施層。

### SessionManager（@Component）

Session 生命週期的中央管理者。SessionWriter 降為純 I/O 元件。

```java
/**
 * Session 生命週期管理。
 *
 * 設計說明：
 * - 集中管理 session 建立/切換/索引，SessionWriter 只負責 JSONL I/O
 * - sessions-index.json 即時更新，每次訊息寫入時同步
 * - Resume 流程：flush 當前 → 載入指定 JSONL → publish event → TUI replay
 *
 * Bean 管理說明：
 * - SessionManager 是 @Component，持有並管理 SessionWriter 的生命週期
 * - SessionWriter 目前是 GrimoStartupRunner 手動 new 的 plain object
 * - 改為由 SessionManager 建構時建立 SessionWriter（注入 ProjectContext.dataDir）
 * - SessionWriter 單一 instance，resume 時透過 init() 切換目標檔案（mutable state）
 * - ChatDispatcher、TuiKeyHandler 等持有同一個 SessionWriter 引用 → 不會有 stale reference
 * - 移除 GrimoStartupRunner 中手動建立 SessionWriter 的邏輯
 */
@Component
public class SessionManager {
    private final SessionWriter writer;           // 單一 instance，由 Manager 建立
    private final ProjectContext projectContext;
    private final ApplicationEventPublisher eventPublisher;

    // 對外 API
    void startNewSession();
    void resumeSession(String sessionId);
    void continueLastSession();
    List<SessionIndex.Entry> listSessions();
    SessionInfo getCurrentInfo();
    SessionWriter getWriter();                    // 供其他元件取得 SessionWriter 引用

    // Writer callback — 每次寫入後更新 index
    void onMessageWritten(String type, String content);
}
```

> **SessionWriter 的 Bean 化路徑**：SessionWriter 改為由 SessionManager 建立並持有（非直接 `@Component`，因為需要 `dataDir` 參數）。其他需要 SessionWriter 的元件（ChatDispatcher、TuiKeyHandler、TuiAdapter）改為注入 SessionManager，再透過 `getWriter()` 取得同一個 SessionWriter instance。Resume 時 `init()` 切換目標檔案是 in-place mutation，所有持有者自動指向新 session。

### SessionWriter 微調

```java
// 新增
void init(String sessionId, Path sessionFile);       // Manager 控制目標檔案
List<SessionMessage> readAllMessages(Path file);     // 讀取 JSONL 供 replay

// 移除
// 自行產生 sessionId 的邏輯 → 改由 Manager 產生

// 變更
// 每次 write 方法結尾加上 callback 通知 Manager
```

### sessions-index.json

位置：`~/.grimo/projects/{encoded-cwd}/sessions-index.json`

```json
{
  "version": 1,
  "sessions": [
    {
      "sessionId": "a3f1b2c4",
      "startedAt": "2026-04-05T10:30:00Z",
      "lastActiveAt": "2026-04-05T11:45:00Z",
      "messageCount": 24,
      "firstUserMessage": "幫我重構 auth module",
      "gitBranch": "main",
      "agent": "claude",
      "model": "opus"
    }
  ]
}
```

| 欄位 | 寫入時機 | 來源 |
|------|---------|------|
| `agent` / `model` | session 建立時 | 當前 active agent/model（建立時快照，不隨切換更新） |
| `gitBranch` | session 建立時 | `git rev-parse --abbrev-ref HEAD` |
| `firstUserMessage` | 第一次 writeUserMessage | 擷取前 80 字元 |
| `lastActiveAt` / `messageCount` | 每次寫入 | 即時更新 |

寫入策略：整個 index 重寫（session 數量通常幾十到幾百，成本可忽略）。使用 atomic write（temp file → rename）防 crash corruption。

Crash recovery：index 遺失或損壞時，掃描目錄下所有 `*.jsonl` 檔案，讀取 header 重建 index。

### Session 生命週期流程

#### 正常啟動（新 session）

```
grimo 啟動
  → SessionManager.startNewSession()
    → 產生 8-char UUID
    → 建立 {dataDir}/{sessionId}.jsonl
    → SessionWriter.init(sessionId, sessionFile)
    → 寫入 system message
    → 更新 sessions-index.json（新增 entry）
  → TuiAdapter 進入 event loop
```

#### `--continue` 啟動

```
grimo --continue
  → SessionManager.continueLastSession()
    → 讀 sessions-index.json → 取 lastActiveAt 最新的 entry
    → 找不到 → fallback startNewSession()
    → 找到 → SessionWriter.init(sessionId, 既有 file)
    → readAllMessages() → replay 到 contentView
    → 不寫新 system message（接續舊的）
    → 更新 index 的 lastActiveAt
  → 使用者看到完整歷史
```

#### `--resume` 啟動

```
grimo --resume
  → 讀 sessions-index.json → JLine terminal picker
  → 使用者選擇 → sessionManager.resumeSession(selectedId)

grimo --resume <id>
  → sessionManager.resumeSession(id) → 直接載入
  → id 不存在 → 錯誤訊息 + 退出（不 fallback）
```

`--resume` 和 `--continue` 互斥，同時指定時 `--resume` 優先。

#### TUI 內 `/session-resume`

```
使用者輸入 /session-resume
  → TuiKeyHandler 攔截（TUI 專屬攔截模式，同 /mcp、/agent-use）
  → 開 SessionPickerOverlay
  → 使用者選擇 session
  → SessionManager.resumeSession(selectedId)
    → flush 當前 session（更新 lastActiveAt、messageCount）
    → SessionWriter.init(selectedId, 目標 file)
    → readAllMessages() → 組成 List<SessionMessage>
    → publish SessionSwitchedEvent(oldId, newId, messages)
  → TuiEventBridge.on(SessionSwitchedEvent)
    → contentView.clear()
    → 逐筆 replay → statusBar 更新 → scrollToBottom
```

#### 訊息寫入時 index 更新

```
writeXxxMessage()
  → append JSONL
  → callback → SessionManager.onMessageWritten(type, content)
    → lastActiveAt = now, messageCount++
    → 若 type == "user" && firstUserMessage == null → 記錄首句（前 80 字元）
    → 寫入 sessions-index.json（atomic write）
```

### 指令設計

遵循 hyphen 命名慣例（參見 CLAUDE.md 指令命名慣例）。

| 指令 | 行為 |
|------|------|
| `/session-resume` | 無參數 → 開 SessionPickerOverlay |
| `/session-info` | 顯示當前 session ID、開始時間、訊息數、agent/model |

`/session-resume` 是 TUI 專屬攔截（不經過 InputPort），與 `/mcp` 無參數、`/agent-use` 無參數同一模式。

`/session-info` 走正常指令管線（InputPort → CommandDispatcher）。

### SessionPickerOverlay

```
┌─ Session Resume ──────────────────────────────────────┐
│                                                        │
│  > a3f1b2c4  2h ago   24 msgs  main  claude/opus     │
│    幫我重構 auth module                                │
│                                                        │
│    b7e2f190  1d ago   12 msgs  main  gemini/flash     │
│    設計 session 管理功能                                │
│                                                        │
│  ↑↓ navigate  Enter select  Esc cancel                │
└────────────────────────────────────────────────────────┘
```

每筆 entry 兩行佈局：
- 第一行：session ID、相對時間、訊息數、git branch、agent/model
- 第二行：firstUserMessage 前 80 字元（truncate by DisplayWidth）

操作：↑↓ 移動、Enter 選取、Esc 取消。

空狀態顯示 "No previous sessions found."。

啟動時 `--resume`（無 TUI）使用 JLine terminal select，格式精簡為單行。

### Event 流

```java
/**
 * Session 切換事件。
 * TuiEventBridge 監聽後清空畫面並 replay 歷史。
 */
public record SessionSwitchedEvent(
    String oldSessionId,              // null if first session on startup
    String newSessionId,
    List<SessionMessage> messages     // 完整歷史供 replay
) {}
```

TuiEventBridge replay 規則：

| 訊息類型 | Replay 行為 |
|---------|------------|
| `system` | 不渲染 |
| `user` | 渲染為 `❯ {content}` |
| `assistant` | 渲染為 AI 回覆區塊 |
| `command` | 渲染為指令輸出區塊 |
| `dispatch-entered` | 單行摘要：`⚙ Dev dispatch: {goal}` |
| `dispatch-completed` | 單行摘要：`✓ Dispatch done: {diffStat}` |

### Status Bar 整合

```
[a3f1b2c4] claude/opus  main  3 msgs        ← 新 session
[a3f1b2c4 ↩] claude/opus  main  27 msgs     ← resumed session
```

`↩` 標記表示 resumed session，新 session 不顯示。此標記在整個 session 期間持續顯示（即使後續有新訊息），因為 JSONL 檔案本身就是舊 session 的延續。

## 影響範圍

### 新增檔案

| 檔案 | 職責 |
|------|------|
| `shared/session/SessionManager.java` | 生命週期管理、index 維護、持有 SessionWriter |
| `shared/session/SessionIndex.java` | index 資料模型（record） |
| `shared/session/SessionMessage.java` | JSONL 訊息讀取模型（record） |
| `command/SessionCommands.java` | `/session-resume`、`/session-info`（在 command/ 而非 shared/） |
| `shared/event/SessionSwitchedEvent.java` | Session 切換事件 |
| `tui/overlays/SessionPickerOverlay.java` | Session 選擇 overlay |

### 修改檔案

| 檔案 | 變更 |
|------|------|
| `shared/session/SessionWriter.java` | 新增 `init()`、`readAllMessages()`；移除自行產生 sessionId；寫入後 callback |
| `TuiAdapter.java` | 啟動流程加入 `--continue` / `--resume` 判斷；注入 SessionManager 取代直接 SessionWriter |
| `ChatDispatcher.java` | 改為注入 SessionManager，透過 `getWriter()` 取得 SessionWriter（原直接持有 SessionWriter） |
| `tui/TuiKeyHandler.java` | 攔截 `/session-resume` 開 overlay；改為注入 SessionManager |
| `tui/TuiEventBridge.java` | 新增 `on(SessionSwitchedEvent)` — 清空 + replay + refresh |
| `tui/views/StatusView.java` | 顯示 session ID + resumed 標記 |
| `GrimoStartupRunner.java`（或等效啟動類） | 移除手動建立 SessionWriter 的邏輯（改由 SessionManager 管理） |

### 不變檔案

| 檔案 | 原因 |
|------|------|
| `InputHandler.java` / `CommandDispatcher.java` | `/session-info` 走正常管線，`/session-resume` 被攔截 |

> **SessionEventListener 注入變更**：SessionEventListener 目前直接注入 SessionWriter。因 SessionWriter 不再是 Spring bean（改由 SessionManager 持有），SessionEventListener 也需改為注入 SessionManager 並透過 `getWriter()` 取得。已列入上方修改檔案表。

在修改檔案表中補充：

| `shared/session/SessionEventListener.java` | 改為注入 SessionManager，透過 `getWriter()` 取得 SessionWriter |

## 測試

| 測試 | 覆蓋 |
|------|------|
| `SessionManagerTest` | startNew / resume / continue / list / index 即時更新 / crash recovery rebuild |
| `SessionWriterTest`（擴充） | init 切換目標 / readAllMessages 解析 / callback 通知 |
| `SessionCommandsTest` | /session-info 輸出格式 |
| `SessionPickerOverlayTest` | 渲染 / 按鍵操作 / 空狀態 |
| `TuiEventBridge integration` | SessionSwitchedEvent → replay 正確性 |

## Glossary 更新

| 術語 | 定義 |
|------|------|
| SessionManager | Session 生命週期管理者，維護 sessions-index.json，提供 list/resume/info API |
| sessions-index.json | Per-project session 索引，記錄所有 session 的 metadata（ID、時間、訊息數、首句、agent、branch） |
| SessionPickerOverlay | TUI overlay，互動式選擇歷史 session 進行 resume |
