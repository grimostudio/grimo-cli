# 動態指令 + SkillExecutor + @mention Implementation Plan (Plan B)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 動態指令系統 — Agent 偵測後自動註冊 `/claude`、Skill 安裝後自動註冊 `/brainstorming`、`@mention` 語法支援、SkillExecutor 編排。

**Architecture:** 透過 domain events（AgentDetectedEvent、SkillInstalledEvent）觸發 CommandDispatcher 動態註冊。SkillExecutor 讀 SKILL.md metadata 決定 tier routing + execution mode（inline → ChatDispatcher / isolated → DevModeRunner）。InputHandler 辨識 agent source 的指令後走 ChatDispatcher.dispatchTo()。

**Tech Stack:** Java 25, Spring Boot 4.0.x, Spring Modulith 2.0.x, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-04-02-command-dispatcher-design.md`（Section 9-10）

**前置：** Plan A 必須完成（InputPort + CommandDispatcher + InputHandler + BuiltinCommandRegistrar）

---

### Task 1: AgentDetectedEvent + 動態 Agent 指令註冊

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/AgentDetectedEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/DynamicCommandRegistrar.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`（startupInitRunner 發 event）
- Create: `src/test/java/io/github/samzhu/grimo/DynamicCommandRegistrarTest.java`

- [ ] **Step 1: 建立 AgentDetectedEvent**

```java
package io.github.samzhu.grimo.shared.event;

/**
 * Agent 偵測到後發布。
 * 訂閱者：DynamicCommandRegistrar（註冊 /agentId 快捷指令）、TuiEventBridge（更新 agent count）。
 */
public record AgentDetectedEvent(String agentId, boolean available) {}
```

- [ ] **Step 2: 在 startupInitRunner 中發布 AgentDetectedEvent**

Agent 偵測完成後，對每個偵測到的 agent 發布 event：

```java
// GrimoStartupRunner.startupInitRunner 的 Step 2 後面
agentModelRegistry.listAll().forEach((agentId, model) -> {
    eventPublisher.publishEvent(new AgentDetectedEvent(agentId, model.isAvailable()));
});
```

- [ ] **Step 3: 寫 DynamicCommandRegistrarTest（TDD）**

測試案例：
- `agentDetectedRegistersSlashCommand` — AgentDetectedEvent("claude", true) → dispatcher.has("claude") == true
- `agentDetectedRegistersAtMention` — → dispatcher.has("@claude") == true
- `unavailableAgentNotRegistered` — available=false → 不註冊
- `agentCommandDescriptionCorrect` — description 包含 agent name

- [ ] **Step 4: 建立 DynamicCommandRegistrar**

```java
package io.github.samzhu.grimo;

/**
 * 動態指令註冊：監聽 domain events，自動在 CommandDispatcher 註冊/解除指令。
 *
 * 設計說明：
 * - AgentDetectedEvent → 註冊 /agentId 和 @agentId
 * - SkillInstalledEvent → 註冊 /skillName（Task 2）
 * - 遵循六角架構：Event = 狀態變更通知 → 訂閱者獨立反應
 */
@Component
public class DynamicCommandRegistrar {

    private final CommandDispatcher dispatcher;
    private final ChatDispatcher chatDispatcher;

    @EventListener
    public void on(AgentDetectedEvent event) {
        if (!event.available()) return;
        String agentId = event.agentId();

        // /claude → direct chat
        dispatcher.register(agentId,
            "Chat with " + agentId, "agent",
            args -> null);  // agent 指令是非同步 — InputHandler 辨識 source="agent" 後走 chatDispatcher

        // @claude → mention
        dispatcher.register("@" + agentId,
            "Mention " + agentId, "agent",
            args -> null);
    }
}
```

> 設計說明：Agent handler 回傳 null — InputHandler 辨識到 `entry.source() == "agent"` 後直接呼叫 `chatDispatcher.dispatchTo(agentId, args, callback)`，不走 commandDispatcher.execute()。這樣 callback 能正確回傳非同步結果。

- [ ] **Step 5: 修改 InputHandler — 辨識 agent source**

Plan A 的 InputHandler 需要加入 agent source 判斷：

