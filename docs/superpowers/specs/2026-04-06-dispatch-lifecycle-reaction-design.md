# Dispatch Lifecycle Event + Reaction Indicator 設計

> Date: 2026-04-06
> Status: Draft
> Depends: Config Lifecycle Refactor (✅ Done), Agent Routing Fix (✅ Done)

## 問題

ChatDispatcher 的 dispatch 過程中，使用者看到的是靜態 `⏳ thinking...` 文字，直到回覆出現。沒有動畫、沒有狀態變化、沒有進度感。使用者不知道系統收到了沒、正在做什麼、要等多久。

## 研究基礎

### OpenClaw Reaction Lifecycle

> Reaction 不是裝飾，而是 agent UX 的「低延遲回饋通道」。

OpenClaw 在 Telegram/Discord 上的設計：收到訊息後立刻給 emoji reaction（👀），隨著 agent 執行狀態切換 emoji（🤔→🔥→👨‍💻→👍），最後才送完整文字回覆。

核心洞察：
- **兩層回饋**：快速 emoji reaction（毫秒級）+ 慢速文字回覆（秒級）
- **Reaction 反映 orchestration state**，不是 message content — 不看使用者輸入了什麼，而是系統正在做什麼
- 狀態對應：`queued→👀, thinking→🤔, tool→🔥, coding→👨‍💻, web→⚡, done→👍, error→😱`

來源：OpenClaw source code 分析、Discord bot interaction patterns

### Spring Modulith Event 設計原則

- 使用 `@EventListener`（非 `@ApplicationModuleListener`，CLI 無 transaction）
- Event 代表已發生的事實（past tense 命名）
- 每個 event 獨立 payload，不用 enum 區分
- sealed interface 分組，訂閱者可訂閱父類別收全部、或訂閱個別 event
- 一個 listener 一個職責

