# 設計：拆分 WorkspaceManager 為 GrimoHome + ProjectContext

> **日期：** 2026-03-31
> **狀態：** Draft
> **範圍：** `shared/workspace/`、`shared/config/`、`shared/session/`（dispatch 紀錄）、`agent/`、TUI 狀態列/Splash

## 動機

目前 `WorkspaceManager` 把全域 app 資料（config、skills、tasks、agents）和專案身分（CWD、sessions）混在一起，路徑寫死為 `~/.grimo`。實際上這是兩個不同層級的概念：

- **App 全域資料** — config、skills、tasks、agents，全機共用，固定在 `~/.grimo`
- **專案上下文** — 開發者啟動 grimo 時的 CWD，代表目前操作的專案，對話 session 歸屬於此

狀態列和 Splash 應該顯示**專案路徑**（CWD），而非 `~/.grimo`。`workspace` 概念是暫時設計，現在可以移除。

## 設計

### 核心模型

#### `GrimoHome`（取代 `WorkspaceManager` 的全域部分）

```
~/.grimo/                    ← GrimoHome.root()
├── config.yaml              ← GrimoHome.configFile()
├── skills/                  ← GrimoHome.skillsDir()
├── tasks/                   ← GrimoHome.tasksDir()
├── agents/                  ← GrimoHome.agentsDir()
├── logs/                    ← GrimoHome.logsDir()
├── conversations/           ← 已棄用，不再建立新目錄（既有資料保留不刪除）
└── projects/                         ← GrimoHome.projectsDir()
    └── {encoded-cwd}/                ← ProjectContext.dataDir()
        ├── {sessionId}.jsonl         ← 主 session 對話（含 dispatch 摘要事件）
        └── {sessionId}/              ← session 附屬資料目錄
            └── dispatches/
                ├── {taskId}.meta.json    ← dispatch metadata
                └── {taskId}.events.jsonl ← dispatch 事件流
```

- **路徑固定**：`Path.of(System.getProperty("user.home"), ".grimo")`，不需要外部配置
- **職責**：提供全域目錄路徑、啟動時 `initialize()` 建立目錄結構和預設 config.yaml
- **包位置**：`io.github.samzhu.grimo.shared.workspace.GrimoHome`
- **可測試性**：建構子接受 `Path homePath` 參數，生產環境傳 `user.home`，測試用 `@TempDir`

```java
public class GrimoHome {
    private final Path root;

    // 生產環境
    public GrimoHome() {
        this(Path.of(System.getProperty("user.home"), ".grimo"));
    }

    // 測試用
    public GrimoHome(Path root) {
        this.root = root;
    }

    public void initialize() { /* 建立目錄 + 預設 config */ }

    public Path root()         { return root; }
    public Path configFile()   { return root.resolve("config.yaml"); }
    public Path skillsDir()    { return root.resolve("skills"); }
    public Path tasksDir()     { return root.resolve("tasks"); }
    public Path agentsDir()    { return root.resolve("agents"); }
    public Path logsDir()      { return root.resolve("logs"); }
    public Path projectsDir()  { return root.resolve("projects"); }
}
```

#### `ProjectContext`（新增，代表目前操作的專案）

- **由 CWD 決定**：`System.getProperty("user.dir")`
- **職責**：提供專案身分資訊和專案級資料目錄
- **包位置**：`io.github.samzhu.grimo.shared.workspace.ProjectContext`
- **依賴 `GrimoHome`**：需要知道 `projectsDir()` 在哪

```java
public class ProjectContext {
    private final Path projectPath;
    private final Path dataDir;

    public ProjectContext(GrimoHome grimoHome) {
        this(grimoHome, Path.of(System.getProperty("user.dir")));
    }

    // 測試用
    public ProjectContext(GrimoHome grimoHome, Path cwd) {
        this.projectPath = cwd;
        this.dataDir = grimoHome.projectsDir()
                .resolve(encodePath(cwd));
    }

    public void initialize() { /* 建立 dataDir 目錄 */ }

    public Path path()         { return projectPath; }
    public String displayPath() {
        return projectPath.toString()
                .replace(System.getProperty("user.home"), "~");
    }
    public Path dataDir()      { return dataDir; }

    /**
     * 沿用既有編碼方式：非英數字元替換為 '-'
     * 例如 /Users/samzhu/workspace/grimo-cli → -Users-samzhu-workspace-grimo-cli
     * 對齊 Claude Code 的 projects/ 編碼慣例
     */
    private String encodePath(Path p) {
        return p.toString().replaceAll("[^a-zA-Z0-9]", "-");
    }
}
```

