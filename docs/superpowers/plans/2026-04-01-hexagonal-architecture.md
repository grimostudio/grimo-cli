# 六角架構 + Event-driven 解耦 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消滅 shared/、Event 歸屬出版者、GrimoTuiRunner God Object 拆解、統一 Input/Output Port — 3 Phase 遞進。

**Architecture:** Phase 1 搬遷 sandbox/session 模組 + 12 event 歸屬出版者 + 消滅 shared/。Phase 2 從 GrimoTuiRunner (1120 lines) 提取 TuiKeyHandler、TuiEventBridge、ChatDispatcher、啟動初始化，精簡為 TuiAdapter (~400 lines)。Phase 3 用 IncomingMessageEvent → MessageRouter → OutgoingMessageEvent 統一 TUI 和 Channel 的訊息管線。

**Tech Stack:** Java 25, Spring Boot 4.0.x, Spring Modulith 2.0.x, Spring Shell 4.0.x, JUnit 5

**Spec:** `docs/superpowers/specs/2026-04-01-hexagonal-architecture-design.md`

---

## Phase 1：模組搬遷 + Event 歸屬 + 消滅 shared/

---

### Task 1: sandbox/ 提升

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/sandbox/package-info.java`
- Move: `shared/sandbox/*.java` → `sandbox/*.java` (4 files)
- Move: `test/.../shared/sandbox/*.java` → `test/.../sandbox/*.java` (4 test files)
- Modify: imports in `agent/DevModeRunner.java`, `GrimoTuiRunner.java`, `GrimoStartupRunner.java`

- [ ] **Step 1: 建立 sandbox/package-info.java**

```java
@org.springframework.modulith.ApplicationModule
package io.github.samzhu.grimo.sandbox;
```

- [ ] **Step 2: 搬移 4 個源碼 + 4 個測試到 sandbox/**

`git mv` 搬移 WorktreeProvisioner, GitHelper, SandboxDetector, WorktreeInfo 及其測試。更新所有 package 聲明。

- [ ] **Step 3: 更新外部 imports**

`grep -r "shared\.sandbox\." src/` 找出所有引用：
- `agent/DevModeRunner.java` — GitHelper, WorktreeProvisioner, WorktreeInfo
- `GrimoTuiRunner.java` — WorktreeProvisioner, WorktreeInfo, SandboxDetector, GitHelper
- `GrimoStartupRunner.java` — GitHelper, WorktreeProvisioner, SandboxDetector

全部改為 `io.github.samzhu.grimo.sandbox.*`。

- [ ] **Step 4: 刪除 shared/sandbox/ package-info.java + 空目錄**

- [ ] **Step 5: 編譯驗證**

Run: `./gradlew compileJava compileTestJava`

- [ ] **Step 6: Commit**

```
refactor: promote sandbox/ to top-level module
```

---

### Task 2: session/ 提升 + 解決循環依賴

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/session/package-info.java`
- Create: `src/main/java/io/github/samzhu/grimo/agent/AgentCallRecordedEvent.java`
- Move: `shared/session/*.java` → `session/*.java` (2 files)
- Move: `test/.../shared/session/*.java` → `test/.../session/*.java` (2 test files)
- Modify: `agent/advisor/GrimoSessionAdvisor.java` — 改用 event publisher
- Modify: `session/SessionEventListener.java` — 新增監聽 AgentCallRecordedEvent
- Modify: imports in `GrimoTuiRunner.java`, `GrimoStartupRunner.java`

- [ ] **Step 1: 建立 AgentCallRecordedEvent**

```java
// src/main/java/io/github/samzhu/grimo/agent/AgentCallRecordedEvent.java
package io.github.samzhu.grimo.agent;

/**
 * Agent 對話記錄事件。由 GrimoSessionAdvisor 發布，SessionEventListener 監聽寫入 JSONL。
 * 設計說明：解決 agent ↔ session 循環依賴。agent 發 event，session 監聽，單向依賴。
 */
public record AgentCallRecordedEvent(String userGoal, String agentResult) {}
```

- [ ] **Step 2: 修改 GrimoSessionAdvisor — 改用 event publisher**

GrimoSessionAdvisor 是普通 Java 物件（非 @Component），需要透過建構子傳入 `ApplicationEventPublisher`。

```java
// Before
private final SessionWriter sessionWriter;
public GrimoSessionAdvisor(SessionWriter sessionWriter) { ... }

