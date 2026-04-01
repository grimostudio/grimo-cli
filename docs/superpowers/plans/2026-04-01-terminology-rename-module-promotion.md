# 術語正名 + 模組提升 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消滅 workspace 術語、將 home/project/config 提升為 top-level 模組、消除 shared→skill 反向依賴。

**Architecture:** 將 `shared.workspace`（GrimoHome + ProjectContext）和 `shared.config`（GrimoConfig）從 shared 子包提升為獨立 top-level 模組。WorkspaceProvisioner 改名 WorktreeProvisioner 並改用 `List<String>` 消除對 skill::loader 的反向依賴。全程 TDD，每個 task 以 `ModulithStructureTest` 或目標測試驗證。

**Tech Stack:** Java 25, Spring Boot 4.0.x, Spring Modulith 2.0.x, Gradle, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-04-01-terminology-rename-workspace-to-project-design.md`

---

### Task 1: 建立 home/ top-level 模組（GrimoHome 搬遷）

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/home/package-info.java`
- Move: `src/main/java/io/github/samzhu/grimo/shared/workspace/GrimoHome.java` → `src/main/java/io/github/samzhu/grimo/home/GrimoHome.java`
- Move: `src/test/java/io/github/samzhu/grimo/shared/workspace/GrimoHomeTest.java` → `src/test/java/io/github/samzhu/grimo/home/GrimoHomeTest.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java` (import)
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` (import)

- [ ] **Step 1: 建立 home/ package-info.java**

```java
// src/main/java/io/github/samzhu/grimo/home/package-info.java
@org.springframework.modulith.ApplicationModule
package io.github.samzhu.grimo.home;
```

- [ ] **Step 2: 搬移 GrimoHome.java**

將 `src/main/java/io/github/samzhu/grimo/shared/workspace/GrimoHome.java` 搬到 `src/main/java/io/github/samzhu/grimo/home/GrimoHome.java`，更新 package 聲明：

```java
package io.github.samzhu.grimo.home;
```

- [ ] **Step 3: 搬移 GrimoHomeTest.java**

將 `src/test/java/io/github/samzhu/grimo/shared/workspace/GrimoHomeTest.java` 搬到 `src/test/java/io/github/samzhu/grimo/home/GrimoHomeTest.java`，更新 package 聲明：

```java
package io.github.samzhu.grimo.home;
```

- [ ] **Step 4: 更新 GrimoStartupRunner.java import**

```java
// Before
import io.github.samzhu.grimo.shared.workspace.GrimoHome;
// After
import io.github.samzhu.grimo.home.GrimoHome;
```

- [ ] **Step 5: 更新 GrimoTuiRunner.java import**

```java
// Before
import io.github.samzhu.grimo.shared.workspace.GrimoHome;
// After
import io.github.samzhu.grimo.home.GrimoHome;
```

- [ ] **Step 6: 執行 GrimoHomeTest 驗證搬遷正確**

Run: `./gradlew test --tests "io.github.samzhu.grimo.home.GrimoHomeTest"`
Expected: 6 tests PASS

- [ ] **Step 7: Commit**

```bash
git add -A src/main/java/io/github/samzhu/grimo/home/ \
         src/test/java/io/github/samzhu/grimo/home/ \
         src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java \
         src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git rm src/main/java/io/github/samzhu/grimo/shared/workspace/GrimoHome.java \
       src/test/java/io/github/samzhu/grimo/shared/workspace/GrimoHomeTest.java
