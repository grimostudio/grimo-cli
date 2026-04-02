# CommandDispatcher：自建指令系統取代 Spring Shell CommandExecutor

> 解決 Spring Shell 4.0 參數綁定不適配問題，支援動態指令（agent 快捷、skill、MCP），符合六角架構。

## 目標

1. 自建 `CommandDispatcher` 取代 Spring Shell `CommandExecutor` — raw string 參數，不需 `@Argument`
2. 支援動態指令：agent 偵測 → 註冊 `/claude`；skill 安裝 → 註冊 `/brainstorming`
3. 新增 `SkillExecutor` — 讀 SKILL.md metadata → tier routing → execution mode 判斷 → 委派 agent
4. 六角架構：CommandDispatcher 在 Core，Adapter 只做 input/output 轉換

## 背景

### 為什麼不用 Spring Shell CommandExecutor

Spring Shell 4.0 的 `CommandExecutor` → `MethodInvokerCommandAdapter.prepareArguments()` 要求參數標註 `@Argument`/`@Option`。Grimo 的指令方法接收 raw string 自行解析 — 不匹配。

此外 Grimo 不是標準 shell：
- 輸入來源是自建 TUI（alternate screen），不是 LineReader
- 補全是自建 GrimoCommandCompleter，不是 Spring Shell CompletionProvider
- 需要 runtime 動態增減指令

### Claude Code 的設計參考

Claude Code 完全自建指令系統（無框架依賴）：
- 三種 command type：PromptCommand、LocalCommand、LocalJSXCommand
- 動態發現：Skills 從磁碟、Plugin 從 npm、MCP 即時注入
- Metadata 驅動：name、aliases、description、isEnabled()、loadedFrom
- Command 回傳資料（messages），REPL 處理渲染
- Memoized 載入 + 每次 fresh availability check

> 參考來源：`claude-code-main/src/commands.ts`、`src/types/command.ts`、`src/utils/processUserInput/processSlashCommand.tsx`

## 設計

### 1. CommandDispatcher

**位置：** `io.github.samzhu.grimo.command/CommandDispatcher.java`（新 top-level module `command/`）

```java
/**
 * 統一指令分派器：靜態指令 + 動態指令共用同一個 registry。
 * 取代 Spring Shell CommandExecutor。
 *
 * 設計說明：
 * - Handler 接收 raw string args，自行解析（不需 @Argument 標註）
 * - 支援 runtime register/unregister（動態 agent/skill/MCP 指令）
 * - ConcurrentHashMap 保證執行緒安全
 * - listAll() 供 SlashMenu 和 CommandCompleter 使用
 *
 * 六角架構定位：Core（Application Service）。
 * Adapter（TUI/Channel）透過 MessageRouter → CommandDispatcher 執行指令。
 *
 * @see <a href="https://github.com/anthropics/claude-code">Claude Code commands.ts</a>
 */
@Component
public class CommandDispatcher {

    @FunctionalInterface
    public interface Handler {
        /** 執行指令，回傳結果文字。null 或空字串 = 無輸出。 */
        String execute(String rawArgs);
    }

    /** 指令 metadata + handler */
    public record Entry(
        String name,
        String description,
        String source,      // "builtin", "agent", "skill", "mcp"
        Handler handler
    ) {}

    private final Map<String, Entry> commands = new ConcurrentHashMap<>();

    /** 註冊指令。已存在則覆蓋。 */
    public void register(String name, String description, String source, Handler handler) {
        commands.put(name, new Entry(name, description, source, handler));
    }

    /** 解除指令。 */
    public void unregister(String name) {
        commands.remove(name);
    }

    /** 執行指令。找不到回傳 null。 */
    public String execute(String name, String rawArgs) {
        var entry = commands.get(name);
        if (entry == null) return null;
        return entry.handler().execute(rawArgs);
    }

    /** 查詢指令是否存在。 */
    public boolean has(String name) {
        return commands.containsKey(name);
    }

    /** 列出所有指令（供 SlashMenu 和 CommandCompleter）。 */
    public List<Entry> listAll() {
        return List.copyOf(commands.values());
    }

    /** 列出指定來源的指令。 */
    public List<Entry> listBySource(String source) {
        return commands.values().stream()
            .filter(e -> e.source().equals(source))
            .toList();
    }
}
```

### 2. 指令分類與 Handler

**三種指令本質：**

| 類型 | 觸發 | Handler 做什麼 | 範例 |
|------|------|---------------|------|
| **LocalCommand** | `/agent-list`, `/version` | 本地執行，回傳結果文字 | `agentCommands::list` |
| **SkillCommand** | `/brainstorming args` | 讀 metadata → tier → execution mode → 委派 agent | `skillExecutor::execute` |
| **AgentCommand** | `/claude args`, `@claude args` | 直接 routing 到指定 agent 對話 | `chatDispatcher::dispatchTo` |

**靜態指令註冊（啟動時）：**

