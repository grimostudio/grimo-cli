# InputPort + CommandDispatcher Implementation Plan (Plan A: Core + Migration)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 InputPort + CommandDispatcher 取代 Spring Shell CommandExecutor 和 MessageRouter，讓 TuiAdapter 直接呼叫 Port（六角架構）。

**Architecture:** 新建 `command/` 模組定義 InputPort（Driving Port）+ CommandDispatcher（內部 registry）。InputHandler 實作 InputPort，路由 /command → CommandDispatcher、文字 → ChatDispatcher。TuiAdapter 改為呼叫 InputPort（不發 IncomingMessageEvent）。移除 MessageRouter。

**Tech Stack:** Java 25 (Virtual Threads), Spring Boot 4.0.x, Spring Modulith 2.0.x, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-04-02-command-dispatcher-design.md`

**Plan B（後續）：** 動態指令（agent 快捷、skill commands、@mention）+ SkillExecutor

---

### Task 1: 建立 command/ 模組（InputPort + InputMetadata + CommandDispatcher）

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/command/package-info.java`
- Create: `src/main/java/io/github/samzhu/grimo/command/InputPort.java`
- Create: `src/main/java/io/github/samzhu/grimo/command/InputMetadata.java`
- Create: `src/main/java/io/github/samzhu/grimo/command/CommandDispatcher.java`
- Create: `src/test/java/io/github/samzhu/grimo/command/CommandDispatcherTest.java`

- [ ] **Step 1: 寫 CommandDispatcherTest（TDD — 先寫測試）**

測試案例：
- `registerAndExecute` — 註冊後可執行，回傳結果
- `executeUnknownReturnsNull` — 找不到回傳 null
- `hasReturnsTrueForRegistered` — 已註冊 = true
- `hasReturnsFalseForUnknown` — 未註冊 = false
- `unregisterRemovesCommand` — 解除後找不到
- `listAllReturnsAllEntries` — 列出所有，含 name/description/source
- `registerOverwritesExisting` — 同名覆蓋
- `listAllSortedByName` — listAll 按名稱排序（給 UI 用）

- [ ] **Step 2: 建立 package-info.java**

```java
@org.springframework.modulith.ApplicationModule
package io.github.samzhu.grimo.command;
```

- [ ] **Step 3: 建立 InputPort.java**

按 spec Section 2 的 interface 定義。包含 `ResponseCallback` functional interface 和 `handleInput()` + `listAvailableCommands()` 方法。

- [ ] **Step 4: 建立 InputMetadata.java**

按 spec Section 3 的 record 定義。static factory: `tui()`, `line()`, `discord()`。

- [ ] **Step 5: 建立 CommandDispatcher.java**

按 spec Section 5 實作。`Handler` functional interface、`CommandEntry` record、`ConcurrentHashMap`、register/unregister/execute/has/listAll。

`listAll()` 回傳按 name 排序的 list（UI 顯示用）。

新增 `getEntry(String name)` 方法（InputHandler 需要檢查 entry.source()）：

```java
public HandlerEntry getEntry(String name) {
    return commands.get(name);
}
```

將 `HandlerEntry` 改為 public（InputHandler 需要讀 source）。

- [ ] **Step 6: 執行測試**

Run: `./gradlew test --tests "io.github.samzhu.grimo.command.CommandDispatcherTest"`
Expected: 8 tests PASS

- [ ] **Step 7: Commit**

```
feat: add command/ module — InputPort + CommandDispatcher (hexagonal driving port)
```

---

### Task 2: ChatDispatcher 加 callback 支援

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/ChatDispatcher.java`

- [ ] **Step 1: 讀取現有 ChatDispatcher**

了解現有的 `dispatch(String text)` 方法 — 它直接操作 TUI 元件（contentView, statusView）。

- [ ] **Step 2: 新增 callback 版本的 dispatch**

```java
/**
 * 非同步 AI 對話。callback 回傳結果（給發起的 adapter）。
 * 同時 publish event 通知其他訂閱者（session, billing）。
 *
 * 設計說明（六角架構）：
 * - callback = 結果回傳給發起者（Adapter 的 closure）
 * - event = 狀態變更通知（session 記錄、計費等）
 * - 兩者不衝突，各有職責
 */
