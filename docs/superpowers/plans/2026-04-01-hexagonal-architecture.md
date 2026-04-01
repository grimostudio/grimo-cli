# 六角架構：GrimoTuiRunner 拆解 + 統一 Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GrimoTuiRunner God Object (1120 lines) 拆為 5 元件 + 統一 IncomingMessage→MessageRouter→OutgoingMessage 訊息管線。

**Architecture:** 逐步提取 — TuiKeyHandler（鍵盤路由）→ TuiEventBridge（event→TUI）→ ChatDispatcher（AI 分派）→ 啟動初始化搬遷 → rename TuiAdapter。再建 MessageRouter 統一訊息管線。AgentCallRecordedEvent 解耦 advisor→session。

**Tech Stack:** Java 25, Spring Boot 4.0.x, Spring Modulith 2.0.x, JUnit 5

**Spec:** `docs/superpowers/specs/2026-04-01-hexagonal-architecture-design.md`

---

### Task 1: AgentCallRecordedEvent 解耦

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/AgentCallRecordedEvent.java`
- Modify: `src/main/java/io/github/samzhu/grimo/agent/advisor/GrimoSessionAdvisor.java`
- Modify: `src/main/java/io/github/samzhu/grimo/shared/session/SessionEventListener.java`

- [ ] **Step 1: 建立 AgentCallRecordedEvent**

```java
package io.github.samzhu.grimo.shared.event;

/**
 * Agent 對話記錄事件。
 * 設計說明：解耦 GrimoSessionAdvisor → SessionWriter 直接依賴。
 * Advisor 發 event，SessionEventListener 監聽後寫入 JSONL。
 */
public record AgentCallRecordedEvent(String userGoal, String agentResult) {}
```

- [ ] **Step 2: 修改 GrimoSessionAdvisor**

讀取現有 `GrimoSessionAdvisor.java`。改為注入 `ApplicationEventPublisher` 取代 `SessionWriter`：

```java
// Before
private final SessionWriter sessionWriter;
public GrimoSessionAdvisor(SessionWriter sessionWriter) { ... }
// adviseCall: sessionWriter.writeUserMessage(...) / writeAssistantMessage(...)

// After
private final ApplicationEventPublisher eventPublisher;
public GrimoSessionAdvisor(ApplicationEventPublisher eventPublisher) { ... }
// adviseCall: eventPublisher.publishEvent(new AgentCallRecordedEvent(goal, result))
```

同步更新所有建立 GrimoSessionAdvisor 的地方 — grep `new GrimoSessionAdvisor` 找到呼叫端，傳入 eventPublisher 取代 sessionWriter。

- [ ] **Step 3: SessionEventListener 新增監聽**

```java
@EventListener
void on(AgentCallRecordedEvent event) {
    sessionWriter.writeUserMessage(event.userGoal());
    sessionWriter.writeAssistantMessage(event.agentResult());
}
```

- [ ] **Step 4: 編譯 + 測試驗證**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```
refactor: decouple GrimoSessionAdvisor from SessionWriter via AgentCallRecordedEvent
```

---

### Task 2: 提取 TuiKeyHandler

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/tui/TuiKeyHandler.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`

- [ ] **Step 1: 讀取 GrimoTuiRunner 的 inner class TuiKeyHandler**

仔細讀取整個 inner class 的程式碼，理解它存取了 GrimoTuiRunner 的哪些 field 和 method。

- [ ] **Step 2: 定義 InputCallback interface**

```java
// 放在 tui/TuiKeyHandler.java 內或獨立檔案
public interface InputCallback {
    void onTextSubmit(String text);
    void onExit();
    void onShowAgentPicker();
    void onOpenMcpManager();
    // 其他需要的 callback
}
```

- [ ] **Step 3: 建立 TuiKeyHandler top-level class**

建構子接收所有需要的 TUI 元件 + InputCallback：

```java
package io.github.samzhu.grimo.tui;

public class TuiKeyHandler implements EventLoop.KeyHandler {
    private final Screen screen;
    private final ContentView contentView;
    private final InputView inputView;
    private final SlashMenu slashMenu;
    // ... 其他 TUI 元件
    private final InputCallback callback;

    @Override
    public void handleKey(String operation, String lastBinding) {
        // 從 inner class 搬過來的邏輯
    }
}
```

- [ ] **Step 4: GrimoTuiRunner 改用新的 TuiKeyHandler**

```java
var keyHandler = new TuiKeyHandler(screen, contentView, inputView, slashMenu, mcpPanel,
    textSelection, clipboard, autoScroller, /* InputCallback */ this::processInput, ...);
eventLoop = new EventLoop(terminal, screen, keyHandler);
```

