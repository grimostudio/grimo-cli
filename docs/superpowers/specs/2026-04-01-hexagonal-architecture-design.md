# 六角架構 + Event-driven 解耦

> Sub-project 4 of 4: TUI 重構系列。消滅 shared/、Event 歸屬出版者、GrimoTuiRunner 拆解、統一 Input/Output Port。

## 目標

1. Phase 1：sandbox/ + session/ 提升、Event 歸屬出版者模組、消滅 `shared/`
2. Phase 2：GrimoTuiRunner (1120 lines) 拆為 5 個元件
3. Phase 3：TUI 和 Channel 走同一個事件管線（IncomingMessageEvent → MessageRouter → OutgoingMessageEvent）

## 背景

SP1-SP3 已將 home/project/config/tui 提升為 top-level 模組。`shared/` 剩餘：

```
shared/
├── event/      ← 11 個 event records + 1 Attachment
├── session/    ← SessionWriter + SessionEventListener
├── sandbox/    ← WorktreeProvisioner + GitHelper + SandboxDetector + WorktreeInfo
└── package-info.java
```

GrimoTuiRunner 是 1120 行的 God Object，混合啟動、TUI 管理、命令分派、AI 對話、事件處理。

## 設計

### Phase 1：模組搬遷 + Event 歸屬 + 消滅 shared/

#### 1a. sandbox/ 提升

`shared.sandbox` → top-level `sandbox/` 模組。

| 檔案 | 搬遷 |
|------|------|
| `shared/sandbox/WorktreeProvisioner.java` | `sandbox/WorktreeProvisioner.java` |
| `shared/sandbox/GitHelper.java` | `sandbox/GitHelper.java` |
| `shared/sandbox/SandboxDetector.java` | `sandbox/SandboxDetector.java` |
| `shared/sandbox/WorktreeInfo.java` | `sandbox/WorktreeInfo.java` |

```java
// sandbox/package-info.java
@org.springframework.modulith.ApplicationModule
package io.github.samzhu.grimo.sandbox;
```

**allowedDependencies 影響：**
- `agent` 的 `"shared::sandbox"` → `"sandbox"`

#### 1b. session/ 提升

`shared.session` → top-level `session/` 模組。

| 檔案 | 搬遷 |
|------|------|
| `shared/session/SessionWriter.java` | `session/SessionWriter.java` |
| `shared/session/SessionEventListener.java` | `session/SessionEventListener.java` |

```java
// session/package-info.java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "agent" }  // SessionEventListener 監聽 DevMode events
)
package io.github.samzhu.grimo.session;
```

> 設計說明：SessionEventListener 用 `@EventListener` 監聽 `DevModeEnteredEvent` / `DevModeCompletedEvent`。Phase 1 把這些 event 搬到 `agent/` 後，session 模組需宣告 `"agent"` 依賴。

**解決 agent ↔ session 循環依賴：**

`GrimoSessionAdvisor`（`agent/advisor/`）直接注入 `SessionWriter`（`session/`），造成 `agent → session` 依賴。同時 `session → agent`（監聽 DevMode events）。Spring Modulith 禁止雙向依賴。

**解法：** `GrimoSessionAdvisor` 改為發布 `AgentCallRecordedEvent`（留在 `agent/`），`SessionEventListener` 新增監聽此 event 並寫入 SessionWriter。agent 不再直接 import session。

```java
// agent/AgentCallRecordedEvent.java（新增）
public record AgentCallRecordedEvent(String goal, String result, String sessionId) {}

// agent/advisor/GrimoSessionAdvisor.java（修改）
// Before: 直接呼叫 sessionWriter.writeXxx()
// After:  eventPublisher.publishEvent(new AgentCallRecordedEvent(...))

// session/SessionEventListener.java（新增監聽）
@EventListener
void on(AgentCallRecordedEvent event) {
    sessionWriter.writeAssistantMessage(event.result());
}
```

