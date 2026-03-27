# F0: 應用程式資料目錄遷移 ~/grimo-workspace → ~/.grimo — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 將所有應用程式資料統一到 `~/.grimo/`，對齊業界慣例（`~/.claude/`、`~/.gemini/`、`~/.codex/`）。同時新增 `agentsDir()` 方法為 F4 Sub-Agent 調度系統做準備。

**Architecture:** 修改 root 路徑源頭（`application.yaml`），讓所有透過 `WorkspaceManager` 讀取路徑的程式碼自動生效。`logback-spring.xml` 改用 `<springProperty>` 從 Spring Environment 讀取相同屬性，確保 log 路徑與 workspace 設定同步（含 `GRIMO_WORKSPACE` 環境變數覆蓋）。

**Tech Stack:** Spring Boot 4.0.x, Logback Spring Extensions（`<springProperty>`）

**Spec:** [docs/superpowers/specs/2026-03-27-grimo-home-directory.md](../specs/2026-03-27-grimo-home-directory.md)

---

## File Map

| 動作 | 檔案 | 職責 |
|------|------|------|
| Modify | `src/main/resources/application.yaml:34` | `grimo.workspace` 預設值從 `grimo-workspace` → `.grimo` |
| Modify | `src/main/resources/logback-spring.xml:9,11` | log 路徑改用 `<springProperty>` 讀取 `grimo.workspace`，不再硬編碼 |
| Modify | `src/main/java/.../shared/workspace/WorkspaceManager.java:16-21,28-33` | 新增 `agentsDir()` 方法 + `initialize()` 新增 agents 目錄建立 |
| Modify | `src/test/java/.../shared/workspace/WorkspaceManagerTest.java` | 新增 `agentsDir` 相關測試 |
| Modify | `src/main/java/.../SessionWriter.java:17,34` | Javadoc 路徑 comment 更新 |
| Modify | `src/main/java/.../task/TaskCommands.java:23` | Javadoc 路徑 comment 更新 |
| Modify | `CLAUDE.md:66` | Markdown persistence 路徑更新 |

---

### Task 1: WorkspaceManager 新增 `agentsDir()` — 寫測試

**Files:**
- Test: `src/test/java/io/github/samzhu/grimo/shared/workspace/WorkspaceManagerTest.java`

- [ ] **Step 1: 新增 `agentsDir` 路徑測試**

在 `shouldReturnCorrectSubPaths()` 測試中加入 `agentsDir` 斷言：

```java
// 在 shouldReturnCorrectSubPaths() 的最後一行 assertThat 之後加入：
assertThat(manager.agentsDir()).isEqualTo(tempDir.resolve("agents"));
```

- [ ] **Step 2: 新增 `initialize` 建立 agents 目錄的測試**

在 `initializeShouldCreateRequiredDirectories()` 測試中加入：

```java
// 在最後一個 assertThat 之後加入：
assertThat(tempDir.resolve("agents")).isDirectory();
```

- [ ] **Step 3: 執行測試確認失敗**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.workspace.WorkspaceManagerTest" --info`
Expected: FAIL — `agentsDir()` 方法不存在

---

### Task 2: WorkspaceManager 新增 `agentsDir()` — 實作

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/workspace/WorkspaceManager.java:16-21,28-33`

- [ ] **Step 1: 在 `initialize()` 新增 `agentsDir` 建立**

```java
public void initialize() {
    createDir(tasksDir());
    createDir(skillsDir());
    createDir(conversationsDir());
    createDir(logsDir());
    createDir(agentsDir());   // F4 Sub-Agent 定義檔目錄
}
```

- [ ] **Step 2: 新增 `agentsDir()` accessor**

在 `configFile()` 方法之後加入：

```java
public Path agentsDir()         { return root.resolve("agents"); }
```

- [ ] **Step 3: 執行測試確認通過**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.workspace.WorkspaceManagerTest" --info`
Expected: PASS — 全部 4 個測試通過

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/workspace/WorkspaceManager.java \
        src/test/java/io/github/samzhu/grimo/shared/workspace/WorkspaceManagerTest.java
git commit -m "feat(workspace): add agentsDir() to WorkspaceManager for F4 sub-agent definitions"
```

---

### Task 3: application.yaml 路徑遷移

**Files:**
- Modify: `src/main/resources/application.yaml:34`

- [ ] **Step 1: 修改 `grimo.workspace` 預設值**

將：
```yaml
grimo:
  workspace: ${user.home}/grimo-workspace
```

改為：
```yaml
grimo:
  workspace: ${user.home}/.grimo
```

- [ ] **Step 2: 確認 build 通過**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yaml
git commit -m "feat(config): migrate workspace default from ~/grimo-workspace to ~/.grimo

Aligns with industry convention (~/.claude/, ~/.gemini/, ~/.codex/).
Existing users: mv ~/grimo-workspace ~/.grimo"
```

---

