# Event-Driven TUI Phase 1: Event 基礎 + Status Bar 解耦

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Commands publish events instead of directly refreshing UI. `@ApplicationModuleListener` bug fixed. Status bar auto-updates via event-driven flow.

**Architecture:** `AgentCommands.use()` publishes `AgentSwitchedEvent`，`GrimoTuiRunner` 的 `@EventListener` 接收後刷新 status bar。消除 command → TUI 直接耦合。同時修正 `@ApplicationModuleListener`（CLI 無 DB 不 fire）→ `@EventListener`。

**Tech Stack:** Java 25, Spring Framework 7.0.6 (`ApplicationEventPublisher`, `@EventListener`), Spring Modulith 2.0.2

**Spec:** `docs/superpowers/specs/2026-03-31-event-driven-tui-refactor.md` Phase 1

**SDK Verification:**
```java
// Spring Framework 7.0.6 — 已驗證存在
ApplicationEventPublisher.publishEvent(Object event)  // 發布任意物件作為 event
@EventListener                                         // 同步，不需 DB/transaction
// Spring Modulith 2.0.2 — @ApplicationModuleListener 需要 transaction，CLI app 不適用
```

**CLAUDE.md 架構須知：**
- CLI app 用 `@EventListener`，不用 `@ApplicationModuleListener`
- Command → Event → TUI 解耦模式

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/io/github/samzhu/grimo/shared/event/AgentSwitchedEvent.java` | Agent 切換事件 |
| Create | `src/main/java/io/github/samzhu/grimo/shared/event/McpCatalogChangedEvent.java` | MCP catalog 變更事件 |
| Modify | `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java` | inject publisher, publish event |
| Modify | `src/main/java/io/github/samzhu/grimo/mcp/McpCommands.java` | publish event |
| Modify | `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` | 加 @EventListener, 移除手動 refreshStatusBar 呼叫 |
| Modify | `src/main/java/io/github/samzhu/grimo/channel/ChannelEventListener.java` | @ApplicationModuleListener → @EventListener |
| Modify | `docs/glossary.md` | 更新 event 術語 |

---

### Task 1: Create Domain Events

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/AgentSwitchedEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/McpCatalogChangedEvent.java`

- [ ] **Step 1: Create AgentSwitchedEvent**

```java
package io.github.samzhu.grimo.shared.event;

/**
 * Agent 或 model 切換時發布。
 * 由 AgentCommands.use() 在 config 寫入後 publish。
 * TUI 層 listen 後自動刷新 status bar。
 *
 * 設計說明：
 * - 遵循 CLAUDE.md 架構須知：Command → Event → TUI
 * - 使用 Spring @EventListener（不是 @ApplicationModuleListener，CLI 無 DB）
 */
public record AgentSwitchedEvent(String agentId, String model) {}
```

- [ ] **Step 2: Create McpCatalogChangedEvent**

```java
package io.github.samzhu.grimo.shared.event;

import java.util.List;

/**
 * MCP server 新增或移除時發布。
 * 由 McpCommands 在 catalog rebuild 後 publish。
 * TUI 層 listen 後自動刷新 status bar 的 mcp count。
 */
public record McpCatalogChangedEvent(List<String> serverNames) {}
```

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/event/AgentSwitchedEvent.java \
       src/main/java/io/github/samzhu/grimo/shared/event/McpCatalogChangedEvent.java
git commit -m "feat(event): add AgentSwitchedEvent + McpCatalogChangedEvent domain events"
```

---

### Task 2: AgentCommands publish event

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java`

- [ ] **Step 1: Read AgentCommands.java**

Read the file to see current imports and constructor.

- [ ] **Step 2: Add ApplicationEventPublisher**

Add import:
```java
import io.github.samzhu.grimo.shared.event.AgentSwitchedEvent;
import org.springframework.context.ApplicationEventPublisher;
```

Add field:
```java
private final ApplicationEventPublisher eventPublisher;
```

Update constructor to accept publisher:
```java
public AgentCommands(AgentModelRegistry registry, GrimoConfig config,
                     ApplicationEventPublisher eventPublisher) {
    this.registry = registry;
    this.config = config;
    this.eventPublisher = eventPublisher;
}
```

- [ ] **Step 3: Publish event in use() method**

After `config.setDefaultModel(model);` (line ~129), add:
```java
eventPublisher.publishEvent(new AgentSwitchedEvent(agentId, model));
```

- [ ] **Step 4: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Update AgentCommandsTest**

Read `src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java`. The constructor call needs updating since we added `eventPublisher`.

In `setUp()`, add a mock publisher:
```java
var eventPublisher = mock(ApplicationEventPublisher.class);
commands = new AgentCommands(registry, config, eventPublisher);
```

Add import:
```java
import org.springframework.context.ApplicationEventPublisher;
```

- [ ] **Step 6: Run tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.AgentCommandsTest" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java \
       src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java
git commit -m "feat(event): AgentCommands publishes AgentSwitchedEvent on /agent-use"
```

---

### Task 3: McpCommands publish event

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/mcp/McpCommands.java`

- [ ] **Step 1: Read McpCommands.java**