**依賴方向：** `session → agent`（單向），`agent` 不依賴 `session`。

**allowedDependencies 影響：**
- `agent` 的 `"shared::session"` → **移除**（不再需要 session 依賴）

#### 1c. Event 歸屬出版者

`shared.event/*` 搬回各自的出版模組。消費者透過 `allowedDependencies` 宣告依賴。

| Event | Publisher | 新位置 | Consumer(s) |
|-------|-----------|--------|-------------|
| `AgentSwitchedEvent` | AgentCommands | `agent/` | TuiRunner (root) |
| `DevModeEnteredEvent` | DevModeRunner | `agent/` | session/SessionEventListener, TuiRunner |
| `DevModeCompletedEvent` | DevModeRunner | `agent/` | session/SessionEventListener, TuiRunner |
| `McpCatalogChangedEvent` | McpCommands | `mcp/` | TuiRunner (root) |
| `OutgoingMessageEvent` | Various | `channel/` | channel/ChannelEventListener |
| `IncomingMessageEvent` | Channel adapters | `channel/` | (Phase 3: MessageRouter) |
| `Attachment` | (OutgoingMessage part) | `channel/` | channel/OutgoingMessage |
| `TaskExecutionEvent` | TaskSchedulerService | `task/` | (future) |
| `TaskCreateRequestEvent` | (placeholder) | `task/` | (future) |
| `TaskCompletedEvent` | TaskSchedulerService | `task/` | (future) |
| `ScheduleTaskEvent` | (placeholder) | `task/` | (future) |

**allowedDependencies 連鎖更新：**

**完整 allowedDependencies（Phase 1 完成後）：**

| 模組 | 完整 After | 變更說明 |
|------|-----------|---------|
| `agent` | `"sandbox", "config", "mcp", "skill::registry", "skill::loader"` | 移除 shared::*；events 在自己裡；不再依賴 session（循環解除） |
| `session` | `"agent", "project"` | 新模組；監聽 agent events + 使用 ProjectContext.dataDir |
| `sandbox` | (無) | 新模組，無外部依賴 |
| `task` | (無) | events 搬入自己；移除 shared::* |
| `mcp` | `"config"` | events 搬入自己；移除 shared::* |
| `channel` | (無) | events 搬入自己；移除 shared::* |
| `skill` | (無) | 移除 shared::* |
| `tui` | `"agent", "mcp"` | Phase 2 需要：TuiEventBridge 監聽 AgentSwitchedEvent + McpCatalogChangedEvent |
| `home` | (無) | 不變 |
| `project` | `"home"` | 不變 |
| `config` | (無) | 不變 |

> 設計說明：`tui/` 需在 Phase 2 加入 `"agent"` 和 `"mcp"` 依賴（TuiEventBridge 監聽這些模組的 events）。Phase 1 時 tui/ 不動（events 還是被 root package 的 GrimoTuiRunner 監聽）。

> 設計說明：root package（GrimoTuiRunner 等）不是 module，可自由 import 任何模組的 event。不需宣告 allowedDependencies。

#### 1d. 消滅 shared/

Phase 1 完成後：
- 刪除 `shared/event/` 目錄
- 刪除 `shared/session/` 目錄
- 刪除 `shared/sandbox/` 目錄
- 刪除 `shared/package-info.java`
- 刪除 `shared/` 目錄

**`shared/` 完全消滅。**

### Phase 2：GrimoTuiRunner 拆解

從 GrimoTuiRunner (1120 lines) 提取 4 個獨立元件，剩餘精簡為 TuiAdapter。

#### 2a. TuiKeyHandler（tui/ 模組）

**從 inner class 提取為 top-level。**

位置：`tui/TuiKeyHandler.java`（tui base package，不在子 package 裡）

