# Dispatch Lifecycle Event + ReactionIndicator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add OpenClaw-style Reaction Lifecycle to Grimo TUI — emoji reactions (👀🤔🔥👨‍💻⚡✨👍😱) reflect agent orchestration state in real time, replacing static "thinking..." text.

**Architecture:** DDD domain events (`sealed interface DispatchLifecycleEvent`) published by ChatDispatcher at each dispatch stage. TuiEventBridge subscribes and drives ReactionIndicator widget (floating line in ContentView, not in buffer). Thinking state cycles random text + timer.

**Tech Stack:** Java 25, Spring Boot 4.0.x (ApplicationEventPublisher + @EventListener), JLine 3 (AttributedString), JUnit 5 + AssertJ

**Spec:** `docs/superpowers/specs/2026-04-06-dispatch-lifecycle-reaction-design.md`

**SDK Note:** Spring AI Community Agent Client 0.11.0 only supports synchronous `client.run()`. No streaming, no intermediate callbacks. tool/coding/web events are **defined but not published** — ready for future SDK streaming support.

**Glossary:** See `docs/glossary.md` for: ReactionIndicator, DispatchLifecycleEvent, Reaction Lifecycle, ContentView, TuiEventBridge, ChatDispatcher, @EventListener conventions.

---

### File Structure

**New files:**

| File | Responsibility |
|------|---------------|
| `shared/event/DispatchLifecycleEvent.java` | sealed interface — event group |
| `shared/event/DispatchQueuedEvent.java` | 👀 queued |
| `shared/event/DispatchThinkingStartedEvent.java` | 🤔 thinking |
| `shared/event/DispatchToolCalledEvent.java` | 🔥 tool (future) |
| `shared/event/DispatchCodingEvent.java` | 👨‍💻 coding (future) |
| `shared/event/DispatchWebSearchEvent.java` | ⚡ web (future) |
| `shared/event/DispatchResponseReceivedEvent.java` | ✨ responding |
| `shared/event/DispatchCompletedEvent.java` | 👍 done |
| `shared/event/DispatchFailedEvent.java` | 😱 error |
| `tui/widget/ReactionIndicator.java` | Reaction state machine + animation + render |

**Modified files:**

| File | Change |
|------|--------|
| `ChatDispatcher.java` | Inject `ApplicationEventPublisher`; publish events at each stage; remove manual `appendLine("thinking...")` and `removeLastLine()` |
| `tui/TuiEventBridge.java` | Add 8 `@EventListener` methods; accept `ReactionIndicator` in bind() |
| `tui/view/ContentView.java` | Add `setReactionIndicator()`; inject reaction line in render pipeline |
| `TuiAdapter.java` | Construct `ReactionIndicator`; pass to ContentView and TuiEventBridge |

---

### Task 1: DispatchLifecycleEvent sealed interface + all event records

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/DispatchLifecycleEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/DispatchQueuedEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/DispatchThinkingStartedEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/DispatchToolCalledEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/DispatchCodingEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/DispatchWebSearchEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/DispatchResponseReceivedEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/DispatchCompletedEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/DispatchFailedEvent.java`
- Test: `src/test/java/io/github/samzhu/grimo/shared/event/DispatchLifecycleEventTest.java`

- [ ] **Step 1: Create sealed interface**

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
 *
 * @see <a href="https://docs.spring.io/spring-modulith/reference/events.html">Spring Modulith Events</a>
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

- [ ] **Step 2: Create all 8 event records**

Each in its own file in `shared/event/`:

```java
// DispatchQueuedEvent.java — 👀 系統收到訊息，開始處理
package io.github.samzhu.grimo.shared.event;
public record DispatchQueuedEvent(String userInput) implements DispatchLifecycleEvent {}

// DispatchThinkingStartedEvent.java — 🤔 Agent 開始思考
package io.github.samzhu.grimo.shared.event;
public record DispatchThinkingStartedEvent(String agentId, String model) implements DispatchLifecycleEvent {}

