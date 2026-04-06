# TUI Refactor Phase 2–4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 拆解 `GrimoTuiRunner` God Object — 抽出 `AgentDispatcher`（Phase 2），清理 Dead Code（Phase 4）。Phase 3 初始化解耦留待後續。

**Architecture:**
Phase 1 已完成（event-driven status bar）。Phase 2 將 `GrimoTuiRunner.processInput()` 中 ~80 行的 agent virtual thread 邏輯抽成 `AgentDispatcher`，publish `AgentCompletedEvent` / `AgentFailedEvent`，TuiRunner 只監聽事件更新 UI。Phase 4 刪除 4 個 dead code event class 和 1 個 dead private method。

**Tech Stack:** Java 25 Virtual Threads, Spring Boot 4.0, Spring Modulith 2.0, `@EventListener`（非 `@ApplicationModuleListener`），Spring AI Community AgentClient

---

## 背景知識（Developer Context）

### 現有 Agent Dispatch 流程（GrimoTuiRunner.processInput()）
```
使用者輸入 → processInput(text)
  → tier routing（TierKeywordDetector + TierRouter）
  → agentModelRegistry.get(agentId)
  → tierOptionsFactory.build(agentId, model)
  → mcpCatalogBuilder.getCatalog() + getServerNames()
  → Thread.startVirtualThread(() -> AgentClient.run())
    → contentView.appendAiReply / appendError
    → statusView.setStatusText(originalStatusText)
    → eventLoop.setDirty()
```

### Phase 2 目標架構
```
GrimoTuiRunner.processInput(text)
  → agentDispatcher.dispatch(text)  ← 同步回來，TUI 不阻塞

AgentDispatcher.dispatch(text)（在 virtual thread 內呼叫）
  → tier routing
  → AgentClient.run()
  → publishEvent(AgentCompletedEvent) or publishEvent(AgentFailedEvent)

GrimoTuiRunner:
  @EventListener on(AgentCompletedEvent) → contentView.appendAiReply + statusView.restore
  @EventListener on(AgentFailedEvent) → contentView.appendError + statusView.restore
```

### 重要：agentRunning flag 的責任歸屬
`agentRunning` / `agentThread` 屬於 TUI 狀態（Ctrl+C 取消），必須留在 `GrimoTuiRunner`。
`AgentDispatcher.dispatch()` 由 TUI 在 virtual thread 中呼叫（同 Phase 1 的做法），Dispatcher 只管 agent 邏輯，不管 TUI 狀態。

---

## File Map

### Task 1（Phase 2）新增／修改
| Action | File | 說明 |
|--------|------|------|
| Create | `src/main/java/io/github/samzhu/grimo/shared/event/AgentCompletedEvent.java` | Agent 成功完成事件 |
| Create | `src/main/java/io/github/samzhu/grimo/shared/event/AgentFailedEvent.java` | Agent 失敗事件 |
| Create | `src/main/java/io/github/samzhu/grimo/agent/AgentDispatcher.java` | 主對話 agent dispatch 邏輯 |
| Modify | `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` | 刪除 7 deps，加 AgentDispatcher，改 event listener |

### Task 2（Phase 4）刪除 Dead Code
| Action | File | 說明 |
|--------|------|------|
| Delete | `src/main/java/io/github/samzhu/grimo/shared/event/IncomingMessageEvent.java` | 未使用 |
| Delete | `src/main/java/io/github/samzhu/grimo/shared/event/TaskCreateRequestEvent.java` | 未使用 |
| Delete | `src/main/java/io/github/samzhu/grimo/shared/event/ScheduleTaskEvent.java` | 未使用 |
| Delete | `src/main/java/io/github/samzhu/grimo/shared/event/TaskCompletedEvent.java` | 未使用 |
| Modify | `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` | 刪除 `displayDiffSummary()` dead method |

---

## Task 1: Phase 2 — Extract AgentDispatcher

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/AgentCompletedEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/AgentFailedEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/agent/AgentDispatcher.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`

### Step 1.1: 建立 AgentCompletedEvent

- [ ] 新增 `src/main/java/io/github/samzhu/grimo/shared/event/AgentCompletedEvent.java`

```java
package io.github.samzhu.grimo.shared.event;

/**
 * 主對話 agent 成功完成。
 * AgentDispatcher 在 virtual thread 中 publish，
 * GrimoTuiRunner listener 在 UI 顯示回覆並恢復 status bar。
 */
