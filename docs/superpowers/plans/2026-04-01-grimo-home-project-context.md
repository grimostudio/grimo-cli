# GrimoHome + ProjectContext 實作計劃

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 拆分 `WorkspaceManager` 為 `GrimoHome`（全域 app 資料）+ `ProjectContext`（CWD 專案上下文），狀態列顯示專案路徑而非 `~/.grimo`。

**Architecture:** `GrimoHome` 固定指向 `~/.grimo`，提供全域目錄（config、skills、tasks、agents、logs、projects）。`ProjectContext` 由 CWD 決定，提供專案層級目錄（session files、dispatch records）。兩者皆為 Spring Bean。`SessionWriter` 改為 Bean 共享，dispatch 持久化透過 `@EventListener` 解耦。

**Tech Stack:** Java 25, Spring Boot 4.0.x, Spring Modulith 2.0.x, JUnit 5, AssertJ, Jackson (`ObjectMapper`)

**Spec:** `docs/superpowers/specs/2026-03-31-workspace-to-grimo-home-project-context-design.md`

---

## File Structure

### 新建

| File | 職責 |
|------|------|
| `src/main/java/.../shared/workspace/GrimoHome.java` | 全域 `~/.grimo` 目錄管理 |
| `src/main/java/.../shared/workspace/ProjectContext.java` | CWD 專案上下文 + 專案資料目錄 |
| `src/main/java/.../shared/session/SessionEventListener.java` | `@EventListener` 監聽 DevMode events → 寫入 dispatch 紀錄 |
| `src/test/java/.../shared/workspace/GrimoHomeTest.java` | GrimoHome 單元測試 |
| `src/test/java/.../shared/workspace/ProjectContextTest.java` | ProjectContext 單元測試 |
| `src/test/java/.../shared/session/SessionEventListenerTest.java` | SessionEventListener 單元測試 |

### 修改

| File | 改動範圍 |
|------|---------|
| `src/main/java/.../GrimoStartupRunner.java` | Bean 定義：移除 `WorkspaceManager` bean，新增 `GrimoHome`、`ProjectContext`、`SessionWriter` bean；移除 `@EnableConfigurationProperties(GrimoProperties.class)` |
| `src/main/java/.../GrimoTuiRunner.java` | 建構子參數 `WorkspaceManager` → `ProjectContext`；移除 session 手動建構；status bar / splash 顯示 `projectContext.displayPath()` |
| `src/main/java/.../shared/session/SessionWriter.java` | 建構子參數改為接收 `dataDir`（session 直接放 dataDir 根目錄）；新增 `writeDispatchEntered()` / `writeDispatchCompleted()` + meta.json 寫入 |
| `src/main/java/.../shared/event/DevModeEnteredEvent.java` | 擴充欄位：`taskId`, `agent`, `model`, `tier`, `goal` |
| `src/main/java/.../shared/event/DevModeCompletedEvent.java` | 擴充欄位：`taskId`, `externalSessionPath` |
| `src/main/java/.../agent/DevModeRunner.java` | 發布 event 時傳入新增欄位 |
| `src/main/resources/application.yaml` | 移除 `grimo.workspace` |
| `src/test/java/.../shared/workspace/WorkspaceManagerTest.java` | 重命名為 `GrimoHomeTest.java`（內容重寫） |
| `docs/glossary.md` | 新增 GrimoHome / ProjectContext / Dispatch 紀錄詞條，更新佈局圖 |

### 刪除

| File | 理由 |
|------|------|
| `src/main/java/.../shared/workspace/WorkspaceManager.java` | 功能拆分到 GrimoHome + ProjectContext |
| `src/main/java/.../shared/config/GrimoProperties.java` | `workspace` 是唯一欄位，路徑固定後不再需要 |

---

