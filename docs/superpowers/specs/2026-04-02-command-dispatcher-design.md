# InputPort + CommandDispatcher：六角架構指令系統

> 單一 Driving Port + 自建指令分派 + 正確的 Port/Event 分離。

## 目標

1. 定義 `InputPort`（單一 Driving Port）— 所有使用者輸入的統一入口
2. 自建 `CommandDispatcher` — raw string 參數、動態指令（agent/skill/MCP）
3. `ResponseCallback` 封裝 adapter 的回覆機制 — Core 不耦合 channel 細節
4. Event 只用於**狀態變更通知**（不用於 request-response）
5. 移除 `MessageRouter`（Port interface 本身就是統一入口）

## 六角架構原則

> **"2-4 個 port 就能代表應用程式所有有目的的對話。"** — Alistair Cockburn
>
> - Port = interface（Core 定義），Adapter = 實作或呼叫
> - 直接呼叫 = 需要結果（request-response）
> - Event = 狀態變更，多訂閱者獨立反應（pub-sub）
> - Port 屬於 Core 內部，Adapter 屬於外部
> - **回覆機制是 Adapter 的事，不是 Core 的事**

## 設計

### 1. 三個 Port

```
Driving Port（Adapter → Core）：
  ① InputPort — "使用者送了輸入"
     所有互動都是同一種 Port。
     Core 內部判斷是 command、chat、skill、mention。

Driven Ports（Core → Infrastructure）：
  ② SessionPort — "核心需要持久化對話"
  ③ SandboxPort — "核心需要隔離執行環境"
```

### 2. InputPort（Driving Port）

**位置：** `io.github.samzhu.grimo.command/InputPort.java`

```java
/**
 * Driving Port：使用者輸入入口。
 *
 * 設計說明（六角架構）：
 * - 單一 Port — Adapter 不判斷 command vs chat，全部送進來
 * - Core 內部路由：/ 開頭 → CommandDispatcher、@ 開頭 → agent mention、其他 → AI 對話
 * - ResponseCallback 是 Adapter 的 closure — 封裝 channel-specific 回覆機制
 *   TUI: contentView.append()、LINE: lineApi.reply(replyToken)、Discord: channelApi.send(channelId)
 * - Core 完全不知道 replyToken、channelId 等 channel 細節
 *
 * @see <a href="https://alistair.cockburn.us/hexagonal-architecture/">Hexagonal Architecture</a>
 */
public interface InputPort {

    @FunctionalInterface
    interface ResponseCallback {
        void onResponse(String result);
    }

    /**
     * 使用者送了輸入。Core 解析、路由、執行。結果透過 callback 回傳。
     *
     * @param text     原始使用者輸入
     * @param metadata adapter 附帶的上下文（使用者識別、session 等）
     * @param callback adapter 的回覆 closure（Core 不知道具體回覆機制）
     */
    void handleInput(String text, InputMetadata metadata, ResponseCallback callback);

    /**
     * 列出可用指令（給 UI 選單用）。
     */
    List<CommandDispatcher.CommandEntry> listAvailableCommands();
}
```

### 3. InputMetadata

```java
/**
 * Adapter 附帶的上下文資訊。
 *
 * 設計說明：
 * - 只帶 Core 需要的資訊（使用者識別、session 歸屬）
 * - 不帶 channel 回覆細節（replyToken、channelId）— 那些封在 ResponseCallback 裡
 * - LINE: userId 用於 session 歸屬、用量統計
 * - Discord: userId 同上
 * - TUI: sessionId 用於 JSONL session 記錄
 */
public record InputMetadata(
    String source,      // "tui", "line", "discord"
    String userId,      // 使用者識別（LINE userId, Discord userId, TUI 可 null）
    String sessionId    // 對話 session ID
) {
    public static InputMetadata tui(String sessionId) {
        return new InputMetadata("tui", null, sessionId);
    }
    public static InputMetadata line(String userId, String sessionId) {
        return new InputMetadata("line", userId, sessionId);
    }
    public static InputMetadata discord(String userId, String sessionId) {
        return new InputMetadata("discord", userId, sessionId);
    }
}
```

### 4. InputHandler（InputPort 實作）

**位置：** root package `InputHandler.java`