public record AgentCompletedEvent(
        String result,
        long durationMs
) {}
```

### Step 1.2: 建立 AgentFailedEvent

- [ ] 新增 `src/main/java/io/github/samzhu/grimo/shared/event/AgentFailedEvent.java`

```java
package io.github.samzhu.grimo.shared.event;

/**
 * 主對話 agent 失敗（例外或 cancelled）。
 * AgentDispatcher publish → GrimoTuiRunner listener 顯示錯誤並恢復 status bar。
 */
public record AgentFailedEvent(
        String errorMessage
) {}
```

### Step 1.3: 建立 AgentDispatcher

- [ ] 新增 `src/main/java/io/github/samzhu/grimo/agent/AgentDispatcher.java`

```java
package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.tier.TierKeywordDetector;
import io.github.samzhu.grimo.agent.tier.TierOptionsFactory;
import io.github.samzhu.grimo.agent.tier.TierRouter;
import io.github.samzhu.grimo.agent.tier.Tier;
import io.github.samzhu.grimo.mcp.McpCatalogBuilder;
import io.github.samzhu.grimo.shared.event.AgentCompletedEvent;
import io.github.samzhu.grimo.shared.event.AgentFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agents.client.AgentClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 主對話（Plan Mode）agent dispatch 邏輯。
 *
 * 設計說明：
 * - GrimoTuiRunner 在 virtual thread 呼叫 dispatch()，不阻塞 TUI 事件迴圈
 * - dispatch() 完成後 publish AgentCompletedEvent 或 AgentFailedEvent
 * - TUI 監聽事件更新 UI（Command → Event → TUI 解耦）
 * - 主對話 Plan Mode：直接在 CWD 工作，不建 worktree（worktree 由 Dev Mode / /dev 處理）
 * - SDK bug 備注：有 MCP 時 ClaudeAgentOptions 被覆蓋，disallowedTools 丟失（已知問題）
 *
 * @see DevModeRunner dev mode（isolated worktree）的對應實作
 */