git commit -m "refactor: promote GrimoHome to top-level home/ module"
```

---

### Task 2: 建立 project/ top-level 模組（ProjectContext 搬遷）

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/project/package-info.java`
- Move: `src/main/java/io/github/samzhu/grimo/shared/workspace/ProjectContext.java` → `src/main/java/io/github/samzhu/grimo/project/ProjectContext.java`
- Move: `src/test/java/io/github/samzhu/grimo/shared/workspace/ProjectContextTest.java` → `src/test/java/io/github/samzhu/grimo/project/ProjectContextTest.java`
- Delete: `src/main/java/io/github/samzhu/grimo/shared/workspace/package-info.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java` (import)
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` (import)

- [ ] **Step 1: 建立 project/ package-info.java**

```java
// src/main/java/io/github/samzhu/grimo/project/package-info.java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "home" }
)
package io.github.samzhu.grimo.project;
```

- [ ] **Step 2: 搬移 ProjectContext.java**

搬到 `src/main/java/io/github/samzhu/grimo/project/ProjectContext.java`，更新 package 和 import：

```java
package io.github.samzhu.grimo.project;

import io.github.samzhu.grimo.home.GrimoHome;
```

同時更新 `@see` 註解：
```java
// Before
@see GrimoHome 全域 app 資料管理
// After
@see io.github.samzhu.grimo.home.GrimoHome 全域 app 資料管理
```

- [ ] **Step 3: 搬移 ProjectContextTest.java**

搬到 `src/test/java/io/github/samzhu/grimo/project/ProjectContextTest.java`，更新 package 和 import：

```java
package io.github.samzhu.grimo.project;

import io.github.samzhu.grimo.home.GrimoHome;
```

- [ ] **Step 4: 刪除舊 shared/workspace/ package-info.java**

刪除 `src/main/java/io/github/samzhu/grimo/shared/workspace/package-info.java`（`@NamedInterface("workspace")` 不再需要）。

確認 `src/main/java/io/github/samzhu/grimo/shared/workspace/` 目錄已空，刪除目錄。

- [ ] **Step 5: 更新 GrimoStartupRunner.java import**

```java
// Before
import io.github.samzhu.grimo.shared.workspace.ProjectContext;
// After
import io.github.samzhu.grimo.project.ProjectContext;
```

- [ ] **Step 6: 更新 GrimoTuiRunner.java import**

```java
// Before
import io.github.samzhu.grimo.shared.workspace.ProjectContext;
// After
import io.github.samzhu.grimo.project.ProjectContext;
```

- [ ] **Step 7: 執行 ProjectContextTest 驗證**

Run: `./gradlew test --tests "io.github.samzhu.grimo.project.ProjectContextTest"`
Expected: 5 tests PASS

- [ ] **Step 8: Commit**

```bash
git add -A src/main/java/io/github/samzhu/grimo/project/ \
         src/test/java/io/github/samzhu/grimo/project/ \
         src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java \
         src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git rm -r src/main/java/io/github/samzhu/grimo/shared/workspace/ \
          src/test/java/io/github/samzhu/grimo/shared/workspace/
git commit -m "refactor: promote ProjectContext to top-level project/ module"
```

---

### Task 3: 建立 config/ top-level 模組（GrimoConfig 搬遷）

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/config/package-info.java`
- Move: `src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java` → `src/main/java/io/github/samzhu/grimo/config/GrimoConfig.java`
- Move: `src/test/java/io/github/samzhu/grimo/shared/config/GrimoConfigTest.java` → `src/test/java/io/github/samzhu/grimo/config/GrimoConfigTest.java`
- Delete: `src/main/java/io/github/samzhu/grimo/shared/config/package-info.java`
- Modify: 10 src files + 7 test files that import `shared.config.GrimoConfig` (listed below)

- [ ] **Step 1: 建立 config/ package-info.java**

```java
// src/main/java/io/github/samzhu/grimo/config/package-info.java
@org.springframework.modulith.ApplicationModule
package io.github.samzhu.grimo.config;
```

- [ ] **Step 2: 搬移 GrimoConfig.java**

搬到 `src/main/java/io/github/samzhu/grimo/config/GrimoConfig.java`，更新 package：

```java
package io.github.samzhu.grimo.config;
```

- [ ] **Step 3: 搬移 GrimoConfigTest.java**

搬到 `src/test/java/io/github/samzhu/grimo/config/GrimoConfigTest.java`，更新 package：

```java
package io.github.samzhu.grimo.config;
```