// DispatchToolCalledEvent.java — 🔥 Agent 呼叫工具（未來 SDK streaming 支援）
package io.github.samzhu.grimo.shared.event;
/** 未來由 Agent Client SDK streaming callback 發布。目前只定義，不發布。 */
public record DispatchToolCalledEvent(String agentId, String toolName) implements DispatchLifecycleEvent {}

// DispatchCodingEvent.java — 👨‍💻 Agent 寫/編輯程式碼（未來 SDK streaming 支援）
package io.github.samzhu.grimo.shared.event;
/** 未來由 Agent Client SDK streaming callback 發布。目前只定義，不發布。 */
public record DispatchCodingEvent(String agentId, String filePath) implements DispatchLifecycleEvent {}

// DispatchWebSearchEvent.java — ⚡ Agent 搜尋/瀏覽網頁（未來 SDK streaming 支援）
package io.github.samzhu.grimo.shared.event;
/** 未來由 Agent Client SDK streaming callback 發布。目前只定義，不發布。 */
public record DispatchWebSearchEvent(String agentId, String query) implements DispatchLifecycleEvent {}

// DispatchResponseReceivedEvent.java — ✨ 收到回覆
package io.github.samzhu.grimo.shared.event;
/** 目前與 Completed 幾乎同時發生。未來 streaming 模式下在第一個 token 到達時發布。 */
public record DispatchResponseReceivedEvent(String agentId, String model, long durationMs) implements DispatchLifecycleEvent {}

// DispatchCompletedEvent.java — 👍 成功完成
package io.github.samzhu.grimo.shared.event;
public record DispatchCompletedEvent(String agentId, String model, long durationMs, int resultLength) implements DispatchLifecycleEvent {}