@Component
public class AgentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AgentDispatcher.class);

    private final AgentModelRegistry agentModelRegistry;
    private final TierRouter tierRouter;
    private final TierKeywordDetector tierKeywordDetector;
    private final TierOptionsFactory tierOptionsFactory;
    private final McpCatalogBuilder mcpCatalogBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final AtomicReference<Tier> sessionTier;

    public AgentDispatcher(AgentModelRegistry agentModelRegistry,
                           TierRouter tierRouter,
                           TierKeywordDetector tierKeywordDetector,
                           TierOptionsFactory tierOptionsFactory,
                           McpCatalogBuilder mcpCatalogBuilder,
                           ApplicationEventPublisher eventPublisher,
                           AtomicReference<Tier> sessionTier) {
        this.agentModelRegistry = agentModelRegistry;
        this.tierRouter = tierRouter;
        this.tierKeywordDetector = tierKeywordDetector;
        this.tierOptionsFactory = tierOptionsFactory;
        this.mcpCatalogBuilder = mcpCatalogBuilder;
        this.eventPublisher = eventPublisher;
        this.sessionTier = sessionTier;
    }

    /**
     * 執行主對話 agent dispatch（在 virtual thread 中呼叫，不阻塞 TUI）。
     *
     * @param text 使用者輸入
     * @param projectDir CWD（Plan Mode 直接使用）
     */
    public void dispatch(String text, Path projectDir) {
        long startTime = System.currentTimeMillis();
        try {
            // Tier routing：決定 agent + model
            var keywordTier = tierKeywordDetector.detect(text).orElse(null);
            var tierCtx = TierRouter.Context.builder()
                    .keywordTier(keywordTier)
                    .sessionTier(sessionTier.get())
                    .build();
            var tierSelection = tierRouter.resolve(tierCtx);

            var agentModel = agentModelRegistry.get(tierSelection.agentId());
            if (agentModel == null) {
                throw new IllegalStateException("Agent not found: " + tierSelection.agentId());
            }

            var tierOptions = tierOptionsFactory.build(
                    tierSelection.agentId(), tierSelection.model());

            var mcpServers = mcpCatalogBuilder.getServerNames();
            log.info("Dispatching agent: tier={} agent={} model={} mcp={}",
                    tierSelection.tier(), tierSelection.agentId(), tierSelection.model(),
                    mcpServers.isEmpty() ? "none" : String.join(", ", mcpServers));

            // AgentClient（Plan Mode：CWD，不建 worktree）
            var client = AgentClient.builder(agentModel)
                    .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
                    .defaultMcpServers(mcpServers)
                    .defaultWorkingDirectory(projectDir)
                    .build();
            var response = client.run(text, tierOptions);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Agent dispatch completed: success={} duration={}ms resultLen={}",
                    response.isSuccessful(), duration,
                    response.getResult() != null ? response.getResult().length() : 0);

            if (response.isSuccessful()) {
                eventPublisher.publishEvent(new AgentCompletedEvent(
                        response.getResult(), duration));
            } else {
                log.warn("Agent dispatch unsuccessful: {}", response.getResult());
                eventPublisher.publishEvent(new AgentFailedEvent(response.getResult()));
            }

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Agent dispatch failed: {}ms error={}", duration, e.getMessage(), e);
            eventPublisher.publishEvent(new AgentFailedEvent(formatError(e)));
        }
    }

    /**
     * 將例外轉為使用者友善的錯誤訊息。
     */
    private String formatError(Exception e) {
        String name = e.getClass().getSimpleName();
        if (name.contains("NotFoundException")) {
            return "\u26a0 CLI not found. Install the agent CLI and try again.";
        } else if (name.contains("AuthenticationException")) {
            return "\u26a0 Authentication failed. Run the agent's login command.";
        } else if (name.contains("TimeoutException")) {
            return "\u26a0 Agent timed out. Try a simpler goal.";
        }
        return "\u26a0 Agent error: " + e.getMessage();
    }
}
```

### Step 1.4: 修改 GrimoTuiRunner

目標：
1. 建構子刪除 7 個 deps（`AgentModelRegistry`, `TierRouter`, `TierKeywordDetector`, `TierOptionsFactory`, `McpCatalogBuilder`, `WorktreeProvisioner`, `GitHelper`）
2. 加入 `AgentDispatcher` dep
3. `processInput()` 中 AI 對話分支改為呼叫 `agentDispatcher.dispatch(text, projectDir)`
4. 加入 `@EventListener on(AgentCompletedEvent)` 和 `on(AgentFailedEvent)`
5. 刪除 `formatAgentError()` method（移至 AgentDispatcher）

- [ ] 修改建構子 — 刪除 7 個 params，加 `AgentDispatcher agentDispatcher`，刪除 `currentTierSelection` field

**Field 刪除清單：**
```java
// 以下 fields 完整刪除：
private final AgentModelRegistry agentModelRegistry;
private final TierRouter tierRouter;
private final TierKeywordDetector tierKeywordDetector;
private final TierOptionsFactory tierOptionsFactory;
private final McpCatalogBuilder mcpCatalogBuilder;
private final WorktreeProvisioner worktreeProvisioner;
private final GitHelper gitHelper;
private volatile TierSelection currentTierSelection;  // dead state，Phase 2 後不需要
```

建構子 import 需要加：
```java
import io.github.samzhu.grimo.agent.AgentDispatcher;
import io.github.samzhu.grimo.shared.event.AgentCompletedEvent;
import io.github.samzhu.grimo.shared.event.AgentFailedEvent;
```

移除這些 import：
```java
// 刪除：
import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.tier.TierKeywordDetector;
import io.github.samzhu.grimo.agent.tier.TierOptionsFactory;
import io.github.samzhu.grimo.agent.tier.TierRouter;
import io.github.samzhu.grimo.agent.tier.TierSelection;
import io.github.samzhu.grimo.mcp.McpCatalogBuilder;
import io.github.samzhu.grimo.shared.sandbox.WorktreeProvisioner;
import io.github.samzhu.grimo.shared.sandbox.GitHelper;
import io.github.samzhu.grimo.shared.sandbox.WorktreeInfo;
```

新建構子（保留順序，刪除 7 個，加 AgentDispatcher）：
```java
// fields 刪除：
// private final AgentModelRegistry agentModelRegistry;
// private final TierRouter tierRouter;
// private final TierKeywordDetector tierKeywordDetector;
// private final TierOptionsFactory tierOptionsFactory;
// private final McpCatalogBuilder mcpCatalogBuilder;
// private final WorktreeProvisioner worktreeProvisioner;
// private final GitHelper gitHelper;
// private volatile TierSelection currentTierSelection;  ← 也刪除（dead state）

