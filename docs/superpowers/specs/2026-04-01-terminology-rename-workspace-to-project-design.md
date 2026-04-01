# 術語正名：workspace → home + project

> Sub-project 1 of 4: TUI 重構系列。基礎性變更，其他 sub-project 建立在正確術語之上。

## 目標

消除 codebase 中殘留的 "workspace" 概念，正名為語意精確的 `home`（全域 app 資料）與 `project`（CWD 專案身份），並同步更新 glossary。

## 背景

前一輪重構（2026-03-31）已完成核心轉換：
- `WorkspaceManager` → `GrimoHome` + `ProjectContext`（新類別已建立）
- `GrimoProperties` 移除，`~/.grimo` 路徑固定

但殘留以下問題：
1. `GrimoHome` 和 `ProjectContext` 仍共存於 `shared.workspace` package — 語意不精確
2. `WorkspaceProvisioner`（`shared.sandbox`）名稱描述的是廢棄概念，而非它實際做的事
3. 變數名 `workspacePath`、`workspaceProvisioner` 散佈多個檔案
4. Modulith `allowedDependencies` 全部指向 `"shared::workspace"`
5. Glossary 的 layout 圖和術語條目未完全更新

## 設計

### 1. Package 拆分

將 `shared.workspace` 拆為兩個語意明確的 package：

| Before | After | 內容 | NamedInterface |
|--------|-------|------|----------------|
| `shared.workspace` | `shared.home` | `GrimoHome` | `"home"` |
| | `shared.project` | `ProjectContext` | `"project"` |

**檔案異動：**

```
src/main/java/io/github/samzhu/grimo/shared/workspace/
  ├── GrimoHome.java           → shared/home/GrimoHome.java
  ├── ProjectContext.java       → shared/project/ProjectContext.java
  └── package-info.java         → 刪除

src/main/java/io/github/samzhu/grimo/shared/home/
  └── package-info.java         → 新建（@NamedInterface("home")）

src/main/java/io/github/samzhu/grimo/shared/project/
  └── package-info.java         → 新建（@NamedInterface("project")）

src/test/java/io/github/samzhu/grimo/shared/workspace/
  ├── GrimoHomeTest.java        → shared/home/GrimoHomeTest.java
  └── ProjectContextTest.java   → shared/project/ProjectContextTest.java
```

**package 聲明與 import 更新：**
- `GrimoHome.java`: `package ...shared.home;`
- `ProjectContext.java`: `package ...shared.project;`
- `GrimoTuiRunner.java`: import 改為 `shared.home.GrimoHome` + `shared.project.ProjectContext`
- `GrimoStartupRunner.java`: 同上

### 2. Modulith allowedDependencies 更新

目前 5 個模組宣告 `"shared::workspace"`。經查，**沒有任何模組的 Java 類別直接 import `shared.workspace`**（只有 root package 的 `GrimoTuiRunner` 和 `GrimoStartupRunner` 使用）。

但為了模組邊界正確性和未來擴展，按實際依賴精確宣告：

| 模組 | Before | After | 原因 |
|------|--------|-------|------|
| `agent` | `"shared::workspace"` | `"shared::home"` | DevModeRunner 透過 bean 間接使用 GrimoHome 路徑 |
| `skill` | `"shared::workspace"` | `"shared::home"` | SkillLoader/SkillCommands 使用 skillsDir（來自 GrimoHome） |
| `task` | `"shared::workspace"` | `"shared::home"` | MarkdownTaskStore 使用 tasksDir（來自 GrimoHome） |
| `mcp` | `"shared::workspace"` | `"shared::home"` | McpCatalogBuilder 間接使用 config（來自 GrimoHome） |
| `channel` | `"shared::workspace"` | `"shared::home"` | 保留備用（channel adapter 可能需 home 路徑） |

> 設計說明：目前各模組透過 `@Bean` 注入 `Path` 物件（如 `skillsDir`、`tasksDir`），不直接 import `GrimoHome`。但 Modulith 邊界宣告的是「允許依賴」而非「目前已依賴」，保留 `"shared::home"` 確保未來可直接 import。若模組未來需要 `ProjectContext`，屆時加 `"shared::project"`。

### 3. 類別重命名：WorkspaceProvisioner → WorktreeProvisioner

**位置：** `shared.sandbox` package（不動）

**改名理由：** Clean code — 類名應描述它管理的資源。它建立/清理 git worktree，不是 "workspace"。在 `sandbox` package 下語意已經清楚。

**影響檔案：**

| 檔案 | 變更 |
|------|------|
| `shared/sandbox/WorkspaceProvisioner.java` | 重命名為 `WorktreeProvisioner.java`，更新類名、logger、JavaDoc |
| `shared/sandbox/WorkspaceProvisionerTest.java` | 重命名為 `WorktreeProvisionerTest.java`，更新類名 |
| `agent/DevModeRunner.java` | field `workspaceProvisioner` → `worktreeProvisioner`，constructor 同步 |
| `GrimoTuiRunner.java` | field `workspaceProvisioner` → `worktreeProvisioner`，constructor 同步 |
| `GrimoStartupRunner.java` | bean name `workspaceProvisioner()` → `worktreeProvisioner()`，更新註解 |
| `docs/glossary.md` | `WorkspaceProvisioner` 條目更新為 `WorktreeProvisioner` |

**內部 log 訊息更新：**
- `WorkspaceProvisioner.java:188` — `"Cleaned up workspace: removed {} symlinks"` → `"Cleaned up worktree: removed {} symlinks"`