public void dispatch(String text, InputPort.ResponseCallback callback) {
    // 包裝現有邏輯，結果透過 callback 回傳
}
```

需要 import `io.github.samzhu.grimo.command.InputPort`。

**重要：** 保留現有的無 callback 版本 `dispatch(String text)` 作為過渡（TuiAdapter 遷移前仍在用）。新 callback 版本包裝現有邏輯。

- [ ] **Step 3: 處理 ChatDispatcher 對 TUI 的直接依賴**

現有 ChatDispatcher 直接操作 `contentView`、`statusView`、`eventLoop`。遷移到 callback 後：
- callback 版本不直接操作 TUI — 結果透過 callback 回傳
- 現有版本（無 callback）暫時保留，等 TuiAdapter 遷移後移除

- [ ] **Step 4: 編譯驗證**

Run: `./gradlew compileJava`

- [ ] **Step 5: Commit**

```
feat: add callback-based dispatch to ChatDispatcher (hexagonal port pattern)
```

---

### Task 3: 建立 InputHandler（InputPort 實作）+ 測試

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/InputHandler.java`
- Create: `src/test/java/io/github/samzhu/grimo/InputHandlerTest.java`

- [ ] **Step 1: 寫 InputHandlerTest（TDD）**

測試案例（mock CommandDispatcher + ChatDispatcher）：
- `slashCommandRoutesToDispatcher` — `/agent-list` → commandDispatcher.execute("agent-list", "")
- `slashCommandWithArgsRoutesCorrectly` — `/agent-use claude opus` → execute("agent-use", "claude opus")
- `unknownSlashRoutesToChat` — `/unknown` → chatDispatcher.dispatch(text, callback)
- `plainTextRoutesToChat` — "hello" → chatDispatcher.dispatch("hello", callback)
- `callbackReceivesCommandResult` — command 結果透過 callback 回傳
- `emptyResultNotSentToCallback` — null/empty 結果不呼叫 callback
- `listAvailableCommandsDelegatesToDispatcher` — 轉發到 commandDispatcher.listAll()

- [ ] **Step 2: 建立 InputHandler**

按 spec Section 4 實作。`@Component`、implements `InputPort`。

```java
package io.github.samzhu.grimo;

@Component
public class InputHandler implements InputPort {
    private final CommandDispatcher commandDispatcher;
    private final ChatDispatcher chatDispatcher;
    // ...
}
```

**注意：** InputHandler 在 root package（不是 module），避免 Modulith 依賴爆炸。它注入 `CommandDispatcher`（command module）和 `ChatDispatcher`（root package）。

- [ ] **Step 3: 執行測試**

Run: `./gradlew test --tests "io.github.samzhu.grimo.InputHandlerTest"`
Expected: 7 tests PASS

- [ ] **Step 4: Commit**

```
feat: add InputHandler — InputPort impl routing commands and chat
```

---

### Task 4: BuiltinCommandRegistrar（靜態指令註冊）

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/BuiltinCommandRegistrar.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`

- [ ] **Step 1: 盤點所有 @Command 方法**

需要註冊的 builtin commands（18 個）：

| Command name | Class | Method | 有參數？ |
|-------------|-------|--------|---------|
| agent-list | AgentCommands | list() | 否 |
| agent-use | AgentCommands | use(String) | 是 |
| dev | DevCommands | dev(String) | 是 |
| tier | TierCommands | tier(String) | 是（可選） |
| skill-tier | TierCommands | skillTier(String, String) | 是 |
| skill-list | SkillCommands | list() | 否 |
| skill-remove | SkillCommands | remove(String) | 是 |
| skill-install | SkillCommands | install(String) | 是 |
| mcp | McpCommands | list() | 否 |
| mcp-add | McpCommands | add(String) | 是 |
| mcp-remove | McpCommands | remove(String) | 是 |
| channel-list | ChannelCommands | list() | 否 |
| channel-remove | ChannelCommands | remove(String) | 是 |
| task-create | TaskCommands | create(String) | 是 |
| task-list | TaskCommands | list() | 否 |
| task-show | TaskCommands | show(String) | 是 |
| task-cancel | TaskCommands | cancel(String) | 是 |
| version | GrimoCommands | version() | 否 |
| chat | GrimoCommands | chat(String) | 是 |
| status | GrimoCommands | status() | 否 |

- [ ] **Step 2: 修改 @Command 方法簽名**

所有有參數的 @Command 方法改為接收 `String rawArgs`（單一 raw string）：

```java
// Before（@Argument 綁定，壞的）
public String use(@Argument(index = 0) String agentId,
                  @Argument(index = 1, defaultValue = "") String modelHint)