```java
/**
 * TUI 鍵盤/滑鼠事件路由。
 * 負責 overlay 管理、文字選取、一般按鍵轉發。
 */
public class TuiKeyHandler implements EventLoop.KeyHandler {
    // 從 GrimoTuiRunner.TuiKeyHandler inner class 提取
    // 需要注入：screen, contentView, inputView, slashMenu, mcpPanel,
    //          textSelection, clipboard, autoScroller, activeSelectOverlay
}
```

**估計 ~200 lines。** 邏輯不改，只搬位置。

#### 2b. TuiEventBridge（tui/ 模組）

**提取所有 @EventListener 方法。**

位置：`tui/TuiEventBridge.java`

```java
@Component
public class TuiEventBridge {
    // 注入：statusView, contentView, eventLoop（用於 setDirty）

    @EventListener
    void on(AgentSwitchedEvent event) { refreshStatusBar(); setDirty(); }

    @EventListener
    void on(McpCatalogChangedEvent event) { refreshStatusBar(); setDirty(); }

    @EventListener
    void on(DevModeEnteredEvent event) { appendWorktreeInfo(event); setDirty(); }

    @EventListener
    void on(DevModeCompletedEvent event) { appendDiffSummary(event); setDirty(); }
}
```

**估計 ~80 lines。** 需要能存取 StatusView、ContentView、config 等 — 可透過建構子注入。

> 設計說明：TuiEventBridge 是 `@Component`，Spring 自動偵測 `@EventListener`。它在 `tui/` 模組內，需宣告 `allowedDependencies` 對 agent（AgentSwitchedEvent）和 mcp（McpCatalogChangedEvent）。

#### 2c. ChatDispatcher（root package）

**提取 AI 對話分派邏輯。**

位置：`ChatDispatcher.java`（root package）

```java
@Component
public class ChatDispatcher {
    // 注入：agentModelRegistry, agentRouter, tierRouter, tierKeywordDetector,
    //       tierOptionsFactory, mcpCatalogBuilder, sessionTier

    /**
     * 分派 AI 對話到指定 agent。在 Virtual Thread 上執行。
     * @return agent 回應文字
     */
    public String dispatch(String userInput) {
        // Tier routing
        // Agent availability check
        // MCP catalog setup
        // AgentClient.builder().build().run()
    }
}
```

**估計 ~120 lines。** 從 GrimoTuiRunner.processInput() 的 AI 對話分支提取。

#### 2d. TuiAdapter（精簡後的 GrimoTuiRunner）

GrimoTuiRunner rename 為 `TuiAdapter`，只保留：
- TUI 元件建構（Views, Screen, EventLoop）
- Banner 渲染 + Status bar 初始化
- Event loop 啟動
- Input 接收 → 轉發給 CommandExecutor 或 ChatDispatcher
- Output 接收 → 渲染到 ContentView

**額外提取（讓 TuiAdapter 降到 ~400 lines）：**
- 啟動初始化（detectAgents, loadSkills, restoreTasks, detectSandbox）→ 搬到 `GrimoStartupRunner`（已是 bean factory，加 `@PostConstruct` 或 `ApplicationRunner` 初始化）
- `buildMenuItems()` → 搬到 CommandDispatcher 或內聯
- `resolveAgentId()` → 搬到 ChatDispatcher

**估計 ~350-400 lines（從 1120 lines 精簡）。**

### Phase 3：統一 Port 介面

#### 3a. TUI 發 IncomingMessageEvent

TuiAdapter 的 input 處理改為：

```java
// Before (直接呼叫)
processInput(text);  // 內部判斷 command vs chat

// After (事件驅動)
eventPublisher.publishEvent(new IncomingMessageEvent(text, "tui", sessionId));
```

#### 3b. MessageRouter 統一路由

位置：`MessageRouter.java`（root package，@Component）