刪除 GrimoTuiRunner 中的 inner class TuiKeyHandler。

- [ ] **Step 5: 編譯驗證**

Run: `./gradlew compileJava`

- [ ] **Step 6: Commit**

```
refactor: extract TuiKeyHandler from GrimoTuiRunner inner class to tui/
```

---

### Task 3: 提取 TuiEventBridge

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/tui/TuiEventBridge.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`

- [ ] **Step 1: 建立 TuiEventBridge**

```java
package io.github.samzhu.grimo.tui;

@Component
public class TuiEventBridge {
    private volatile StatusView statusView;
    private volatile ContentView contentView;
    private volatile Runnable setDirty;
    // 其他需要的引用（config, registry 等 for refreshStatusBar）

    /** TuiAdapter 在建構 TUI 元件後呼叫 bind */
    public void bind(StatusView sv, ContentView cv, Runnable dirty, ...) {
        this.statusView = sv;
        this.contentView = cv;
        this.setDirty = dirty;
    }

    @EventListener void on(AgentSwitchedEvent event) {
        if (statusView == null) return;
        refreshStatusBar();
        setDirty.run();
    }
    // ... 其他 3 個 @EventListener
}
```

- [ ] **Step 2: GrimoStartupRunner 加 TuiEventBridge bean（如果不是自動掃描）**

TuiEventBridge 有 @Component，Spring 會自動掃描。但確認它在正確的 package 被掃描到。

- [ ] **Step 3: GrimoTuiRunner 注入 TuiEventBridge + bind**

建構子加入 `TuiEventBridge tuiEventBridge`。在 run() 建構 TUI 元件後：

```java
tuiEventBridge.bind(statusView, contentView, () -> eventLoop.setDirty(), ...);
```

移除 GrimoTuiRunner 中的 4 個 @EventListener 方法和 refreshStatusBar()。

- [ ] **Step 4: 編譯驗證**

Run: `./gradlew compileJava`

- [ ] **Step 5: Commit**

```
refactor: extract TuiEventBridge — domain events → TUI updates
```

---

### Task 4: 提取 ChatDispatcher + 啟動初始化搬遷

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/ChatDispatcher.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`

- [ ] **Step 1: 建立 ChatDispatcher**

讀取 GrimoTuiRunner.processInput() 中 AI 對話分派的邏輯（tier routing → AgentClient → response）。

```java
@Component
public class ChatDispatcher {
    // 注入：agentModelRegistry, agentRouter, tierRouter,
    //       tierKeywordDetector, tierOptionsFactory, mcpCatalogBuilder,
    //       sessionTier, eventPublisher

    public String dispatch(String userInput) {
        // tier routing
        // agent availability check
        // build AgentClient with MCP + advisors
        // run and return result
    }
}
```

- [ ] **Step 2: 搬移啟動初始化到 GrimoStartupRunner**

GrimoTuiRunner.run() 的 Phase 1-2（detectAgents, loadSkills, restoreTasks, detectSandbox）搬到 GrimoStartupRunner。可以用 `@PostConstruct` 或另一個 `ApplicationRunner` bean。

保留在 GrimoTuiRunner：Phase 3（環境資訊）、Phase 4（TUI 建構）、Phase 5（event loop）。

- [ ] **Step 3: GrimoTuiRunner 使用 ChatDispatcher**

processInput() 中 AI 對話分支改為：
```java
Thread.ofVirtual().name("grimo-chat").start(() -> {
    String response = chatDispatcher.dispatch(userInput);
    contentView.appendLine(new AttributedString(response));
    eventLoop.setDirty();
});
```

- [ ] **Step 4: 編譯 + 全量測試**

Run: `./gradlew build`

- [ ] **Step 5: Commit**

```
refactor: extract ChatDispatcher + move startup init to StartupRunner
```

---

### Task 5: Rename GrimoTuiRunner → TuiAdapter

**Files:**
- Rename: `GrimoTuiRunner.java` → `TuiAdapter.java`

- [ ] **Step 1: Rename**

`git mv GrimoTuiRunner.java TuiAdapter.java`，更新類名、Logger、建構子。

- [ ] **Step 2: 驗證行數**

Run: `wc -l src/main/java/io/github/samzhu/grimo/TuiAdapter.java`
Expected: ≤ 400 lines

如超過 400，識別可進一步提取的邏輯（buildMenuItems、overlay helper methods 等）。

- [ ] **Step 3: Full build**

Run: `./gradlew build`

- [ ] **Step 4: Commit**

```
refactor: rename GrimoTuiRunner → TuiAdapter
```

---

