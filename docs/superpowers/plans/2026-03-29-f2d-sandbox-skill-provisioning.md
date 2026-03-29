# F2-d: Sandbox 環境準備與 Skill 自動配置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 派遣 agent 前自動將 Grimo 管理的 skill symlink 到工作目錄 `.agents/skills/`，讓所有 CLI agent（Claude/Gemini/Codex）原生發現並使用。

**Architecture:** 新增 `WorkspaceProvisioner` 元件（`shared/sandbox` 套件），負責在 agent 派遣前建立 symlink、派遣後清理。新增 `SandboxDetector` 偵測可用的 sandbox 後端（Local/Docker/E2B）。GrimoTuiRunner 在 agent dispatch 流程中呼叫 provision/cleanup，並在 Content 區顯示已配置的 skill。config.yaml 新增 `sandbox` 區段。Phase A 僅實作 Local 模式。

**Tech Stack:** Java 25 NIO (`Files.createSymbolicLink`), Spring Shell 4.0, JUnit 5, AssertJ

**SDK 驗證結果：**
- `org.springaicommunity.sandbox.LocalSandbox` — 已透過 `spring-ai-gemini` 間接引入（0.9.0-SNAPSHOT），確認有 `workDir()`, `files()`, `exec()` API
- `SandboxFiles` — 確認有 `create()`, `createDirectory()`, `exists()`, `delete()` API（Phase B Docker 模式時使用）
- `AgentClient.Builder` — 確認有 `mcpServerCatalog()`, `defaultMcpServers()`, `defaultWorkingDirectory()` 但**無 skill 相關 API**
- `.agents/skills/` — Agent Skills 開放標準的跨 agent 路徑，Claude Code / Gemini CLI / Codex CLI 皆掃描
- Phase A 的 `WorkspaceProvisioner` 使用 Java NIO 操作 symlink，不直接使用 SDK 的 `LocalSandbox`

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `src/main/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisioner.java` | Skill symlink 建立 + 清理 |
| Create | `src/main/java/io/github/samzhu/grimo/shared/sandbox/SandboxDetector.java` | 啟動時偵測 Sandbox 後端可用性 |
| Create | `src/main/java/io/github/samzhu/grimo/shared/sandbox/package-info.java` | Spring Modulith named interface |
| Create | `src/test/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisionerTest.java` | 單元測試 |
| Create | `src/test/java/io/github/samzhu/grimo/shared/sandbox/SandboxDetectorTest.java` | 單元測試 |
| Modify | `src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java` | 讀取 `sandbox` 設定 |
| Modify | `src/main/java/io/github/samzhu/grimo/shared/workspace/WorkspaceManager.java` | DEFAULT_CONFIG 增加 sandbox 區段 |
| Modify | `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java` | 註冊新 bean |
| Modify | `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` | 注入 WorkspaceProvisioner，dispatch 流程加 provision/cleanup |
| Modify | `docs/glossary.md` | 新增 Sandbox / WorkspaceProvisioner 術語 |

**Not modified:**
- `build.gradle.kts` — `agent-sandbox-core` 已透過 `spring-ai-gemini` 間接引入
- `AgentConfiguration.java` — 現有 `LocalSandbox` 使用與 WorkspaceProvisioner 獨立不衝突
- `BannerRenderer.java` — Phase A 不改 banner（sandbox mode 顯示留待 Phase B）

---