```java
/**
 * InputPort 實作：Core 內部路由。
 *
 * 路由邏輯：
 *   / 開頭 → CommandDispatcher（本地指令 or skill or agent 快捷）
 *   @ 開頭 → CommandDispatcher（agent mention）
 *   其他   → ChatDispatcher（AI 對話）
 */
@Component
public class InputHandler implements InputPort {

    private final CommandDispatcher commandDispatcher;
    private final ChatDispatcher chatDispatcher;

    @Override
    public void handleInput(String text, InputMetadata metadata, ResponseCallback callback) {
        if (text.startsWith("/") || text.startsWith("@")) {
            String name = extractCommandName(text);
            String args = extractArgs(text);

            if (commandDispatcher.has(name)) {
                // 同步指令（LocalCommand / SkillCommand 結果）
                String result = commandDispatcher.execute(name, args);
                if (result != null && !result.isEmpty()) {
                    callback.onResponse(result);
                }
                // SkillCommand isolated 模式不回傳同步結果 — DevModeRunner 透過 event 通知
            } else {
                // 找不到指令 → 當作 AI 對話
                chatDispatcher.dispatch(text, callback);
            }
        } else {
            // AI 對話 — 非同步，結果透過 callback 回傳
            chatDispatcher.dispatch(text, callback);
        }
    }

    @Override
    public List<CommandDispatcher.CommandEntry> listAvailableCommands() {
        return commandDispatcher.listAll();
    }

    private String extractCommandName(String text) {
        // "/agent-use claude opus" → "agent-use"
        // "@claude 寫測試" → "claude"
        String stripped = text.startsWith("/") ? text.substring(1) :
                          text.startsWith("@") ? text.substring(1) : text;
        int space = stripped.indexOf(' ');
        return space > 0 ? stripped.substring(0, space) : stripped;
    }

    private String extractArgs(String text) {
        String stripped = text.startsWith("/") ? text.substring(1) :
                          text.startsWith("@") ? text.substring(1) : text;
        int space = stripped.indexOf(' ');
        return space > 0 ? stripped.substring(space + 1).trim() : "";
    }
}
```

### 5. CommandDispatcher

**位置：** `io.github.samzhu.grimo.command/CommandDispatcher.java`

```java
/**
 * 指令 registry + executor。
 * 不是 Port — 是 Core 內部元件，被 InputHandler 使用。
 *
 * 支援動態 register/unregister：
 * - builtin: 啟動時註冊（/agent-use, /skill-list...）
 * - agent: 偵測到 agent 後註冊（/claude, /gemini, @claude...）
 * - skill: 安裝 skill 後註冊（/brainstorming, /code-review...）
 * - mcp: MCP server 連線後註冊（未來）
 */
@Component
public class CommandDispatcher {

    @FunctionalInterface
    public interface Handler {
        String execute(String rawArgs);
    }

    public record CommandEntry(String name, String description, String source) {}

    private record HandlerEntry(String name, String description, String source, Handler handler) {}

    private final Map<String, HandlerEntry> commands = new ConcurrentHashMap<>();

    public void register(String name, String description, String source, Handler handler) {
        commands.put(name, new HandlerEntry(name, description, source, handler));
    }

    public void unregister(String name) {
        commands.remove(name);
    }

    public String execute(String name, String rawArgs) {
        var entry = commands.get(name);
        if (entry == null) return null;
        return entry.handler().execute(rawArgs);
    }

    public boolean has(String name) {
        return commands.containsKey(name);
    }

    public List<CommandEntry> listAll() {
        return commands.values().stream()
            .map(e -> new CommandEntry(e.name(), e.description(), e.source()))
            .toList();
    }
}
```

### 6. ChatDispatcher 加 callback 支援

```java
@Component
public class ChatDispatcher {

    /**
     * 非同步 AI 對話。Virtual Thread 執行，結果透過 callback 回傳。
     * 同時 publish AgentCallCompletedEvent → session 記錄、計費等。
     */
    public void dispatch(String text, InputPort.ResponseCallback callback) {
        Thread.ofVirtual().name("grimo-chat").start(() -> {
            try {
                String result = doDispatch(text);  // 同步部分：tier routing → AgentClient.run()
                callback.onResponse(result);        // 結果回到發起者

                // 狀態變更通知（不是回傳結果 — 是通知其他關注者）
                eventPublisher.publishEvent(
                    new AgentCallCompletedEvent(text, result, tierSelection));
            } catch (Exception e) {
                callback.onResponse("Error: " + e.getMessage());
            }
        });
    }

    public void dispatchTo(String agentId, String text, InputPort.ResponseCallback callback) {
        // 指定 agent，不走 tier routing
    }
}
```

### 7. Adapter 使用模式

**TuiAdapter：**