```java
/**
 * 啟動時將 @Command 標註的方法註冊到 CommandDispatcher。
 * 參考 Claude Code commands.ts 的集中註冊模式。
 */
@Component
public class BuiltinCommandRegistrar {

    @PostConstruct  // 或在 startupInitRunner 中呼叫
    void registerAll() {
        // Local commands
        dispatcher.register("agent-list", "List all configured agents", "builtin",
            args -> agentCommands.list());
        dispatcher.register("agent-use", "Switch agent (auto-picks model)", "builtin",
            args -> agentCommands.use(args));
        dispatcher.register("mcp", "List MCP servers", "builtin",
            args -> mcpCommands.list());
        dispatcher.register("mcp-add", "Add MCP server", "builtin",
            args -> mcpCommands.add(args));
        dispatcher.register("skill-list", "List loaded skills", "builtin",
            args -> skillCommands.list());
        dispatcher.register("tier", "Show/set tier", "builtin",
            args -> tierCommands.tier(args));
        dispatcher.register("dev", "Start Dev Mode", "builtin",
            args -> devCommands.dev(args));
        dispatcher.register("version", "Show version", "builtin",
            args -> grimoCommands.version());
        // ... 其他 builtin commands
    }
}
```

> 設計說明：`@Command` 方法的參數改回 `String rawArgs`（單一 raw string）。方法內部自行 split/parse。不需要 Spring Shell 的 `@Argument`、`CommandContext`、`CommandExecutor`。

**動態 Agent 指令（偵測後註冊）：**

```java
// 在 startupInitRunner 中，Agent 偵測完成後
agentModelRegistry.listAvailable().forEach((agentId, model) -> {
    dispatcher.register(agentId,
        "Chat with " + agentId,
        "agent",
        args -> chatDispatcher.dispatchTo(agentId, args));
});
```

**動態 Skill 指令（安裝/載入後註冊）：**

```java
// Skill 載入完成後
skillRegistry.listAll().forEach(skill -> {
    dispatcher.register(skill.name(),
        skill.description(),
        "skill",
        args -> skillExecutor.execute(skill.name(), args));
});
```

### 3. SkillExecutor

**位置：** `io.github.samzhu.grimo.skill/SkillExecutor.java`（skill module）

```java
/**
 * Skill 指令執行：讀 metadata → tier routing → execution mode → 委派 agent。
 *
 * 設計說明：
 * - execution=isolated → DevModeRunner（worktree + 全開）
 * - execution=inline（預設）→ ChatDispatcher（主對話 + Plan Mode）
 * - 無論哪種模式，都先做 tier routing 選定 agent + model
 * - 將 "/skill-name args" 完整送入 agent（agent 有對應 SKILL.md）
 */
@Component
public class SkillExecutor {

    public String execute(String skillName, String args) {
        var skill = skillRegistry.get(skillName);
        if (skill == null) return "Skill not found: " + skillName;

        String executionMode = skill.metadata()
            .getOrDefault("grimo.execution", "inline");

        String fullGoal = "/" + skillName + " " + args;

        if ("isolated".equals(executionMode)) {
            // Worktree + 全開權限 → DevModeRunner
            devModeRunner.run(fullGoal, projectDir);
            return null;  // DevModeRunner 透過 event 通知結果
        } else {
            // 主對話脈絡 + Plan Mode → ChatDispatcher
            return chatDispatcher.dispatch(fullGoal);
        }
    }
}
```

### 4. MessageRouter 改用 CommandDispatcher

```java
@EventListener
public void on(IncomingMessageEvent event) {
    String text = event.text();

    if (text.startsWith("/")) {
        String name = extractCommandName(text);   // "brainstorming"
        String args = extractArgs(text);           // "設計登入頁面"
        String result = commandDispatcher.execute(name, args);
        if (result == null && !commandDispatcher.has(name)) {
            // 找不到指令 → 當作 AI 對話
            chatDispatcher.dispatch(text);
        } else if (result != null && !result.isEmpty()) {
            publishOutgoing(result, event);
        }
    } else if (text.startsWith("@")) {
        // @mention 語法 → agent 快捷
        String agentId = extractMention(text);     // "claude"
        String args = extractMentionArgs(text);    // "寫測試"
        String result = commandDispatcher.execute(agentId, args);
        if (result == null) chatDispatcher.dispatch(text);
        else publishOutgoing(result, event);
    } else {
        chatDispatcher.dispatch(text);
    }
}
```

### 5. SlashMenu 改讀 CommandDispatcher

```java
// Before（Spring Shell CommandRegistry）
var menuItems = commandRegistry.getCommands().stream()
    .map(cmd -> new SlashMenu.MenuItem(cmd.getName(), cmd.getDescription()))
    .toList();

// After（CommandDispatcher）
var menuItems = commandDispatcher.listAll().stream()
    .map(e -> new SlashMenu.MenuItem(e.name(), e.description()))
    .toList();
```

### 6. Spring Shell 移除範圍

