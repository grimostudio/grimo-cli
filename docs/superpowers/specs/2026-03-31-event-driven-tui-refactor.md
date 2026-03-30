# Event-Driven TUI 重構 — 解耦 GrimoTuiRunner God Object

> Date: 2026-03-31
> Status: Draft

## 問題

`GrimoTuiRunner` 是 God Object：21 個建構子參數、13+ 職責。功能層（Commands）直接操作 TUI 元件（`refreshStatusBar()`），每加一個功能就要改 TuiRunner。現有 6 個 domain event 中 4 個是 dead code，`@ApplicationModuleListener` 在沒 DB 的 CLI 不會 fire。

### 現況耦合圖

```
GrimoTuiRunner（God Object, 21 deps）
  ├─ 初始化：workspace, agents, skills, mcp, tasks, sandbox
  ├─ TUI 建構：5 個 View + Screen + EventLoop
  ├─ 輸入處理：keyboard, mouse, slash commands
  ├─ Agent 派遣：tier routing, worktree, skill provision, client call
  ├─ 結果顯示：diff summary, error formatting
  └─ Status bar 手動刷新：refreshStatusBar()
```

## 重構目標

**Command/Service publish event → TUI listen → 自動更新。GrimoTuiRunner 只管 TUI lifecycle。**

### 目標架構

```
Commands/Services → ApplicationEventPublisher → @EventListener

AgentCommands.use()          → AgentSwitchedEvent(agentId, model)
McpCommands.add()            → McpCatalogChangedEvent(serverCount)
SkillLoader.loadAll()        → SkillsLoadedEvent(skillCount)
WorkspaceProvisioner         → WorktreeCreatedEvent(branchName)
AgentClient.run()            → AgentCompletedEvent(result, duration)

TuiEventListener:
  on(AgentSwitchedEvent)     → statusView.refresh()
  on(McpCatalogChangedEvent) → statusView.refresh()
  on(AgentCompletedEvent)    → contentView.appendResult()
```

## 設計

### Phase 1：Event 基礎 + Status Bar 解耦

修正 `@ApplicationModuleListener` → `@EventListener`，定義 UI 相關 events，將 `refreshStatusBar()` 改為 event-driven。

**新增 Events（`shared.event` 包）：**

```java
// Agent 切換（/agent-use 後 publish）
record AgentSwitchedEvent(String agentId, String model) {}

// MCP catalog 變更（/mcp-add, /mcp-remove 後 publish）
record McpCatalogChangedEvent(List<String> serverNames) {}

// Config 變更的通用 event（其他 config 變更用）
record ConfigChangedEvent(String section) {}
```

**新增 Listener（root 包或 `shared.tui` 包）：**

```java
@Component
public class TuiEventListener {
    private final GrimoStatusView statusView;
    private final GrimoEventLoop eventLoop;
    // ... minimal deps

    @EventListener
    void on(AgentSwitchedEvent e) {
        statusView.setStatusText(buildStatusText());
        eventLoop.setDirty();
    }
}
```

**修改 AgentCommands：** inject `ApplicationEventPublisher`，`use()` 結尾 publish event，刪除 `refreshStatusBar()` 手動呼叫。

**修改 ChannelEventListener：** `@ApplicationModuleListener` → `@EventListener`

### Phase 2：Agent 派遣解耦

將 GrimoTuiRunner 的 agent dispatch 邏輯抽成獨立 service：

```java
@Component
public class AgentDispatcher {
    // inject: workspaceProvisioner, mcpCatalogBuilder, skillRegistry,
    //         tierRouter, gitHelper, eventPublisher

    public void dispatch(String text, AgentModel model, AgentOptions options) {
        // 1. provision worktree
        // 2. provision skills
        // 3. create client
        // 4. run agent
        // 5. publish AgentCompletedEvent
    }
}
```

GrimoTuiRunner 只負責：收到使用者輸入 → 呼叫 `agentDispatcher.dispatch()` → listen `AgentCompletedEvent` 更新 UI。

### Phase 3：初始化解耦

GrimoTuiRunner.run() 裡的初始化步驟改為 event chain：

```
ApplicationStartedEvent（Spring 內建）
  → AgentDetectionService.on() → detectAndRegister → publish AgentsDetectedEvent
  → SkillService.on() → loadAll → publish SkillsLoadedEvent
  → McpService.on() → rebuild → publish McpCatalogChangedEvent
  → TaskService.on() → restoreAll → publish TasksRestoredEvent

TuiRunner.on(all above) → 建構 TUI 元件、更新 banner/status
```

### Phase 4：清理 Dead Code

- 刪除未使用的 events：`IncomingMessageEvent`, `TaskCreateRequestEvent`, `ScheduleTaskEvent`, `TaskCompletedEvent`
- 或重新設計為實際需要的 event

## 影響範圍

| Phase | 動作 | 檔案 |
|-------|------|------|
| 1 | Create | `shared/event/AgentSwitchedEvent.java` |
| 1 | Create | `shared/event/McpCatalogChangedEvent.java` |
| 1 | Create | `TuiEventListener.java` |
| 1 | Modify | `AgentCommands.java` — inject publisher, publish event |
| 1 | Modify | `McpCommands.java` — publish event |
| 1 | Modify | `ChannelEventListener.java` — `@ApplicationModuleListener` → `@EventListener` |
| 1 | Modify | `GrimoTuiRunner.java` — 刪除 `refreshStatusBar()` 手動呼叫 |
| 2 | Create | `AgentDispatcher.java` |
| 2 | Create | `shared/event/AgentDispatch*Event.java` |
| 2 | Modify | `GrimoTuiRunner.java` — 抽出 agent dispatch 邏輯 |
| 3 | Create | 各模組 initialization listener |
| 3 | Modify | `GrimoTuiRunner.run()` — 簡化為 TUI 建構 + event loop |
| 4 | Delete | 未使用的 event classes |

## 重構順序

```
Phase 1 先做（小範圍，立即可見效果）
  → /agent-use 切換後 status bar 自動更新（event-driven）
  → 修正 @ApplicationModuleListener bug

Phase 2 第二波（中等範圍）
  → GrimoTuiRunner constructor 從 21 降到 ~10 個 deps
  → Agent dispatch 邏輯可獨立測試

Phase 3 第三波（大範圍）
  → GrimoTuiRunner 只管 TUI lifecycle
  → 初始化完全 event-driven

Phase 4 清理（小範圍）
  → 刪除 dead code
```