### 移除項目

| 移除目標 | 說明 |
|---------|------|
| `WorkspaceManager` | 功能拆分到 `GrimoHome` + `ProjectContext` |
| `GrimoProperties` record | `workspace` 是唯一欄位，移除後整個 record 為空，一併刪除 |
| `application.yaml` 的 `grimo.workspace` | 路徑固定，無需配置 |
| `conversationsDir()` | 未使用，`GrimoHome.initialize()` 不再建立此目錄（既有目錄保留不刪） |

### 消費者遷移

| 消費者 | 目前用法 | 遷移後 |
|--------|---------|--------|
| `GrimoConfig` | `workspaceManager.configFile()` | `grimoHome.configFile()` |
| `SkillLoader` | `workspaceManager.skillsDir()` | `grimoHome.skillsDir()` |
| `MarkdownTaskStore` | `workspaceManager.tasksDir()` | `grimoHome.tasksDir()` |
| `WorkspaceProvisioner` | `workspaceManager.skillsDir()` | `grimoHome.skillsDir()` |
| `GrimoStartupRunner`（`skillsDir` Path bean） | `workspaceManager.skillsDir()` 獨立 `@Bean` | 改為 `grimoHome.skillsDir()` 或移除獨立 bean |
| `SessionWriter` | 在 `GrimoTuiRunner` 手動建構，傳入 `Path sessionsBaseDir` | 改為 Spring `@Bean`，注入 `ProjectContext`。session 直接放 dataDir 根目錄（對齊 Claude Code） |
| `GrimoSessionAdvisor` | 建構子注入 `SessionWriter`，呼叫 `writeUserMessage()` / `writeAssistantMessage()` | `SessionWriter` 改為 bean 後，自動注入不需改動 advisor 程式碼 |
| `GrimoTuiRunner`（Splash） | `workspaceManager.root()` 顯示路徑 | `projectContext.displayPath()` |
| `GrimoTuiRunner`（Status Bar） | `workspaceManager.root()` 顯示路徑 | `projectContext.displayPath()` |
| `GrimoStartupRunner` | `workspaceManager.initialize()` | `grimoHome.initialize()` + `projectContext.initialize()` |
| `DevModeRunner` | 無（目前不存 dispatch 紀錄） | 不變 — 既有 `DevModeEnteredEvent` / `DevModeCompletedEvent` 已帶足夠資訊，新增 `SessionEventListener` 監聽即可 |

### Bean 註冊

```java
@Bean
GrimoHome grimoHome() {
    return new GrimoHome();
}

@Bean
ProjectContext projectContext(GrimoHome grimoHome) {
    return new ProjectContext(grimoHome);
}

@Bean
SessionWriter sessionWriter(ProjectContext projectContext) {
    return new SessionWriter(projectContext.dataDir());
}
```

取代目前 `GrimoStartupRunner` 中的 `workspaceManager(GrimoProperties)` bean。

**`SessionWriter` 改為 Spring Bean 的理由**：目前 `SessionWriter` 在 `GrimoTuiRunner` 中手動建構，但 `GrimoSessionAdvisor`（`@Component`）需要使用它。改為 bean 後，消費者共享同一個 `SessionWriter` 實例。

**Event-driven dispatch 紀錄（Spring Modulith 解耦）**：`DevModeRunner` **不直接注入** `SessionWriter`。它已經發布 `DevModeEnteredEvent` / `DevModeCompletedEvent`（現有設計），新增一個 `SessionEventListener` 監聽這些 event 來寫入 dispatch 紀錄。這遵守 Spring Modulith 模組邊界：`agent/` 不依賴 `shared/session/`。

```
DevModeRunner → publish(DevModeEnteredEvent) →
  ├── TuiEventListener      → UI 更新（已有）
  └── SessionEventListener  → 寫入主 session JSONL + dispatches/

DevModeRunner → publish(DevModeCompletedEvent) →
  ├── TuiEventListener      → UI 更新（已有）
  └── SessionEventListener  → 寫入主 session JSONL + meta.json
```

`SessionEventListener` 放在 `shared/session/` 包內，注入 `SessionWriter`。`DevModeRunner` 完全不知道 session 持久化的存在。

### 狀態列 / Splash 改動

**Before：**
```
claude · claude-sonnet-4-6 │ ~/.grimo │ 3 agent · 0 skill · 0 mcp · 0 task
```