### Task 4: logback-spring.xml 路徑遷移

**Files:**
- Modify: `src/main/resources/logback-spring.xml:1-15`

設計說明：使用 Spring Boot 的 `<springProperty>` 從 Spring Environment 讀取 `grimo.workspace` 屬性，
讓 log 路徑與 application.yaml 的 `grimo.workspace` 保持同步。
這樣 `GRIMO_WORKSPACE` 環境變數覆蓋時，log 路徑也會跟著變。

參考：[Spring Boot — Logback Extensions: Environment Properties](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.logback-extensions.environment-properties)

- [ ] **Step 1: 新增 `<springProperty>` 並修改 FILE appender 路徑**

將整個 `logback-spring.xml` 改為：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <!-- 從 Spring Environment 讀取 grimo.workspace，與 application.yaml 同步 -->
    <!-- 支援 GRIMO_WORKSPACE 環境變數覆蓋 -->
    <!-- 參考：https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.logback-extensions.environment-properties -->
    <springProperty scope="context" name="grimoWorkspace" source="grimo.workspace"
                    defaultValue="${user.home}/.grimo"/>

    <!-- 互動式 CLI 不需要 console 日誌，所有 UI 由 JLine Terminal 直接處理 -->
    <!-- 日誌只寫入檔案，console 完全靜音 -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${grimoWorkspace}/logs/grimo.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${grimoWorkspace}/logs/grimo-%d{yyyy-MM-dd}.%i.gz</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>7</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="WARN">
        <appender-ref ref="FILE"/>
    </root>

    <!-- 自己的 package 用 DEBUG，方便開發除錯（只寫入檔案，不影響 console） -->
    <logger name="io.github.samzhu.grimo" level="DEBUG"/>
</configuration>
```

- [ ] **Step 2: 確認 build 通過**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/logback-spring.xml
git commit -m "feat(logging): use springProperty for log path, sync with grimo.workspace

Replace hardcoded grimo-workspace with <springProperty> that reads grimo.workspace
from Spring Environment. GRIMO_WORKSPACE env var override now applies to logs too."
```

---

### Task 5: Javadoc 與文件路徑更新

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/SessionWriter.java:17,34`
- Modify: `src/main/java/io/github/samzhu/grimo/task/TaskCommands.java:23`
- Modify: `CLAUDE.md:66`

- [ ] **Step 1: 更新 SessionWriter.java Javadoc**

Line 17，將 `~/grimo-workspace/projects/` 改為 `~/.grimo/projects/`：
```java
 * - 每次 TUI session 的對話紀錄持久化到 ~/.grimo/projects/<encoded-cwd>/sessions/<uuid>.jsonl
```

Line 34，將 `~/grimo-workspace/projects/` 改為 `~/.grimo/projects/`：
```java
 * @param sessionsBaseDir 基礎目錄（如 ~/.grimo/projects/<encoded-cwd>/sessions/）
```

- [ ] **Step 2: 更新 TaskCommands.java Javadoc**

Line 23，將 `~/grimo-workspace/tasks/` 改為 `~/.grimo/tasks/`：
```java
 * - 透過 MarkdownTaskStore 持久化任務至 ~/.grimo/tasks/ 目錄
```

- [ ] **Step 3: 更新 CLAUDE.md**

Line 66，將 `~/grimo-workspace/tasks/` 改為 `~/.grimo/tasks/`：
```markdown
- **Markdown persistence**: Tasks stored as `.md` files with YAML frontmatter in `~/.grimo/tasks/`.
```

- [ ] **Step 4: 確認 build 通過**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/SessionWriter.java \
        src/main/java/io/github/samzhu/grimo/task/TaskCommands.java \
        CLAUDE.md
git commit -m "docs: update path references from ~/grimo-workspace to ~/.grimo"
```

---

### Task 6: 端到端驗證

- [ ] **Step 1: 完整 build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（compile + test 全通過）

- [ ] **Step 2: 確認沒有遺漏的 grimo-workspace 參考**

Run: `grep -r "grimo-workspace" src/ CLAUDE.md --include="*.java" --include="*.yaml" --include="*.xml" --include="*.md"`
Expected: 無輸出（src/ 和 CLAUDE.md 中不應有 grimo-workspace 殘留）

> 注意：`docs/superpowers/` 下的舊 spec/plan 文件中仍會有 `grimo-workspace` 引用，這是正常的歷史記錄，不需修改。

- [ ] **Step 3: 啟動驗證（手動）**

如果要做完整的端到端驗證：
```bash
# 確保 ~/.grimo 不存在（或備份）
mv ~/.grimo ~/.grimo.bak 2>/dev/null
./gradlew bootRun
# 確認 ~/.grimo/ 自動建立，含 tasks/ skills/ conversations/ logs/ agents/ 子目錄
# 確認 log 寫入 ~/.grimo/logs/grimo.log
# /exit 離開
ls -la ~/.grimo/
```
