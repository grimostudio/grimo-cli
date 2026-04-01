# 術語正名 + 模組提升：workspace → home + project + config

> Sub-project 1 of 4: TUI 重構系列。基礎性變更 — 消滅 workspace 術語、提升核心模組為 top-level、消除反向依賴。

## 目標

1. 消除 codebase 中殘留的 "workspace" 概念
2. 將 GrimoHome、ProjectContext、GrimoConfig 從 `shared/` 提升為獨立 top-level 模組
3. 消除 `shared → skill::loader` 反向依賴
4. 更新 glossary 和 CLAUDE.md

## 背景

### 已完成
前一輪重構（2026-03-31）已建立 `GrimoHome` + `ProjectContext`（取代 `WorkspaceManager`），但留在 `shared.workspace` package。

### 問題
1. **`shared/` 是 catch-all 反模式** — Spring Modulith [明確不鼓勵](https://docs.spring.io/spring-modulith/reference/fundamentals.html) shared/common 模組。目前 `shared/` 裝了 6 個不同關注點（config、event、workspace、session、tui、sandbox），其中 tui/sandbox/session 不是真正共用
2. **`shared → skill::loader` 反向依賴** — 基礎設施層依賴功能模組，違反依賴方向原則。`WorkspaceProvisioner.provision()` 的 `List<SkillDefinition>` 參數造成
3. **殘留的 "workspace" 術語** — 變數名、註解、glossary 散佈多處

### 架構願景

本次是 4 個 sub-project 的第一步，逐步消滅 `shared/` catch-all：

```
Sub-project 1（本次）：home/, project/, config/ 提升 + 術語正名 + 消除反向依賴
Sub-project 2：tui/ 模組化（TUI = Adapter，跟 channel 平行）
Sub-project 3：新 UI 組件設計
Sub-project 4：sandbox/, session/ 提升 + event 歸屬出版者 + 六角架構解耦
```

SP4 完成後 `shared/` 模組完全消滅，所有子包已升為獨立 top-level 模組。

## 設計

### 1. 模組提升：shared 子包 → top-level 模組

依據 Spring Modulith 原則：每個 top-level package 是一個獨立應用模組。只有真正跨模組共用的才留 shared — 但 config、home、project 各自是獨立關注點，應為獨立模組。

| Before | After | 內容 | 原因 |
|--------|-------|------|------|
| `shared.workspace.GrimoHome` | `home.GrimoHome` | 全域 app 資料 (~/.grimo) | 獨立關注點，所有模組間接使用 |
| `shared.workspace.ProjectContext` | `project.ProjectContext` | CWD 專案身份 | 獨立關注點，session + tui 使用 |
| `shared.config.GrimoConfig` | `config.GrimoConfig` | YAML 設定讀寫 | 獨立關注點，agent/mcp 直接 import |

> 設計說明：`ProjectContext` 的建構子依賴 `GrimoHome`（需要 `projectsDir()` 路徑）。提升後 `project/` 模組宣告 `allowedDependencies = { "home" }`。這是正常的基礎設施依賴，不是循環。

**檔案異動：**

```
刪除：
  src/main/java/.../shared/workspace/package-info.java
  src/main/java/.../shared/config/package-info.java

搬移 + 改 package：
  shared/workspace/GrimoHome.java      → home/GrimoHome.java
  shared/workspace/ProjectContext.java  → project/ProjectContext.java
  shared/config/GrimoConfig.java        → config/GrimoConfig.java

新建 package-info.java：
  home/package-info.java     → @ApplicationModule
  project/package-info.java  → @ApplicationModule(allowedDependencies = { "home" })
  config/package-info.java   → @ApplicationModule

搬移測試：
  test/.../shared/workspace/GrimoHomeTest.java     → test/.../home/GrimoHomeTest.java
  test/.../shared/workspace/ProjectContextTest.java → test/.../project/ProjectContextTest.java
```

### 2. Modulith allowedDependencies 全面更新

移除所有 `"shared::workspace"` 和 `"shared::config"`，替換為實際 import 的 top-level 模組。原則：**只宣告實際 import 的模組**（經 `grep` 驗證）。

完整的 `allowedDependencies` 陣列（含未變更的部分）：

| 模組 | 移除 | 新增 | 完整 After |
|------|------|------|-----------|
| `agent` | `shared::workspace`, `shared::config` | `config` | `shared, shared::event, shared::session, shared::tui, shared::sandbox, config, mcp, skill::registry` |
| `skill` | `shared::workspace`, `shared::config` | (無) | `shared, shared::event` |
| `task` | `shared::workspace`, `shared::config` | (無) | `shared, shared::event` |
| `mcp` | `shared::workspace`, `shared::config` | `config` | `shared, shared::event, config` |
| `channel` | `shared::workspace`, `shared::config` | (無) | `shared, shared::event` |
| `shared` | `skill::loader` | (無) | (無依賴) |
| `project` (new) | — | `home` | `home` |
| `home` (new) | — | — | (無依賴) |
| `config` (new) | — | — | (無依賴) |

> 設計說明：`skill`、`task`、`channel` 不直接 import `GrimoHome` 或 `GrimoConfig` — 它們透過 `@Bean` 注入 `Path` 物件取得路徑，不需要宣告這些依賴。`agent` 和 `mcp` 有直接 import `GrimoConfig` 的類別（經 grep 確認），所以加 `"config"`。

### 3. 消除 shared → skill::loader 反向依賴

**根因：** `WorkspaceProvisioner.provision()` 參數 `List<SkillDefinition> skills`，`SkillDefinition` 來自 `skill.loader` package。

**修正：** 改為 `List<String> skillNames`。`provisionSkills()` 內部只用 `skill.name()` 做 symlink，不需要完整 SkillDefinition。

```java
// Before
public WorktreeInfo provision(Path projectDir, String taskId, List<SkillDefinition> skills)

// After
public WorktreeInfo provision(Path projectDir, String taskId, List<String> skillNames)
```

**呼叫端（DevModeRunner.java）調整：**

```java
// Before
var worktree = worktreeProvisioner.provision(projectDir, taskId, skillRegistry.listAll());

// After
var skillNames = skillRegistry.listAll().stream().map(SkillDefinition::name).toList();
var worktree = worktreeProvisioner.provision(projectDir, taskId, skillNames);
```

**連帶清理 `shared/package-info.java`：**
- 移除 `allowedDependencies = { "skill::loader" }`
- `shared` 模組不再依賴任何功能模組

### 4. 類別重命名：WorkspaceProvisioner → WorktreeProvisioner

**位置：** `shared.sandbox` package（SP4 再搬遷為獨立 `sandbox/` 模組）

**改名理由：** Clean code — 類名描述它管理的資源（git worktree），不是廢棄的 "workspace" 概念。

**影響檔案：**

| 檔案 | 變更 |
|------|------|
| `shared/sandbox/WorkspaceProvisioner.java` | 改名 `WorktreeProvisioner.java`，更新類名、logger、JavaDoc |
| `shared/sandbox/WorkspaceProvisionerTest.java` | 改名 `WorktreeProvisionerTest.java`，更新類名、所有 `new WorkspaceProvisioner(...)` |
| `agent/DevModeRunner.java` | field/param `workspaceProvisioner` → `worktreeProvisioner` |
| `GrimoTuiRunner.java` | field/param `workspaceProvisioner` → `worktreeProvisioner` |
| `GrimoStartupRunner.java` | bean method `workspaceProvisioner()` → `worktreeProvisioner()` |
| `WorkspaceProvisioner.java:188` | log `"Cleaned up workspace:"` → `"Cleaned up worktree:"` |

### 5. 變數與參數正名

遵循 clean code — 變數名反映實際內容：

| 檔案 | Before | After |
|------|--------|-------|
| `GrimoTuiRunner.java:210,855` | `String workspacePath` | `String projectPath` |
| `GrimoTuiRunner.java:220,862` | `workspacePath` (方法呼叫參數) | `projectPath` |
| `GrimoTuiRunner.java:227` | `+ workspacePath` (status text) | `+ projectPath` |
| `BannerRenderer.java:43` | `@param workspacePath 工作目錄路徑` | `@param projectPath 當前專案路徑` |
| `BannerRenderer.java:52,80` | `String workspacePath` | `String projectPath` |

### 6. 註解與 JavaDoc 正名

| 檔案 | Before | After |
|------|--------|-------|
| `GrimoStatusView.java:14` | `agent/model/workspace/計數` | `agent/model/project/計數` |
| `GrimoStartupRunner.java:89` | `提供 workspace 下的 skills 目錄` | `提供 GrimoHome 的 skills 目錄` |
| `GrimoTuiRunner.java:179` | `Phase 1: Workspace 初始化` | `Phase 1: Home & Project 初始化` |
| `WorkspaceProvisioner.java:43` | `準備 agent 工作區` | `準備 agent worktree` |

### 7. 測試更新

| 測試檔案 | 變更 |
|----------|------|
| `GrimoHomeTest.java` | 搬至 `home/` package |
| `ProjectContextTest.java` | 搬至 `project/` package，更新 GrimoHome import |
| `WorkspaceProvisionerTest.java` | 改名 `WorktreeProvisionerTest.java`，改用 `List<String>` 參數 |
| `BannerRendererTest.java` | 測試資料 `"~/workspace/grimo-cli"` 不改（合法 CWD 路徑） |
| `ModulithStructureTest` | 驗證新模組結構通過 |

### 8. Glossary 更新

| 動作 | 條目 | 說明 |
|------|------|------|
| 更新 | `WorkspaceProvisioner` → `WorktreeProvisioner` | 名稱、英文、說明同步 |
| 更新 | 佈局圖 Status 區 | `~/workspace/grimo` → `~/projects/grimo`（範例） |
| 更新 | 佈局圖 Content 區 | `~/workspace/grimo-cli` → `~/projects/grimo-cli`（範例） |
| 更新 | 技術元件對應表 | `WorkspaceProvisioner` → `WorktreeProvisioner` |
| 新增 | 模組架構段落 | 說明 home/project/config 為獨立 top-level 模組 |

### 9. CLAUDE.md 同步

Architecture 表格更新：

```markdown
# Before
| `shared/` | Domain events, config loading, workspace path management |

# After（反映 SP1 完成後的狀態）
| `home/` | GrimoHome — 全域 app 資料目錄 (~/.grimo) 管理 |
| `project/` | ProjectContext — CWD 專案身份、per-project 資料目錄 |
| `config/` | GrimoConfig — YAML 設定讀寫 |
| `shared/` | Domain events, session persistence, TUI framework, sandbox (SP2/SP4 搬遷) |
```

## 不在範圍內

- TUI 組件命名重構（Sub-project 2）
- 新 UI 組件設計（Sub-project 3）
- 六角架構 + Event-driven 解耦（Sub-project 4）：sandbox/ 提升、session/ 提升、event 歸屬出版者
- `GrimoHome`、`ProjectContext`、`GrimoConfig` 類別本身的邏輯不改，只搬 package
- 歷史文件（`docs/superpowers/specs/`、`docs/superpowers/plans/`）不修改

## 風險與緩解

| 風險 | 緩解 |
|------|------|
| Top-level 模組提升影響所有 import | Compiler 報錯 + ModulithStructureTest 驗證 |
| `List<SkillDefinition>` → `List<String>` 改變 API | 只有 DevModeRunner 一個呼叫端，影響可控 |
| 新模組未正確宣告 @ApplicationModule | ModulithStructureTest 在 build 時驗證 |
| GrimoConfig 被多模組直接 import | 提升為 top-level 後各模組改宣告 `"config"` 依賴 |

## 驗收標準

1. `./gradlew build` 全部通過
2. `ModulithStructureTest` 通過（驗證新模組邊界）
3. `grep -r "shared.workspace\|shared.config\|shared::workspace\|shared::config" src/` 回傳空
4. `grep -r "WorkspaceProvisioner" src/` 回傳空
5. `shared/package-info.java` 的 `allowedDependencies` 不含 `"skill::loader"`
6. Glossary 中無 "workspace" 術語（路徑範例除外）
7. CLAUDE.md Architecture 表格反映新結構