- [ ] **Step 4: 刪除舊 shared/config/ package-info.java 和目錄**

刪除 `src/main/java/io/github/samzhu/grimo/shared/config/package-info.java`。
確認目錄已空後刪除 `src/main/java/io/github/samzhu/grimo/shared/config/`。
同理刪除 `src/test/java/io/github/samzhu/grimo/shared/config/`（若已空）。

- [ ] **Step 5: 更新所有 import（17 個檔案）**

以下檔案的 `import io.github.samzhu.grimo.shared.config.GrimoConfig` 改為 `import io.github.samzhu.grimo.config.GrimoConfig`：

**src/main/java:**
1. `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`
2. `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`
3. `src/main/java/io/github/samzhu/grimo/mcp/McpCatalogBuilder.java`
4. `src/main/java/io/github/samzhu/grimo/mcp/McpCommands.java`
5. `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java`
6. `src/main/java/io/github/samzhu/grimo/agent/AgentConfiguration.java`
7. `src/main/java/io/github/samzhu/grimo/agent/router/AgentRouter.java`
8. `src/main/java/io/github/samzhu/grimo/agent/tier/TierCommands.java`
9. `src/main/java/io/github/samzhu/grimo/agent/tier/TierRouter.java`
10. `src/main/java/io/github/samzhu/grimo/agent/tier/TierConfiguration.java`

**src/test/java:**
11. `src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java`
12. `src/test/java/io/github/samzhu/grimo/agent/router/AgentRouterTest.java`
13. `src/test/java/io/github/samzhu/grimo/agent/tier/TierCommandsTest.java`
14. `src/test/java/io/github/samzhu/grimo/agent/tier/TierRouterTest.java`
15. `src/test/java/io/github/samzhu/grimo/agent/tier/TierIntegrationTest.java`
16. `src/test/java/io/github/samzhu/grimo/mcp/McpCommandsTest.java`
17. `src/test/java/io/github/samzhu/grimo/mcp/McpCatalogBuilderTest.java`

- [ ] **Step 6: 編譯驗證**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A src/main/java/io/github/samzhu/grimo/config/ \
         src/test/java/io/github/samzhu/grimo/config/
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java \
        src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java \
        src/main/java/io/github/samzhu/grimo/mcp/McpCatalogBuilder.java \
        src/main/java/io/github/samzhu/grimo/mcp/McpCommands.java \
        src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java \
        src/main/java/io/github/samzhu/grimo/agent/AgentConfiguration.java \
        src/main/java/io/github/samzhu/grimo/agent/router/AgentRouter.java \
        src/main/java/io/github/samzhu/grimo/agent/tier/TierCommands.java \
        src/main/java/io/github/samzhu/grimo/agent/tier/TierRouter.java \
        src/main/java/io/github/samzhu/grimo/agent/tier/TierConfiguration.java
git rm -r src/main/java/io/github/samzhu/grimo/shared/config/ \
          src/test/java/io/github/samzhu/grimo/shared/config/
git commit -m "refactor: promote GrimoConfig to top-level config/ module"
```

---

### Task 4: 更新所有 Modulith allowedDependencies

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/package-info.java`
- Modify: `src/main/java/io/github/samzhu/grimo/skill/package-info.java`
- Modify: `src/main/java/io/github/samzhu/grimo/task/package-info.java`
- Modify: `src/main/java/io/github/samzhu/grimo/mcp/package-info.java`
- Modify: `src/main/java/io/github/samzhu/grimo/channel/package-info.java`
- Modify: `src/main/java/io/github/samzhu/grimo/shared/package-info.java`

- [ ] **Step 1: 更新 agent/package-info.java**

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared", "shared::event", "shared::session", "shared::tui", "shared::sandbox",
        "config", "mcp", "skill::registry"
    }
)
package io.github.samzhu.grimo.agent;
```

- [ ] **Step 2: 更新 skill/package-info.java**

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "shared", "shared::event" }
)
package io.github.samzhu.grimo.skill;
```