```java
if (text.startsWith("/") || text.startsWith("@")) {
    String name = extractCommandName(text);
    String args = extractArgs(text);

    var entry = commandDispatcher.getEntry(name);
    if (entry != null) {
        if ("agent".equals(entry.source())) {
            // Agent 快捷 → 非同步 ChatDispatcher
            String agentId = name.startsWith("@") ? name.substring(1) : name;
            chatDispatcher.dispatchTo(agentId, args, callback);
        } else {
            // builtin / skill → 同步
            String result = commandDispatcher.execute(name, args);
            if (result != null && !result.isEmpty()) {
                callback.onResponse(result);
            }
        }
    } else {
        chatDispatcher.dispatch(text, callback);
    }
}
```

如果 Plan A 已有此邏輯，確認無需修改。如果沒有，在此 task 加入。

- [ ] **Step 6: 執行測試**

Run: `./gradlew test --tests "io.github.samzhu.grimo.DynamicCommandRegistrarTest"`
Expected: 4 tests PASS

- [ ] **Step 7: Commit**

```
feat: dynamic agent command registration via AgentDetectedEvent
```

---

### Task 2: SkillInstalledEvent + 動態 Skill 指令註冊

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/SkillInstalledEvent.java`
- Modify: `src/main/java/io/github/samzhu/grimo/DynamicCommandRegistrar.java`（加 skill listener）
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`（skill 載入後發 event）
- Modify: `src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java`（install 後發 event）

- [ ] **Step 1: 建立 SkillInstalledEvent**

```java
package io.github.samzhu.grimo.shared.event;

public record SkillInstalledEvent(String skillName, String description) {}
```

- [ ] **Step 2: startupInitRunner 中 skill 載入後發布 event**

```java
skills.forEach(skill -> {
    skillRegistry.register(skill);
    eventPublisher.publishEvent(new SkillInstalledEvent(skill.name(), skill.description()));
});
```

- [ ] **Step 3: SkillCommands.install() 後也發布 event**

確認 `SkillCommands.install()` 方法在安裝成功後 publish `SkillInstalledEvent`。

- [ ] **Step 4: DynamicCommandRegistrar 加 skill listener**

```java
@EventListener
public void on(SkillInstalledEvent event) {
    dispatcher.register(event.skillName(),
        event.description(), "skill",
        args -> skillExecutor.execute(event.skillName(), args));
}
```

> 此步驟依賴 Task 3 的 SkillExecutor。先註冊一個 placeholder handler（回傳 "SkillExecutor not yet implemented"），Task 3 完成後替換。

- [ ] **Step 5: 編譯驗證**

Run: `./gradlew compileJava`

- [ ] **Step 6: Commit**

```
feat: dynamic skill command registration via SkillInstalledEvent
```

---

### Task 3: SkillExecutor — Skill 指令執行

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/SkillExecutor.java`
- Create: `src/test/java/io/github/samzhu/grimo/SkillExecutorTest.java`

- [ ] **Step 1: 寫 SkillExecutorTest（TDD）**

測試案例（mock SkillRegistry、ChatDispatcher、DevModeRunner）：
- `inlineSkillDispatchesToChat` — execution=inline → chatDispatcher.doDispatch(fullGoal) 被呼叫
- `isolatedSkillDispatchesToDevMode` — execution=isolated → devModeRunner.run(fullGoal, projectDir) 被呼叫
- `defaultExecutionModeIsInline` — 無 grimo.execution metadata → 走 inline
- `unknownSkillReturnsError` — skillRegistry.get() = null → "Skill not found: xxx"
- `fullGoalIncludesSlashAndArgs` — fullGoal 格式正確："/brainstorming 設計登入頁面"

- [ ] **Step 2: 建立 SkillExecutor**

```java
package io.github.samzhu.grimo;

@Component
public class SkillExecutor {

    private final SkillRegistry skillRegistry;
    private final ChatDispatcher chatDispatcher;
    private final DevModeRunner devModeRunner;