// After（raw string，自行解析）
public String use(String rawArgs) {
    if (rawArgs == null || rawArgs.isBlank()) return "Usage: ...";
    String[] parts = rawArgs.trim().split("\\s+", 2);
    // ...
}
```

影響的檔案：AgentCommands、DevCommands、TierCommands、SkillCommands、McpCommands、ChannelCommands、TaskCommands、GrimoCommands。

同步更新測試檔案中的呼叫簽名。

- [ ] **Step 3: 建立 BuiltinCommandRegistrar**

```java
@Component
public class BuiltinCommandRegistrar {
    private final CommandDispatcher dispatcher;
    // 注入所有 Commands beans...

    @PostConstruct
    public void registerAll() {
        dispatcher.register("agent-list", "List all configured agents", "builtin",
            args -> agentCommands.list());
        dispatcher.register("agent-use", "Switch agent", "builtin",
            agentCommands::use);
        // ... 所有 18+ commands
    }
}
```

- [ ] **Step 4: 在 GrimoStartupRunner 的 startupInitRunner 中確認 BuiltinCommandRegistrar 在 TuiAdapter 之前就緒**

BuiltinCommandRegistrar 是 `@Component` + `@PostConstruct` — Spring context 建立時就執行，早於 ApplicationRunner。確認順序正確。

- [ ] **Step 5: 編譯 + 測試**

Run: `./gradlew build`

- [ ] **Step 6: Commit**

```
feat: add BuiltinCommandRegistrar — register all static commands to CommandDispatcher
```

---

### Task 5: TuiAdapter 遷移到 InputPort

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/TuiAdapter.java`

- [ ] **Step 1: 注入 InputPort**

建構子加入 `InputPort inputPort`。

- [ ] **Step 2: 修改 processInput() — 改呼叫 InputPort**

```java
private void processInput(String text) {
    // TUI-only overlay（不進 InputPort）
    if (text.equals("/exit")) { eventLoop.stop(); return; }
    if (text.equals("/agent-use")) { showAgentPicker(); return; }
    if (text.equals("/mcp")) { tuiKeyHandler.openMcpManager(); return; }

    contentView.appendUserInput(text);
    sessionWriter.writeUserMessage(text);

    // 六角架構：Adapter 呼叫 Port，不發 event
    inputPort.handleInput(text,
        InputMetadata.tui(sessionWriter.getSessionId()),
        result -> {
            contentView.appendCommandOutput(result);
            eventLoop.setDirty();
        });
}
```

- [ ] **Step 3: 移除 IncomingMessageEvent 發送 + OutgoingMessageEvent 監聽**

刪除：
- `publishEvent(new IncomingMessageEvent(...))` 呼叫
- `@EventListener public void on(OutgoingMessageEvent event)` 方法
- 相關 import（IncomingMessageEvent, OutgoingMessageEvent, Instant, List）

- [ ] **Step 4: 移除 MessageRouter 相關的依賴注入**

TuiAdapter 建構子移除 `CommandParser`、`CommandExecutor`、`CommandRegistry`（改用 InputPort）。

- [ ] **Step 5: 更新 SlashMenu 資料來源**

```java
// Before（Spring Shell CommandRegistry）
var menuItems = buildMenuItems();  // 讀 commandRegistry

// After（CommandDispatcher via InputPort）
var menuItems = inputPort.listAvailableCommands().stream()
    .map(e -> new SlashMenu.MenuItem(e.name(), e.description()))
    .toList();
```

- [ ] **Step 6: 編譯驗證**

Run: `./gradlew compileJava`

- [ ] **Step 7: Commit**

```
refactor: TuiAdapter uses InputPort — direct port call replaces event pipeline
```

---

