# 設計：拆分 WorkspaceManager 為 GrimoHome + ProjectContext

> **日期：** 2026-03-31
> **狀態：** Draft
> **範圍：** `shared/workspace/`、`shared/config/`、TUI 狀態列/Splash

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
└── projects/                ← GrimoHome.projectsDir()
    └── {encoded-cwd}/       ← ProjectContext.dataDir()
        └── sessions/        ← ProjectContext.sessionsDir()
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

    public void initialize() { /* 建立 dataDir + sessions 目錄 */ }

    public Path path()         { return projectPath; }
    public String displayPath() {
        return projectPath.toString()
                .replace(System.getProperty("user.home"), "~");
    }
    public Path dataDir()      { return dataDir; }
    public Path sessionsDir()  { return dataDir.resolve("sessions"); }

    /**
     * 沿用既有編碼方式：非英數字元替換為 '-'
     * 例如 /Users/samzhu/workspace/grimo-cli → -Users-samzhu-workspace-grimo-cli
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
| `SessionWriter` | `workspaceManager.root().resolve("projects")` | `projectContext.sessionsDir()` |
| `GrimoTuiRunner`（Splash） | `workspaceManager.root()` 顯示路徑 | `projectContext.displayPath()` |
| `GrimoTuiRunner`（Status Bar） | `workspaceManager.root()` 顯示路徑 | `projectContext.displayPath()` |
| `GrimoStartupRunner` | `workspaceManager.initialize()` | `grimoHome.initialize()` + `projectContext.initialize()` |

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
```

取代目前 `GrimoStartupRunner` 中的 `workspaceManager(GrimoProperties)` bean。

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

## 錯誤處理

- **目錄建立失敗**（權限不足）：`GrimoHome.initialize()` 和 `ProjectContext.initialize()` 拋出 `UncheckedIOException`，app 啟動時 fail-fast
- **CWD 不存在**：OS 保證 `user.dir` 有效，不額外處理

## 測試策略

| 測試目標 | 方式 |
|---------|------|
| `GrimoHome` | 單元測試：`@TempDir` 覆蓋 home 路徑，驗證 `initialize()` 建出正確目錄結構、各 `xxxDir()` 回傳正確路徑 |
| `ProjectContext` | 單元測試：注入假的 CWD 和 `GrimoHome`，驗證 `displayPath()` tilde 縮寫、`dataDir()` encoded path 正確 |
| 消費者遷移 | 既有測試改注入新的 `GrimoHome` / `ProjectContext`，確認行為不變 |

## Glossary 更新

新增：
- **GrimoHome** — `~/.grimo`，應用程式全域資料目錄，存放 config、skills、tasks、agents、logs
- **ProjectContext** — 啟動時的 CWD，代表目前操作的專案。專案資料存放在 `~/.grimo/projects/{encoded-cwd}/`

移除：
- **Workspace** 相關用法（如果有）

更新 glossary 佈局圖和 Status 區說明中的 `~/grimo-workspace` → 改為顯示專案路徑。