Read the file to find where `config.setMcpServer()` and `config.removeMcpServer()` are called.

- [ ] **Step 2: Add ApplicationEventPublisher**

Add import:
```java
import io.github.samzhu.grimo.shared.event.McpCatalogChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
```

Add field and update constructor to accept `ApplicationEventPublisher eventPublisher`.

- [ ] **Step 3: Publish event after MCP add/remove**

After `catalogBuilder.rebuild()` in the add method, add:
```java
eventPublisher.publishEvent(new McpCatalogChangedEvent(catalogBuilder.getServerNames()));
```

Same after the rebuild in the remove method.

- [ ] **Step 4: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/mcp/McpCommands.java
git commit -m "feat(event): McpCommands publishes McpCatalogChangedEvent on add/remove"
```

---

### Task 4: GrimoTuiRunner listens for events

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`

- [ ] **Step 1: Add @EventListener methods**

Add imports:
```java
import io.github.samzhu.grimo.shared.event.AgentSwitchedEvent;
import io.github.samzhu.grimo.shared.event.McpCatalogChangedEvent;
import org.springframework.context.event.EventListener;
```

Add listener methods (before the `refreshStatusBar` method):

```java
/**
 * 設計說明：Command → Event → TUI 解耦
 * AgentCommands.use() publish event → 這裡自動刷新 status bar
 * 不再需要 command 執行後手動呼叫 refreshStatusBar()
 */
@EventListener
void on(AgentSwitchedEvent event) {
    if (statusView == null) return; // TUI 尚未初始化
    refreshStatusBar();
    if (eventLoop != null) eventLoop.setDirty();
}

@EventListener
void on(McpCatalogChangedEvent event) {
    if (statusView == null) return;
    refreshStatusBar();
    if (eventLoop != null) eventLoop.setDirty();
}
```

- [ ] **Step 2: Remove manual refreshStatusBar() call after command execution**

Find the line (around 515-516) in `processInput()`:
```java
// 設計說明：command 執行後刷新 status bar
// /agent-use 切換 agent/model 後，status bar 要即時反映
refreshStatusBar();
```

Delete these 3 lines (the 2 comments + the `refreshStatusBar()` call). The event listener now handles this automatically.

- [ ] **Step 3: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "feat(event): GrimoTuiRunner listens AgentSwitchedEvent/McpCatalogChangedEvent, remove manual refresh"
```

---

### Task 5: Fix ChannelEventListener @ApplicationModuleListener

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/channel/ChannelEventListener.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java` (javadoc only)

- [ ] **Step 1: Read ChannelEventListener.java**

Read the file to find the `@ApplicationModuleListener` annotation.

- [ ] **Step 2: Replace annotation**

Change import from:
```java
import org.springframework.modulith.events.ApplicationModuleListener;
```
To:
```java
import org.springframework.context.event.EventListener;
```

Change annotation on the listener method from:
```java
@ApplicationModuleListener
```
To:
```java
@EventListener
```

- [ ] **Step 3: Update GrimoStartupRunner javadoc**

Find the comment (around line 120) that mentions `@ApplicationModuleListener` and update to `@EventListener`.

- [ ] **Step 4: Compile check**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/channel/ChannelEventListener.java \
       src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java
git commit -m "fix(event): ChannelEventListener @ApplicationModuleListener → @EventListener (CLI has no DB)"
```

---

### Task 6: Glossary + spec status update

**Files:**
- Modify: `docs/glossary.md`
- Modify: `docs/superpowers/specs/2026-03-31-event-driven-tui-refactor.md`

- [ ] **Step 1: Update glossary event terms**

Read `docs/glossary.md` and update/add event-related terms. If placeholder entries exist for `AgentChangedEvent`, rename to `AgentSwitchedEvent`. Add:

```markdown
| **AgentSwitchedEvent** | Agent Switched Event | `/agent-use` 執行後由 AgentCommands 發布。TUI 的 @EventListener 接收後自動刷新 status bar。 |
| **McpCatalogChangedEvent** | MCP Catalog Changed Event | MCP server 新增/移除後發布。TUI 自動更新 mcp count。 |
```

- [ ] **Step 2: Mark spec Phase 1 as Done**

In `docs/superpowers/specs/2026-03-31-event-driven-tui-refactor.md`, change Phase 1 section header to include `✅ Done`.

- [ ] **Step 3: Commit**

```bash
git add docs/glossary.md docs/superpowers/specs/2026-03-31-event-driven-tui-refactor.md
git commit -m "docs: update glossary with event terms + mark Phase 1 as Done"
```

---

### Task 7: Manual Verification

- [ ] **Step 1: Build and run**

```bash
./run.sh
```

- [ ] **Step 2: Test event-driven status bar update**

```
/agent-use gemini
```

Expected: Status bar immediately changes to `gemini · gemini-2.5-pro │ ...`
Log should show: `AgentSwitchedEvent` published (if debug logging)

```
/agent-use claude opus
```

Expected: Status bar immediately changes to `claude · claude-opus-4-6 │ ...`

- [ ] **Step 3: Verify /agent-list still works**

```
/agent-list
```

Expected: Aligned table with `>` indicator on current agent