- [ ] **Step 3: 更新 task/package-info.java**

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "shared", "shared::event" }
)
package io.github.samzhu.grimo.task;
```

- [ ] **Step 4: 更新 mcp/package-info.java**

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "shared", "shared::event", "config" }
)
package io.github.samzhu.grimo.mcp;
```

- [ ] **Step 5: 更新 channel/package-info.java**

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "shared", "shared::event" }
)
package io.github.samzhu.grimo.channel;
```

- [ ] **Step 6: 更新 shared/package-info.java — 移除反向依賴**

```java
// Before
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "skill::loader" }
)
package io.github.samzhu.grimo.shared;

// After
@org.springframework.modulith.ApplicationModule
package io.github.samzhu.grimo.shared;
```

- [ ] **Step 7: 執行 ModulithStructureTest**

Run: `./gradlew test --tests "*.ModulithStructureTest"`
Expected: PASS（若失敗，根據錯誤訊息調整 allowedDependencies）

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/package-info.java \
        src/main/java/io/github/samzhu/grimo/skill/package-info.java \
        src/main/java/io/github/samzhu/grimo/task/package-info.java \
        src/main/java/io/github/samzhu/grimo/mcp/package-info.java \
        src/main/java/io/github/samzhu/grimo/channel/package-info.java \
        src/main/java/io/github/samzhu/grimo/shared/package-info.java
git commit -m "refactor: update all Modulith allowedDependencies for new module structure"
```

---

### Task 5: 消除反向依賴 — WorkspaceProvisioner API 改 List\<String\>

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisioner.java`
- Modify: `src/main/java/io/github/samzhu/grimo/agent/DevModeRunner.java`
- Modify: `src/test/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisionerTest.java`

- [ ] **Step 1: 修改 WorkspaceProvisioner.provision() 參數**

`src/main/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisioner.java`:

```java
// 移除 import
// import io.github.samzhu.grimo.skill.loader.SkillDefinition;  ← 刪除此行