**After：**
```
claude · claude-sonnet-4-6 │ ~/workspace/github-grimostudio/grimo-cli │ 3 agent · 0 skill · 0 mcp · 0 task
```

Splash 第三行同理，從 `~/.grimo` 改為 `projectContext.displayPath()`。

### Sub-agent Dispatch 紀錄（對齊 Claude Code session 設計）

#### 設計原則：Dispatch 歸屬於 Session

對標 Claude Code 的 `{sessionId}/subagents/` 設計：sub-agent 資料放在**主 session 的附屬目錄內**，不是獨立的頂層資料夾。這確保：

1. **可關聯** — 每個 dispatch 明確屬於某個 session，resume 時能還原完整脈絡
2. **可追溯** — 主 session JSONL 內有 dispatch 摘要事件，深入時去附屬目錄找完整資料
3. **不混雜** — 不同 session 的 dispatch 天然隔離

Claude Code 的參考結構：
```
projects/{encoded-cwd}/
├── {sessionId}.jsonl                       ← 主 session（含 Agent tool_use）
└── {sessionId}/
    └── subagents/
        ├── agent-{agentId}.jsonl           ← sub-agent 完整對話
        └── agent-{agentId}.meta.json       ← {"agentType":"Explore","description":"..."}
```

Grimo 的對應結構：
```
projects/{encoded-cwd}/
├── {sessionId}.jsonl                       ← 主 session（含 dispatch-entered/completed 事件）
└── {sessionId}/
    └── dispatches/
        ├── {taskId}.meta.json              ← dispatch metadata（靜態）
        └── {taskId}.events.jsonl           ← dispatch 事件流（動態，append-only）
```

#### Session 檔案位置調整

目前 `SessionWriter` 把 session 放在 `sessions/` 子目錄。為了對齊 Claude Code（session 直接放在 project data 根目錄），改為：

```
# Before
~/.grimo/projects/{encoded-cwd}/sessions/{sessionId}.jsonl

# After（對齊 Claude Code）
~/.grimo/projects/{encoded-cwd}/{sessionId}.jsonl
~/.grimo/projects/{encoded-cwd}/{sessionId}/dispatches/
```

`ProjectContext` 不再需要 `sessionsDir()`，`SessionWriter` 直接用 `projectContext.dataDir()` 作為 session 根目錄。

#### 主 Session 內的 Dispatch 摘要事件

`SessionWriter` 新增兩個事件類型，寫入主 session JSONL：

```json
{"type":"dispatch-entered","taskId":"1b88fc01","agent":"claude","model":"claude-sonnet-4-6","tier":"std","branchName":"grimo/1b88fc01","timestamp":"...","sessionId":"...","uuid":"...","parentUuid":"..."}

{"type":"dispatch-completed","taskId":"1b88fc01","hasChanges":true,"commitCount":3,"diffStat":"5 files changed, 120(+), 30(-)","durationMs":45000,"summary":"Fixed auth bug","timestamp":"...","sessionId":"...","uuid":"...","parentUuid":"..."}
```

單看主 session JSONL 就能看到所有 dispatch 的摘要。

#### Dispatch Meta 檔案格式

`{sessionId}/dispatches/{taskId}.meta.json`：

```json
{
  "taskId": "1b88fc01",
  "agent": "claude",
  "model": "claude-sonnet-4-6",
  "tier": "std",
  "goal": "Fix authentication bug in login flow",
  "execution": {
    "mode": "worktree",
    "workDir": "/tmp/grimo-worktree-1b88fc01",
    "branchName": "grimo/1b88fc01",
    "baseSha": "abc123def456",
    "containerId": null
  },
  "result": {
    "hasChanges": true,
    "commitCount": 3,
    "diffStat": "5 files changed, 120 insertions(+), 30 deletions(-)",
    "durationMs": 45000,
    "summary": "Fixed authentication bug in login flow"
  },
  "externalSessions": {
    "claude": "~/.claude/projects/-private-var-...-grimo-worktree-1b88fc01/"
  }
}
```

- **`execution.mode`**：`worktree` / `docker` / `e2b` — 對應 `SandboxDetector` 的三種模式
- **`externalSessions`**：記錄各 CLI agent 的 session 路徑，提供跨系統追溯線索。worktree 清理後 CLI agent 的 session 仍存在（孤兒但可查閱）
- **寫入時機**：`DevModeRunner` cleanup 階段

#### Dispatch Events 檔案格式