### Task 6: 移除 MessageRouter + SlashStrippingCommandParser

**Files:**
- Delete: `src/main/java/io/github/samzhu/grimo/MessageRouter.java`
- Delete: `src/main/java/io/github/samzhu/grimo/SlashStrippingCommandParser.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java` — 移除相關 bean

- [ ] **Step 1: 刪除 MessageRouter.java**

- [ ] **Step 2: 刪除 SlashStrippingCommandParser.java**

- [ ] **Step 3: 更新 GrimoStartupRunner**

移除 `commandParser` bean（SlashStrippingCommandParser）。
`commandExecutor` bean 可保留（Spring Shell 內部可能仍需要）或移除。

- [ ] **Step 4: 移除不再使用的 Spring Shell import**

搜尋 `CommandParser`、`CommandExecutor`、`CommandContext` import，確認只剩 Spring Shell 內部使用。

- [ ] **Step 5: 編譯 + 全量測試**

Run: `./gradlew build`

若 ModulithStructureTest 失敗 → 調整 allowedDependencies。

- [ ] **Step 6: Commit**

```
refactor: remove MessageRouter + SlashStrippingCommandParser — InputPort replaces event pipeline
```

---

### Task 7: Glossary + CLAUDE.md 更新

**Files:**
- Modify: `docs/glossary.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: CLAUDE.md 更新**

- 更新架構表：新增 `command/` 模組
- 更新 TuiAdapter 架構段落：移除 MessageRouter，加入 InputPort
- 更新統一管線規則：從 "event pipeline" 改為 "InputPort direct call"
- 更新六角架構說明：Port = interface、Event = state change

- [ ] **Step 2: Glossary 新增術語**

| 名詞 | 英文 | 說明 |
|------|------|------|
| **InputPort** | Input Port | Driving Port。使用者輸入統一入口。Adapter 呼叫 `handleInput(text, metadata, callback)`，Core 路由到 command 或 chat。 |
| **InputMetadata** | Input Metadata | Adapter 附帶的上下文：source、userId、sessionId。不含 channel 回覆細節（封在 callback）。 |
| **CommandDispatcher** | Command Dispatcher | 指令 registry + executor。支援動態 register/unregister。取代 Spring Shell CommandExecutor。 |
| **InputHandler** | Input Handler | InputPort 實作。Core 內部路由：/ → CommandDispatcher、文字 → ChatDispatcher。 |
| **ResponseCallback** | Response Callback | Adapter 的回覆 closure。封裝 channel-specific 回覆機制（TUI: contentView、LINE: replyToken、Discord: channelId）。Core 不知道具體機制。 |

- [ ] **Step 3: Commit**

```
docs: update glossary and CLAUDE.md for InputPort + CommandDispatcher
```

---

### Task 8: 全量驗證

- [ ] **Step 1: Full build**

Run: `./gradlew build`

- [ ] **Step 2: 驗證元件**

```bash
# 新元件存在
ls src/main/java/io/github/samzhu/grimo/command/InputPort.java
ls src/main/java/io/github/samzhu/grimo/command/CommandDispatcher.java
ls src/main/java/io/github/samzhu/grimo/InputHandler.java
ls src/main/java/io/github/samzhu/grimo/BuiltinCommandRegistrar.java

# 舊元件已移除
ls src/main/java/io/github/samzhu/grimo/MessageRouter.java 2>&1
# Expected: No such file
ls src/main/java/io/github/samzhu/grimo/SlashStrippingCommandParser.java 2>&1
# Expected: No such file
```

- [ ] **Step 3: 驗證 IncomingMessageEvent 不再用於路由**

```bash
grep -r "IncomingMessageEvent" src/main/java/io/github/samzhu/grimo/TuiAdapter.java
# Expected: 無輸出

grep -r "MessageRouter" src/main/java/ --include="*.java" | grep -v "test\|Test\|//"
# Expected: 無輸出
```

- [ ] **Step 4: 功能驗證**

手動測試：
- `/agent-list` → 顯示 agent 列表
- `/agent-use claude opus` → 切換成功
- `/version` → 顯示版本
- 一般文字 → AI 對話

- [ ] **Step 5: Commit（若有修正）**

```
fix: address remaining issues from Plan A verification
```