// provision() 方法簽名：
// Before
public WorktreeInfo provision(Path projectDir, String taskId, List<SkillDefinition> skills)
// After
public WorktreeInfo provision(Path projectDir, String taskId, List<String> skillNames)
```

- [ ] **Step 2: 修改 provisionSkills() 內部**

```java
// Before
private List<String> provisionSkills(Path targetDir, List<SkillDefinition> skills) {
    if (skills.isEmpty()) return List.of();
    ...
    for (var skill : skills) {
        Path sourceSkillDir = skillsSourceDir.resolve(skill.name());
        ...
        Path targetSkillDir = agentsSkillsDir.resolve(skill.name());
        ...
        provisioned.add(skill.name());
        log.debug("Symlinked skill: {} -> {}", skill.name(), sourceSkillDir);

// After
private List<String> provisionSkills(Path targetDir, List<String> skillNames) {
    if (skillNames.isEmpty()) return List.of();
    ...
    for (var name : skillNames) {
        Path sourceSkillDir = skillsSourceDir.resolve(name);
        ...
        Path targetSkillDir = agentsSkillsDir.resolve(name);
        ...
        provisioned.add(name);
        log.debug("Symlinked skill: {} -> {}", name, sourceSkillDir);
```

provision() 內部兩處呼叫也更新參數名稱：
```java
// Line 70
List<String> provisioned = provisionSkills(worktreeDir, skillNames);
// Line 81
List<String> provisioned = provisionSkills(projectDir, skillNames);
```

JavaDoc `@param skills` → `@param skillNames 要配置的 skill 名稱列表`。

- [ ] **Step 3: 修改 DevModeRunner.java 呼叫端**

`src/main/java/io/github/samzhu/grimo/agent/DevModeRunner.java` line 106-107:

```java
// Before
var worktree = workspaceProvisioner.provision(
        projectDir, taskId, skillRegistry.listAll());

// After
var skillNames = skillRegistry.listAll().stream()
        .map(io.github.samzhu.grimo.skill.loader.SkillDefinition::name).toList();
var worktree = workspaceProvisioner.provision(projectDir, taskId, skillNames);
```

- [ ] **Step 4: 修改 WorkspaceProvisionerTest.java**

移除 `import io.github.samzhu.grimo.skill.loader.SkillDefinition` 和 `import java.util.Map`。

刪除 `testSkill()` helper method（不再需要）。

所有測試中 `List.of(testSkill("code-review"))` 改為 `List.of("code-review")`：

```java
// Before（出現在 5 個測試方法中）
provisioner.provision(repo, "task-001", List.of(testSkill("code-review")));

// After
provisioner.provision(repo, "task-001", List.of("code-review"));
```

影響的測試方法（6 處）：
- `provisionShouldCreateWorktreeInGitRepo` (line 73)
- `cleanupShouldRemoveWorktreeAndDeleteBranchWhenNoChanges` (line 105)
- `provisionShouldFallbackToCwdForNonGitDir` (line 142)
- `cleanupFallbackShouldRemoveSymlinksOnly` (line 160)
- `provisionShouldSkipConflictingUserSkillInWorktree` (line 209)
- `cleanupShouldDeleteBranchWhenNoAgentChanges` (line 224)

- [ ] **Step 5: 執行 WorkspaceProvisionerTest**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.sandbox.WorkspaceProvisionerTest"`
Expected: 11 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisioner.java \
        src/main/java/io/github/samzhu/grimo/agent/DevModeRunner.java \
        src/test/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisionerTest.java
git commit -m "refactor: replace List<SkillDefinition> with List<String> in WorkspaceProvisioner

Eliminates shared → skill::loader reverse dependency. Only skill names
are needed for symlink provisioning."
```

---

### Task 6: 重命名 WorkspaceProvisioner → WorktreeProvisioner

**Files:**
- Rename: `src/main/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisioner.java` → `WorktreeProvisioner.java`
- Rename: `src/test/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisionerTest.java` → `WorktreeProvisionerTest.java`
- Modify: `src/main/java/io/github/samzhu/grimo/agent/DevModeRunner.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`

- [ ] **Step 1: 重命名 WorkspaceProvisioner.java**

`git mv` 到 `WorktreeProvisioner.java`，然後更新檔案內容：

- 類名 `WorkspaceProvisioner` → `WorktreeProvisioner`
- Logger: `LoggerFactory.getLogger(WorkspaceProvisioner.class)` → `LoggerFactory.getLogger(WorktreeProvisioner.class)`
- 建構子: `public WorkspaceProvisioner(` → `public WorktreeProvisioner(`
- JavaDoc: `準備 agent 工作區` → `準備 agent worktree`
- Log line 188: `"Cleaned up workspace: removed {} symlinks"` → `"Cleaned up worktree: removed {} symlinks"`

- [ ] **Step 2: 重命名 WorkspaceProvisionerTest.java**

`git mv` 到 `WorktreeProvisionerTest.java`，然後更新：

- 類名 `WorkspaceProvisionerTest` → `WorktreeProvisionerTest`
- 所有 `new WorkspaceProvisioner(` → `new WorktreeProvisioner(` (11 處)

- [ ] **Step 3: 更新 DevModeRunner.java**

```java
// import
import io.github.samzhu.grimo.shared.sandbox.WorktreeProvisioner;  // was WorkspaceProvisioner

// field (line 40)
private final WorktreeProvisioner worktreeProvisioner;  // was workspaceProvisioner

// constructor param (line 51)
public DevModeRunner(WorktreeProvisioner worktreeProvisioner,  // was WorkspaceProvisioner workspaceProvisioner

// constructor body (line 61)
this.worktreeProvisioner = worktreeProvisioner;

// usage (line 106)
var worktree = worktreeProvisioner.provision(  // was workspaceProvisioner

// cleanup calls (lines 135, 171)
worktreeProvisioner.cleanup(worktree, projectDir);  // was workspaceProvisioner
```

- [ ] **Step 4: 更新 GrimoTuiRunner.java**

```java
// import (line 14)
import io.github.samzhu.grimo.shared.sandbox.WorktreeProvisioner;

// field (line 94)
private final WorktreeProvisioner worktreeProvisioner;

// constructor param (line 145)
WorktreeProvisioner worktreeProvisioner,

// constructor body (line 168)
this.worktreeProvisioner = worktreeProvisioner;
```

- [ ] **Step 5: 更新 GrimoStartupRunner.java**

```java
// import (line 13)
import io.github.samzhu.grimo.shared.sandbox.WorktreeProvisioner;

// bean method (line 101-104)
@Bean
WorktreeProvisioner worktreeProvisioner(GrimoHome grimoHome, GitHelper gitHelper) {
    return new WorktreeProvisioner(grimoHome.skillsDir(), gitHelper);
}
```

注意：GrimoHome import 已在 Task 1 更新為 `io.github.samzhu.grimo.home.GrimoHome`。

- [ ] **Step 6: 執行 WorktreeProvisionerTest**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.sandbox.WorktreeProvisionerTest"`
Expected: 11 tests PASS

- [ ] **Step 7: Commit**

```bash
git add -A src/main/java/io/github/samzhu/grimo/shared/sandbox/ \
         src/test/java/io/github/samzhu/grimo/shared/sandbox/ \
         src/main/java/io/github/samzhu/grimo/agent/DevModeRunner.java \
         src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java \
         src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java
git commit -m "refactor: rename WorkspaceProvisioner → WorktreeProvisioner"
```

---

### Task 7: 變數與註解正名（workspace → project/worktree）

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`
- Modify: `src/main/java/io/github/samzhu/grimo/BannerRenderer.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStatusView.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`
- Modify: `src/main/java/io/github/samzhu/grimo/skill/loader/SkillLoader.java` (comment)
- Modify: `src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java` (comment)

- [ ] **Step 1: GrimoTuiRunner.java 變數正名**

Line 179 註解：
```java
// Before
// === Phase 1: Workspace 初始化 ===
// After
// === Phase 1: Home & Project 初始化 ===
```

Line 210:
```java
// Before
String workspacePath = projectContext.displayPath();
// After
String projectPath = projectContext.displayPath();
```

Line 220 和 227（使用新變數名 `projectPath`）：
```java
String bannerText = bannerRenderer.render(
        version, agentId, model, projectPath,
        ...);

String statusText = agentId + " · " + model + " │ " + projectPath
        + " │ " + ...;
```

Line 855:
```java
String projectPath = projectContext.displayPath();
```

Line 862:
```java
String newStatus = agentId + " · " + model + " │ " + projectPath
        + " │ " + ...;
```

- [ ] **Step 2: BannerRenderer.java 參數正名**

Line 43 JavaDoc：
```java
// Before
@param workspacePath 工作目錄路徑
// After
@param projectPath 當前專案路徑
```

Line 52 參數名：
```java
// Before
String workspacePath, int agentCount, ...
// After
String projectPath, int agentCount, ...
```

Line 80 使用：
```java
// Before
GRAY + workspacePath + RESET,
// After
GRAY + projectPath + RESET,
```

- [ ] **Step 3: GrimoStatusView.java 註解正名**

Line 14:
```java
// Before
* Status 區：顯示 agent/model/workspace/計數資訊（1 行）。
// After
* Status 區：顯示 agent/model/project/計數資訊（1 行）。
```

- [ ] **Step 4: GrimoStartupRunner.java 註解正名**

Line 89:
```java
// Before
* SkillCommands 需要注入 Path skillsDir，此 bean 提供 workspace 下的 skills 目錄路徑。
// After
* SkillCommands 需要注入 Path skillsDir，此 bean 提供 GrimoHome 的 skills 目錄路徑。
```

- [ ] **Step 5: SkillLoader.java 註解正名**

Line 19 (or nearby):
```java
// Before
從 workspace skills 目錄載入 SKILL.md
// After
從 ~/.grimo/skills 目錄載入 SKILL.md
```

- [ ] **Step 6: SkillCommands.java 註解正名**

Line 59 (or nearby):
```java
// Before
Installs a skill by cloning its Git repository into the workspace skills directory
// After
Installs a skill by cloning its Git repository into the ~/.grimo/skills directory
```

- [ ] **Step 7: 編譯驗證**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java \
        src/main/java/io/github/samzhu/grimo/BannerRenderer.java \
        src/main/java/io/github/samzhu/grimo/GrimoStatusView.java \
        src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java \
        src/main/java/io/github/samzhu/grimo/skill/loader/SkillLoader.java \
        src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java
git commit -m "refactor: rename workspacePath → projectPath, update comments"
```

---

### Task 8: 更新 Glossary 和 CLAUDE.md

**Files:**
- Modify: `docs/glossary.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: 更新 glossary.md 佈局圖**

Line 12: `█●██●█  ~/workspace/grimo-cli` → `█●██●█  ~/projects/grimo-cli`
Line 28: `~/workspace/grimo` → `~/projects/grimo`

- [ ] **Step 2: 更新 glossary.md WorkspaceProvisioner 條目**

在「調度系統術語」表中更新：

```markdown
| **WorktreeProvisioner** | Worktree Provisioner | 派遣 agent 前建立獨立 git worktree + 將 Grimo 管理的 Skill symlink 到 `.agents/skills/`（跨 agent 標準路徑），讓 CLI agent 原生發現。完成後清理 worktree、保留有變更的分支。位於 `shared.sandbox` package。 |
```

- [ ] **Step 3: 更新 glossary.md 技術元件對應表**

Line 138: `WorkspaceProvisioner` + `GitHelper` → `WorktreeProvisioner` + `GitHelper`

- [ ] **Step 4: 新增模組架構資訊到 glossary.md**

在「Session 與歷史」區段的 `GrimoHome` 和 `ProjectContext` 條目中更新 package 資訊：

GrimoHome 說明加上：`位於獨立 top-level 模組 `home/`。`
ProjectContext 說明加上：`位於獨立 top-level 模組 `project/`。`

- [ ] **Step 5: 更新 CLAUDE.md Architecture 表格**

找到 `shared/` 行並替換為：

```markdown
| `home/` | GrimoHome — 全域 app 資料目錄 (~/.grimo) 管理 |
| `project/` | ProjectContext — CWD 專案身份、per-project 資料目錄 |
| `config/` | GrimoConfig — YAML 設定讀寫 |
| `shared/` | Domain events, session persistence, TUI framework, sandbox |
```

- [ ] **Step 6: Commit**

```bash
git add docs/glossary.md CLAUDE.md
git commit -m "docs: update glossary and CLAUDE.md for new module structure"
```

---

### Task 9: 全量驗證

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 2: 驗證 package/import 殘留**

Run: `grep -r "shared\.workspace\|shared\.config\|shared::workspace\|shared::config" src/`
Expected: 無輸出

Run: `grep -r "WorkspaceProvisioner" src/`
Expected: 無輸出

Run: `grep -r "skill::loader" src/main/java/io/github/samzhu/grimo/shared/package-info.java`
Expected: 無輸出

- [ ] **Step 3: 廣泛搜尋 "workspace" 殘留**

Run: `grep -ri "workspace" src/main/java/ --include="*.java"`
Expected: 僅剩合法殘留（如 GrimoHome.java 歷史註解 "取代 WorkspaceManager"）。若發現變數名或術語殘留，修正。

Run: `grep -i "workspace" docs/glossary.md`
Expected: 只剩路徑範例中的合法 occurrence（如果有的話）

- [ ] **Step 4: Commit（若有遺漏修正）**

若前述驗證發現遺漏，修正後 commit：
```bash
git add -A
git commit -m "fix: address remaining workspace references"
```