    /**
     * Skill 指令執行：讀 metadata → execution mode → 委派。
     *
     * execution=isolated → DevModeRunner（worktree + 全開）
     * execution=inline（預設）→ ChatDispatcher（主對話 + 現有模式）
     *
     * 設計說明：
     * - Grimo 是 orchestrator：讀 metadata、選 tier、選 execution mode
     * - Agent 有對應的 SKILL.md，在被隔離的環境中全開執行
     * - fullGoal 包含 /skillName + args，agent 看到跟使用者輸入一樣的格式
     */
    public String execute(String skillName, String args) {
        var skill = skillRegistry.get(skillName);
        if (skill == null) return "Skill not found: " + skillName;

        String executionMode = "inline";
        var metadata = skill.metadata();
        if (metadata != null) {
            executionMode = metadata.getOrDefault("grimo.execution", "inline").toString();
        }

        String fullGoal = "/" + skillName + (args.isEmpty() ? "" : " " + args);

        if ("isolated".equals(executionMode)) {
            var projectDir = java.nio.file.Path.of(System.getProperty("user.dir"));
            devModeRunner.run(fullGoal, projectDir);
            return null;  // 非同步 — DevModeCompletedEvent 通知
        } else {
            return chatDispatcher.doDispatch(fullGoal);
        }
    }
}
```

**注意：** `chatDispatcher.doDispatch()` 需要是 public（同步版本），確認 ChatDispatcher 有此方法。如果沒有，使用現有的同步路徑。

- [ ] **Step 3: 更新 DynamicCommandRegistrar — skill handler 指向 SkillExecutor**

替換 Task 2 的 placeholder：

```java
@EventListener
public void on(SkillInstalledEvent event) {
    dispatcher.register(event.skillName(),
        event.description(), "skill",
        args -> skillExecutor.execute(event.skillName(), args));
}
```

加入 `SkillExecutor` 依賴注入。

- [ ] **Step 4: 執行測試**

Run: `./gradlew test --tests "io.github.samzhu.grimo.SkillExecutorTest"`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```
feat: add SkillExecutor — skill metadata → tier routing → execution mode dispatch
```

---

### Task 4: ChatDispatcher.dispatchTo() — 指定 agent 對話

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/ChatDispatcher.java`
- Create: `src/test/java/io/github/samzhu/grimo/ChatDispatcherTest.java`（或修改現有）

- [ ] **Step 1: 新增 dispatchTo() 方法**

```java
/**
 * 指定 agent 對話。不走 tier routing — 直接用指定的 agent。
 * 用於 /claude args、@claude args 等 agent 快捷指令。
 */
public void dispatchTo(String agentId, String text, InputPort.ResponseCallback callback) {
    Thread.ofVirtual().name("grimo-chat-" + agentId).start(() -> {
        try {
            var model = agentModelRegistry.get(agentId);
            if (model == null) {
                callback.onResponse("Agent not available: " + agentId);
                return;
            }
            // 不走 tier routing — 使用 agent 的預設 model
            var options = tierOptionsFactory.build(agentId,
                config.getAgentOption(agentId, "model"));
            var client = AgentClient.builder(model)
                .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
                .defaultMcpServers(mcpCatalogBuilder.getServerNames())
                .defaultWorkingDirectory(Path.of(System.getProperty("user.dir")))
                .build();
            var response = client.run(text, options);
            callback.onResponse(response.getResult());

            eventPublisher.publishEvent(
                new AgentCallCompletedEvent(text, response.getResult(), null));
        } catch (Exception e) {
            callback.onResponse("Error: " + e.getMessage());
        }
    });
}
```

**注意：** 確認 `AgentCallCompletedEvent` 存在。如果不存在（SP4 spec 提到但可能未建立），使用現有 event 或先跳過。

- [ ] **Step 2: 寫測試**

- `dispatchToCallsCallback` — mock AgentClient，驗證 callback 被呼叫
- `dispatchToUnknownAgentReturnsError` — agent not found → error callback

- [ ] **Step 3: 編譯 + 測試**

Run: `./gradlew build`

- [ ] **Step 4: Commit**

```
feat: add ChatDispatcher.dispatchTo() for agent shortcut commands
```

---

### Task 5: InputHandler 完善 agent source 路由

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/InputHandler.java`
- Modify: `src/test/java/io/github/samzhu/grimo/InputHandlerTest.java`

- [ ] **Step 1: 確認 InputHandler 路由邏輯涵蓋 agent source**

如果 Plan A 已實作 agent source 判斷（check `entry.source() == "agent"` → `chatDispatcher.dispatchTo()`），此 task 只需加測試。

如果尚未實作，按 spec Section 9 修正版加入：

```java
var entry = commandDispatcher.getEntry(name);
if (entry != null) {
    if ("agent".equals(entry.source())) {
        String agentId = name.startsWith("@") ? name.substring(1) : name;
        chatDispatcher.dispatchTo(agentId, args, callback);
    } else {
        String result = commandDispatcher.execute(name, args);
        if (result != null && !result.isEmpty()) {
            callback.onResponse(result);
        }
    }
}
```

- [ ] **Step 2: 新增測試**

- `agentSourceRoutesToDispatchTo` — /claude → chatDispatcher.dispatchTo("claude", args, callback)
- `atMentionRoutesToDispatchTo` — @gemini → chatDispatcher.dispatchTo("gemini", args, callback)
- `skillSourceRoutesToCommandExecute` — /brainstorming → commandDispatcher.execute("brainstorming", args)

- [ ] **Step 3: 執行測試**

Run: `./gradlew test --tests "io.github.samzhu.grimo.InputHandlerTest"`

- [ ] **Step 4: Commit**

```
feat: InputHandler routes agent commands to ChatDispatcher.dispatchTo()
```

---

### Task 6: SlashMenu 顯示動態指令

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/TuiAdapter.java`（或 SlashMenu 資料建構）