// field 新增：
private final AgentDispatcher agentDispatcher;
```

- [ ] 修改 `processInput()` 中的 AI 對話分支

**整個 `else` 區塊完整替換**（原本包含 tier routing、agentRunning、virtual thread、AgentClient 的約 85 行，全部換成以下）：

```java
} else {
    // AI 對話 — 透過 AgentDispatcher 呼叫 CLI agent
    // 設計說明：tier routing、AgentClient.run() 移至 AgentDispatcher，
    //   TUI 只負責狀態管理（agentRunning flag）和 UI 回饋（thinking... + event listener）
    if (agentRunning) {
        contentView.appendError("Agent is still running. Wait or press Ctrl+C to cancel.");
        return;
    }
    agentRunning = true;
    contentView.appendLine(new org.jline.utils.AttributedString("\u23f3 thinking...",
            org.jline.utils.AttributedStyle.DEFAULT.foreground(245)));
    if (eventLoop != null) eventLoop.setDirty();

    var projectDir = java.nio.file.Path.of(System.getProperty("user.dir"));
    agentThread = Thread.startVirtualThread(() -> {
        contentView.removeLastLine();
        agentDispatcher.dispatch(text, projectDir);
        // UI 更新由 @EventListener on(AgentCompletedEvent/AgentFailedEvent) 同步處理，
        // dispatch() 返回時 listener 已執行完畢
        agentRunning = false;
        agentThread = null;
        screen.requestFullRedraw();
        if (eventLoop != null) eventLoop.setDirty();
    });
}
```

**注意：** 原 `processInput()` 中的 tier routing 相關程式碼（`TierKeywordDetector.detect()`、`TierRouter.resolve()`、`currentTierSelection = tierSelection`）已移至 `AgentDispatcher.dispatch()` 內部，不需保留在 TuiRunner 中。`currentTierSelection = null` 亦因 field 刪除而一並移除。

- [ ] 加入 `AgentCompletedEvent` 和 `AgentFailedEvent` listener

```java
/**
 * 設計說明：AgentDispatcher publish → TUI 自動更新（Command → Event → TUI 解耦）
 * null guard：TUI 元件在 run() 後才初始化，需防禦性檢查（對齊其他 @EventListener 做法）
 */
@EventListener
void on(AgentCompletedEvent event) {
    if (contentView == null || statusView == null) return;
    if (event.result() != null && !event.result().isBlank()) {
        contentView.appendAiReply(event.result());
    }
    sessionWriter.writeAssistantMessage(event.result());
    statusView.setStatusText(originalStatusText);
    if (eventLoop != null) eventLoop.setDirty();
}

@EventListener
void on(AgentFailedEvent event) {
    if (contentView == null || statusView == null) return;
    contentView.appendError(event.errorMessage());
    // 設計說明：原本 AgentClient 非成功回應（非例外）也會寫入 session；保持同等行為
    sessionWriter.writeAssistantMessage(event.errorMessage());
    statusView.setStatusText(originalStatusText);
    if (eventLoop != null) eventLoop.setDirty();
}
```

- [ ] 刪除 `formatAgentError()` private method（已移至 AgentDispatcher）

### Step 1.5: Build 驗證

- [ ] 執行 build，確認無編譯錯誤：
```bash
./gradlew compileJava
```
Expected：`BUILD SUCCESSFUL`

### Step 1.6: 快速功能測試

- [ ] 啟動 app 確認基本流程可運作（可用 `./gradlew bootRun`，輸入一段對話確認 agent 回覆正常顯示）
- [ ] Ctrl+C 取消中的 agent 仍然有效（`agentThread.interrupt()`）

### Step 1.7: Commit

- [ ] Commit Phase 2：
```bash
git add src/main/java/io/github/samzhu/grimo/shared/event/AgentCompletedEvent.java
git add src/main/java/io/github/samzhu/grimo/shared/event/AgentFailedEvent.java
git add src/main/java/io/github/samzhu/grimo/agent/AgentDispatcher.java
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "refactor: extract AgentDispatcher, decouple agent dispatch from TUI (Phase 2)"
```

---

## Task 2: Phase 4 — 刪除 Dead Code

**Files:**
- Delete: `src/main/java/io/github/samzhu/grimo/shared/event/IncomingMessageEvent.java`
- Delete: `src/main/java/io/github/samzhu/grimo/shared/event/TaskCreateRequestEvent.java`
- Delete: `src/main/java/io/github/samzhu/grimo/shared/event/ScheduleTaskEvent.java`
- Delete: `src/main/java/io/github/samzhu/grimo/shared/event/TaskCompletedEvent.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` — 刪除 `displayDiffSummary()`

**先決條件：** Task 1 完成

### Step 2.1: 確認 dead code 真的沒被使用

- [ ] 搜尋確認 4 個 events 沒有被 import 或 publish：
```bash
grep -r "IncomingMessageEvent\|TaskCreateRequestEvent\|ScheduleTaskEvent\|TaskCompletedEvent" \
  src/main/java --include="*.java" -l