```java
private void processInput(String text) {
    // TUI-only overlay（不進 InputPort）
    if (text.equals("/exit")) { eventLoop.stop(); return; }
    if (text.equals("/agent-use")) { showAgentPicker(); return; }
    if (text.equals("/mcp")) { openMcpManager(); return; }

    contentView.appendUserInput(text);

    inputPort.handleInput(text,
        InputMetadata.tui(sessionWriter.getSessionId()),
        result -> {
            contentView.appendResponse(result);
            eventLoop.setDirty();
        });
}
```

**未來 LINE Adapter：**

```java
/**
 * LINE webhook → InputPort。
 * ResponseCallback 封裝 LINE replyToken 回覆機制。
 * Core 不知道 replyToken 的存在。
 */
void onWebhook(LineWebhookEvent event) {
    String text = event.getMessage().getText();
    String replyToken = event.getReplyToken();
    String userId = event.getSource().getUserId();
    String sessionId = resolveSession(userId);

    inputPort.handleInput(text,
        InputMetadata.line(userId, sessionId),
        result -> lineMessagingClient.replyMessage(
            replyToken, TextMessage.of(result)));
}
```

**未來 Discord Adapter：**

```java
/**
 * Discord MESSAGE_CREATE → InputPort。
 * ResponseCallback 封裝 Discord channelId + message_reference 回覆。
 *
 * 設計說明：
 * - 一般訊息用 createMessage + message_reference 回覆
 * - Interaction（slash command）需在 3 秒內回應或 defer
 *   → 可先 defer，再用 callback 的 follow-up 回覆
 */
void onMessageCreate(MessageCreateEvent event) {
    String text = event.getMessage().getContent();
    String channelId = event.getChannelId();
    String messageId = event.getMessage().getId();
    String userId = event.getAuthor().getId();
    String sessionId = resolveSession(userId, channelId);

    inputPort.handleInput(text,
        InputMetadata.discord(userId, sessionId),
        result -> discordApi.createMessage(channelId,
            CreateMessageRequest.builder()
                .content(result)
                .messageReference(MessageReference.of(messageId))
                .build()));
}
```

### 8. Event 只用於狀態變更通知

| Event | 觸發時機 | 訂閱者 |
|-------|---------|--------|
| `AgentSwitchedEvent` | `/agent-use` 切換後 | TuiEventBridge（status bar）、SessionPort（記錄） |
| `AgentCallCompletedEvent` | ChatDispatcher 完成 AI 對話後 | SessionPort（記錄）、未來 BillingService |
| `DevModeCompletedEvent` | DevModeRunner 完成後 | TuiEventBridge（diff summary）、SessionPort |
| `McpCatalogChangedEvent` | MCP 增減後 | TuiEventBridge（count） |
| `SkillInstalledEvent` | Skill 安裝後 | CommandDispatcher（註冊指令）、TuiEventBridge（count） |
| `AgentDetectedEvent`（新） | Agent 偵測到後 | CommandDispatcher（註冊快捷指令） |

**不再有 `IncomingMessageEvent` → `MessageRouter` → `OutgoingMessageEvent`** 做指令路由。

### 9. 動態指令註冊

**靜態 builtin（啟動時）：**

```java
@Component
public class BuiltinCommandRegistrar {
    void registerAll(CommandDispatcher dispatcher) {
        dispatcher.register("agent-list", "List all configured agents", "builtin", agentCommands::list);
        dispatcher.register("agent-use", "Switch agent", "builtin", agentCommands::use);
        dispatcher.register("skill-list", "List loaded skills", "builtin", skillCommands::list);
        dispatcher.register("mcp", "List MCP servers", "builtin", mcpCommands::list);
        dispatcher.register("mcp-add", "Add MCP server", "builtin", mcpCommands::add);
        dispatcher.register("tier", "Show/set tier", "builtin", tierCommands::tier);
        dispatcher.register("dev", "Start Dev Mode", "builtin", devCommands::dev);
        dispatcher.register("version", "Show version", "builtin", grimoCommands::version);
    }
}
```

**動態 Agent 快捷（偵測後）：**

```java
@EventListener
void on(AgentDetectedEvent event) {
    String agentId = event.agentId();
    // /claude → direct chat with claude
    dispatcher.register(agentId, "Chat with " + agentId, "agent",
        args -> { chatDispatcher.dispatchTo(agentId, args, ???); return null; });
    // @claude → mention
    dispatcher.register("@" + agentId, "Mention " + agentId, "agent",
        args -> { chatDispatcher.dispatchTo(agentId, args, ???); return null; });
}
```

> 設計說明：Agent 快捷指令是非同步（AI 對話），但 `CommandDispatcher.Handler` 回傳 `String`（同步）。
> 解法：Agent 快捷指令不走 `commandDispatcher.execute()` — InputHandler 辨識 agent name 後直接呼叫 `chatDispatcher.dispatchTo()`。
> 或者：handler 回傳 null（無同步結果），ChatDispatcher 透過 callback 非同步回傳。