## Task 1: GrimoHome — 建立全域目錄管理

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/workspace/GrimoHome.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/workspace/GrimoHomeTest.java`

- [ ] **Step 1: 寫 GrimoHome 的失敗測試**

```java
package io.github.samzhu.grimo.shared.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GrimoHomeTest {

    @TempDir
    Path tempDir;

    @Test
    void initializeShouldCreateRequiredDirectories() {
        var home = new GrimoHome(tempDir);
        home.initialize();

        assertThat(tempDir.resolve("tasks")).isDirectory();
        assertThat(tempDir.resolve("skills")).isDirectory();
        assertThat(tempDir.resolve("agents")).isDirectory();
        assertThat(tempDir.resolve("logs")).isDirectory();
        assertThat(tempDir.resolve("projects")).isDirectory();
        assertThat(tempDir.resolve("config.yaml")).isRegularFile();
    }

    @Test
    void initializeShouldNotCreateConversationsDir() {
        var home = new GrimoHome(tempDir);
        home.initialize();
        // conversations 已棄用，不再建立
        assertThat(tempDir.resolve("conversations")).doesNotExist();
    }

    @Test
    void initializeShouldNotOverwriteExistingConfig() throws Exception {
        var home = new GrimoHome(tempDir);
        Files.writeString(tempDir.resolve("config.yaml"), "custom: true\n");
        home.initialize();
        assertThat(tempDir.resolve("config.yaml")).content().contains("custom: true");
    }

    @Test
    void shouldReturnCorrectSubPaths() {
        var home = new GrimoHome(tempDir);
        assertThat(home.root()).isEqualTo(tempDir);
        assertThat(home.configFile()).isEqualTo(tempDir.resolve("config.yaml"));
        assertThat(home.skillsDir()).isEqualTo(tempDir.resolve("skills"));
        assertThat(home.tasksDir()).isEqualTo(tempDir.resolve("tasks"));
        assertThat(home.agentsDir()).isEqualTo(tempDir.resolve("agents"));
        assertThat(home.logsDir()).isEqualTo(tempDir.resolve("logs"));
        assertThat(home.projectsDir()).isEqualTo(tempDir.resolve("projects"));
    }

    @Test
    void isInitializedShouldReturnFalseForEmptyDir() {
        assertThat(new GrimoHome(tempDir).isInitialized()).isFalse();
    }

    @Test
    void isInitializedShouldReturnTrueAfterInit() {
        var home = new GrimoHome(tempDir);
        home.initialize();
        assertThat(home.isInitialized()).isTrue();
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.workspace.GrimoHomeTest" -x nativeTest`
Expected: FAIL — `GrimoHome` 類別不存在

- [ ] **Step 3: 實作 GrimoHome**

從 `WorkspaceManager.java` 複製並修改。關鍵差異：
- 無參建構子固定路徑 `Path.of(System.getProperty("user.home"), ".grimo")`
- `initialize()` 不建立 `conversationsDir()`，新增 `projectsDir()`
- 移除 `conversationsDir()` 方法，新增 `projectsDir()` 方法

```java
package io.github.samzhu.grimo.shared.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Grimo 全域 app 資料目錄管理。
 *
 * 設計說明：
 * - 路徑固定為 ~/.grimo，不需要外部配置
 * - 提供全域目錄（config、skills、tasks、agents、logs、projects）
 * - 啟動時 initialize() 建立目錄結構和預設 config.yaml
 * - conversations/ 已棄用，不再建立（既有資料保留不刪除）
 *
 * @see ProjectContext 專案層級資料管理
 */
public class GrimoHome {

    private final Path root;

    /**
     * 生產環境建構子：固定指向 ~/.grimo。
     */
    public GrimoHome() {
        this(Path.of(System.getProperty("user.home"), ".grimo"));
    }

    /**
     * 測試用建構子：接受自訂路徑。
     */
    public GrimoHome(Path root) {
        this.root = root;
    }

    /**
     * 初始化：建立所有必要目錄，並在 config.yaml 不存在時建立帶範例的預設檔。
     */
    public void initialize() {
        createDir(tasksDir());
        createDir(skillsDir());
        createDir(logsDir());
        createDir(agentsDir());
        createDir(projectsDir());
        createDefaultConfig();
    }

    public boolean isInitialized() {
        return Files.isDirectory(tasksDir())
            && Files.isDirectory(skillsDir());
    }

    public Path root()         { return root; }
    public Path configFile()   { return root.resolve("config.yaml"); }
    public Path skillsDir()    { return root.resolve("skills"); }
    public Path tasksDir()     { return root.resolve("tasks"); }
    public Path agentsDir()    { return root.resolve("agents"); }
    public Path logsDir()      { return root.resolve("logs"); }
    public Path projectsDir()  { return root.resolve("projects"); }

    private void createDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create directory: " + dir, e);
        }
    }

    private void createDefaultConfig() {
        Path config = configFile();
        if (Files.exists(config)) {
            return;
        }
        try {
            Files.writeString(config, DEFAULT_CONFIG);
        } catch (IOException e) {
            // config 建立失敗不中斷啟動，使用者可以手動建立
        }
    }

    // DEFAULT_CONFIG 常數：從 WorkspaceManager 搬過來（完全相同內容）
    private static final String DEFAULT_CONFIG = """
            # Grimo CLI 設定檔
            ... (搬移 WorkspaceManager.DEFAULT_CONFIG 的完整內容)
            """;
}
```

> **注意**：`DEFAULT_CONFIG` 的完整內容從現有 `WorkspaceManager.java:44-121` 原封不動搬過來。

- [ ] **Step 4: 執行測試確認通過**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.workspace.GrimoHomeTest" -x nativeTest`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/workspace/GrimoHome.java \
        src/test/java/io/github/samzhu/grimo/shared/workspace/GrimoHomeTest.java
git commit -m "feat: add GrimoHome — fixed ~/.grimo global directory manager"
```

---

## Task 2: ProjectContext — 建立 CWD 專案上下文

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/workspace/ProjectContext.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/workspace/ProjectContextTest.java`

- [ ] **Step 1: 寫失敗測試**

```java
package io.github.samzhu.grimo.shared.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectContextTest {

    @TempDir
    Path tempHome;

    @Test
    void dataDirShouldBeUnderProjectsWithEncodedCwd() {
        var home = new GrimoHome(tempHome);
        var cwd = Path.of("/Users/samzhu/workspace/grimo-cli");
        var ctx = new ProjectContext(home, cwd);

        assertThat(ctx.dataDir()).isEqualTo(
            tempHome.resolve("projects").resolve("-Users-samzhu-workspace-grimo-cli"));
    }

    @Test
    void displayPathShouldReplacHomeWithTilde() {
        var home = new GrimoHome(tempHome);
        // 模擬 CWD 在 user.home 下
        var userHome = System.getProperty("user.home");
        var cwd = Path.of(userHome, "workspace", "grimo-cli");
        var ctx = new ProjectContext(home, cwd);

        assertThat(ctx.displayPath()).isEqualTo("~/workspace/grimo-cli");
    }

    @Test
    void displayPathShouldReturnFullPathWhenNotUnderHome() {
        var home = new GrimoHome(tempHome);
        var cwd = Path.of("/opt/projects/grimo");
        var ctx = new ProjectContext(home, cwd);

        assertThat(ctx.displayPath()).isEqualTo("/opt/projects/grimo");
    }

    @Test
    void pathShouldReturnOriginalCwd() {
        var home = new GrimoHome(tempHome);
        var cwd = Path.of("/Users/samzhu/workspace/grimo-cli");
        var ctx = new ProjectContext(home, cwd);

        assertThat(ctx.path()).isEqualTo(cwd);
    }

    @Test
    void initializeShouldCreateDataDir() {
        var home = new GrimoHome(tempHome);
        home.initialize();
        var cwd = Path.of("/Users/samzhu/workspace/grimo-cli");
        var ctx = new ProjectContext(home, cwd);

        ctx.initialize();

        assertThat(ctx.dataDir()).isDirectory();
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.workspace.ProjectContextTest" -x nativeTest`
Expected: FAIL — `ProjectContext` 類別不存在

- [ ] **Step 3: 實作 ProjectContext**

```java
package io.github.samzhu.grimo.shared.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 專案上下文：由啟動時的 CWD 決定。
 *
 * 設計說明：
 * - CWD 代表開發者目前操作的專案
 * - 專案資料（session、dispatch）存放在 ~/.grimo/projects/{encoded-cwd}/
 * - 對齊 Claude Code 的 projects/ 結構
 * - encoded-cwd 用 replaceAll("[^a-zA-Z0-9]", "-")，例如
 *   /Users/samzhu/workspace/grimo-cli → -Users-samzhu-workspace-grimo-cli
 *
 * @see GrimoHome 全域 app 資料管理
 */
public class ProjectContext {

    private final Path projectPath;
    private final Path dataDir;

    /**
     * 生產環境建構子：CWD 從 System property 取得。
     */
    public ProjectContext(GrimoHome grimoHome) {
        this(grimoHome, Path.of(System.getProperty("user.dir")));
    }

    /**
     * 測試用建構子：接受自訂 CWD。
     */
    public ProjectContext(GrimoHome grimoHome, Path cwd) {
        this.projectPath = cwd;
        this.dataDir = grimoHome.projectsDir().resolve(encodePath(cwd));
    }

    public void initialize() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create project data dir: " + dataDir, e);
        }
    }

    public Path path() { return projectPath; }

    public String displayPath() {
        return projectPath.toString()
                .replace(System.getProperty("user.home"), "~");
    }

    public Path dataDir() { return dataDir; }

    /**
     * 沿用既有編碼方式：非英數字元替換為 '-'。
     * 對齊 Claude Code 的 projects/ 編碼慣例。
     */
    private String encodePath(Path p) {
        return p.toString().replaceAll("[^a-zA-Z0-9]", "-");
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.workspace.ProjectContextTest" -x nativeTest`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/workspace/ProjectContext.java \
        src/test/java/io/github/samzhu/grimo/shared/workspace/ProjectContextTest.java
git commit -m "feat: add ProjectContext — CWD-based project identity"
```

---

## Task 3: Bean 註冊遷移 + 移除 WorkspaceManager

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`
- Modify: `src/main/resources/application.yaml`
- Delete: `src/main/java/io/github/samzhu/grimo/shared/workspace/WorkspaceManager.java`
- Delete: `src/main/java/io/github/samzhu/grimo/shared/config/GrimoProperties.java`
- Delete: `src/test/java/io/github/samzhu/grimo/shared/workspace/WorkspaceManagerTest.java`

- [ ] **Step 1: 修改 GrimoStartupRunner — 替換所有 bean 定義**

重點改動（`GrimoStartupRunner.java`）：

1. **移除** import:
   - `io.github.samzhu.grimo.shared.config.GrimoProperties`
   - `io.github.samzhu.grimo.shared.workspace.WorkspaceManager`

2. **新增** import:
   - `io.github.samzhu.grimo.shared.workspace.GrimoHome`
   - `io.github.samzhu.grimo.shared.workspace.ProjectContext`
   - `io.github.samzhu.grimo.shared.session.SessionWriter`

3. **移除** `@EnableConfigurationProperties(GrimoProperties.class)`（line 41）

4. **替換 bean 定義**（原 lines 47-105）：

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

@Bean
GrimoConfig grimoConfig(GrimoHome grimoHome) {
    return new GrimoConfig(grimoHome.configFile());
}

@Bean
SkillLoader skillLoader(GrimoHome grimoHome) {
    return new SkillLoader(grimoHome.skillsDir());
}

@Bean
Path skillsDir(GrimoHome grimoHome) {
    return grimoHome.skillsDir();
}

@Bean
WorkspaceProvisioner workspaceProvisioner(GrimoHome grimoHome, GitHelper gitHelper) {
    return new WorkspaceProvisioner(grimoHome.skillsDir(), gitHelper);
}

@Bean
MarkdownTaskStore markdownTaskStore(GrimoHome grimoHome) {
    return new MarkdownTaskStore(grimoHome.tasksDir());
}
```

- [ ] **Step 2: 移除 application.yaml 的 grimo.workspace 配置**

`src/main/resources/application.yaml` — 找到以下內容並移除：
```yaml
grimo:
  workspace: ${user.home}/.grimo
```

> 如果 `grimo:` 底下還有其他屬性，只移除 `workspace` 行。如果 `workspace` 是唯一屬性，移除整個 `grimo:` 區塊。

- [ ] **Step 3: 刪除 WorkspaceManager 和 GrimoProperties**

```bash
rm src/main/java/io/github/samzhu/grimo/shared/workspace/WorkspaceManager.java
rm src/main/java/io/github/samzhu/grimo/shared/config/GrimoProperties.java
rm src/test/java/io/github/samzhu/grimo/shared/workspace/WorkspaceManagerTest.java
```

- [ ] **Step 4: 編譯確認沒有遺漏引用**

Run: `./gradlew compileJava 2>&1 | head -50`
Expected: 如果有其他檔案引用 WorkspaceManager 或 GrimoProperties，這裡會報錯。逐一修復。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: replace WorkspaceManager with GrimoHome + ProjectContext beans"
```

---

## Task 4: GrimoTuiRunner 遷移

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`

- [ ] **Step 1: 替換建構子參數**

`GrimoTuiRunner.java` 修改：

1. **import 替換**：
   - 移除: `import io.github.samzhu.grimo.shared.workspace.WorkspaceManager;`
   - 新增: `import io.github.samzhu.grimo.shared.workspace.ProjectContext;`
   - 新增: `import io.github.samzhu.grimo.shared.workspace.GrimoHome;`

2. **欄位替換**（line 79）：
   - 移除: `private final WorkspaceManager workspaceManager;`
   - 新增: `private final GrimoHome grimoHome;`
   - 新增: `private final ProjectContext projectContext;`

3. **建構子參數**（line 128）：
   - 移除: `WorkspaceManager workspaceManager,`
   - 新增: `GrimoHome grimoHome,`
   - 新增: `ProjectContext projectContext,`
   - 新增: `SessionWriter sessionWriter,`（bean 注入）
   - 對應 `this.` 賦值修改

4. **`sessionWriter` 欄位**（line 122）改為 `final`（由建構子注入）

- [ ] **Step 2: 修改 run() 方法中的 workspace 相關程式碼**

**Phase 1: 初始化**（lines 174-176）：
```java
// Before:
if (!workspaceManager.isInitialized()) {
    workspaceManager.initialize();
}

// After:
if (!grimoHome.isInitialized()) {
    grimoHome.initialize();
}
projectContext.initialize();
```

**Phase 3: 顯示路徑**（lines 203-204）：
```java
// Before:
String workspacePath = workspaceManager.root().toString()
        .replace(System.getProperty("user.home"), "~");

// After:
String workspacePath = projectContext.displayPath();
```

**Session 建構**（lines 236-241）：完全移除手動建構邏輯，改用注入的 `sessionWriter`：
```java
// Before:
String cwd = System.getProperty("user.dir");
String encodedCwd = cwd.replaceAll("[^a-zA-Z0-9]", "-");
var sessionsDir = workspaceManager.root().resolve("projects").resolve(encodedCwd).resolve("sessions");
sessionWriter = new SessionWriter(sessionsDir);
sessionWriter.writeSystemMessage(cwd, version, "Grimo TUI session");

// After:
sessionWriter.writeSystemMessage(
    projectContext.path().toString(), version, "Grimo TUI session");
```

**refreshStatusBar()**（lines 852-853）：
```java
// Before:
String workspacePath = workspaceManager.root().toString()
        .replace(System.getProperty("user.home"), "~");

// After:
String workspacePath = projectContext.displayPath();
```

- [ ] **Step 3: 編譯確認**

Run: `./gradlew compileJava -x nativeTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "refactor: GrimoTuiRunner — use ProjectContext for display path, inject SessionWriter"
```

---

## Task 5: SessionWriter 路徑調整

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/session/SessionWriter.java`

- [ ] **Step 1: 修改建構子 — session 直接放 dataDir 根目錄**

```java
// Before（line 36-39）:
public SessionWriter(Path sessionsBaseDir) {
    this.sessionId = UUID.randomUUID().toString().substring(0, 8);
    this.sessionFile = sessionsBaseDir.resolve(sessionId + ".jsonl");
}

// After — sessionsBaseDir 改名為 dataDir，語意更清楚:
/**
 * 建構子：session 檔案直接放在 project data 根目錄。
 * 對齊 Claude Code 的 projects/{encoded-cwd}/{sessionId}.jsonl 結構。
 *
 * @param dataDir 專案資料目錄（如 ~/.grimo/projects/{encoded-cwd}/）
 */
public SessionWriter(Path dataDir) {
    this.sessionId = UUID.randomUUID().toString().substring(0, 8);
    this.dataDir = dataDir;
    this.sessionFile = dataDir.resolve(sessionId + ".jsonl");
}
```

新增欄位：
```java
private final Path dataDir;
```

- [ ] **Step 2: 新增 dispatchesDir() 方法**

```java
/**
 * dispatch 紀錄存放目錄：{dataDir}/{sessionId}/dispatches/
 * 對齊 Claude Code 的 {sessionId}/subagents/ 結構。
 */
public Path dispatchesDir() {
    return dataDir.resolve(sessionId).resolve("dispatches");
}
```

- [ ] **Step 3: 執行既有 SessionWriter 使用處的編譯確認**

Run: `./gradlew compileJava -x nativeTest`
Expected: PASS（建構子簽名相容 — 仍接受 `Path`）

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/session/SessionWriter.java
git commit -m "refactor: SessionWriter — session in dataDir root, add dispatchesDir()"
```

---

## Task 6: SessionWriter — 新增 dispatch 寫入方法

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/session/SessionWriter.java`

- [ ] **Step 1: 新增 writeDispatchEntered()**

```java
/**
 * 寫入 dispatch-entered 摘要到主 session JSONL。
 */
public void writeDispatchEntered(String taskId, String agent, String model,
                                  String tier, String branchName, String goal) {
    var uuid = newUuid();
    var node = createBase("dispatch-entered", uuid, lastUuid);
    node.put("taskId", taskId);
    node.put("agent", agent);
    node.put("model", model);
    node.put("tier", tier);
    node.put("branchName", branchName);
    node.put("goal", goal);
    appendLine(node);
    lastUuid = uuid;
}
```

- [ ] **Step 2: 新增 writeDispatchCompleted()**

```java
/**
 * 寫入 dispatch-completed 摘要到主 session JSONL，
 * 並建立 {sessionId}/dispatches/{taskId}.meta.json。
 */
public void writeDispatchCompleted(String taskId, String agent, String model,
                                    String tier, String goal,
                                    String executionMode, String workDir,
                                    String branchName, String baseSha,
                                    String containerId,
                                    boolean hasChanges, int commitCount,
                                    String diffStat, long durationMs,
                                    String summary, String externalSessionPath) {
    // 1. 主 session JSONL 摘要
    var uuid = newUuid();
    var node = createBase("dispatch-completed", uuid, lastUuid);
    node.put("taskId", taskId);
    node.put("hasChanges", hasChanges);
    node.put("commitCount", commitCount);
    node.put("diffStat", diffStat);
    node.put("durationMs", durationMs);
    node.put("summary", summary);
    appendLine(node);
    lastUuid = uuid;

    // 2. meta.json
    writeDispatchMeta(taskId, agent, model, tier, goal,
            executionMode, workDir, branchName, baseSha, containerId,
            hasChanges, commitCount, diffStat, durationMs, summary,
            externalSessionPath);
}

private void writeDispatchMeta(String taskId, String agent, String model,
                                String tier, String goal,
                                String executionMode, String workDir,
                                String branchName, String baseSha,
                                String containerId,
                                boolean hasChanges, int commitCount,
                                String diffStat, long durationMs,
                                String summary, String externalSessionPath) {
    try {
        Path dir = dispatchesDir();
        Files.createDirectories(dir);
        var meta = mapper.createObjectNode();
        meta.put("taskId", taskId);
        meta.put("agent", agent);
        meta.put("model", model);
        meta.put("tier", tier);
        meta.put("goal", goal);

        var exec = mapper.createObjectNode();
        exec.put("mode", executionMode);
        exec.put("workDir", workDir);
        exec.put("branchName", branchName);
        exec.put("baseSha", baseSha);
        if (containerId != null) exec.put("containerId", containerId);
        meta.set("execution", exec);

        var result = mapper.createObjectNode();
        result.put("hasChanges", hasChanges);
        result.put("commitCount", commitCount);
        result.put("diffStat", diffStat);
        result.put("durationMs", durationMs);
        result.put("summary", summary);
        meta.set("result", result);

        if (externalSessionPath != null) {
            var ext = mapper.createObjectNode();
            ext.put(agent, externalSessionPath);
            meta.set("externalSessions", ext);
        }

        var writer = mapper.writerWithDefaultPrettyPrinter();
        Files.writeString(dir.resolve(taskId + ".meta.json"),
                writer.writeValueAsString(meta));
    } catch (IOException e) {
        // Dispatch meta 寫入失敗不中斷 TUI 運作
    }
}
```

- [ ] **Step 3: 執行編譯確認**

Run: `./gradlew compileJava -x nativeTest`
Expected: PASS

- [ ] **Step 4: 寫 SessionWriter dispatch 方法的測試**

新增測試到既有 test 或建新 test file：

```java
@Test
void writeDispatchEnteredShouldAppendToSessionFile() throws Exception {
    var dataDir = tempDir.resolve("project-data");
    Files.createDirectories(dataDir);
    var writer = new SessionWriter(dataDir);
    writer.writeSystemMessage("/test", "1.0", "test");

    writer.writeDispatchEntered("abc123", "claude", "claude-sonnet-4-6", "std", "grimo/abc123", "fix bug");

    var content = Files.readString(writer.getSessionFile());
    assertThat(content).contains("dispatch-entered");
    assertThat(content).contains("abc123");
    assertThat(content).contains("claude-sonnet-4-6");
}

@Test
void writeDispatchCompletedShouldCreateMetaJson() throws Exception {
    var dataDir = tempDir.resolve("project-data");
    Files.createDirectories(dataDir);
    var writer = new SessionWriter(dataDir);
    writer.writeSystemMessage("/test", "1.0", "test");

    writer.writeDispatchCompleted("abc123", "claude", "claude-sonnet-4-6", "std",
            "fix bug", "worktree", "/tmp/wt", "grimo/abc123", "sha123", null,
            true, 3, "5 files changed", 45000, "Fixed bug", null);

    // 檢查主 session 摘要
    assertThat(Files.readString(writer.getSessionFile()))
        .contains("dispatch-completed").contains("abc123");

    // 檢查 meta.json
    var metaFile = writer.dispatchesDir().resolve("abc123.meta.json");
    assertThat(metaFile).exists();
    assertThat(Files.readString(metaFile))
        .contains("claude-sonnet-4-6")
        .contains("worktree")
        .contains("Fixed bug");
}
```

- [ ] **Step 5: 執行測試**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.session.*" -x nativeTest`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/session/SessionWriter.java \
        src/test/java/io/github/samzhu/grimo/shared/session/
git commit -m "feat: SessionWriter — dispatch entered/completed + meta.json persistence"
```

---

## Task 7: 擴充 DevMode Events + SessionEventListener

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/event/DevModeEnteredEvent.java`
- Modify: `src/main/java/io/github/samzhu/grimo/shared/event/DevModeCompletedEvent.java`
- Modify: `src/main/java/io/github/samzhu/grimo/agent/DevModeRunner.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/session/SessionEventListener.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/session/SessionEventListenerTest.java`

- [ ] **Step 1: 擴充 DevModeEnteredEvent**

```java
package io.github.samzhu.grimo.shared.event;

/**
 * Dev Mode 進入時發布。
 * TUI 收到後顯示 worktree 資訊。
 * SessionEventListener 收到後寫入 dispatch-entered 到 session。
 */
public record DevModeEnteredEvent(
    String taskId,
    String agent,
    String model,
    String tier,
    String goal,
    String branchName,
    String workDir
) {}
```

- [ ] **Step 2: 擴充 DevModeCompletedEvent**

```java
package io.github.samzhu.grimo.shared.event;

/**
 * Dev Mode 完成時發布。
 *
 * @param taskId 派遣任務 ID
 * @param agent 使用的 agent ID
 * @param model 使用的 model
 * @param tier 使用的 tier
 * @param goal 使用者輸入的目標
 * @param executionMode worktree / docker / e2b
 * @param workDir agent 實際工作目錄
 * @param branchName worktree 分支名稱
 * @param baseSha worktree 建立時的 HEAD SHA
 * @param commitCount baseSha 到分支的 commit 數量
 * @param diffStat diff 統計文字
 * @param durationMs 執行時間（毫秒）
 * @param hasChanges agent 是否實際修改了檔案
 * @param result agent 回覆文字
 * @param externalSessionPath CLI agent 的 session 路徑（追溯用）
 */
public record DevModeCompletedEvent(
    String taskId,
    String agent,
    String model,
    String tier,
    String goal,
    String executionMode,
    String workDir,
    String branchName,
    String baseSha,
    int commitCount,
    String diffStat,
    long durationMs,
    boolean hasChanges,
    String result,
    String externalSessionPath
) {}
```

- [ ] **Step 3: 修改 DevModeRunner 發布 event 的程式碼**

`DevModeRunner.java` — 所有 `publishEvent(new DevModeEnteredEvent(...))` 和 `publishEvent(new DevModeCompletedEvent(...))` 呼叫點需傳入新增欄位。

**Line 107-109（entered）：**
```java
// Before:
eventPublisher.publishEvent(new DevModeEnteredEvent(
    worktree.branchName() != null ? worktree.branchName() : "dev-" + taskId,
    worktree.workDir().toString()));

// After:
eventPublisher.publishEvent(new DevModeEnteredEvent(
    taskId,
    tierSelection.agentId(),
    tierSelection.model(),
    tierSelection.tier().name().toLowerCase(),
    goal,
    worktree.branchName() != null ? worktree.branchName() : "dev-" + taskId,
    worktree.workDir().toString()));
```

**Line 149-150（completed success）：**
```java
// Before:
eventPublisher.publishEvent(new DevModeCompletedEvent(
    worktree.branchName(), commitCount, diffStat, duration, hasChanges, result));

// After:
String extPath = resolveExternalSessionPath(tierSelection.agentId(), worktree);
eventPublisher.publishEvent(new DevModeCompletedEvent(
    taskId, tierSelection.agentId(), tierSelection.model(),
    tierSelection.tier().name().toLowerCase(), goal,
    worktree.isWorktree() ? "worktree" : "local",
    worktree.workDir().toString(), worktree.branchName(),
    worktree.baseSha(),
    commitCount, diffStat, duration, hasChanges, result, extPath));
```

**Line 91-93（agent not found — worktree 尚未建立）：**
```java
// Before:
eventPublisher.publishEvent(new DevModeCompletedEvent(
    "grimo/dev-" + taskId, 0, "", 0, false,
    "Agent not found: " + tierSelection.agentId()));

// After — 此時 worktree 不存在，用 null/空值:
eventPublisher.publishEvent(new DevModeCompletedEvent(
    taskId, tierSelection.agentId(), tierSelection.model(),
    tierSelection.tier().name().toLowerCase(), goal,
    "local", projectDir.toString(), null, null,
    0, "", 0, false,
    "Agent not found: " + tierSelection.agentId(), null));
```

**Line 162-164（exception — worktree 已建立但 agent 執行失敗）：**
```java
// Before:
eventPublisher.publishEvent(new DevModeCompletedEvent(
    worktree.branchName(), 0, "", duration, false,
    "Dev Mode error: " + e.getMessage()));

// After:
eventPublisher.publishEvent(new DevModeCompletedEvent(
    taskId, tierSelection.agentId(), tierSelection.model(),
    tierSelection.tier().name().toLowerCase(), goal,
    worktree.isWorktree() ? "worktree" : "local",
    worktree.workDir().toString(), worktree.branchName(),
    worktree.baseSha(),
    0, "", duration, false,
    "Dev Mode error: " + e.getMessage(), null));
```

**新增 helper method：**
```java
/**
 * 推算 CLI agent session 路徑（追溯用）。
 * Claude Code: ~/.claude/projects/{encoded-workDir}/
 */
private String resolveExternalSessionPath(String agentId,
        io.github.samzhu.grimo.shared.sandbox.WorktreeInfo worktree) {
    if (!"claude".equals(agentId)) return null;
    String encoded = worktree.workDir().toString().replaceAll("[^a-zA-Z0-9]", "-");
    return "~/.claude/projects/" + encoded + "/";
}
```

- [ ] **Step 4: 確認 GrimoTuiRunner event listener — 不需改動**

`GrimoTuiRunner.java` 中 `@EventListener void on(DevModeEnteredEvent event)` 和 `on(DevModeCompletedEvent event)`：

**不需要改任何程式碼。** 驗證方式：新 record 保留了所有原有欄位名稱（`branchName`, `workDir`, `hasChanges`, `commitCount`, `diffStat`, `durationMs`, `result`），Java record 自動生成同名 accessor。新增欄位（`taskId`, `agent`, `model` 等）TUI listener 不使用，不影響。

Run: `./gradlew compileJava -x nativeTest` 確認無編譯錯誤即可。

- [ ] **Step 5: 建立 SessionEventListener**

```java
package io.github.samzhu.grimo.shared.session;

import io.github.samzhu.grimo.shared.event.DevModeEnteredEvent;
import io.github.samzhu.grimo.shared.event.DevModeCompletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 監聽 DevMode 事件，寫入 dispatch 紀錄到 session。
 *
 * 設計說明：
 * - 遵循 event-driven 架構：agent/ 模組不直接依賴 shared/session/
 * - DevModeRunner 只負責發布事件，SessionEventListener 負責持久化
 * - 使用 @EventListener（非 @ApplicationModuleListener）因為 CLI app 沒有 DB transaction
 *
 * @see SessionWriter#writeDispatchEntered
 * @see SessionWriter#writeDispatchCompleted
 */
@Component
public class SessionEventListener {

    private final SessionWriter sessionWriter;

    public SessionEventListener(SessionWriter sessionWriter) {
        this.sessionWriter = sessionWriter;
    }

    @EventListener
    public void on(DevModeEnteredEvent event) {
        sessionWriter.writeDispatchEntered(
            event.taskId(), event.agent(), event.model(),
            event.tier(), event.branchName(), event.goal());
    }

    @EventListener
    public void on(DevModeCompletedEvent event) {
        sessionWriter.writeDispatchCompleted(
            event.taskId(), event.agent(), event.model(),
            event.tier(), event.goal(),
            event.executionMode(), event.workDir(),
            event.branchName(), event.baseSha(), null,
            event.hasChanges(), event.commitCount(),
            event.diffStat(), event.durationMs(),
            event.result(), event.externalSessionPath());
    }
}
```

- [ ] **Step 6: 寫 SessionEventListener 測試**

```java
package io.github.samzhu.grimo.shared.session;

import io.github.samzhu.grimo.shared.event.DevModeEnteredEvent;
import io.github.samzhu.grimo.shared.event.DevModeCompletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SessionEventListenerTest {

    @TempDir
    Path tempDir;

    @Test
    void onDevModeEnteredShouldWriteDispatchEntry() throws Exception {
        var dataDir = tempDir.resolve("project");
        Files.createDirectories(dataDir);
        var writer = new SessionWriter(dataDir);
        writer.writeSystemMessage("/test", "1.0", "test");
        var listener = new SessionEventListener(writer);

        listener.on(new DevModeEnteredEvent(
            "task1", "claude", "claude-sonnet-4-6", "std",
            "fix bug", "grimo/task1", "/tmp/wt"));

        assertThat(Files.readString(writer.getSessionFile()))
            .contains("dispatch-entered")
            .contains("task1");
    }

    @Test
    void onDevModeCompletedShouldWriteMetaJson() throws Exception {
        var dataDir = tempDir.resolve("project");
        Files.createDirectories(dataDir);
        var writer = new SessionWriter(dataDir);
        writer.writeSystemMessage("/test", "1.0", "test");
        var listener = new SessionEventListener(writer);

        listener.on(new DevModeCompletedEvent(
            "task1", "claude", "claude-sonnet-4-6", "std",
            "fix bug", "worktree", "/tmp/wt", "grimo/task1", "sha123",
            2, "3 files", 30000, true, "Done", null));

        // 主 session 摘要
        assertThat(Files.readString(writer.getSessionFile()))
            .contains("dispatch-completed");

        // meta.json
        assertThat(writer.dispatchesDir().resolve("task1.meta.json")).exists();
    }
}
```

- [ ] **Step 7: 執行全部測試**

Run: `./gradlew test -x nativeTest`
Expected: ALL PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/event/DevModeEnteredEvent.java \
        src/main/java/io/github/samzhu/grimo/shared/event/DevModeCompletedEvent.java \
        src/main/java/io/github/samzhu/grimo/agent/DevModeRunner.java \
        src/main/java/io/github/samzhu/grimo/shared/session/SessionEventListener.java \
        src/test/java/io/github/samzhu/grimo/shared/session/SessionEventListenerTest.java
git commit -m "feat: event-driven dispatch recording — SessionEventListener + expanded DevMode events"
```

---

## Task 8: Glossary + 文件更新

**Files:**
- Modify: `docs/glossary.md`

- [ ] **Step 1: 更新佈局圖**

```
# Before (line 12-13):
│ █●██●█  ~/grimo-workspace                       │

# After:
│ █●██●█  ~/workspace/github-grimostudio/grimo-cli │
```

```
# Before (line 28):
│ claude-cli · unknown │ ~/grimo │ 1 agent · 0 mcp│

# After:
│ claude · claude-sonnet-4-6 │ ~/workspace/grimo │ 1 agent · 0 mcp│
```

- [ ] **Step 2: 更新 Status 區說明**

```
# Before:
| **Status 區** | Status Bar | 固定在最底行的狀態列，顯示 agent、model、workspace、資源計數。

# After:
| **Status 區** | Status Bar | 固定在最底行的狀態列，顯示 agent、model、專案路徑（CWD）、資源計數。
```

- [ ] **Step 3: 更新 Session 與歷史 section**

```markdown
| **Session 檔案** | Session File | `~/.grimo/projects/<encoded-cwd>/<session-uuid>.jsonl`。對齊 Claude Code 結構。每行一個 JSON 物件（含 uuid、parentUuid 支援對話樹），append-only。附屬資料目錄 `<session-uuid>/dispatches/` 存放 sub-agent 派遣紀錄。 |
```

- [ ] **Step 4: 新增術語**

在 Domain Events 表格後或 Session 表格中新增：

```markdown
| **GrimoHome** | Grimo Home | `~/.grimo`，應用程式全域資料目錄，存放 config、skills、tasks、agents、logs。路徑固定，不可配置。 |
| **ProjectContext** | Project Context | 啟動時的 CWD，代表目前操作的專案。專案資料存放在 `~/.grimo/projects/{encoded-cwd}/`。 |
| **Dispatch 紀錄** | Dispatch Record | sub-agent 派遣的 metadata 和事件，歸屬於 session。主 session JSONL 含摘要事件（`dispatch-entered` / `dispatch-completed`），附屬目錄含 `{taskId}.meta.json`。 |
| **SessionEventListener** | Session Event Listener | `@EventListener` 監聽 `DevModeEnteredEvent` / `DevModeCompletedEvent`，透過 `SessionWriter` 寫入 dispatch 紀錄。遵循 event-driven 設計，`agent/` 模組不直接依賴 `shared/session/`。 |
```

- [ ] **Step 5: 移除過時的 Workspace 引用**

搜尋 glossary 中所有 "workspace" 字眼，確認是否有需要更新的地方。`WorkspaceProvisioner` 保留（它的名稱不變，職責不變）。

- [ ] **Step 6: Commit**

```bash
git add docs/glossary.md
git commit -m "docs: update glossary — GrimoHome, ProjectContext, Dispatch records"
```

---

## Task 9: 完整測試 + 清理

**Files:**
- All modified files

- [ ] **Step 1: 執行全部測試**

Run: `./gradlew test -x nativeTest`
Expected: ALL PASS

- [ ] **Step 2: 確認無殘留 WorkspaceManager 引用**

Run: `grep -r "WorkspaceManager" src/ --include="*.java"`
Expected: 0 results

Run: `grep -r "GrimoProperties" src/ --include="*.java"`
Expected: 0 results

Run: `grep -r "grimo\.workspace" src/ --include="*.yaml" --include="*.yml" --include="*.properties"`
Expected: 0 results

- [ ] **Step 3: 確認 application.yaml 乾淨**

讀取 `application.yaml` 確認 `grimo.workspace` 已移除，其他配置不受影響。

- [ ] **Step 4: 執行 build**

Run: `./gradlew build -x nativeTest -x nativeCompile`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit（如有清理）**

```bash
git add -A
git commit -m "chore: cleanup — remove all WorkspaceManager references"
```