### Task 1: WorkspaceProvisioner — Skill symlink 建立與清理

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisioner.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/sandbox/package-info.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisionerTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package io.github.samzhu.grimo.shared.sandbox;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceProvisionerTest {

    @TempDir
    Path projectDir;

    @TempDir
    Path skillsSourceDir;

    // === provision 測試 ===

    @Test
    void provisionShouldSymlinkSkillsToAgentsDirectory() throws Exception {
        // 準備 source skill
        var skillDir = skillsSourceDir.resolve("code-review");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: code-review\n---\n# CR");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir);
        var skills = List.of(testSkill("code-review"));

        var result = provisioner.provision(projectDir, skills);

        assertThat(result).containsExactly("code-review");
        Path symlink = projectDir.resolve(".agents/skills/code-review");
        assertThat(Files.isSymbolicLink(symlink)).isTrue();
        assertThat(Files.readSymbolicLink(symlink)).isEqualTo(skillDir);
    }

    @Test
    void provisionShouldCreateAgentsSkillsDirectoryIfNotExists() throws Exception {
        var skillDir = skillsSourceDir.resolve("test-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: test-skill\n---\n# Test");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir);
        provisioner.provision(projectDir, List.of(testSkill("test-skill")));

        assertThat(Files.isDirectory(projectDir.resolve(".agents/skills"))).isTrue();
    }

    @Test
    void provisionShouldSkipIfSkillSourceDirNotExists() throws Exception {
        var provisioner = new WorkspaceProvisioner(skillsSourceDir);
        // skill name 不對應實際目錄
        var result = provisioner.provision(projectDir, List.of(testSkill("nonexistent")));

        assertThat(result).isEmpty();
    }

    @Test
    void provisionShouldSkipConflictingUserSkill() throws Exception {
        // 使用者已有同名 skill
        var userSkillDir = projectDir.resolve(".agents/skills/code-review");
        Files.createDirectories(userSkillDir);
        Files.writeString(userSkillDir.resolve("SKILL.md"), "user version");

        var grimoSkillDir = skillsSourceDir.resolve("code-review");
        Files.createDirectories(grimoSkillDir);
        Files.writeString(grimoSkillDir.resolve("SKILL.md"), "grimo version");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir);
        var result = provisioner.provision(projectDir, List.of(testSkill("code-review")));

        // 跳過衝突的 skill
        assertThat(result).isEmpty();
        // 使用者的 skill 保持不變
        assertThat(Files.readString(userSkillDir.resolve("SKILL.md"))).isEqualTo("user version");
    }

    @Test
    void provisionShouldReturnEmptyForNoSkills() {
        var provisioner = new WorkspaceProvisioner(skillsSourceDir);
        var result = provisioner.provision(projectDir, List.of());
        assertThat(result).isEmpty();
    }

    // === cleanup 測試 ===

    @Test
    void cleanupShouldRemoveProvisionedSymlinks() throws Exception {
        var skillDir = skillsSourceDir.resolve("code-review");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: code-review\n---\n# CR");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir);
        var provisioned = provisioner.provision(projectDir, List.of(testSkill("code-review")));

        provisioner.cleanup(projectDir, provisioned);

        assertThat(Files.exists(projectDir.resolve(".agents/skills/code-review"))).isFalse();
    }

    @Test
    void cleanupShouldNotRemoveUserSkills() throws Exception {
        // 使用者自己的 skill（非 symlink）
        var userSkillDir = projectDir.resolve(".agents/skills/user-skill");
        Files.createDirectories(userSkillDir);
        Files.writeString(userSkillDir.resolve("SKILL.md"), "user skill");

        var provisioner = new WorkspaceProvisioner(skillsSourceDir);
        provisioner.cleanup(projectDir, List.of("user-skill"));

        // 非 symlink 不刪除
        assertThat(Files.exists(userSkillDir)).isTrue();
    }

    // === Helper ===

    private SkillDefinition testSkill(String name) {
        return new SkillDefinition(
            name, "Test skill", null, null, List.of(), Map.of(),
            null, null, null, null, null, null, null, List.of(), null,
            "# Test"
        );
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.sandbox.WorkspaceProvisionerTest" 2>&1 | tail -10`
Expected: FAIL — class WorkspaceProvisioner does not exist

- [ ] **Step 3: Write package-info.java**

```java
@org.springframework.modulith.NamedInterface("sandbox")
package io.github.samzhu.grimo.shared.sandbox;
```

- [ ] **Step 4: Write WorkspaceProvisioner implementation**

```java
package io.github.samzhu.grimo.shared.sandbox;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 派遣 agent 前準備工作目錄：將 Grimo 管理的 Skill symlink 到
 * .agents/skills/（跨 agent 標準路徑），讓 CLI agent 原生發現。
 *
 * 設計說明：
 * - 使用 .agents/skills/ 路徑，Claude Code / Gemini CLI / Codex CLI 皆掃描
 * - symlink 而非複製，避免重複檔案
 * - 名稱衝突時以使用者的為優先，跳過 Grimo 版本（WARN log）
 * - cleanup() 只移除 Grimo 建立的 symlink，不刪除使用者自己的 skill
 * - 併發安全：GrimoTuiRunner 已有 agentRunning 守衛，同時只有一個 agent 執行
 *
 * @see <a href="https://agentskills.io/client-implementation/adding-skills-support">
 *      Agent Skills — .agents/skills/ cross-client convention</a>
 */
public class WorkspaceProvisioner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceProvisioner.class);

    private static final String AGENTS_SKILLS_DIR = ".agents/skills";

    private final Path skillsSourceDir;

    public WorkspaceProvisioner(Path skillsSourceDir) {
        this.skillsSourceDir = skillsSourceDir;
    }

    /**
     * 將 skills symlink 到 projectDir/.agents/skills/。
     * 回傳實際配置成功的 skill 名稱清單。
     */
    public List<String> provision(Path projectDir, List<SkillDefinition> skills) {
        if (skills.isEmpty()) return List.of();

        var provisioned = new ArrayList<String>();
        Path targetDir = projectDir.resolve(AGENTS_SKILLS_DIR);

        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            log.error("Failed to create .agents/skills/ directory: {}", e.getMessage());
            return List.of();
        }

        for (var skill : skills) {
            Path sourceSkillDir = skillsSourceDir.resolve(skill.name());
            if (!Files.isDirectory(sourceSkillDir)) {
                log.debug("Skill source directory not found, skipping: {}", sourceSkillDir);
                continue;
            }

            Path targetSkillDir = targetDir.resolve(skill.name());
            if (Files.exists(targetSkillDir)) {
                log.warn("Skill '{}' already exists in project .agents/skills/, skipping Grimo version", skill.name());
                continue;
            }

            try {
                Files.createSymbolicLink(targetSkillDir, sourceSkillDir);
                provisioned.add(skill.name());
                log.debug("Symlinked skill: {} -> {}", skill.name(), sourceSkillDir);
            } catch (IOException e) {
                log.warn("Failed to symlink skill '{}': {}", skill.name(), e.getMessage());
            }
        }

        if (!provisioned.isEmpty()) {
            log.info("Provisioned {} skills to .agents/skills/: [{}]",
                    provisioned.size(), String.join(", ", provisioned));
        }

        return provisioned;
    }

    /**
     * 清理 Grimo 建立的 symlink。只移除 symlink，不刪除實體目錄。
     */
    public void cleanup(Path projectDir, List<String> provisionedSkillNames) {
        Path targetDir = projectDir.resolve(AGENTS_SKILLS_DIR);
        int removed = 0;

        for (var name : provisionedSkillNames) {
            Path symlink = targetDir.resolve(name);
            if (Files.isSymbolicLink(symlink)) {
                try {
                    Files.delete(symlink);
                    removed++;
                } catch (IOException e) {
                    log.warn("Failed to cleanup symlink '{}': {}", name, e.getMessage());
                }
            }
        }

        if (removed > 0) {
            log.debug("Cleaned up workspace: removed {} symlinks", removed);
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.sandbox.WorkspaceProvisionerTest" 2>&1 | tail -10`
Expected: All 7 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/sandbox/ \
        src/test/java/io/github/samzhu/grimo/shared/sandbox/WorkspaceProvisionerTest.java
git commit -m "feat: add WorkspaceProvisioner for skill symlink to .agents/skills/ (F2-d)"
```

---

### Task 2: SandboxDetector — 偵測可用 Sandbox 後端

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/sandbox/SandboxDetector.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/sandbox/SandboxDetectorTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package io.github.samzhu.grimo.shared.sandbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxDetectorTest {

    @Test
    void localShouldAlwaysBeAvailable() {
        var detector = new SandboxDetector();
        var results = detector.detect();
        assertThat(results.localAvailable()).isTrue();
    }

    @Test
    void detectShouldReturnAllBackends() {
        var detector = new SandboxDetector();
        var results = detector.detect();
        // local 永遠 true，docker/e2b 取決於環境
        assertThat(results.localAvailable()).isTrue();
        assertThat(results).isNotNull();
    }

    @Test
    void resolveModeShouldFallbackToLocal() {
        var detector = new SandboxDetector();
        var results = detector.detect();
        // 要求 docker 但不一定可用 → fallback to local
        String mode = detector.resolveMode(results, "docker");
        assertThat(mode).isIn("docker", "local");
    }

    @Test
    void resolveModeShouldReturnLocalWhenRequested() {
        var detector = new SandboxDetector();
        var results = detector.detect();
        assertThat(detector.resolveMode(results, "local")).isEqualTo("local");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.sandbox.SandboxDetectorTest" 2>&1 | tail -10`
Expected: FAIL — class SandboxDetector does not exist

- [ ] **Step 3: Write SandboxDetector implementation**

```java
package io.github.samzhu.grimo.shared.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 啟動時偵測可用的 Sandbox 後端（Local / Docker / E2B）。
 *
 * 設計說明：
 * - Local 永遠可用
 * - Docker 檢查 daemon 是否運行（ProcessBuilder 執行 docker info）
 * - E2B 檢查 E2B_API_KEY 環境變數
 * - 偵測結果用於驗證 config.yaml 中設定的 mode 是否可用
 */
public class SandboxDetector {

    private static final Logger log = LoggerFactory.getLogger(SandboxDetector.class);

    public record DetectionResult(
        boolean localAvailable,
        boolean dockerAvailable,
        boolean e2bAvailable
    ) {
    }

    /**
     * 解析實際使用的 sandbox mode。
     * 若要求的 mode 不可用，fallback 到 local 並 WARN。
     */
    public String resolveMode(DetectionResult result, String requestedMode) {
        if (requestedMode == null || requestedMode.isBlank()) return "local";
        return switch (requestedMode) {
            case "local" -> "local";
            case "docker" -> {
                if (result.dockerAvailable()) yield "docker";
                log.warn("Docker sandbox requested but not available, falling back to local");
                yield "local";
            }
            case "e2b" -> {
                if (result.e2bAvailable()) yield "e2b";
                log.warn("E2B sandbox requested but not available, falling back to local");
                yield "local";
            }
            default -> {
                log.warn("Unknown sandbox mode '{}', falling back to local", requestedMode);
                yield "local";
            }
        };
    }

    public DetectionResult detect() {
        boolean docker = checkDocker();
        boolean e2b = checkE2B();

        log.info("Sandbox backends: local ✓, docker {}, e2b {}",
                docker ? "✓" : "✗", e2b ? "✓" : "✗");

        return new DetectionResult(true, docker, e2b);
    }

    private boolean checkDocker() {
        try {
            var process = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            int exit = process.waitFor();
            // 讀掉 stdout 避免 process hang
            process.getInputStream().readAllBytes();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkE2B() {
        String apiKey = System.getenv("E2B_API_KEY");
        return apiKey != null && !apiKey.isBlank();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.sandbox.SandboxDetectorTest" 2>&1 | tail -10`
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/sandbox/SandboxDetector.java \
        src/test/java/io/github/samzhu/grimo/shared/sandbox/SandboxDetectorTest.java
git commit -m "feat: add SandboxDetector for backend availability check (F2-d)"
```

---

### Task 3: GrimoConfig — 讀取 sandbox 設定 + WorkspaceManager 更新 DEFAULT_CONFIG

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java`
- Modify: `src/main/java/io/github/samzhu/grimo/shared/workspace/WorkspaceManager.java`
- Modify: `src/test/java/io/github/samzhu/grimo/shared/config/GrimoConfigTest.java`

- [ ] **Step 1: Write the failing test**

Add to existing `GrimoConfigTest.java`:

```java
@Test
void getSandboxModeShouldReturnConfiguredMode(@TempDir Path tempDir) throws Exception {
    Path configFile = tempDir.resolve("config.yaml");
    Files.writeString(configFile, """
        sandbox:
          mode: docker
        """);
    var config = new GrimoConfig(configFile);
    assertThat(config.getSandboxMode()).isEqualTo("docker");
}

@Test
void getSandboxModeShouldReturnLocalWhenNotConfigured(@TempDir Path tempDir) throws Exception {
    Path configFile = tempDir.resolve("config.yaml");
    Files.writeString(configFile, "agents:\n  default: claude\n");
    var config = new GrimoConfig(configFile);
    assertThat(config.getSandboxMode()).isEqualTo("local");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.config.GrimoConfigTest" 2>&1 | tail -10`
Expected: FAIL — method getSandboxMode() does not exist

- [ ] **Step 3: Add getSandboxMode() to GrimoConfig**

In `GrimoConfig.java`, add after `removeMcpServer()`:

```java
    /**
     * 取得 sandbox 模式設定（local / docker / e2b）。
     * 預設 "local"。
     */
    public synchronized String getSandboxMode() {
        String mode = getNestedString("sandbox", "mode");
        return mode != null ? mode : "local";
    }
```

- [ ] **Step 4: Update WorkspaceManager DEFAULT_CONFIG**

In `WorkspaceManager.java`, add sandbox section to `DEFAULT_CONFIG`:

```java
            # Sandbox 設定（agent 執行環境）
            # skill 和 MCP 會自動配置到 sandbox 中讓 CLI agent 使用
            #sandbox:
            #  mode: local              # local | docker | e2b（預設 local）
            #  docker:
            #    image: ghcr.io/spring-ai-community/agents-runtime:latest
            #  e2b:
            #    api-key: ${E2B_API_KEY}
            #    template: base
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.config.GrimoConfigTest" 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java \
        src/main/java/io/github/samzhu/grimo/shared/workspace/WorkspaceManager.java \
        src/test/java/io/github/samzhu/grimo/shared/config/GrimoConfigTest.java
git commit -m "feat: add sandbox config section and getSandboxMode() (F2-d)"
```

---

### Task 4: Bean 註冊 + GrimoTuiRunner 注入

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`

- [ ] **Step 1: Register beans in GrimoStartupRunner**

Add after `skillsDir` bean:

```java
    @Bean
    WorkspaceProvisioner workspaceProvisioner(WorkspaceManager workspaceManager) {
        return new WorkspaceProvisioner(workspaceManager.skillsDir());
    }

    @Bean
    SandboxDetector sandboxDetector() {
        return new SandboxDetector();
    }
```

Add imports:
```java
import io.github.samzhu.grimo.shared.sandbox.WorkspaceProvisioner;
import io.github.samzhu.grimo.shared.sandbox.SandboxDetector;
```

- [ ] **Step 2: Add WorkspaceProvisioner and SandboxDetector to GrimoTuiRunner constructor**

Add fields:
```java
    private final WorkspaceProvisioner workspaceProvisioner;
    private final SandboxDetector sandboxDetector;
```

Add constructor parameters (after `McpCatalogBuilder mcpCatalogBuilder`):
```java
                           WorkspaceProvisioner workspaceProvisioner,
                           SandboxDetector sandboxDetector) {
```

Add assignments:
```java
        this.workspaceProvisioner = workspaceProvisioner;
        this.sandboxDetector = sandboxDetector;
```

- [ ] **Step 3: Add sandbox detection to Phase 2 in run()**

After `restoreTasks();` (around line 136), add:

```java
        // Phase 2: Sandbox 後端偵測
        var sandboxResult = sandboxDetector.detect();
        String sandboxMode = sandboxDetector.resolveMode(sandboxResult, grimoConfig.getSandboxMode());
        log.info("Using sandbox mode: {}", sandboxMode);
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java \
        src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "feat: wire WorkspaceProvisioner and SandboxDetector into startup (F2-d)"
```

---

### Task 5: Agent dispatch 流程加入 provision/cleanup + TUI 顯示

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` (agent dispatch block, ~line 489-534)

- [ ] **Step 1: Add skill provision before agent dispatch**

In the virtual thread block, **before** the `try {` block (after `long startTime = System.currentTimeMillis();`), add:

```java
                        // 設計說明：派遣前將 Grimo 管理的 skill symlink 到 .agents/skills/
                        // CLI agent（Claude/Gemini/Codex）原生掃描 .agents/skills/ 發現 skill
                        // Progressive Disclosure：agent 只讀 name+description，需要時才載入 body
                        var projectDir = java.nio.file.Path.of(System.getProperty("user.dir"));
                        var provisionedSkills = workspaceProvisioner.provision(
                                projectDir, skillRegistry.listAll());
```

This must be **outside** the `try` block so `provisionedSkills` is accessible in the `finally` block for cleanup.

- [ ] **Step 2: Add TUI display of provisioned skills**

After `provisionedSkills` assignment, before `var client =`, add:

```java
                        // 在 Content 區顯示已配置的 skill（對齊 Claude Code 風格）
                        for (var skillName : provisionedSkills) {
                            var nameLine = new org.jline.utils.AttributedStringBuilder();
                            nameLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(2), "● ");
                            nameLine.append("Skill(" + skillName + ")");
                            contentView.appendLine(nameLine.toAttributedString());

                            var statusLine = new org.jline.utils.AttributedStringBuilder();
                            statusLine.styled(org.jline.utils.AttributedStyle.DEFAULT.foreground(245),
                                    "  └ Successfully loaded skill");
                            contentView.appendLine(statusLine.toAttributedString());
                            eventLoop.setDirty();
                        }
```

- [ ] **Step 3: Add cleanup in finally block**

In the `finally` block (after `agentRunning = false;`), add:

```java
                        // 清理 Grimo 建立的 symlink
                        workspaceProvisioner.cleanup(
                                java.nio.file.Path.of(System.getProperty("user.dir")),
                                provisionedSkills);
```

Note: `provisionedSkills` 宣告在 `try` 外部（Step 1），這樣 `finally` block（Step 3）才能存取它。

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run all skill-related tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.skill.*" --tests "io.github.samzhu.grimo.shared.sandbox.*" 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "feat: provision skills to .agents/skills/ on agent dispatch with TUI display (F2-d)"
```

---

### Task 6: Glossary 更新 + Spec 狀態 + 全量測試

**Files:**
- Modify: `docs/glossary.md`
- Modify: `docs/superpowers/specs/2026-03-29-f2d-sandbox-skill-mcp-provisioning.md`

- [ ] **Step 1: Update glossary.md**

在調度系統術語 section 新增：

```markdown
| **WorkspaceProvisioner** | Workspace Provisioner | 派遣 agent 前將 Grimo 管理的 Skill symlink 到工作目錄 `.agents/skills/`（跨 agent 標準路徑）。CLI agent（Claude/Gemini/Codex）原生發現 skill，Progressive Disclosure 自然運作。Grimo 的環境準備層，不同於 SDK 的 `LocalSandbox`（agent 執行隔離層）。 |
| **Sandbox** | Sandbox | Agent 執行環境。Local 模式直接使用工作目錄（symlink skill）；Docker/E2B 模式使用隔離容器（Phase B/C）。由 `SandboxDetector` 偵測可用後端，`WorkspaceProvisioner` 負責環境配置。 |
```

- [ ] **Step 2: Mark F2-d spec as Done**

Change `> Status: Draft` to `> Status: Done`

- [ ] **Step 3: Run full build**

Run: `./gradlew test 2>&1 | tail -15`
Expected: All tests pass (except 2 pre-existing failures: GrimoApplicationTests, ModulithStructureTest)

- [ ] **Step 4: Commit**

```bash
git add docs/glossary.md \
        docs/superpowers/specs/2026-03-29-f2d-sandbox-skill-mcp-provisioning.md
git commit -m "docs: update glossary with Sandbox terms and mark F2-d spec as Done"
```