### 4. 變數與參數正名

遵循 clean code 精神，變數名反映其實際內容：

| 檔案 | Before | After |
|------|--------|-------|
| `GrimoTuiRunner.java:210` | `String workspacePath = projectContext.displayPath()` | `String projectPath = ...` |
| `GrimoTuiRunner.java:220` | `..., workspacePath, ...` | `..., projectPath, ...` |
| `GrimoTuiRunner.java:227` | `... + workspacePath` | `... + projectPath` |
| `GrimoTuiRunner.java:855` | `String workspacePath = projectContext.displayPath()` | `String projectPath = ...` |
| `GrimoTuiRunner.java:862` | `... + workspacePath` | `... + projectPath` |
| `BannerRenderer.java:43` | `@param workspacePath 工作目錄路徑` | `@param projectPath 當前專案路徑` |
| `BannerRenderer.java:52` | `String workspacePath` | `String projectPath` |
| `BannerRenderer.java:80` | `GRAY + workspacePath + RESET` | `GRAY + projectPath + RESET` |

### 5. 註解與 JavaDoc 正名

| 檔案:行 | Before | After |
|---------|--------|-------|
| `GrimoStatusView.java:14` | `agent/model/workspace/計數資訊` | `agent/model/project/計數資訊` |
| `GrimoStartupRunner.java:89` | `此 bean 提供 workspace 下的 skills 目錄路徑` | `此 bean 提供 GrimoHome 的 skills 目錄路徑` |
| `GrimoTuiRunner.java:179` | `Phase 1: Workspace 初始化` | `Phase 1: Home & Project 初始化` |
| `WorkspaceProvisioner.java:43` | `準備 agent 工作區` | `準備 agent worktree` |

### 6. 測試更新

| 測試檔案 | 變更 |
|----------|------|
| `GrimoHomeTest.java` | 搬至 `shared/home/` package，更新 package 聲明 |
| `ProjectContextTest.java` | 搬至 `shared/project/` package，更新 package 聲明 |
| `WorkspaceProvisionerTest.java` | 重命名為 `WorktreeProvisionerTest.java`，更新所有 `new WorkspaceProvisioner(...)` → `new WorktreeProvisioner(...)` |
| `BannerRendererTest.java:12` | 測試資料 `"~/workspace/grimo-cli"` 不改（這是合法的 CWD 路徑，不是 workspace 術語） |
| `BannerRendererTest.java:16` | 同上（assertion 測的是 path 顯示，不是術語） |
| `ModulithStructureTest` | 確認通過（package 結構變更後 Modulith 驗證） |

### 7. Glossary 更新

**移除/更新條目：**

| 動作 | 條目 | 說明 |
|------|------|------|
| 更新 | `WorkspaceProvisioner` → `WorktreeProvisioner` | 名稱、英文、說明同步更新 |
| 更新 | 佈局圖 Status 區 | `│ ~/workspace/grimo │` → `│ ~/projects/grimo │`（範例路徑） |
| 更新 | 佈局圖 Content 區 | `█●██●█  ~/workspace/grimo-cli` → `█●██●█  ~/projects/grimo-cli`（範例路徑） |
| 更新 | 技術元件對應表 | `WorkspaceProvisioner` → `WorktreeProvisioner` |
| 保留 | `GrimoHome`、`ProjectContext`、`Session` 等 | 已在前輪更新，無需再改 |

**新增條目（調度系統術語表）：**

| 名詞 | 英文 | 說明 |
|------|------|------|
| **WorktreeProvisioner** | Worktree Provisioner | 派遣 agent 前建立獨立 git worktree + 將 Grimo 管理的 Skill symlink 到 `.agents/skills/`。完成後清理 worktree、保留有變更的分支。位於 `shared.sandbox` package。 |

### 8. CLAUDE.md 同步

`CLAUDE.md` 的「Architecture」表格中若有 `WorkspaceManager` 或 `workspace` 字眼，需同步更新。經查，前輪已移除 `WorkspaceManager` 引用，但 `shared/` 的 Purpose 描述需確認是否提及 workspace path management。

## 不在範圍內

- TUI 組件命名重構（Sub-project 2）
- 新 UI 組件設計（Sub-project 3）
- 六角架構 + Event-driven 解耦（Sub-project 4）
- `shared.workspace` package 下的 `GrimoHome`、`ProjectContext` 類別本身的邏輯不改，只搬 package
- 文件 `docs/superpowers/specs/` 和 `docs/superpowers/plans/` 內的歷史文件不修改（描述的是遷移過程）

## 風險與緩解

| 風險 | 緩解 |
|------|------|
| Package rename 導致 import 遺漏 | IDE/compiler 會報錯；ModulithStructureTest 驗證邊界 |
| Bean name 改變導致注入失敗 | Spring by-type injection，bean name 不影響注入 |
| 外部工具引用舊 package | 目前無外部消費者，codebase 封閉 |

## 驗收標準

1. `./gradlew build` 通過（含所有既有測試）
2. `ModulithStructureTest` 通過
3. `grep -r "workspace" src/` 只剩以下合法殘留：
   - 測試資料中的檔案路徑（如 `~/workspace/grimo-cli` — 合法 CWD）
   - `shared.sandbox` package 中 `WorktreeProvisioner` 的 JavaDoc 可能引用 worktree 概念
4. Glossary 中無 "workspace" 術語（路徑範例除外）
5. `git diff --stat` 確認無遺漏檔案