**修正版 InputHandler 路由：**

```java
@Override
public void handleInput(String text, InputMetadata metadata, ResponseCallback callback) {
    if (text.startsWith("/") || text.startsWith("@")) {
        String name = extractCommandName(text);
        String args = extractArgs(text);

        var entry = commandDispatcher.getEntry(name);
        if (entry != null) {
            if ("agent".equals(entry.source())) {
                // Agent 快捷 → 非同步 AI 對話
                chatDispatcher.dispatchTo(extractAgentId(name), args, callback);
            } else {
                // builtin / skill → 同步執行
                String result = entry.handler().execute(args);
                if (result != null && !result.isEmpty()) {
                    callback.onResponse(result);
                }
            }
        } else {
            // 找不到 → AI 對話
            chatDispatcher.dispatch(text, callback);
        }
    } else {
        chatDispatcher.dispatch(text, callback);
    }
}
```

### 10. SkillExecutor

```java
@Component
public class SkillExecutor {
    /**
     * Skill 指令執行：讀 metadata → tier → execution mode → 委派。
     * execution=isolated → DevModeRunner（worktree + 全開）
     * execution=inline   → ChatDispatcher（主對話 + Plan Mode）
     */
    public String execute(String skillName, String args) {
        var skill = skillRegistry.get(skillName);
        if (skill == null) return "Skill not found: " + skillName;

        String executionMode = skill.metadata()
            .getOrDefault("grimo.execution", "inline");
        String fullGoal = "/" + skillName + " " + args;

        if ("isolated".equals(executionMode)) {
            devModeRunner.run(fullGoal, projectDir);
            return null;  // 非同步 — DevModeRunner 透過 DevModeCompletedEvent 通知
        } else {
            return chatDispatcher.doDispatch(fullGoal);  // 同步
        }
    }
}
```

### 11. 移除 / 保留

| 移除 | 原因 |
|------|------|
| `MessageRouter.java` | InputHandler 取代其角色 |
| `SlashStrippingCommandParser.java` | extractCommandName 直接做 |
| Spring Shell `CommandExecutor` 使用 | CommandDispatcher 取代 |
| `@Argument` 標註 | 不需要了 |
| TuiAdapter 中的 `IncomingMessageEvent` 發送 | 改為直接呼叫 `inputPort.handleInput()` |
| TuiAdapter 中的 `@EventListener on(OutgoingMessageEvent)` | callback 取代 |

| 保留 | 原因 |
|------|------|
| `@Command` 標註 | 可選：metadata 發現 or 改用 BuiltinCommandRegistrar 手動註冊 |
| `CommandRegistry` bean | SlashMenu 改讀 CommandDispatcher.listAll()，但保留備用 |
| Spring Shell starter | 其他功能可能仍用到 |
| 所有 domain events | 正確的狀態變更通知用法 |

## 模組結構

```
command/                          ← 新 top-level module
├── InputPort.java                ← Driving Port interface
├── InputMetadata.java            ← Adapter 附帶資訊
├── CommandDispatcher.java        ← 指令 registry + executor

root package/
├── InputHandler.java             ← InputPort 實作（Core 路由）
├── BuiltinCommandRegistrar.java  ← 靜態指令註冊
├── ChatDispatcher.java           ← AI 對話分派（加 callback）
├── SkillExecutor.java            ← Skill 指令執行
├── TuiAdapter.java               ← Driving Adapter
└── GrimoStartupRunner.java

移除：
├── MessageRouter.java
└── SlashStrippingCommandParser.java
```

## 測試

| 元件 | 測試 |
|------|------|
| `CommandDispatcher` | register/unregister/execute/listAll、動態增減、找不到=null |
| `InputHandler` | / 路由到 commandDispatcher、@ 路由到 agent、文字路由到 chat、callback 被呼叫 |
| `ChatDispatcher` | callback 被呼叫、event 被 publish |
| `SkillExecutor` | isolated → DevModeRunner、inline → ChatDispatcher |

## 驗收標準

1. `./gradlew build` 通過
2. `/agent-use claude opus` 正常（raw string，不需 @Argument）
3. `/brainstorming args` 觸發 SkillExecutor
4. Agent 偵測後 `/claude`、`@claude` 可用
5. SlashMenu 顯示 builtin + agent + skill 指令
6. `MessageRouter.java` 已移除
7. Adapter 不判斷 command vs chat — 全部走 `inputPort.handleInput()`
8. Event 不用於指令路由（只用於狀態變更通知）