// After
private final ApplicationEventPublisher eventPublisher;
public GrimoSessionAdvisor(ApplicationEventPublisher eventPublisher) { ... }

// adviseCall() 內：
// Before: sessionWriter.writeUserMessage(...)
// After:  eventPublisher.publishEvent(new AgentCallRecordedEvent(goal, result))
```

同步更新建立 GrimoSessionAdvisor 的地方（GrimoTuiRunner 中的 AgentClient.builder() 呼叫）— 傳入 eventPublisher 取代 sessionWriter。

- [ ] **Step 3: 建立 session/package-info.java**

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "agent", "project" }
)
package io.github.samzhu.grimo.session;
```

- [ ] **Step 4: 搬移 SessionWriter + SessionEventListener 到 session/**

更新 package 聲明和 imports。

- [ ] **Step 5: SessionEventListener 新增 AgentCallRecordedEvent 監聽**

```java
@EventListener
void on(AgentCallRecordedEvent event) {
    sessionWriter.writeUserMessage(event.userGoal());
    sessionWriter.writeAssistantMessage(event.agentResult());
}
```

同時把現有的 DevModeEnteredEvent/CompletedEvent import 改為 `io.github.samzhu.grimo.agent.*`（events 在 Phase 1 Task 3 搬到 agent/，但為了避免搬遷順序問題，此處先用 shared.event 的 import，Task 3 統一更新）。

- [ ] **Step 6: 搬移測試 + 更新**

- [ ] **Step 7: 更新外部 imports**

`GrimoTuiRunner.java` 和 `GrimoStartupRunner.java` 的 SessionWriter import 改為 `session.SessionWriter`。

- [ ] **Step 8: 刪除 shared/session/ + 編譯驗證**

Run: `./gradlew compileJava compileTestJava`

- [ ] **Step 9: Commit**

```
refactor: promote session/ to top-level module, resolve agent↔session cycle

GrimoSessionAdvisor now publishes AgentCallRecordedEvent instead of
directly importing SessionWriter. Dependency is one-directional:
session → agent (via events).
```

---

### Task 3: Event 歸屬出版者（11 events + Attachment）

**Files:**
- Move: `shared/event/*.java` → 各出版模組（agent/, mcp/, channel/, task/）
- Delete: `shared/event/package-info.java`
- Modify: ALL files that import `shared.event.*`

**搬遷表：**

| Event | → 模組 |
|-------|--------|
| AgentSwitchedEvent | agent/ |
| DevModeEnteredEvent | agent/ |
| DevModeCompletedEvent | agent/ |
| McpCatalogChangedEvent | mcp/ |
| OutgoingMessageEvent | channel/ |
| IncomingMessageEvent | channel/ |
| Attachment | channel/ |
| TaskExecutionEvent | task/ |
| TaskCreateRequestEvent | task/ |
| TaskCompletedEvent | task/ |
| ScheduleTaskEvent | task/ |

- [ ] **Step 1: 搬移 3 個 agent events**

`git mv` AgentSwitchedEvent, DevModeEnteredEvent, DevModeCompletedEvent 到 `agent/`。更新 package 聲明。

- [ ] **Step 2: 搬移 1 個 mcp event**

`git mv` McpCatalogChangedEvent 到 `mcp/`。

- [ ] **Step 3: 搬移 3 個 channel events**

`git mv` OutgoingMessageEvent, IncomingMessageEvent, Attachment 到 `channel/`。

- [ ] **Step 4: 搬移 4 個 task events**

`git mv` TaskExecutionEvent, TaskCreateRequestEvent, TaskCompletedEvent, ScheduleTaskEvent 到 `task/`。

- [ ] **Step 5: 更新所有外部 imports**

用 `grep -r "shared\.event\." src/` 找出所有引用。逐一更新：

主要消費者：
- `GrimoTuiRunner.java` — AgentSwitchedEvent, DevModeEnteredEvent, DevModeCompletedEvent, McpCatalogChangedEvent → `agent.*`, `mcp.*`
- `agent/AgentCommands.java` — AgentSwitchedEvent → 同 package，移除 shared import
- `agent/DevModeRunner.java` — DevModeEnteredEvent, DevModeCompletedEvent → 同 package
- `session/SessionEventListener.java` — DevModeEnteredEvent, DevModeCompletedEvent → `agent.*`
- `channel/ChannelEventListener.java` — OutgoingMessageEvent → 同 package
- `channel/OutgoingMessage.java` — Attachment → 同 package
- `task/scheduler/TaskSchedulerService.java` — TaskExecutionEvent → 同 package (via `task.*`)
- `mcp/McpCommands.java` — McpCatalogChangedEvent → 同 package

- [ ] **Step 6: 刪除 shared/event/ 目錄**

- [ ] **Step 7: 編譯驗證**

Run: `./gradlew compileJava compileTestJava`

- [ ] **Step 8: Commit**

```
refactor: relocate all events to publishing modules (Modulith principle)

Events now live where they're published: agent/ (3), mcp/ (1),
channel/ (3), task/ (4). Consumers declare allowedDependencies.
```

---

### Task 4: 更新所有 allowedDependencies + 消滅 shared/

**Files:**
- Modify: ALL `package-info.java` files (agent, session, sandbox, task, mcp, channel, skill)
- Delete: `src/main/java/io/github/samzhu/grimo/shared/package-info.java`
- Delete: `src/main/java/io/github/samzhu/grimo/shared/` directory

- [ ] **Step 1: 更新每個模組的 allowedDependencies**

按 spec 的完整表：

```java
// agent/package-info.java
allowedDependencies = { "sandbox", "config", "mcp", "skill::registry", "skill::loader" }

// session/package-info.java (已在 Task 2 建立)
allowedDependencies = { "agent", "project" }

// sandbox/package-info.java (已在 Task 1 建立)
// 無 allowedDependencies

// task/package-info.java
// 無 allowedDependencies（移除所有 shared::*）

// mcp/package-info.java
allowedDependencies = { "config" }

// channel/package-info.java
// 無 allowedDependencies（移除所有 shared::*）

// skill/package-info.java
// 無 allowedDependencies（移除所有 shared::*）
```

- [ ] **Step 2: 刪除 shared/ 模組**

```bash
rm src/main/java/io/github/samzhu/grimo/shared/package-info.java
rmdir src/main/java/io/github/samzhu/grimo/shared/
rmdir src/test/java/io/github/samzhu/grimo/shared/ 2>/dev/null
```

- [ ] **Step 3: 執行 ModulithStructureTest**

Run: `./gradlew test --tests "*.ModulithStructureTest"`
Expected: PASS。如失敗，根據錯誤訊息調整 allowedDependencies。

- [ ] **Step 4: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 驗證 shared/ 消滅**

Run: `ls src/main/java/io/github/samzhu/grimo/shared/ 2>&1`
Expected: No such file or directory

Run: `grep -r "shared\." src/main/java/ --include="*.java" | grep -v "//\|*\|docs/" | head -20`
Expected: 無 shared. import（排除註解）

- [ ] **Step 6: Commit**

```
refactor: update all allowedDependencies, eliminate shared/ module

shared/ module is completely removed. All 11 modules have precise
dependency declarations verified by ModulithStructureTest.
```

---

## Phase 2：GrimoTuiRunner 拆解

---

### Task 5: 提取 TuiKeyHandler（inner class → top-level）

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/tui/TuiKeyHandler.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`

- [ ] **Step 1: 提取 inner class**

GrimoTuiRunner 的 `TuiKeyHandler` inner class 搬到 `tui/TuiKeyHandler.java`。

它 implements `EventLoop.KeyHandler`，需要存取 GrimoTuiRunner 的多個 field。改為建構子注入：

```java
package io.github.samzhu.grimo.tui;

public class TuiKeyHandler implements EventLoop.KeyHandler {
    private final Screen screen;
    private final ContentView contentView;
    private final InputView inputView;
    private final SlashMenu slashMenu;
    private final McpPanel mcpPanel;
    private final TextSelection textSelection;
    private final Clipboard clipboard;
    private final AutoScroller autoScroller;
    // ... 其他需要的依賴

    public TuiKeyHandler(...) { ... }

    @Override
    public void handleKey(String operation, String lastBinding) { ... }
}
```

**重要：** handleKey() 內部可能呼叫 GrimoTuiRunner 的方法（如 processInput, showAgentPicker 等）。這些需要透過 callback interface 或直接 method reference 傳入。

定義 callback interface：
```java
public interface InputCallback {
    void onTextSubmit(String text);
    void onShowAgentPicker();
    // ... 其他需要的 callbacks
}
```

- [ ] **Step 2: GrimoTuiRunner 建立 TuiKeyHandler 並傳入**

```java
// Before
eventLoop = new EventLoop(terminal, screen, new TuiKeyHandler());

// After
var keyHandler = new TuiKeyHandler(screen, contentView, inputView, slashMenu, mcpPanel, ...);
eventLoop = new EventLoop(terminal, screen, keyHandler);
```

- [ ] **Step 3: 刪除 GrimoTuiRunner 中的 inner class**

- [ ] **Step 4: 編譯驗證**

Run: `./gradlew compileJava`

- [ ] **Step 5: Commit**

```
refactor: extract TuiKeyHandler from GrimoTuiRunner inner class to tui/
```

---

### Task 6: 提取 TuiEventBridge（@EventListener methods）

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/tui/TuiEventBridge.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` — 移除 4 個 @EventListener methods
- Modify: `src/main/java/io/github/samzhu/grimo/tui/package-info.java` — 加 allowedDependencies

- [ ] **Step 1: 建立 TuiEventBridge**

```java
package io.github.samzhu.grimo.tui;

@Component
public class TuiEventBridge {
    private final StatusView statusView;
    private final ContentView contentView;
    // 需要：config, agentModelRegistry, skillRegistry, mcpConfig, taskScheduler（for refreshStatusBar）
    // 需要：EventLoop reference（for setDirty）— 可透過 Runnable callback

    @EventListener void on(AgentSwitchedEvent event) { ... }
    @EventListener void on(McpCatalogChangedEvent event) { ... }
    @EventListener void on(DevModeEnteredEvent event) { ... }
    @EventListener void on(DevModeCompletedEvent event) { ... }
}
```

**注意：** TuiEventBridge 是 @Component（Spring bean），但 StatusView、ContentView 等不是 Spring bean — 它們在 GrimoTuiRunner.run() 中手動建立。需要一種方式讓 TuiEventBridge 取得這些引用。

方案：TuiEventBridge 有 setter 方法，GrimoTuiRunner 在建構 TUI 元件後呼叫 `tuiEventBridge.bind(statusView, contentView, eventLoop::setDirty)`。

- [ ] **Step 2: 更新 tui/package-info.java**

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "agent", "mcp" }
)
package io.github.samzhu.grimo.tui;
```

- [ ] **Step 3: GrimoTuiRunner 移除 4 個 @EventListener + inject TuiEventBridge**

建構子加入 `TuiEventBridge tuiEventBridge`，在 run() 中 bind TUI 元件。

- [ ] **Step 4: 編譯驗證**

Run: `./gradlew compileJava`

- [ ] **Step 5: Commit**

```
refactor: extract TuiEventBridge — domain events → TUI updates
```

---

### Task 7: 提取 ChatDispatcher + 啟動初始化搬遷

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/ChatDispatcher.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` — 移除 AI dispatch 邏輯 + 啟動步驟
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java` — 接收啟動初始化

- [ ] **Step 1: 建立 ChatDispatcher**

```java
package io.github.samzhu.grimo;

@Component
public class ChatDispatcher {
    private final AgentModelRegistry agentModelRegistry;
    private final AgentRouter agentRouter;
    private final TierRouter tierRouter;
    private final TierKeywordDetector tierKeywordDetector;
    private final TierOptionsFactory tierOptionsFactory;
    private final McpCatalogBuilder mcpCatalogBuilder;
    private final AtomicReference<Tier> sessionTier;
    private final ApplicationEventPublisher eventPublisher;

    // 從 GrimoTuiRunner.processInput() 的 AI 對話分支提取
    public String dispatch(String userInput) { ... }
}
```

- [ ] **Step 2: 搬移啟動初始化到 GrimoStartupRunner**

GrimoTuiRunner.run() 的 Phase 1-2（agent detection, skill loading, mcp build, task restore, sandbox detect）搬到 GrimoStartupRunner 的 `@Bean` 方法或新的 initializer。

保留 Phase 3-5 在 TuiRunner（環境資訊收集、TUI 建構、event loop 啟動）。

- [ ] **Step 3: GrimoTuiRunner 使用 ChatDispatcher**

```java
// Before: processInput() 中直接做 tier routing + AgentClient dispatch
// After:
String response = chatDispatcher.dispatch(userInput);
contentView.appendLine(new AttributedString(response));
```

- [ ] **Step 4: 編譯驗證 + 全量測試**

Run: `./gradlew build`

- [ ] **Step 5: Commit**

```
refactor: extract ChatDispatcher + move startup init to StartupRunner
```

---

### Task 8: Rename GrimoTuiRunner → TuiAdapter + Phase 2 驗證

**Files:**
- Rename: `GrimoTuiRunner.java` → `TuiAdapter.java`（移到 tui/ 或留在 root — 依 Modulith 限制決定）
- Modify: `GrimoStartupRunner.java`（如有 bean reference）

- [ ] **Step 1: 評估是否移到 tui/**

TuiAdapter（原 GrimoTuiRunner）是 @Component + ApplicationRunner，import 大量模組。如果放在 tui/ 模組，tui/ 的 allowedDependencies 會很長。

**建議：保留在 root package**（root 不是 module，無 allowedDependencies 限制）。只 rename 類名。

- [ ] **Step 2: Rename**

`git mv GrimoTuiRunner.java TuiAdapter.java`，更新：
- 類名、Logger、建構子
- 所有測試中的引用（如有）

- [ ] **Step 3: 驗證行數**

Run: `wc -l src/main/java/io/github/samzhu/grimo/TuiAdapter.java`
Expected: ≤ 400 lines

如超過 400，識別可進一步提取的邏輯。

- [ ] **Step 4: Full build**

Run: `./gradlew build`

- [ ] **Step 5: Commit**

```
refactor: rename GrimoTuiRunner → TuiAdapter (~400 lines from 1120)
```

---

## Phase 3：統一 Port 介面

---

### Task 9: 擴充 IncomingMessageEvent + OutgoingMessageEvent

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/channel/IncomingMessageEvent.java`
- Modify: `src/main/java/io/github/samzhu/grimo/channel/OutgoingMessageEvent.java`

- [ ] **Step 1: 讀取現有 event record 欄位**

讀 IncomingMessageEvent 和 OutgoingMessageEvent 的現有欄位，確認需要新增/重命名哪些。

按 spec：
- IncomingMessageEvent 需要：`text`, `sourceAdapter`, `sessionId`, `conversationId`
- OutgoingMessageEvent 需要：`text`, `targetAdapter`, `sessionId`, `conversationId`, `attachments`

保留現有欄位的相容性，新增缺少的欄位。

- [ ] **Step 2: 更新 record 定義 + 所有呼叫端**

- [ ] **Step 3: 編譯驗證**

Run: `./gradlew compileJava`

- [ ] **Step 4: Commit**

```
refactor: extend IncomingMessage/OutgoingMessageEvent with adapter routing fields
```

---

### Task 10: 建立 MessageRouter + TuiAdapter 事件管線

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/MessageRouter.java`
- Modify: `src/main/java/io/github/samzhu/grimo/TuiAdapter.java`

- [ ] **Step 1: 建立 MessageRouter**

```java
package io.github.samzhu.grimo;

@Component
public class MessageRouter {
    private final CommandParser commandParser;
    private final CommandExecutor commandExecutor;
    private final ChatDispatcher chatDispatcher;
    private final ApplicationEventPublisher eventPublisher;
    private final SessionWriter sessionWriter;

    @EventListener
    void on(IncomingMessageEvent event) {
        String text = event.text();
        String result;
        if (text.startsWith("/")) {
            // Command
            var context = commandParser.parse(text.substring(1));
            result = commandExecutor.execute(context);
        } else {
            // AI chat
            result = chatDispatcher.dispatch(text);
        }
        if (result != null && !result.isEmpty()) {
            eventPublisher.publishEvent(new OutgoingMessageEvent(
                result, event.sourceAdapter(), event.sessionId(),
                event.conversationId(), List.of()));
        }
    }
}
```

- [ ] **Step 2: TuiAdapter 改為發送 IncomingMessageEvent**

```java
// Before: processInput(text) 直接處理
// After:
private void onUserSubmit(String text) {
    // 記錄使用者輸入到 ContentView
    contentView.appendUserInput(text);
    sessionWriter.writeUserMessage(text);

    // 發送事件
    eventPublisher.publishEvent(new IncomingMessageEvent(
        text, "tui", sessionWriter.getSessionId(), null));
}
```

- [ ] **Step 3: TuiAdapter 監聽 OutgoingMessageEvent**

```java
@EventListener
void on(OutgoingMessageEvent event) {
    if ("tui".equals(event.targetAdapter())) {
        contentView.appendLine(new AttributedString(event.text()));
        eventLoop.setDirty();
    }
}
```

- [ ] **Step 4: 處理特殊指令（overlay 觸發等）**

某些指令不走 MessageRouter（如 `/agent-use` 無參數觸發 overlay、`/mcp` 開啟 panel）。這些需要在 TuiAdapter 中 intercept，不發 IncomingMessageEvent：

```java
private void onUserSubmit(String text) {
    // TUI-specific intercepts (overlays)
    if (text.equals("/agent-use")) { showAgentPicker(); return; }
    if (text.equals("/mcp")) { openMcpManager(); return; }

    // General flow → event pipeline
    eventPublisher.publishEvent(new IncomingMessageEvent(...));
}
```

- [ ] **Step 5: 編譯 + 測試驗證**

Run: `./gradlew build`

- [ ] **Step 6: Commit**

```
feat: add MessageRouter — unified IncomingMessage→OutgoingMessage pipeline

TUI and Channel adapters now share the same message processing pipeline.
MessageRouter handles command vs chat routing, publishes OutgoingMessageEvent.
```

---

### Task 11: Glossary + CLAUDE.md 更新

**Files:**
- Modify: `docs/glossary.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: CLAUDE.md Architecture 表格**

- 刪除 `shared/` 行
- 新增 `sandbox/`、`session/` 行
- 更新 `tui/` 描述（加入 TuiEventBridge）
- 更新 event-driven 說明
- 更新 root package 說明（加入 TuiAdapter, ChatDispatcher, MessageRouter）

- [ ] **Step 2: Glossary 更新**

- 新增 TuiAdapter、ChatDispatcher、MessageRouter、TuiEventBridge、TuiKeyHandler 術語
- 更新 event-driven 區段（events 在出版模組）
- 移除 shared/ 相關描述

- [ ] **Step 3: Commit**

```
docs: update glossary and CLAUDE.md for hexagonal architecture
```

---

### Task 12: 全量驗證

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 驗證 shared/ 消滅**

```bash
ls src/main/java/io/github/samzhu/grimo/shared/ 2>&1
# Expected: No such file or directory
```

- [ ] **Step 3: 驗證 GrimoTuiRunner 不存在**

```bash
ls src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java 2>&1
# Expected: No such file or directory
```

- [ ] **Step 4: 驗證 TuiAdapter 行數**

```bash
wc -l src/main/java/io/github/samzhu/grimo/TuiAdapter.java
# Expected: ≤ 400
```

- [ ] **Step 5: 驗證新元件存在**

```bash
ls src/main/java/io/github/samzhu/grimo/ChatDispatcher.java
ls src/main/java/io/github/samzhu/grimo/MessageRouter.java
ls src/main/java/io/github/samzhu/grimo/tui/TuiKeyHandler.java
ls src/main/java/io/github/samzhu/grimo/tui/TuiEventBridge.java
```

- [ ] **Step 6: 驗證 shared import 殘留**

```bash
grep -r "shared\." src/main/java/ --include="*.java" | grep "^.*import" | head -20
# Expected: 無輸出
```

- [ ] **Step 7: Commit（若有遺漏修正）**

```
fix: address remaining issues from SP4 verification
```