```
Expected：只列出 4 個 event class 自身

- [ ] 確認 `displayDiffSummary` 沒有被呼叫：
```bash
grep -r "displayDiffSummary" src/main/java --include="*.java"
```
Expected：只有 method 定義那一行（GrimoTuiRunner:925）

### Step 2.2: 刪除 4 個 Dead Event Classes

- [ ] 刪除 `IncomingMessageEvent.java`：
```bash
rm src/main/java/io/github/samzhu/grimo/shared/event/IncomingMessageEvent.java
```

- [ ] 刪除 `TaskCreateRequestEvent.java`：
```bash
rm src/main/java/io/github/samzhu/grimo/shared/event/TaskCreateRequestEvent.java
```

- [ ] 刪除 `ScheduleTaskEvent.java`：
```bash
rm src/main/java/io/github/samzhu/grimo/shared/event/ScheduleTaskEvent.java
```

- [ ] 刪除 `TaskCompletedEvent.java`：
```bash
rm src/main/java/io/github/samzhu/grimo/shared/event/TaskCompletedEvent.java
```

### Step 2.3: 刪除 GrimoTuiRunner 中的 displayDiffSummary()

- [ ] 刪除 `GrimoTuiRunner.java` 中約 925–975 行的 `displayDiffSummary()` private method（包含 Javadoc）

確認刪除前後 `GrimoTuiRunner.java` 仍有 `WorktreeInfo` import — 如果 Task 1 已刪除 `WorktreeProvisioner`/`GitHelper` import，`WorktreeInfo` 也應一並移除（若已無其他使用）。執行 grep 確認：
```bash
grep "WorktreeInfo\|WorktreeProvisioner\|GitHelper" \
  src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
```
若有殘留 import → 一併刪除。

### Step 2.4: Build 驗證

- [ ] 確認無編譯錯誤：
```bash
./gradlew compileJava
```
Expected：`BUILD SUCCESSFUL`

### Step 2.5: Commit

- [ ] Commit Phase 4：
```bash
git add -u  # 刪除 + 修改的檔案
git commit -m "refactor: delete dead event classes and unused displayDiffSummary (Phase 4)"
```

---

## 驗收標準

完成後，`GrimoTuiRunner` 建構子應從 **23 個**參數降至 **17 個**：
- 刪除（7）：`AgentModelRegistry`、`TierRouter`、`TierKeywordDetector`、`TierOptionsFactory`、`McpCatalogBuilder`、`WorktreeProvisioner`、`GitHelper`
- 新增（1）：`AgentDispatcher`

（原本 spec 說 21 個，實際數過是 23 個；刪 7 加 1 = 17）

`shared/event/` 包只剩 active events：
- `AgentSwitchedEvent`、`McpCatalogChangedEvent`（Phase 1 已建）
- `AgentCompletedEvent`、`AgentFailedEvent`（本次新建）
- `DevModeEnteredEvent`、`DevModeCompletedEvent`（Dev Mode 用）
- `OutgoingMessageEvent`、`Attachment`（channel 用）
- `package-info.java`

---

## 注意事項

### sessionWriter.writeAssistantMessage() 呼叫時機
原本在 virtual thread 中，dispatch 結束後 call。重構後移到 `on(AgentCompletedEvent)` listener。注意 listener 在 Spring 的 caller thread 執行（即 virtual thread），不是 main event loop thread，不會有 thread 安全問題（`contentView.appendAiReply()` 本身已處理 concurrent append）。

### agentRunning / agentThread cleanup
`agentDispatcher.dispatch()` 是同步的（在 virtual thread 中阻塞直到 agent 完成）。Spring `publishEvent()` 也是同步的：listener 在 `publishEvent()` 呼叫返回前就已執行完畢。

執行順序：
1. `agentDispatcher.dispatch(text, projectDir)` 執行中（阻塞）
2. dispatch 內部 `publishEvent(AgentCompletedEvent)` → listener `on(AgentCompletedEvent)` 在同一 thread 同步執行 → `contentView.appendAiReply()`, `statusView.restore()`
3. `publishEvent()` 返回 → `dispatch()` 返回
4. virtual thread 繼續：`agentRunning = false; agentThread = null;`

結果：UI 更新（步驟 2）在 agentRunning 清零（步驟 4）之前完成，UI 不會閃爍。