來源：[Spring Modulith Events](https://docs.spring.io/spring-modulith/reference/events.html)

## 目標

1. 定義 Dispatch Lifecycle Events（DDD domain events，sealed interface 分組）
2. 實作 ReactionIndicator widget — 將 orchestration state 映射為 emoji + 動態文字
3. ChatDispatcher 在 dispatch 各階段 publish event
4. TuiEventBridge 訂閱 event 驅動 ReactionIndicator
5. Thinking 狀態支援隨機文字循環 + 經過時間計時

## 設計

### 7 個 Reaction 狀態（對齊 OpenClaw）

| 狀態 | Emoji | 觸發 | 含義 |
|------|-------|------|------|
| queued | 👀 | 系統收到訊息 | 已收到，開始處理 |
| thinking | 🤔 | agent process 開始 | 正在思考 |
| tool | 🔥 | agent 呼叫工具 | 使用工具中 |
| coding | 👨‍💻 | agent 寫/編輯程式碼 | 寫程式中 |
| web | ⚡ | agent 搜尋網頁 | 搜尋中 |
| done | 👍 | 完成 | 成功 |
| error | 😱 | 失敗 | 出錯 |

這些狀態反映 **orchestration state**（系統正在做什麼），不是 message content（使用者說了什麼）。

### DDD Domain Events（sealed interface）

```java
package io.github.samzhu.grimo.shared.event;

/**
 * AI dispatch 生命週期事件群組。
 *
 * 設計說明（OpenClaw Reaction Lifecycle 模式）：
 * - sealed interface 分組：訂閱者可訂閱父類別收全部（logging），或訂閱個別 event（TUI）
 * - 每個 event 獨立 record，帶自己的 payload（DDD domain event）
 * - 反映 orchestration state，不是 message content
 * - 遵循 Spring @EventListener（非 @ApplicationModuleListener，CLI 無 transaction）
 */
public sealed interface DispatchLifecycleEvent permits
    DispatchQueuedEvent,
    DispatchThinkingStartedEvent,
    DispatchToolCalledEvent,
    DispatchCodingEvent,
    DispatchWebSearchEvent,
    DispatchResponseReceivedEvent,
    DispatchCompletedEvent,
    DispatchFailedEvent {}
```

### 個別 Event Records

```java
// 👀 系統收到訊息，開始處理
public record DispatchQueuedEvent(
    String userInput
) implements DispatchLifecycleEvent {}

// 🤔 Agent 開始思考
public record DispatchThinkingStartedEvent(
    String agentId,
    String model
) implements DispatchLifecycleEvent {}

// 🔥 Agent 呼叫工具
public record DispatchToolCalledEvent(
    String agentId,
    String toolName       // 工具名稱（"read_file", "bash" 等）
) implements DispatchLifecycleEvent {}

// 👨‍💻 Agent 寫/編輯程式碼
public record DispatchCodingEvent(
    String agentId,
    String filePath       // nullable，正在編輯的檔案
) implements DispatchLifecycleEvent {}

// ⚡ Agent 搜尋/瀏覽網頁
public record DispatchWebSearchEvent(
    String agentId,
    String query          // nullable，搜尋關鍵字
) implements DispatchLifecycleEvent {}

// ✨ 收到回覆（目前與 Completed 幾乎同時發生；為未來 streaming 預留：
//    streaming 模式下 ResponseReceived 在第一個 token 到達時發布，
//    Completed 在整個回覆串流結束後發布，中間 RESPONDING 狀態持續數秒）
public record DispatchResponseReceivedEvent(
    String agentId,
    String model,
    long durationMs
) implements DispatchLifecycleEvent {}

// 👍 成功完成
public record DispatchCompletedEvent(
    String agentId,
    String model,
    long durationMs,
    int resultLength
) implements DispatchLifecycleEvent {}

// 😱 失敗
public record DispatchFailedEvent(
    String agentId,       // nullable（routing 前失敗時）
    String model,         // nullable
    String errorMessage,
    long durationMs
) implements DispatchLifecycleEvent {}
```

### ReactionIndicator Widget

位置：`tui/widget/ReactionIndicator.java`

```java
/**
 * Reaction Indicator：將 agent 執行狀態映射為 emoji reaction。
 *
 * 設計說明（參考 OpenClaw Reaction Lifecycle）：
 * - 不在 ContentView buffer 裡 — 是 render 時動態加入的浮動行
 * - 不需要 appendLine/removeLastLine — isActive() 為 false 時自動消失
 * - Thinking 狀態支援隨機文字循環（每 3 秒換）+ 超過 5 秒顯示計時
 * - 純 Java widget，不依賴 Spring（由 TuiEventBridge 驅動）
 */
public class ReactionIndicator {

    public enum State {
        IDLE,        // 不顯示
        QUEUED,      // 👀
        THINKING,    // 🤔（動畫循環）
        TOOL,        // 🔥
        CODING,      // 👨‍💻
        WEB,         // ⚡
        RESPONDING,  // ✨
        DONE,        // 👍（短暫顯示後消失）
        ERROR        // 😱（短暫顯示後消失）
    }

    private static final Map<State, String> EMOJI_MAP = Map.of(
        State.QUEUED, "👀",
        State.THINKING, "🤔",
        State.TOOL, "🔥",
        State.CODING, "👨‍💻",
        State.WEB, "⚡",
        State.RESPONDING, "✨",
        State.DONE, "👍",
        State.ERROR, "😱"
    );

    private volatile State state = State.IDLE;
    private volatile String text;
    private volatile long startMs;
    private volatile boolean animating;
    private final List<String> thinkingTexts;
    private final Runnable setDirty;

    // 動畫 thread（用於取消）
    private volatile Thread animationThread;

    // 對外 API
    void setState(State state, String text);
    void startThinkingAnimation();    // 啟動 Virtual Thread 定時器
    void stop();                       // animating=false + interrupt animationThread
    boolean isActive();
    AttributedString render(int cols);
}
```

**動畫取消機制**：`startThinkingAnimation()` 將 Virtual Thread 存入 `animationThread` 欄位。`stop()` 設 `animating = false` 並呼叫 `animationThread.interrupt()`，讓 `Thread.sleep(3000)` 立即中斷。動畫 thread 在 `while(animating)` 迴圈頭檢查旗標，收到 InterruptedException 後自動退出。
```

### Thinking 隨機文字池

```java
private static final List<String> THINKING_TEXTS = List.of(
    "Thinking...",
    "Pondering the possibilities...",
    "Connecting the dots...",
    "Mulling it over...",
    "Assembling neurons...",
    "Turning the gears...",
    "Letting ideas simmer...",
    "Brewing an answer...",
    "Chewing on that...",
    "Contemplating deeply...",
    "Weighing the options...",
    "Crystallizing a response...",
    "Composing thoughts..."
);
```

每 3 秒從池中隨機取一個（不連續重複）。超過 5 秒後加計時：`🤔 Mulling it over... (8s)`。

### ContentView 整合

ReactionIndicator 行**不在 buffer 裡**，是 ContentView render 時動態加入的浮動行：

```java
// ContentView
private ReactionIndicator reactionIndicator;

public void setReactionIndicator(ReactionIndicator indicator) {
    this.reactionIndicator = indicator;
}

// 在 render(int cols, int viewHeight) 內，bottom-alignment 計算之前插入 reaction 行
// 具體位置：buffer 內容組裝完成後、padding 計算前，將 reaction 行加到 buffer 尾部
// 這樣 reaction 行會像最後一條訊息一樣，跟隨 autoFollow 滾動到底部
```

**整合位置**：在 `ContentView.render(int cols, int viewHeight)` 方法中，buffer wrap 完成後、bottom-alignment padding 計算前。Reaction 行作為 buffer 的最後一行參與 viewHeight 計算，確保它總是出現在可見區域底部。不另外加 `renderBuffer()` 方法 — 直接在現有 render pipeline 中有條件插入。

好處：
- 不需要獨立的 `appendLine` / `removeLastLine` / `updateLastLine`
- 不打亂 buffer 的捲動位置（reaction 行不存在於 persistent buffer）
- `stop()` 後下次 render 自動消失
- `appendAiReply()` 時 reaction 自動被推上去

### ChatDispatcher Event 發布位置

```java
dispatch(String userInput) {
    eventPublisher.publishEvent(new DispatchQueuedEvent(userInput));

    try {
        var tierSelection = resolveTier(userInput);
        // 不再手動 appendLine("thinking...") — 由 ReactionIndicator 處理

        agentState.agentRunning = true;
        agentState.agentThread = Thread.startVirtualThread(() -> {
            long startMs = System.currentTimeMillis();
            try {
                eventPublisher.publishEvent(new DispatchThinkingStartedEvent(
                    tierSelection.agentId(), tierSelection.model()));

                String result = doDispatch(userInput, tierSelection);
                long duration = System.currentTimeMillis() - startMs;

                eventPublisher.publishEvent(new DispatchResponseReceivedEvent(
                    tierSelection.agentId(), tierSelection.model(), duration));

                contentView.appendAiReply(result);
                sessionManager.getWriter().writeAssistantMessage(result);

                eventPublisher.publishEvent(new DispatchCompletedEvent(
                    tierSelection.agentId(), tierSelection.model(), duration,
                    result != null ? result.length() : 0));
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startMs;
                eventPublisher.publishEvent(new DispatchFailedEvent(
                    tierSelection.agentId(), tierSelection.model(),
                    e.getMessage(), duration));
                contentView.appendError(formatAgentError(e));
            } finally {
                agentState.agentRunning = false;
                agentState.agentThread = null;
                statusView.setStatusText(tuiEventBridge.getOriginalStatusText());
                eventLoop.setDirty();
            }
        });
    } catch (IllegalStateException e) {
        eventPublisher.publishEvent(new DispatchFailedEvent(
            null, null, e.getMessage(), 0));
        contentView.appendError(e.getMessage());
    }
}
```

### TuiEventBridge Listeners

```java
@EventListener
void on(DispatchQueuedEvent e) {
    reactionIndicator.setState(QUEUED, "Received...");
    setDirty.run();
}

@EventListener
void on(DispatchThinkingStartedEvent e) {
    reactionIndicator.startThinkingAnimation();
    setDirty.run();
}

@EventListener
void on(DispatchToolCalledEvent e) {
    reactionIndicator.setState(TOOL,
        e.toolName() != null ? "Using " + e.toolName() + "..." : "Using tools...");
    setDirty.run();
}

@EventListener
void on(DispatchCodingEvent e) {
    reactionIndicator.setState(CODING,
        e.filePath() != null ? "Editing " + shortenPath(e.filePath()) + "..." : "Writing code...");
    setDirty.run();
}

@EventListener
void on(DispatchWebSearchEvent e) {
    reactionIndicator.setState(WEB,
        e.query() != null ? "Searching: " + e.query() : "Browsing the web...");
    setDirty.run();
}

@EventListener
void on(DispatchResponseReceivedEvent e) {
    reactionIndicator.setState(RESPONDING, "Receiving...");
    setDirty.run();
}

@EventListener
void on(DispatchCompletedEvent e) {
    reactionIndicator.stop();
    setDirty.run();
}

@EventListener
void on(DispatchFailedEvent e) {
    reactionIndicator.stop();
    setDirty.run();
}
```

### 狀態機流程圖

```
IDLE → QUEUED(👀) → THINKING(🤔) → RESPONDING(✨) → IDLE
                        ↕                ↘ ERROR(😱) → IDLE
                   TOOL(🔥)
                   CODING(👨‍💻)
                   WEB(⚡)
```

THINKING 期間可來回切換 TOOL/CODING/WEB（視 agent 實際動作），每次切換更新 emoji + text。

### 目前可發布 vs 未來

| Event | 目前可發布 | 來源 |
|-------|:---:|------|
| DispatchQueuedEvent | ✅ | ChatDispatcher 進入 |
| DispatchThinkingStartedEvent | ✅ | doDispatch 前 |
| DispatchToolCalledEvent | ⏳ | 需 Agent Client SDK streaming callback |
| DispatchCodingEvent | ⏳ | 需 Agent Client SDK streaming callback |
| DispatchWebSearchEvent | ⏳ | 需 Agent Client SDK streaming callback |
| DispatchResponseReceivedEvent | ✅ | doDispatch 回傳後 |
| DispatchCompletedEvent | ✅ | 成功完成 |
| DispatchFailedEvent | ✅ | catch exception |

tool/coding/web 三個 event **先定義 class 和 TuiEventBridge listener**，ChatDispatcher 目前不發布。等 SDK 支援 streaming callback 時直接接上。設計先到位，實作漸進。

### 與既有 Event 的關係

| 既有 Event | 保留 | 原因 |
|-----------|:---:|------|
| `DevModeEnteredEvent` / `DevModeCompletedEvent` | ✅ | Dev Mode 有自己的 lifecycle，不屬於 DispatchLifecycleEvent |
| `AgentCallRecordedEvent` | ✅ | SessionAdvisor 發的，用於 JSONL 紀錄 |
| `AgentSwitchedEvent` | ✅ | 使用者主動切換，非 dispatch lifecycle |
| `McpCatalogChangedEvent` | ✅ | MCP 管理，非 dispatch lifecycle |
| `SessionSwitchedEvent` | ✅ | Session 管理，非 dispatch lifecycle |

DevModeEnteredEvent / DevModeCompletedEvent 可選擇性加上 `sealed interface DevModeLifecycleEvent`，但本次不做。

## 影響範圍

### 新增檔案

| 檔案 | 職責 |
|------|------|
| `shared/event/DispatchLifecycleEvent.java` | sealed interface |
| `shared/event/DispatchQueuedEvent.java` | 👀 queued |
| `shared/event/DispatchThinkingStartedEvent.java` | 🤔 thinking |
| `shared/event/DispatchToolCalledEvent.java` | 🔥 tool（未來 SDK 支援） |
| `shared/event/DispatchCodingEvent.java` | 👨‍💻 coding（未來 SDK 支援） |
| `shared/event/DispatchWebSearchEvent.java` | ⚡ web（未來 SDK 支援） |
| `shared/event/DispatchResponseReceivedEvent.java` | ✨ responding |
| `shared/event/DispatchCompletedEvent.java` | 👍 done |
| `shared/event/DispatchFailedEvent.java` | 😱 error |
| `tui/widget/ReactionIndicator.java` | Reaction 狀態機 + 動畫 + render |

### 修改檔案

| 檔案 | 變更 |
|------|------|
| `ChatDispatcher.java` | 注入 `ApplicationEventPublisher`；dispatch 各階段 publish event；移除手動 `appendLine("thinking...")` 和 `removeLastLine()` |
| `tui/TuiEventBridge.java` | 新增 8 個 `@EventListener`；bind 時接收 `ReactionIndicator` |
| `tui/view/ContentView.java` | 新增 `setReactionIndicator()`；render 動態加入 reaction 行 |
| `TuiAdapter.java` | 建構 `ReactionIndicator`，傳給 ContentView 和 TuiEventBridge |

### 不變檔案

| 檔案 | 原因 |
|------|------|
| `DevModeRunner.java` | 保留自己的 event lifecycle |
| `SessionEventListener.java` | 不訂閱 DispatchLifecycleEvent |
| `TuiKeyHandler.java` | 不感知 dispatch lifecycle |

## 測試

| 測試 | 覆蓋 |
|------|------|
| `ReactionIndicatorTest` | 狀態切換、render 輸出、動畫啟停、isActive、隨機文字不連續重複 |
| `TuiEventBridge integration` | DispatchLifecycleEvent → ReactionIndicator 狀態正確 |
| `ChatDispatcher event publish` | 各階段 event 正確發布（用 mock ApplicationEventPublisher 驗證） |

## Glossary 新增

| 術語 | 定義 |
|------|------|
| ReactionIndicator | TUI widget，將 agent 執行狀態映射為 emoji reaction（👀🤔🔥👨‍💻⚡✨👍😱）。ContentView render 時動態加入的浮動行。參考 OpenClaw Reaction Lifecycle 設計。 |
| DispatchLifecycleEvent | sealed interface，AI dispatch 生命週期事件群組。反映 orchestration state（系統正在做什麼），不是 message content（使用者說了什麼）。 |
| Reaction Lifecycle | 設計模式（源自 OpenClaw）：系統在執行任務的不同階段主動切換 emoji reaction，表達目前的執行狀態。低延遲回饋通道，不是裝飾。 |