```java
@Component
public class MessageRouter {
    // 注入：CommandParser, CommandExecutor, ChatDispatcher, ApplicationEventPublisher

    @EventListener
    void on(IncomingMessageEvent event) {
        String text = event.text();
        String result;
        if (isCommand(text)) {
            result = executeCommand(text);
        } else {
            result = chatDispatcher.dispatch(text);
        }
        eventPublisher.publishEvent(new OutgoingMessageEvent(
            result, event.sourceAdapter(), event.sessionId(), null, List.of()));
    }
}
```

> 設計說明：IncomingMessageEvent 攜帶 `sourceAdapter`（"tui" / "line" / "discord"），MessageRouter 不關心來源，統一處理。OutgoingMessageEvent 攜帶 `targetAdapter`，各 adapter 的 @EventListener 只處理自己的。

#### 3c. TUI 收 OutgoingMessageEvent

TuiAdapter 的 @EventListener：

```java
@EventListener
void on(OutgoingMessageEvent event) {
    if ("tui".equals(event.targetAdapter()) || "all".equals(event.targetAdapter())) {
        contentView.appendLine(new AttributedString(event.text()));
        eventLoop.setDirty();
    }
}
```

Channel 的 ChannelEventListener 已有類似邏輯（轉發到 ChannelAdapter.send()）。

#### 3d. IncomingMessageEvent 擴充

現有 `IncomingMessageEvent` 可能需要加欄位：

```java
public record IncomingMessageEvent(
    String text,
    String sourceAdapter,   // "tui", "line", "discord"
    String sessionId,
    String conversationId   // channel 用，TUI 可 null
) {}
```

`OutgoingMessageEvent` 類似，加 `targetAdapter` 欄位（如果沒有的話）。

## 測試策略

| Phase | 測試 |
|-------|------|
| Phase 1 | ModulithStructureTest 驗證新模組邊界；各模組既有測試不變 |
| Phase 2 | ChatDispatcher 單元測試（mock AgentClient）；TuiEventBridge 測試（mock Views）；TuiKeyHandler 不單獨測（太多依賴，靠 integration） |
| Phase 3 | MessageRouter 單元測試（mock CommandExecutor + ChatDispatcher）；event flow integration test |

## Glossary + CLAUDE.md 更新

**CLAUDE.md Architecture 表格：**
- `shared/` 行刪除
- 新增 `sandbox/`、`session/` 行
- 更新 `tui/` 描述（加入 TuiAdapter, TuiKeyHandler, TuiEventBridge）
- 更新 event-driven 說明（events 在出版模組，不在 shared）

## 不在範圍內

- Channel adapter 實作（LINE/Discord）— 未來功能
- 多輪對話上下文管理
- Agent streaming response

## 風險與緩解

| 風險 | 緩解 |
|------|------|
| Event 搬遷影響所有模組 import | Compiler 報錯 + ModulithStructureTest |
| GrimoTuiRunner 拆解引入 regression | 逐步提取，每步 build 驗證 |
| MessageRouter 的 command vs chat 判斷 | 提取自現有 processInput() 邏輯，不重寫 |
| Phase 3 改變 TUI input flow | 可選擇保留直接呼叫作為 fallback |
| TuiEventBridge 需存取多個 TUI 元件 | 透過建構子注入，在 GrimoStartupRunner 建立 |

## 驗收標準

### Phase 1
1. `./gradlew build` 通過
2. `ModulithStructureTest` 通過
3. `shared/` 目錄不存在
4. `grep -r "shared\." src/` 回傳空（除歷史文件參考）

### Phase 2
1. `./gradlew build` 通過
2. GrimoTuiRunner 不存在（已 rename 為 TuiAdapter）
3. `wc -l TuiAdapter.java` ≤ 400 lines
4. ChatDispatcher + TuiEventBridge + TuiKeyHandler 各為獨立檔案

### Phase 3
1. `./gradlew build` 通過
2. TUI input 走 IncomingMessageEvent → MessageRouter → OutgoingMessageEvent
3. MessageRouter 處理 command + chat 兩種路徑
4. Channel adapters 和 TUI 走同一條 event pipeline
