# 六角架構：GrimoTuiRunner 拆解 + 統一 Port

> Sub-project 4 of 4: TUI 重構系列。拆解 God Object、建立統一訊息管線。

## 目標

1. GrimoTuiRunner (1120 lines) 拆為 5 個元件（≤400 lines）
2. TUI 和 Channel 走同一個事件管線（IncomingMessageEvent → MessageRouter → OutgoingMessageEvent）
3. GrimoSessionAdvisor 改用 event 解耦（AgentCallRecordedEvent）

## 背景

### shared/ 保留但合理

SP1-SP3 已將 home/project/config/tui 提升為 top-level 模組。`shared/` 剩餘 event/session/sandbox — 這些是**真正跨模組的基礎設施**，留在 shared/ 合理：

```
shared/
├── event/      ← 模組間通訊語言（所有模組 consume）
├── session/    ← 對話持久化（跨 TUI + agent）
└── sandbox/    ← Git worktree 基礎設施
```

不強制消滅 shared/。不搬遷 events 到各出版模組（理想但複雜度高、收益低）。

### GrimoTuiRunner 是最大痛點

1120 行、21 個建構子依賴、混合 18 個職責。CLAUDE.md 已標記為 God Object。

## 設計

### 1. GrimoTuiRunner 拆為 5 個元件

```
GrimoTuiRunner (1120 lines)
    │
    ├── TuiKeyHandler (tui/)           ~200 lines
    │   鍵盤/滑鼠事件路由、overlay 管理、文字選取
    │   從 inner class 提取為 top-level
    │
    ├── TuiEventBridge (tui/)          ~80 lines
    │   @Component @EventListener
    │   Domain events → TUI 更新（status bar, diff summary）
    │
    ├── ChatDispatcher (root)          ~120 lines
    │   @Component
    │   Tier routing → AgentClient dispatch
    │   Virtual Thread 管理
    │
    ├── MessageRouter (root)           ~80 lines
    │   @Component @EventListener
    │   IncomingMessageEvent → command/chat → OutgoingMessageEvent
    │
    └── TuiAdapter (root, ≤400 lines)
        精簡後的 ApplicationRunner
        TUI 建構 + event loop + input/output 事件橋接
```

#### 1a. TuiKeyHandler

**位置：** `tui/TuiKeyHandler.java`

從 GrimoTuiRunner 的 inner class `TuiKeyHandler` 提取為 top-level。

```java
public class TuiKeyHandler implements EventLoop.KeyHandler {
    // 建構子注入 TUI 元件 + callback interface
    // handleKey() 邏輯不改，只搬位置
}
```

需要 callback interface 讓 TuiKeyHandler 通知 TuiAdapter（例如使用者按 Enter 提交文字）：

```java
public interface InputCallback {
    void onTextSubmit(String text);
    void onExit();
}
```

#### 1b. TuiEventBridge

**位置：** `tui/TuiEventBridge.java`

```java
@Component
public class TuiEventBridge {
    // 需要 bind() 方法讓 TuiAdapter 在建構 TUI 元件後傳入引用
    // （StatusView, ContentView 等不是 Spring bean）

    public void bind(StatusView statusView, ContentView contentView,
                     Runnable setDirty, ...) { ... }

    @EventListener void on(AgentSwitchedEvent event) { ... }
    @EventListener void on(McpCatalogChangedEvent event) { ... }
    @EventListener void on(DevModeEnteredEvent event) { ... }
    @EventListener void on(DevModeCompletedEvent event) { ... }
}
```

> 設計說明：bind() 模式是因為 TUI 元件在 run() 時才建立。TuiEventBridge 作為 Spring bean 先建立，之後 bind TUI 元件引用。@EventListener 方法在 bind 前被呼叫會檢查 null 並忽略（現有模式已是如此：`if (statusView == null) return;`）。

#### 1c. ChatDispatcher

**位置：** `ChatDispatcher.java`（root package）

```java
@Component
public class ChatDispatcher {
    // 注入：agentModelRegistry, agentRouter, tierRouter,
    //       tierKeywordDetector, tierOptionsFactory, mcpCatalogBuilder, sessionTier

    /**
     * 分派 AI 對話。同步呼叫（caller 負責 Virtual Thread 管理）。
     * @return agent 回應文字
     */
    public String dispatch(String userInput) {
        // 從 GrimoTuiRunner.processInput() AI 分支提取
        // tier routing → agent availability → MCP setup → AgentClient.run()
    }
}
```

#### 1d. MessageRouter

**位置：** `MessageRouter.java`（root package）