`{sessionId}/dispatches/{taskId}.events.jsonl`：

```jsonl
{"type":"entered","timestamp":"...","workDir":"/tmp/grimo-worktree-1b88fc01","branchName":"grimo/1b88fc01"}
{"type":"progress","timestamp":"...","message":"Agent is working..."}
{"type":"completed","timestamp":"...","hasChanges":true,"commitCount":3,"diffStat":"...","durationMs":45000}
```

Append-only，記錄 dispatch 生命週期。需要深入追溯時使用。

#### 影響到的元件

| 元件 | 改動 |
|------|------|
| `SessionWriter` | 新增 `writeDispatchEntered()` / `writeDispatchCompleted()`、`dispatchesDir()` 方法；session 檔案改為直接放在 `dataDir/` 下 |
| `SessionEventListener`（**新增**） | `shared/session/` 包內的 `@EventListener`，監聯 `DevModeEnteredEvent` / `DevModeCompletedEvent`，呼叫 `SessionWriter` 寫入 dispatch 紀錄 |
| `DevModeEnteredEvent` | 確認已包含足夠欄位（taskId、agent、model、tier、branchName、workDir）。如不足需擴充 |
| `DevModeCompletedEvent` | 確認已包含足夠欄位（taskId、hasChanges、commitCount、diffStat、duration、summary、externalSessionPaths）。如不足需擴充 |
| `DevModeRunner` | **不改** — 已發布上述 event，dispatch 持久化由 `SessionEventListener` 處理 |
| `ProjectContext` | 移除 `sessionsDir()`，只提供 `dataDir()` |

Event-driven 設計確保 `agent/` 模組不依賴 `shared/session/`，遵守 Spring Modulith 模組邊界。

## 錯誤處理

- **目錄建立失敗**（權限不足）：`GrimoHome.initialize()` 和 `ProjectContext.initialize()` 拋出 `UncheckedIOException`，app 啟動時 fail-fast
- **CWD 不存在**：OS 保證 `user.dir` 有效，不額外處理

## 測試策略

| 測試目標 | 方式 |
|---------|------|
| `GrimoHome` | 單元測試：`@TempDir` 覆蓋 home 路徑，驗證 `initialize()` 建出正確目錄結構、各 `xxxDir()` 回傳正確路徑 |
| `ProjectContext` | 單元測試：注入假的 CWD 和 `GrimoHome`，驗證 `displayPath()` tilde 縮寫、`dataDir()` encoded path 正確 |
| `SessionWriter` | 單元測試：驗證 session 檔案放在 `dataDir/{sessionId}.jsonl`、dispatch 事件正確寫入主 session、`dispatches/` 目錄和 meta.json 正確建立 |
| 消費者遷移 | 既有測試改注入新的 `GrimoHome` / `ProjectContext`，確認行為不變 |
| `SessionEventListener` | 單元測試：發布 `DevModeEnteredEvent` / `DevModeCompletedEvent`，驗證 listener 正確呼叫 `SessionWriter` 寫入 dispatch 紀錄 |
| Dispatch 端到端 | 整合測試：`DevModeRunner` 發布 event → `SessionEventListener` 寫入 → 驗證主 session JSONL 含 dispatch 摘要 + `{sessionId}/dispatches/` 含 meta.json |

## Glossary 更新

新增：
- **GrimoHome** — `~/.grimo`，應用程式全域資料目錄，存放 config、skills、tasks、agents、logs
- **ProjectContext** — 啟動時的 CWD，代表目前操作的專案。專案資料存放在 `~/.grimo/projects/{encoded-cwd}/`

新增：
- **Dispatch 紀錄** — sub-agent 派遣的 metadata 和事件，歸屬於 session（`{sessionId}/dispatches/{taskId}.*`）。主 session JSONL 含摘要事件，附屬目錄含完整 meta.json 和 events.jsonl。對齊 Claude Code 的 `{sessionId}/subagents/` 設計。Grimo 調度層只記 metadata，完整對話由各 CLI agent 自行管理

移除：
- **Workspace** 相關用法（如果有）

更新：
- glossary 佈局圖和 Status 區說明中的 `~/grimo-workspace` → 改為顯示專案路徑
- **Session 檔案**路徑描述：從 `sessions/{uuid}.jsonl` 改為 `{sessionId}.jsonl`（直接放 project data 根目錄）
- **Session** 術語補充：附屬資料目錄 `{sessionId}/dispatches/` 存放 sub-agent 派遣紀錄