// DispatchFailedEvent.java — 😱 失敗
package io.github.samzhu.grimo.shared.event;
public record DispatchFailedEvent(String agentId, String model, String errorMessage, long durationMs) implements DispatchLifecycleEvent {}
```

- [ ] **Step 3: Write test verifying sealed interface hierarchy**

```java
package io.github.samzhu.grimo.shared.event;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DispatchLifecycleEventTest {

    @Test
    void allEventsImplementSealedInterface() {
        DispatchLifecycleEvent queued = new DispatchQueuedEvent("hello");
        DispatchLifecycleEvent thinking = new DispatchThinkingStartedEvent("claude", "sonnet");
        DispatchLifecycleEvent tool = new DispatchToolCalledEvent("claude", "read_file");
        DispatchLifecycleEvent coding = new DispatchCodingEvent("claude", "/src/Main.java");
        DispatchLifecycleEvent web = new DispatchWebSearchEvent("claude", "spring modulith");
        DispatchLifecycleEvent response = new DispatchResponseReceivedEvent("claude", "sonnet", 5000);
        DispatchLifecycleEvent completed = new DispatchCompletedEvent("claude", "sonnet", 5000, 1024);
        DispatchLifecycleEvent failed = new DispatchFailedEvent("claude", "sonnet", "timeout", 120000);

        assertThat(queued).isInstanceOf(DispatchLifecycleEvent.class);
        assertThat(thinking).isInstanceOf(DispatchLifecycleEvent.class);
        assertThat(tool).isInstanceOf(DispatchLifecycleEvent.class);
        assertThat(coding).isInstanceOf(DispatchLifecycleEvent.class);
        assertThat(web).isInstanceOf(DispatchLifecycleEvent.class);
        assertThat(response).isInstanceOf(DispatchLifecycleEvent.class);
        assertThat(completed).isInstanceOf(DispatchLifecycleEvent.class);
        assertThat(failed).isInstanceOf(DispatchLifecycleEvent.class);
    }

    @Test
    void patternMatchingOnSealedType() {
        DispatchLifecycleEvent event = new DispatchThinkingStartedEvent("claude", "sonnet");
        String result = switch (event) {
            case DispatchQueuedEvent e -> "queued";
            case DispatchThinkingStartedEvent e -> "thinking:" + e.agentId();
            case DispatchToolCalledEvent e -> "tool:" + e.toolName();
            case DispatchCodingEvent e -> "coding";
            case DispatchWebSearchEvent e -> "web";
            case DispatchResponseReceivedEvent e -> "response";
            case DispatchCompletedEvent e -> "completed";
            case DispatchFailedEvent e -> "failed";
        };
        assertThat(result).isEqualTo("thinking:claude");
    }

    @Test
    void failedEventAllowsNullAgentAndModel() {
        var event = new DispatchFailedEvent(null, null, "routing failed", 0);
        assertThat(event.agentId()).isNull();
        assertThat(event.model()).isNull();
        assertThat(event.errorMessage()).isEqualTo("routing failed");
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.event.DispatchLifecycleEventTest" -x nativeTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/event/Dispatch*.java \
        src/test/java/io/github/samzhu/grimo/shared/event/DispatchLifecycleEventTest.java
git commit -m "feat(event): add DispatchLifecycleEvent sealed interface + 8 event records"
```

---

### Task 2: ReactionIndicator widget

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/tui/widget/ReactionIndicator.java`
- Test: `src/test/java/io/github/samzhu/grimo/tui/widget/ReactionIndicatorTest.java`

- [ ] **Step 1: Write ReactionIndicator tests**

```java
package io.github.samzhu.grimo.tui.widget;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReactionIndicatorTest {

    @Test
    void initialStateIsIdle() {
        var indicator = new ReactionIndicator(() -> {});
        assertThat(indicator.isActive()).isFalse();
        assertThat(indicator.render(80)).isNull();
    }

    @Test
    void setStateShouldActivateAndRender() {
        var indicator = new ReactionIndicator(() -> {});
        indicator.setState(ReactionIndicator.State.QUEUED, "Received...");

        assertThat(indicator.isActive()).isTrue();
        var rendered = indicator.render(80);
        assertThat(rendered).isNotNull();
        assertThat(rendered.toString()).contains("👀").contains("Received...");
    }

    @Test
    void stopShouldDeactivate() {
        var indicator = new ReactionIndicator(() -> {});
        indicator.setState(ReactionIndicator.State.THINKING, "Thinking...");
        assertThat(indicator.isActive()).isTrue();

        indicator.stop();
        assertThat(indicator.isActive()).isFalse();
        assertThat(indicator.render(80)).isNull();
    }

    @Test
    void toolStateShouldShowFireEmoji() {
        var indicator = new ReactionIndicator(() -> {});
        indicator.setState(ReactionIndicator.State.TOOL, "Using read_file...");

        var rendered = indicator.render(80);
        assertThat(rendered.toString()).contains("🔥").contains("Using read_file...");
    }

    @Test
    void codingStateShouldShowCodingEmoji() {
        var indicator = new ReactionIndicator(() -> {});
        indicator.setState(ReactionIndicator.State.CODING, "Writing code...");

        var rendered = indicator.render(80);
        assertThat(rendered.toString()).contains("👨‍💻").contains("Writing code...");
    }

    @Test
    void webStateShouldShowLightningEmoji() {
        var indicator = new ReactionIndicator(() -> {});
        indicator.setState(ReactionIndicator.State.WEB, "Searching...");

        var rendered = indicator.render(80);
        assertThat(rendered.toString()).contains("⚡").contains("Searching...");
    }

    @Test
    void respondingStateShouldShowSparkleEmoji() {
        var indicator = new ReactionIndicator(() -> {});
        indicator.setState(ReactionIndicator.State.RESPONDING, "Receiving...");

        var rendered = indicator.render(80);
        assertThat(rendered.toString()).contains("✨").contains("Receiving...");
    }

    @Test
    void errorStateShouldShowErrorEmoji() {
        var indicator = new ReactionIndicator(() -> {});
        indicator.setState(ReactionIndicator.State.ERROR, "Timeout");

        var rendered = indicator.render(80);
        assertThat(rendered.toString()).contains("😱").contains("Timeout");
    }

    @Test
    void startThinkingAnimationShouldActivate() throws InterruptedException {
        var indicator = new ReactionIndicator(() -> {});
        indicator.startThinkingAnimation();

        Thread.sleep(100); // let animation thread start
        assertThat(indicator.isActive()).isTrue();
        var rendered = indicator.render(80);
        assertThat(rendered.toString()).contains("🤔");

        indicator.stop();
    }

    @Test
    void stopShouldCancelAnimation() throws InterruptedException {
        var indicator = new ReactionIndicator(() -> {});
        indicator.startThinkingAnimation();
        Thread.sleep(100);

        indicator.stop();
        Thread.sleep(100);
        assertThat(indicator.isActive()).isFalse();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.widget.ReactionIndicatorTest" -x nativeTest`
Expected: FAIL — ReactionIndicator does not exist

- [ ] **Step 3: Implement ReactionIndicator**

```java
package io.github.samzhu.grimo.tui.widget;

import io.github.samzhu.grimo.tui.core.DisplayWidth;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reaction Indicator：將 agent 執行狀態映射為 emoji reaction。
 *
 * 設計說明（參考 OpenClaw Reaction Lifecycle）：
 * - 不在 ContentView buffer 裡 — 是 render 時動態加入的浮動行
 * - isActive() 為 false 時 render() 回傳 null，ContentView 不加入任何行
 * - Thinking 狀態支援隨機文字循環（每 3 秒換）+ 超過 5 秒顯示計時
 * - 純 Java widget，不依賴 Spring（由 TuiEventBridge 驅動）
 * - 動畫取消：stop() 設 animating=false + interrupt animationThread
 */
public class ReactionIndicator {

    public enum State {
        IDLE, QUEUED, THINKING, TOOL, CODING, WEB, RESPONDING, DONE, ERROR
    }

    private static final AttributedStyle DIM_STYLE = AttributedStyle.DEFAULT.foreground(245);

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

    private volatile State state = State.IDLE;
    private volatile String emoji = "";
    private volatile String text = "";
    private volatile long startMs;
    private volatile boolean animating;
    private volatile Thread animationThread;
    private String lastThinkingText = "";
    private final Runnable setDirty;

    public ReactionIndicator(Runnable setDirty) {
        this.setDirty = setDirty;
    }

    public void setState(State state, String text) {
        stopAnimation();
        this.state = state;
        this.emoji = emojiFor(state);
        this.text = text;
        this.startMs = System.currentTimeMillis();
    }

    public void startThinkingAnimation() {
        stopAnimation();
        this.state = State.THINKING;
        this.emoji = emojiFor(State.THINKING);
        this.text = randomThinkingText();
        this.startMs = System.currentTimeMillis();
        this.animating = true;

        this.animationThread = Thread.ofVirtual()
                .name("grimo-reaction-anim")
                .start(() -> {
                    try {
                        while (animating) {
                            Thread.sleep(3000);
                            if (!animating) break;
                            this.text = randomThinkingText();
                            setDirty.run();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
    }

    public void stop() {
        this.state = State.IDLE;
        stopAnimation();
    }

    public boolean isActive() {
        return state != State.IDLE;
    }

    public AttributedString render(int cols) {
        if (state == State.IDLE) return null;

        long elapsed = (System.currentTimeMillis() - startMs) / 1000;
        String display;
        if (state == State.THINKING && elapsed >= 5) {
            display = emoji + " " + text + " (" + elapsed + "s)";
        } else {
            display = emoji + " " + text;
        }

        String fitted = DisplayWidth.padRight(DisplayWidth.truncate(display, cols), cols);
        return new AttributedString(fitted, DIM_STYLE);
    }

    // --- internal ---

    private void stopAnimation() {
        animating = false;
        var thread = animationThread;
        if (thread != null) {
            thread.interrupt();
            animationThread = null;
        }
    }

    private String randomThinkingText() {
        String picked;
        do {
            picked = THINKING_TEXTS.get(
                    ThreadLocalRandom.current().nextInt(THINKING_TEXTS.size()));
        } while (picked.equals(lastThinkingText) && THINKING_TEXTS.size() > 1);
        lastThinkingText = picked;
        return picked;
    }

    private static String emojiFor(State state) {
        return switch (state) {
            case QUEUED -> "👀";
            case THINKING -> "🤔";
            case TOOL -> "🔥";
            case CODING -> "👨‍💻";
            case WEB -> "⚡";
            case RESPONDING -> "✨";
            case DONE -> "👍";
            case ERROR -> "😱";
            case IDLE -> "";
        };
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.widget.ReactionIndicatorTest" -x nativeTest`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/tui/widget/ReactionIndicator.java \
        src/test/java/io/github/samzhu/grimo/tui/widget/ReactionIndicatorTest.java
git commit -m "feat(tui): add ReactionIndicator widget — OpenClaw-style emoji state feedback"
```

---

### Task 3: ContentView integration — floating reaction line

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/tui/view/ContentView.java`

- [ ] **Step 1: Add ReactionIndicator to ContentView**

Add field and setter:

```java
private volatile ReactionIndicator reactionIndicator;

public void setReactionIndicator(ReactionIndicator indicator) {
    this.reactionIndicator = indicator;
}
```

- [ ] **Step 2: Inject reaction line in render pipeline**

In `render(int cols, int viewHeight)`, after building `wrappedVisible` list (around line 342) and before the bottom-alignment section (line 345), add:

```java
// Inject floating reaction line (not in persistent buffer)
if (reactionIndicator != null && reactionIndicator.isActive()) {
    var reactionLine = reactionIndicator.render(cols);
    if (reactionLine != null) {
        wrappedVisible.add(reactionLine);
    }
}
```

This inserts the reaction line AFTER the last buffer content, BEFORE the bottom-alignment padding calculation. The reaction line participates in viewHeight sizing so it always appears at the visible bottom.

- [ ] **Step 3: Run full tests**

Run: `./gradlew test -x nativeTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/tui/view/ContentView.java
git commit -m "feat(tui): ContentView floating reaction line in render pipeline"
```

---

### Task 4: ChatDispatcher — publish lifecycle events

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/ChatDispatcher.java`

- [ ] **Step 1: Inject ApplicationEventPublisher**

ChatDispatcher already has many constructor params. Add `ApplicationEventPublisher eventPublisher` to the constructor. Read the current constructor to see exact position.

Add import:
```java
import io.github.samzhu.grimo.shared.event.*;
```

- [ ] **Step 2: Modify dispatch(String userInput) — TUI path**

Replace the current dispatch method body (lines 111-162) with event-publishing version:

```java
public void dispatch(String userInput) {
    if (agentState == null || contentView == null) {
        log.warn("ChatDispatcher not bound to TUI yet, ignoring dispatch");
        return;
    }
    if (agentState.agentRunning) {
        contentView.appendError("Agent is still running. Wait or press Ctrl+C to cancel.");
        return;
    }

    // 👀 Queued
    eventPublisher.publishEvent(new DispatchQueuedEvent(userInput));

    try {
        var tierSelection = resolveTier(userInput);
        currentTierSelection = tierSelection;

        agentState.agentRunning = true;
        // REMOVED: manual appendLine("thinking...") — ReactionIndicator handles this via events
        eventLoop.setDirty();

        agentState.agentThread = Thread.startVirtualThread(() -> {
            long startMs = System.currentTimeMillis();
            try {
                // 🤔 Thinking
                eventPublisher.publishEvent(new DispatchThinkingStartedEvent(
                    tierSelection.agentId(), tierSelection.model()));

                // REMOVED: contentView.removeLastLine() — no manual thinking line to remove
                String result = doDispatch(userInput, tierSelection);
                long duration = System.currentTimeMillis() - startMs;

                // ✨ Response received
                eventPublisher.publishEvent(new DispatchResponseReceivedEvent(
                    tierSelection.agentId(), tierSelection.model(), duration));

                if (result != null && !result.isBlank()) {
                    contentView.appendAiReply(result);
                }
                sessionManager.getWriter().writeAssistantMessage(result);

                // 👍 Completed
                eventPublisher.publishEvent(new DispatchCompletedEvent(
                    tierSelection.agentId(), tierSelection.model(), duration,
                    result != null ? result.length() : 0));

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startMs;
                log.error("Agent call failed: error={}", e.getMessage(), e);

                // 😱 Failed
                eventPublisher.publishEvent(new DispatchFailedEvent(
                    tierSelection.agentId(), tierSelection.model(),
                    e.getMessage(), duration));

                String errorMsg = formatAgentError(e);
                contentView.appendError(errorMsg);
            } finally {
                agentState.agentRunning = false;
                agentState.agentThread = null;
                currentTierSelection = null;
                statusView.setStatusText(tuiEventBridge.getOriginalStatusText());
                eventLoop.setDirty();
            }
        });
    } catch (IllegalStateException e) {
        // 😱 Routing failed
        eventPublisher.publishEvent(new DispatchFailedEvent(
            null, null, e.getMessage(), 0));
        log.warn("Agent routing failed: {}", e.getMessage());
        contentView.appendError(e.getMessage());
    }
}
```

- [ ] **Step 3: Run full tests**

Run: `./gradlew test -x nativeTest`
Expected: PASS (existing tests + event tests)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/ChatDispatcher.java
git commit -m "feat(event): ChatDispatcher publishes DispatchLifecycleEvent at each stage"
```

---

### Task 5: TuiEventBridge + TuiAdapter wiring — subscribe to events and drive ReactionIndicator

**Note:** Tasks 5 and 6 (originally separate) are merged because `TuiEventBridge.bind()` signature change and `TuiAdapter` call site update must happen atomically — otherwise compilation fails.

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/tui/TuiEventBridge.java`
- Modify: `src/main/java/io/github/samzhu/grimo/TuiAdapter.java`
- Modify: `docs/glossary.md`

- [ ] **Step 1: Add ReactionIndicator to bind()**

Add field:
```java
private volatile ReactionIndicator reactionIndicator;
```

Update `bind()` method signature to accept `ReactionIndicator`:
```java
public void bind(StatusView statusView, ContentView contentView,
                 Runnable setDirty, String originalStatusText,
                 ReactionIndicator reactionIndicator) {
    // ... existing assignments ...
    this.reactionIndicator = reactionIndicator;
}
```

- [ ] **Step 2: Add 8 @EventListener methods**

Add imports:
```java
import io.github.samzhu.grimo.shared.event.*;
import io.github.samzhu.grimo.tui.widget.ReactionIndicator;
```

Add listeners:

```java
// --- Dispatch Lifecycle Event Listeners ---

@EventListener
void on(DispatchQueuedEvent event) {
    if (reactionIndicator == null) return;
    reactionIndicator.setState(ReactionIndicator.State.QUEUED, "Received...");
    setDirty.run();
}

@EventListener
void on(DispatchThinkingStartedEvent event) {
    if (reactionIndicator == null) return;
    reactionIndicator.startThinkingAnimation();
    setDirty.run();
}

@EventListener
void on(DispatchToolCalledEvent event) {
    if (reactionIndicator == null) return;
    reactionIndicator.setState(ReactionIndicator.State.TOOL,
        event.toolName() != null ? "Using " + event.toolName() + "..." : "Using tools...");
    setDirty.run();
}

@EventListener
void on(DispatchCodingEvent event) {
    if (reactionIndicator == null) return;
    String display = event.filePath() != null
        ? "Editing " + shortenPath(event.filePath()) + "..." : "Writing code...";
    reactionIndicator.setState(ReactionIndicator.State.CODING, display);
    setDirty.run();
}

@EventListener
void on(DispatchWebSearchEvent event) {
    if (reactionIndicator == null) return;
    reactionIndicator.setState(ReactionIndicator.State.WEB,
        event.query() != null ? "Searching: " + event.query() : "Browsing the web...");
    setDirty.run();
}

@EventListener
void on(DispatchResponseReceivedEvent event) {
    if (reactionIndicator == null) return;
    reactionIndicator.setState(ReactionIndicator.State.RESPONDING, "Receiving...");
    setDirty.run();
}

@EventListener
void on(DispatchCompletedEvent event) {
    if (reactionIndicator == null) return;
    reactionIndicator.stop();
    setDirty.run();
}

@EventListener
void on(DispatchFailedEvent event) {
    if (reactionIndicator == null) return;
    reactionIndicator.stop();
    setDirty.run();
}

// --- Helper ---

private String shortenPath(String path) {
    if (path == null) return "";
    int lastSlash = path.lastIndexOf('/');
    return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
}
```

- [ ] **Step 3: Wire ReactionIndicator in TuiAdapter.run()**

In `run()`, after creating contentView and before `tuiEventBridge.bind(...)`:

```java
// Reaction Indicator（OpenClaw-style emoji state feedback）
var reactionIndicator = new ReactionIndicator(() -> eventLoop.setDirty());
contentView.setReactionIndicator(reactionIndicator);
```

Update `tuiEventBridge.bind()` call to pass `reactionIndicator`:

```java
tuiEventBridge.bind(statusView, contentView, () -> eventLoop.setDirty(), statusText, reactionIndicator);
```

Add import:
```java
import io.github.samzhu.grimo.tui.widget.ReactionIndicator;
```

**Note:** `ReactionIndicator` needs `eventLoop.setDirty()` as its `setDirty` callback, but `eventLoop` is constructed AFTER `tuiKeyHandler` which is AFTER this point. Check the current construction order in `run()` and ensure `reactionIndicator` is constructed before `eventLoop`. Since `ReactionIndicator` takes a `Runnable`, we can use a lambda that lazily references `eventLoop`:

```java
// If eventLoop is not yet constructed at this point, use a wrapper:
var reactionIndicator = new ReactionIndicator(() -> {
    if (eventLoop != null) eventLoop.setDirty();
});
```

But since `TuiEventBridge.bind()` is called BEFORE `eventLoop` is constructed (check line 223 vs 201), and the listeners won't fire until user input starts (after `eventLoop.run()`), the lazy reference is safe.

- [ ] **Step 4: Remove old thinking indicator from processInput()**

In `processInput()`, find and remove this block (if it exists — check current code, it may already have been moved to ChatDispatcher):

```java
// REMOVE if present:
boolean isChat = !text.startsWith("/");
if (isChat) {
    contentView.appendLine(new AttributedString("\u23f3 thinking...", ...));
    eventLoop.setDirty();
}
```

The thinking indicator is now handled by `DispatchQueuedEvent` → `TuiEventBridge` → `ReactionIndicator`.

- [ ] **Step 5: Update glossary**

Add to `docs/glossary.md`:

```markdown
| **ReactionIndicator** | Reaction Indicator | TUI widget，將 agent 執行狀態映射為 emoji reaction（👀🤔🔥👨‍💻⚡✨👍😱）。ContentView render 時動態加入的浮動行，不在 buffer 裡。參考 OpenClaw Reaction Lifecycle 設計。 |
| **DispatchLifecycleEvent** | Dispatch Lifecycle Event | sealed interface，AI dispatch 生命週期事件群組。子 event：Queued、ThinkingStarted、ToolCalled、Coding、WebSearch、ResponseReceived、Completed、Failed。反映 orchestration state（系統正在做什麼），不是 message content。 |
| **Reaction Lifecycle** | Reaction Lifecycle | 設計模式（源自 OpenClaw）：系統在執行任務的不同階段主動切換 emoji reaction，表達執行狀態。低延遲回饋通道，不是裝飾。 |
```

- [ ] **Step 6: Run full tests**

Run: `./gradlew test -x nativeTest`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/tui/TuiEventBridge.java \
        src/main/java/io/github/samzhu/grimo/TuiAdapter.java \
        docs/glossary.md
git commit -m "feat(tui): wire ReactionIndicator — TuiEventBridge subscribers + TuiAdapter wiring + glossary"
```

---

### Task 6: Integration smoke test

- [ ] **Step 1: Build**

Run: `./gradlew build -x nativeCompile -x nativeTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run and verify reaction lifecycle**

Run: `./gradlew bootRun`

Type `hi` → verify:
1. 👀 appears immediately after Enter
2. 🤔 appears with random thinking text
3. Text changes every ~3 seconds
4. Timer appears after 5 seconds: `🤔 Mulling it over... (8s)`
5. ✨ briefly appears when response arrives
6. Reaction line disappears, AI reply renders

- [ ] **Step 3: Verify error state**

If agent is unavailable, verify 😱 appears briefly before error message.

- [ ] **Step 4: Verify Ctrl+C cancellation**

Type a message, then Ctrl+C while 🤔 is showing → reaction should stop immediately.