### Task 6: 擴充 IncomingMessageEvent + OutgoingMessageEvent

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/event/IncomingMessageEvent.java`
- Modify: `src/main/java/io/github/samzhu/grimo/shared/event/OutgoingMessageEvent.java`

- [ ] **Step 1: 讀取現有 record 欄位**

讀 IncomingMessageEvent 和 OutgoingMessageEvent 現有定義，確認需要新增哪些欄位。

需要：
- IncomingMessageEvent: `sourceAdapter`（"tui"/"line"/"discord"）、`sessionId`
- OutgoingMessageEvent: `targetAdapter`（routing 到特定 adapter）

保持向後相容 — 新增欄位，不刪除現有欄位。

- [ ] **Step 2: 更新 record + 所有呼叫端**

- [ ] **Step 3: 編譯驗證**

Run: `./gradlew compileJava`

- [ ] **Step 4: Commit**

```
refactor: extend Incoming/OutgoingMessageEvent with adapter routing fields
```

---

### Task 7: 建立 MessageRouter + TuiAdapter 事件管線

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/MessageRouter.java`
- Modify: `src/main/java/io/github/samzhu/grimo/TuiAdapter.java`

- [ ] **Step 1: 建立 MessageRouter**

```java
@Component
public class MessageRouter {
    private final CommandParser commandParser;
    private final CommandExecutor commandExecutor;
    private final ChatDispatcher chatDispatcher;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    void on(IncomingMessageEvent event) {
        String text = event.text();
        String result;
        if (text.startsWith("/")) {
            var context = commandParser.parse(text.substring(1));
            result = commandExecutor.execute(context);
        } else {
            result = chatDispatcher.dispatch(text);
        }
        if (result != null && !result.isEmpty()) {
            eventPublisher.publishEvent(new OutgoingMessageEvent(
                result, event.sourceAdapter(), ...));
        }
    }
}
```

- [ ] **Step 2: TuiAdapter 改為發送 IncomingMessageEvent**

processInput() 改為：
```java
private void onUserSubmit(String text) {
    // TUI-specific intercepts（不走 MessageRouter）
    if (text.equals("/agent-use")) { showAgentPicker(); return; }
    if (text.equals("/mcp")) { openMcpManager(); return; }
    if (text.equals("/exit")) { ... return; }

    // 記錄使用者輸入到 ContentView
    contentView.appendUserInput(text);

    // 發送事件 → MessageRouter 處理
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

- [ ] **Step 4: 移除 TuiAdapter 中的直接 CommandExecutor/ChatDispatcher 呼叫**

processInput() 中所有走 MessageRouter 的路徑，不再直接呼叫 CommandExecutor 或 ChatDispatcher。

- [ ] **Step 5: 編譯 + 全量測試**

Run: `./gradlew build`

- [ ] **Step 6: Commit**

```
feat: add MessageRouter — unified IncomingMessage→OutgoingMessage pipeline
```

---

### Task 8: Glossary + CLAUDE.md 更新

**Files:**
- Modify: `docs/glossary.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: CLAUDE.md 更新**

Architecture 表格：
- 更新 root package 說明（加入 TuiAdapter, ChatDispatcher, MessageRouter）
- 更新 tui/ 說明（加入 TuiKeyHandler, TuiEventBridge）
- 更新 event-driven 說明

- [ ] **Step 2: Glossary 新增術語**

TuiAdapter、ChatDispatcher、MessageRouter、TuiEventBridge、TuiKeyHandler、AgentCallRecordedEvent。

- [ ] **Step 3: Commit**

```
docs: update glossary and CLAUDE.md for hexagonal architecture
```

---

### Task 9: 全量驗證

- [ ] **Step 1: Full build**

Run: `./gradlew build`

- [ ] **Step 2: 驗證元件**

```bash
# TuiAdapter 存在且 ≤ 400 lines
wc -l src/main/java/io/github/samzhu/grimo/TuiAdapter.java

# GrimoTuiRunner 不存在
ls src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java 2>&1

# 新元件都存在
ls src/main/java/io/github/samzhu/grimo/ChatDispatcher.java
ls src/main/java/io/github/samzhu/grimo/MessageRouter.java
ls src/main/java/io/github/samzhu/grimo/tui/TuiKeyHandler.java
ls src/main/java/io/github/samzhu/grimo/tui/TuiEventBridge.java
```

- [ ] **Step 3: 驗證訊息管線**

`grep -r "IncomingMessageEvent" src/main/java/` — 確認 TuiAdapter 發送、MessageRouter 接收。
`grep -r "OutgoingMessageEvent" src/main/java/` — 確認 MessageRouter 發送、TuiAdapter + ChannelEventListener 接收。

- [ ] **Step 4: Commit（若有遺漏）**

```
fix: address remaining issues from SP4 verification
```