| 移除 | 原因 |
|------|------|
| `CommandExecutor` 使用 | 改用 CommandDispatcher.execute() |
| `CommandParser` 使用 | 改用 String.split 提取 name + args |
| `SlashStrippingCommandParser` | 不需要了（/ strip 在 extractCommandName 裡） |
| `CommandContext` 使用 | 不需要了 |
| `@Argument`, `@Option` 標註 | 不需要了 |

| 保留 | 原因 |
|------|------|
| `@Command` 標註 | 可保留做 metadata 發現（或改成自建標註） |
| `CommandRegistry` bean | 可在 BuiltinCommandRegistrar 中讀取自動發現的指令 |
| Spring Shell starter | Tab 補全等其他功能可能仍用到 |

> 設計說明：不急著完全移除 Spring Shell — 先旁路 CommandExecutor，後續可逐步減少依賴。

### 7. command/ 模組

**位置：** `io.github.samzhu.grimo.command/`（新 top-level module）

```
command/
├── package-info.java          ← @ApplicationModule
├── CommandDispatcher.java     ← 核心 registry + executor
└── BuiltinCommandRegistrar.java ← 靜態指令註冊
```

**allowedDependencies：**
- 不依賴其他模組（CommandDispatcher 是純 infrastructure）
- BuiltinCommandRegistrar 依賴 agent、skill、mcp、config 等模組（注入 Command beans）

> 設計說明：CommandDispatcher 本身不依賴任何模組。BuiltinCommandRegistrar 負責組裝，它可以放在 root package（跟 MessageRouter 一樣，避免 Modulith 依賴爆炸）。

**修正歸屬：**
```
command/
└── CommandDispatcher.java     ← Core infrastructure（command module）

root package/
├── BuiltinCommandRegistrar.java  ← 組裝層（不是 module）
├── MessageRouter.java            ← 路由層
└── ChatDispatcher.java           ← AI 分派
```

### 8. @ Mention 語法

```
@claude 幫我寫測試
  → extractMention("@claude 幫我寫測試") = "claude"
  → extractMentionArgs("@claude 幫我寫測試") = "幫我寫測試"
  → commandDispatcher.execute("claude", "幫我寫測試")
  → Handler: chatDispatcher.dispatchTo("claude", "幫我寫測試")
```

@mention 跟 `/command` 共用同一個 CommandDispatcher registry。agent 快捷指令同時註冊兩個 name：

```java
dispatcher.register("claude", "Chat with Claude", "agent", handler);
dispatcher.register("@claude", "@mention Claude", "agent", handler);  // alias
```

### 9. 指令優先順序

當 skill name 跟 agent name 衝突時（例如安裝了名為 "claude" 的 skill）：

```
優先順序：builtin > skill > agent
```

`register()` 不覆蓋 source 優先順序更高的指令。或用 `registerIfAbsent()`。

## 測試

| 元件 | 測試 |
|------|------|
| `CommandDispatcher` | 單元測試：register/unregister/execute/listAll、找不到回傳 null、動態增減 |
| `BuiltinCommandRegistrar` | Integration test：驗證所有 builtin 指令已註冊 |
| `SkillExecutor` | 單元測試：mock SkillRegistry + DevModeRunner + ChatDispatcher，驗證 isolated/inline 路徑 |
| `MessageRouter` | 更新測試：用 CommandDispatcher 替代 CommandExecutor |

## Glossary 更新

| 名詞 | 英文 | 說明 |
|------|------|------|
| **CommandDispatcher** | Command Dispatcher | 統一指令分派器。靜態 + 動態指令共用 registry。Handler 接收 raw string，自行解析。取代 Spring Shell CommandExecutor。 |
| **SkillExecutor** | Skill Executor | Skill 指令執行。讀 metadata → tier routing → execution mode → 委派 agent。isolated → DevModeRunner，inline → ChatDispatcher。 |
| **BuiltinCommandRegistrar** | Builtin Command Registrar | 啟動時將 builtin command 方法註冊到 CommandDispatcher。 |

## 不在範圍

- 完全移除 Spring Shell（保留做 metadata 發現）
- FuzzySearch 補全（未來加）
- MCP tool 動態註冊為指令（未來）
- 指令 alias 系統（未來）

## 風險與緩解

| 風險 | 緩解 |
|------|------|
| @Command 方法參數簽名改變 | 全部改回 `String rawArgs`，一次性改完 |
| SlashMenu 資料來源改變 | 改讀 CommandDispatcher.listAll() |
| CommandCompleter 資料來源改變 | 同上 |
| 靜態指令遺漏未註冊 | BuiltinCommandRegistrar 的 integration test 驗證 |

## 驗收標準

1. `./gradlew build` 通過
2. `/agent-use claude opus` 正常執行（不再需要 @Argument）
3. `/brainstorming 設計登入頁面` 觸發 SkillExecutor（如有 skill）
4. Agent 偵測後自動註冊 `/claude`、`/gemini` 等快捷指令
5. SlashMenu 顯示所有 builtin + dynamic 指令
6. `@claude 幫我寫測試` 等 mention 語法生效