```java
@Component
public class MessageRouter {
    @EventListener
    void on(IncomingMessageEvent event) {
        // command → CommandExecutor → result
        // chat → ChatDispatcher.dispatch() → result
        // → publish OutgoingMessageEvent
    }
}
```

#### 1e. TuiAdapter

GrimoTuiRunner rename 為 TuiAdapter，精簡後只保留：
- TUI 元件建構（Views, Screen, EventLoop）
- Banner 渲染 + Status bar 初始化
- Event loop 啟動
- User input → publish IncomingMessageEvent
- Listen OutgoingMessageEvent → render to ContentView
- TUI-specific intercepts（overlay 觸發如 /agent-use, /mcp）

**額外提取（降到 ≤400 lines）：**
- 啟動初始化（detectAgents, loadSkills, restoreTasks, detectSandbox）→ 搬到 GrimoStartupRunner
- resolveAgentId() → 搬到 ChatDispatcher

### 2. AgentCallRecordedEvent 解耦

`GrimoSessionAdvisor` 直接注入 `SessionWriter` 是不理想的耦合。改為發 event：

```java
// agent/AgentCallRecordedEvent.java（新增，在 shared/event/ 裡）
public record AgentCallRecordedEvent(String userGoal, String agentResult) {}

// GrimoSessionAdvisor：改注入 ApplicationEventPublisher
// SessionEventListener：新增 @EventListener on(AgentCallRecordedEvent)
```

> 設計說明：AgentCallRecordedEvent 放在 shared/event/（跟其他 event 一起），不是 agent/ 裡。這樣 session/ 不需依賴 agent 模組。

### 3. 統一 Port：IncomingMessage → MessageRouter → OutgoingMessage

**訊息流：**

```
鍵盤 → TuiAdapter → IncomingMessageEvent → MessageRouter
                                               ↓
                                         command / chat
                                               ↓
                                     OutgoingMessageEvent
                                               ↓
         TuiAdapter ← ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘
         (render to ContentView)
```

**IncomingMessageEvent 擴充：**

現有 IncomingMessageEvent 可能需要加 `sourceAdapter` 和 `sessionId` 欄位。讀取現有 record 定義後決定新增/保留哪些欄位。保持向後相容。

**OutgoingMessageEvent 擴充：**

需要 `targetAdapter` 欄位讓各 adapter 的 @EventListener 過濾。

**TUI-specific intercepts：**

某些指令不走 MessageRouter（overlay 觸發）：
- `/agent-use`（無參數）→ TuiAdapter 直接處理（showAgentPicker overlay）
- `/mcp`（無子指令）→ TuiAdapter 直接處理（openMcpManager overlay）
- `/exit` → TuiAdapter 直接處理

其他指令和 AI 對話 → IncomingMessageEvent → MessageRouter → OutgoingMessageEvent。

## 測試策略

| 元件 | 測試方式 |
|------|---------|
| ChatDispatcher | 單元測試（mock AgentClient、registry） |
| MessageRouter | 單元測試（mock CommandExecutor + ChatDispatcher + EventPublisher） |
| TuiEventBridge | 單元測試（mock StatusView + ContentView） |
| TuiKeyHandler | 不單獨測（太多依賴，靠 integration / 手動測試） |
| TuiAdapter | 不單獨測（ApplicationRunner，靠 full build + 手動測試） |

## Glossary + CLAUDE.md 更新

- 新增 TuiAdapter、ChatDispatcher、MessageRouter、TuiEventBridge、TuiKeyHandler 術語
- 更新 CLAUDE.md Architecture 表格

## 不在範圍內

- shared/ 消滅或 event 歸屬出版者（shared/ 合理保留）
- Channel adapter 實作（LINE/Discord）
- 多輪對話上下文管理
- Agent streaming response

## 風險與緩解

| 風險 | 緩解 |
|------|------|
| GrimoTuiRunner 拆解引入 regression | 逐步提取，每步 build 驗證 |
| TuiKeyHandler 依賴太多 TUI 元件 | callback interface 解耦 |
| TuiEventBridge bind() 時機問題 | null check（現有模式） |
| MessageRouter async + sync 混合 | ChatDispatcher 同步返回，caller（MessageRouter）管 thread |

## 驗收標準

1. `./gradlew build` 通過
2. GrimoTuiRunner 不存在（renamed TuiAdapter）
3. `wc -l TuiAdapter.java` ≤ 400 lines
4. ChatDispatcher + TuiEventBridge + TuiKeyHandler + MessageRouter 各為獨立檔案
5. TUI input 走 IncomingMessageEvent → MessageRouter → OutgoingMessageEvent
6. `/agent-use`、`/mcp` 等 overlay 指令仍正常（TUI intercept）