- [ ] **Step 1: SlashMenu 從 InputPort.listAvailableCommands() 取得完整指令清單**

確認 Plan A Task 5 已改為從 `inputPort.listAvailableCommands()` 取得。動態指令（agent/skill）在 CommandDispatcher 註冊後，自動出現在 SlashMenu。

如果 listAvailableCommands() 包含 `@claude` 等 mention 指令，SlashMenu 可能需要過濾：只顯示 `/` 開頭的（不顯示 `@` 開頭的）。

```java
var menuItems = inputPort.listAvailableCommands().stream()
    .filter(e -> !e.name().startsWith("@"))  // 過濾 @mention（不在 slash menu 顯示）
    .map(e -> new SlashMenu.MenuItem(e.name(), e.description()))
    .toList();
```

- [ ] **Step 2: 驗證動態指令出現在選單**

啟動後 `/` 觸發 slash menu → 應看到 builtin + agent（claude, gemini, codex）+ skill。

- [ ] **Step 3: Commit**

```
feat: SlashMenu shows dynamic agent and skill commands
```

---

### Task 7: Glossary + CLAUDE.md 更新

**Files:**
- Modify: `docs/glossary.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Glossary 新增術語**

| 名詞 | 英文 | 說明 |
|------|------|------|
| **SkillExecutor** | Skill Executor | Skill 指令執行。讀 SKILL.md metadata → execution mode 判斷：`isolated` → DevModeRunner（worktree + 全開）、`inline` → ChatDispatcher。Grimo 的 orchestrator 角色。 |
| **DynamicCommandRegistrar** | Dynamic Command Registrar | 監聽 domain events（AgentDetectedEvent、SkillInstalledEvent）自動在 CommandDispatcher 註冊/解除指令。 |
| **AgentDetectedEvent** | Agent Detected Event | Agent 偵測到後發布。DynamicCommandRegistrar 監聽，註冊 `/agentId` 和 `@agentId` 快捷指令。 |
| **SkillInstalledEvent** | Skill Installed Event | Skill 安裝/載入後發布。DynamicCommandRegistrar 監聽，註冊 `/skillName` 指令到 SkillExecutor。 |

- [ ] **Step 2: CLAUDE.md 更新**

- 新增動態指令說明
- 新增 SkillExecutor 到 Architecture 表格
- 新增 @mention 語法說明

- [ ] **Step 3: Commit**

```
docs: update glossary and CLAUDE.md for dynamic commands + SkillExecutor
```

---

### Task 8: 全量驗證

- [ ] **Step 1: Full build**

Run: `./gradlew build`

- [ ] **Step 2: 驗證動態指令**

```bash
# 新檔案存在
ls src/main/java/io/github/samzhu/grimo/SkillExecutor.java
ls src/main/java/io/github/samzhu/grimo/DynamicCommandRegistrar.java
ls src/main/java/io/github/samzhu/grimo/shared/event/AgentDetectedEvent.java
ls src/main/java/io/github/samzhu/grimo/shared/event/SkillInstalledEvent.java
```

- [ ] **Step 3: 驗證測試數量**

```bash
./gradlew test --tests "io.github.samzhu.grimo.DynamicCommandRegistrarTest" 2>&1 | tail -3
./gradlew test --tests "io.github.samzhu.grimo.SkillExecutorTest" 2>&1 | tail -3
```

- [ ] **Step 4: 功能驗證**

手動測試（需有可用 agent）：
- 啟動後 SlashMenu 包含 agent 快捷指令（/claude, /gemini...）
- `/claude 你好` → 直接跟 claude 對話
- `@gemini 解釋一下` → mention 語法
- `/brainstorming 設計登入頁面`（需有 brainstorming skill）→ SkillExecutor 處理

- [ ] **Step 5: Commit（若有修正）**

```
fix: address remaining issues from Plan B verification
```
