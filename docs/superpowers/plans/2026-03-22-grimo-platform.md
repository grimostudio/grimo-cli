# Grimo AI Assistant Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a locally-hosted AI assistant platform as a Spring Shell CLI with pluggable channels (Telegram, LINE), task scheduling with Markdown persistence, unified agent providers, hot-loadable skills, and MCP client support.

**Architecture:** Single Spring Boot JAR with Spring Modulith module boundaries enforced by package structure under `io.github.samzhu.grimo`. Modules communicate via domain events (`ApplicationModuleListener`). LLM providers, MCP servers, and channels are managed as plain Java objects at runtime (not Spring beans), enabling dynamic add/remove via CLI commands.

**Tech Stack:** Java 25 (Virtual Threads), Spring Boot 4.0.x, Spring Modulith 2.0.x, Spring Shell 4.0.x, Spring AI 2.0.0-M3 (library, not starter), MCP Java SDK 1.1.x (not Spring AI MCP Starter)

**Spec:** `docs/superpowers/specs/2026-03-22-grimo-openclaw-design-zh-TW.md`

---

## File Structure

### Phase 1: Foundation (shared + build setup)

| File | Responsibility |
|------|----------------|
| `build.gradle.kts` | Modify: switch from starters to libraries per spec |
| `src/main/resources/application.yaml` | Modify: add workspace and module config |
| `src/main/java/.../shared/workspace/WorkspaceManager.java` | Workspace path resolution and directory initialization |
| `src/main/java/.../shared/config/GrimoConfig.java` | YAML config loading from workspace `config.yaml` |
| `src/main/java/.../shared/config/GrimoProperties.java` | Spring Boot `@ConfigurationProperties` for bootstrap config |
| `src/main/java/.../shared/event/IncomingMessageEvent.java` | Domain event: incoming message from any channel |
| `src/main/java/.../shared/event/OutgoingMessageEvent.java` | Domain event: outgoing message to a channel |
| `src/main/java/.../shared/event/TaskCreateRequestEvent.java` | Domain event: request to create a task |
| `src/main/java/.../shared/event/ScheduleTaskEvent.java` | Domain event: request to schedule a task |
| `src/main/java/.../shared/event/TaskExecutionEvent.java` | Domain event: task execution triggered |
| `src/main/java/.../shared/event/TaskCompletedEvent.java` | Domain event: task completed |
| `src/main/java/.../shared/package-info.java` | Spring Modulith module declaration |
| `src/test/java/.../shared/workspace/WorkspaceManagerTest.java` | Unit tests for workspace management |
| `src/test/java/.../shared/config/GrimoConfigTest.java` | Unit tests for config loading |

### Phase 2: Agent Module

| File | Responsibility |
|------|----------------|
| `src/main/java/.../agent/provider/AgentProvider.java` | Unified provider interface (API + CLI) |
| `src/main/java/.../agent/provider/AgentType.java` | Enum: API, CLI |
| `src/main/java/.../agent/provider/AgentRequest.java` | Request record |
| `src/main/java/.../agent/provider/AgentResult.java` | Result record |
| `src/main/java/.../agent/provider/AnthropicAgentProvider.java` | Anthropic API provider implementation |
| `src/main/java/.../agent/provider/OpenAiAgentProvider.java` | OpenAI API provider implementation |
| `src/main/java/.../agent/provider/OllamaAgentProvider.java` | Ollama API provider implementation |
| `src/main/java/.../agent/provider/ClaudeCliAgentProvider.java` | Claude CLI provider implementation |
| `src/main/java/.../agent/registry/AgentProviderRegistry.java` | ConcurrentHashMap-based runtime registry |
| `src/main/java/.../agent/detect/AgentDetector.java` | Startup auto-detection of available agents |
| `src/main/java/.../agent/router/AgentRouter.java` | Route requests to best available provider |
| `src/main/java/.../agent/AgentCommands.java` | Spring Shell commands: agent list/add/switch |
| `src/main/java/.../agent/package-info.java` | Spring Modulith module declaration |
| `src/test/java/.../agent/registry/AgentProviderRegistryTest.java` | Unit tests |
| `src/test/java/.../agent/detect/AgentDetectorTest.java` | Unit tests |
| `src/test/java/.../agent/router/AgentRouterTest.java` | Unit tests |

### Phase 3: Task Module

| File | Responsibility |
|------|----------------|
| `src/main/java/.../task/model/Task.java` | Task record with all metadata |
| `src/main/java/.../task/model/TaskType.java` | Enum: IMMEDIATE, DELAYED, CRON |
| `src/main/java/.../task/model/TaskStatus.java` | Enum: PENDING, RUNNING, COMPLETED, FAILED, CANCELLED |
| `src/main/java/.../task/store/MarkdownTaskStore.java` | Read/write tasks as Markdown with YAML frontmatter |
| `src/main/java/.../task/scheduler/TaskSchedulerService.java` | Spring TaskScheduler + CronTrigger, restore on startup |
| `src/main/java/.../task/TaskCommands.java` | Spring Shell commands: task create/list/show/cancel/history |
| `src/main/java/.../task/TaskEventListener.java` | Listen for task-related domain events |
| `src/main/java/.../task/package-info.java` | Spring Modulith module declaration |
| `src/test/java/.../task/model/TaskTest.java` | Unit tests |
| `src/test/java/.../task/store/MarkdownTaskStoreTest.java` | Unit tests for markdown parsing |
| `src/test/java/.../task/scheduler/TaskSchedulerServiceTest.java` | Unit tests for scheduling |

### Phase 4: Skill Module

| File | Responsibility |
|------|----------------|
| `src/main/java/.../skill/loader/SkillLoader.java` | Scan and parse SKILL.md files |
| `src/main/java/.../skill/loader/SkillDefinition.java` | Parsed skill record (from SKILL.md frontmatter + body) |
| `src/main/java/.../skill/registry/SkillRegistry.java` | Runtime skill registry with hot-reload |
| `src/main/java/.../skill/builtin/HealthcheckSkill.java` | Built-in: HTTP/Actuator health check |
| `src/main/java/.../skill/builtin/RemindSkill.java` | Built-in: delayed reminder |
| `src/main/java/.../skill/builtin/CronReportSkill.java` | Built-in: scheduled task execution report |
| `src/main/java/.../skill/SkillCommands.java` | Spring Shell commands: skill list/install/remove |
| `src/main/java/.../skill/package-info.java` | Spring Modulith module declaration |
| `src/test/java/.../skill/loader/SkillLoaderTest.java` | Unit tests |
| `src/test/java/.../skill/registry/SkillRegistryTest.java` | Unit tests |

### Phase 5: MCP Module

| File | Responsibility |
|------|----------------|
| `src/main/java/.../mcp/client/McpClientRegistry.java` | Runtime MCP client registry (ConcurrentHashMap) |
| `src/main/java/.../mcp/client/McpClientManager.java` | Create/destroy MCP clients (STDIO + SSE) |
| `src/main/java/.../mcp/McpCommands.java` | Spring Shell commands: mcp add/list/remove |
| `src/main/java/.../mcp/package-info.java` | Spring Modulith module declaration |
| `src/test/java/.../mcp/client/McpClientRegistryTest.java` | Unit tests |
| `src/test/java/.../mcp/client/McpClientManagerTest.java` | Unit tests |

### Phase 6: Channel Module

| File | Responsibility |
|------|----------------|
| `src/main/java/.../channel/ChannelAdapter.java` | Unified adapter interface |
| `src/main/java/.../channel/ChannelRegistry.java` | Runtime channel registry |
| `src/main/java/.../channel/telegram/TelegramAdapter.java` | Telegram Long Polling adapter |
| `src/main/java/.../channel/line/LineAdapter.java` | LINE Webhook adapter |
| `src/main/java/.../channel/ChannelCommands.java` | Spring Shell commands: channel list/add/remove |
| `src/main/java/.../channel/ChannelEventListener.java` | Listen for OutgoingMessageEvent, route to channel |
| `src/main/java/.../channel/package-info.java` | Spring Modulith module declaration |
| `src/test/java/.../channel/ChannelRegistryTest.java` | Unit tests |

### Phase 7: Integration

| File | Responsibility |
|------|----------------|
| `src/main/java/.../GrimoApplication.java` | Modify: startup flow orchestration |
| `src/main/java/.../GrimoStartupRunner.java` | ApplicationRunner: ordered startup sequence |
| `src/main/java/.../GrimoOnboardingService.java` | First-run onboarding wizard |
| `src/main/java/.../GrimoCommands.java` | Top-level Shell commands: chat, status, help, config |
| `src/test/java/.../ModulithStructureTest.java` | Spring Modulith structure verification |
| `src/test/java/.../IntegrationTest.java` | Cross-module event flow tests |

---

## Phase 1: Foundation

### Task 1: Update build.gradle.kts

**Files:**
- Modify: `build.gradle.kts`

The current `build.gradle.kts` uses Spring AI Starters and includes dependencies not in the spec (SAML2, WebMVC). The spec requires using libraries (not starters) for Spring AI and MCP, to enable runtime dynamic management.

- [ ] **Step 1: Update dependencies to match spec**

Replace the current dependencies block. Key changes:
- Remove: `spring-ai-starter-mcp-client`, `spring-ai-starter-model-anthropic`, `spring-ai-starter-model-openai` (starters)
- Add: `spring-ai-anthropic`, `spring-ai-openai`, `spring-ai-ollama` (libraries)
- Add: `io.modelcontextprotocol.sdk:mcp:1.1.1` (MCP Java SDK, not starter)
- Remove: `spring-boot-starter-security-saml2` and its test dependency (not in spec)
- Keep: `spring-boot-starter-webmvc` (needed for LINE webhook)
- Keep: `spring-boot-starter-actuator`, `spring-shell-starter`, `spring-modulith-starter-core`
- Add: `org.yaml:snakeyaml` (for config.yaml parsing — already transitive but explicit)
- Add: TelegramBots Spring Boot Starter `org.telegram:telegrambots-spring-boot-starter:8.0.0`
- Add: LINE Bot SDK `com.linecorp.bot:line-bot-messaging-api-client:9.0.0`

```kotlin
dependencies {
    // Core
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.shell:spring-shell-starter")

    // Observability
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")

    // Spring AI — library, NOT starter (runtime dynamic creation)
    implementation("org.springframework.ai:spring-ai-anthropic")
    implementation("org.springframework.ai:spring-ai-openai")
    implementation("org.springframework.ai:spring-ai-ollama")

    // MCP Java SDK — NOT Spring AI MCP Starter (runtime dynamic management)
    implementation("io.modelcontextprotocol.sdk:mcp:1.1.1")

    // Channels
    implementation("org.telegram:telegrambots-spring-boot-starter:8.0.0")
    implementation("com.linecorp.bot:line-bot-messaging-api-client:9.0.0")

    // Modulith runtime
    runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
    runtimeOnly("org.springframework.modulith:spring-modulith-observability")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-micrometer-tracing-test")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.ai:spring-ai-spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.shell:spring-shell-starter-test")
    testImplementation("org.testcontainers:testcontainers-grafana")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "build: switch from Spring AI starters to libraries for runtime dynamic management"
```

---

### Task 2: Shared Module — Workspace Management

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/package-info.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/workspace/WorkspaceManager.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/workspace/WorkspaceManagerTest.java`

- [ ] **Step 1: Write failing tests for WorkspaceManager**

```java
package io.github.samzhu.grimo.shared.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void initializeShouldCreateRequiredDirectories() {
        var manager = new WorkspaceManager(tempDir);
        manager.initialize();

        assertThat(tempDir.resolve("tasks")).isDirectory();
        assertThat(tempDir.resolve("skills")).isDirectory();
        assertThat(tempDir.resolve("conversations")).isDirectory();
        assertThat(tempDir.resolve("logs")).isDirectory();
    }

    @Test
    void shouldReturnCorrectSubPaths() {
        var manager = new WorkspaceManager(tempDir);

        assertThat(manager.tasksDir()).isEqualTo(tempDir.resolve("tasks"));
        assertThat(manager.skillsDir()).isEqualTo(tempDir.resolve("skills"));
        assertThat(manager.conversationsDir()).isEqualTo(tempDir.resolve("conversations"));
        assertThat(manager.logsDir()).isEqualTo(tempDir.resolve("logs"));
        assertThat(manager.configFile()).isEqualTo(tempDir.resolve("config.yaml"));
    }

    @Test
    void isInitializedShouldReturnFalseForEmptyDir() {
        var manager = new WorkspaceManager(tempDir);
        assertThat(manager.isInitialized()).isFalse();
    }

    @Test
    void isInitializedShouldReturnTrueAfterInit() {
        var manager = new WorkspaceManager(tempDir);
        manager.initialize();
        assertThat(manager.isInitialized()).isTrue();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.workspace.WorkspaceManagerTest"`
Expected: FAIL — class does not exist

- [ ] **Step 3: Create Spring Modulith package-info.java**

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {}
)
package io.github.samzhu.grimo.shared;
```

- [ ] **Step 4: Implement WorkspaceManager**

```java
package io.github.samzhu.grimo.shared.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class WorkspaceManager {

    private final Path root;

    public WorkspaceManager(Path root) {
        this.root = root;
    }

    public void initialize() {
        createDir(tasksDir());
        createDir(skillsDir());
        createDir(conversationsDir());
        createDir(logsDir());
    }

    public boolean isInitialized() {
        return Files.isDirectory(tasksDir())
            && Files.isDirectory(skillsDir());
    }

    public Path root()              { return root; }
    public Path tasksDir()          { return root.resolve("tasks"); }
    public Path skillsDir()         { return root.resolve("skills"); }
    public Path conversationsDir()  { return root.resolve("conversations"); }
    public Path logsDir()           { return root.resolve("logs"); }
    public Path configFile()        { return root.resolve("config.yaml"); }

    private void createDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create directory: " + dir, e);
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.workspace.WorkspaceManagerTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/ src/test/java/io/github/samzhu/grimo/shared/
git commit -m "feat(shared): add WorkspaceManager for workspace directory management"
```

---

### Task 3: Shared Module — Config Loading

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/config/GrimoProperties.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java`
- Create: `src/test/java/io/github/samzhu/grimo/shared/config/GrimoConfigTest.java`
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Update application.yaml with workspace default**

```yaml
spring:
  application:
    name: grimo

grimo:
  workspace: ${user.home}/grimo-workspace
```

- [ ] **Step 2: Write failing tests for GrimoConfig**

```java
package io.github.samzhu.grimo.shared.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GrimoConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void loadShouldReturnEmptyMapWhenFileDoesNotExist() {
        var config = new GrimoConfig(tempDir.resolve("config.yaml"));
        assertThat(config.load()).isEmpty();
    }

    @Test
    void loadShouldParseYamlFile() throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            agents:
              default: claude-cli
            channels:
              telegram:
                enabled: true
            """);

        var config = new GrimoConfig(configFile);
        Map<String, Object> data = config.load();

        assertThat(data).containsKey("agents");
        assertThat(data).containsKey("channels");
    }

    @Test
    void saveShouldWriteYamlFile() {
        var configFile = tempDir.resolve("config.yaml");
        var config = new GrimoConfig(configFile);

        config.save(Map.of("agents", Map.of("default", "anthropic")));

        assertThat(configFile).exists();
        assertThat(configFile).content().contains("default: anthropic");
    }

    @Test
    void shouldSetFilePermissionsTo600() throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        var config = new GrimoConfig(configFile);

        config.save(Map.of("test", "data"));

        // On POSIX systems, verify permissions
        var perms = Files.getPosixFilePermissions(configFile);
        assertThat(perms).hasSize(2); // OWNER_READ, OWNER_WRITE
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.config.GrimoConfigTest"`
Expected: FAIL

- [ ] **Step 4: Implement GrimoProperties**

```java
package io.github.samzhu.grimo.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "grimo")
public record GrimoProperties(
    String workspace
) {}
```

- [ ] **Step 5: Implement GrimoConfig**

```java
package io.github.samzhu.grimo.shared.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class GrimoConfig {

    private final Path configFile;

    public GrimoConfig(Path configFile) {
        this.configFile = configFile;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> load() {
        if (!Files.exists(configFile)) {
            return new LinkedHashMap<>();
        }
        try (var reader = Files.newBufferedReader(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(reader);
            return data != null ? data : new LinkedHashMap<>();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read config: " + configFile, e);
        }
    }

    public void save(Map<String, Object> data) {
        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);

        try {
            Files.writeString(configFile, yaml.dump(data));
            Files.setPosixFilePermissions(configFile,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write config: " + configFile, e);
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.config.GrimoConfigTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/config/ src/test/java/io/github/samzhu/grimo/shared/config/ src/main/resources/application.yaml
git commit -m "feat(shared): add GrimoConfig for workspace config.yaml management"
```

---

### Task 4: Shared Module — Domain Events

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/IncomingMessageEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/OutgoingMessageEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/TaskCreateRequestEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/ScheduleTaskEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/TaskExecutionEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/TaskCompletedEvent.java`
- Create: `src/main/java/io/github/samzhu/grimo/shared/event/Attachment.java`

- [ ] **Step 1: Create all domain event records**

```java
// Attachment.java
package io.github.samzhu.grimo.shared.event;

public record Attachment(
    String type,      // "image", "file", "audio"
    String url,
    String fileName
) {}
```

```java
// IncomingMessageEvent.java
package io.github.samzhu.grimo.shared.event;

import java.time.Instant;
import java.util.List;

public record IncomingMessageEvent(
    String channelType,       // "telegram" | "line" | "cli"
    String channelUserId,
    String conversationId,
    String text,
    List<Attachment> attachments,
    Instant timestamp
) {}
```

```java
// OutgoingMessageEvent.java
package io.github.samzhu.grimo.shared.event;

import java.util.List;

public record OutgoingMessageEvent(
    String channelType,
    String conversationId,
    String text,
    List<Attachment> attachments
) {}
```

```java
// TaskCreateRequestEvent.java
package io.github.samzhu.grimo.shared.event;

public record TaskCreateRequestEvent(
    String description,
    String taskType,    // "immediate" | "delayed" | "cron"
    String cron,        // null for non-cron
    Long delaySeconds,  // null for non-delayed
    String sourceChannel,
    String conversationId
) {}
```

```java
// ScheduleTaskEvent.java
package io.github.samzhu.grimo.shared.event;

public record ScheduleTaskEvent(
    String taskId,
    String cron,
    String description
) {}
```

```java
// TaskExecutionEvent.java
package io.github.samzhu.grimo.shared.event;

import java.time.Instant;

public record TaskExecutionEvent(
    String taskId,
    String description,
    Instant triggeredAt
) {}
```

```java
// TaskCompletedEvent.java
package io.github.samzhu.grimo.shared.event;

import java.time.Instant;

public record TaskCompletedEvent(
    String taskId,
    boolean success,
    String result,
    Instant completedAt
) {}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/event/
git commit -m "feat(shared): add domain event records for inter-module communication"
```

---

## Phase 2: Agent Module

### Task 5: Agent Provider Interface and Registry

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/package-info.java`
- Create: `src/main/java/io/github/samzhu/grimo/agent/provider/AgentProvider.java`
- Create: `src/main/java/io/github/samzhu/grimo/agent/provider/AgentType.java`
- Create: `src/main/java/io/github/samzhu/grimo/agent/provider/AgentRequest.java`
- Create: `src/main/java/io/github/samzhu/grimo/agent/provider/AgentResult.java`
- Create: `src/main/java/io/github/samzhu/grimo/agent/registry/AgentProviderRegistry.java`
- Create: `src/test/java/io/github/samzhu/grimo/agent/registry/AgentProviderRegistryTest.java`

- [ ] **Step 1: Write failing tests for AgentProviderRegistry**

```java
package io.github.samzhu.grimo.agent.registry;

import io.github.samzhu.grimo.agent.provider.AgentProvider;
import io.github.samzhu.grimo.agent.provider.AgentRequest;
import io.github.samzhu.grimo.agent.provider.AgentResult;
import io.github.samzhu.grimo.agent.provider.AgentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProviderRegistryTest {

    AgentProviderRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentProviderRegistry();
    }

    @Test
    void registerAndRetrieveProvider() {
        var provider = stubProvider("test-agent", AgentType.API, true);
        registry.register("test-agent", provider);

        assertThat(registry.get("test-agent")).isPresent();
        assertThat(registry.get("test-agent").get().id()).isEqualTo("test-agent");
    }

    @Test
    void getReturnsEmptyForUnknownId() {
        assertThat(registry.get("nonexistent")).isEmpty();
    }

    @Test
    void removeProvider() {
        var provider = stubProvider("to-remove", AgentType.API, true);
        registry.register("to-remove", provider);
        registry.remove("to-remove");

        assertThat(registry.get("to-remove")).isEmpty();
    }

    @Test
    void listAllProviders() {
        registry.register("a", stubProvider("a", AgentType.API, true));
        registry.register("b", stubProvider("b", AgentType.CLI, true));

        assertThat(registry.listAll()).hasSize(2);
    }

    @Test
    void listAvailableProviders() {
        registry.register("available", stubProvider("available", AgentType.API, true));
        registry.register("unavailable", stubProvider("unavailable", AgentType.API, false));

        assertThat(registry.listAvailable()).hasSize(1);
        assertThat(registry.listAvailable().getFirst().id()).isEqualTo("available");
    }

    private AgentProvider stubProvider(String id, AgentType type, boolean available) {
        return new AgentProvider() {
            @Override public String id() { return id; }
            @Override public AgentType type() { return type; }
            @Override public boolean isAvailable() { return available; }
            @Override public AgentResult execute(AgentRequest request) {
                return new AgentResult(true, "stub response");
            }
        };
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.registry.AgentProviderRegistryTest"`
Expected: FAIL

- [ ] **Step 3: Create package-info.java for agent module**

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "shared", "shared::event", "shared::config", "shared::workspace" }
)
package io.github.samzhu.grimo.agent;
```

- [ ] **Step 4: Create AgentType enum**

```java
package io.github.samzhu.grimo.agent.provider;

public enum AgentType {
    API,
    CLI
}
```

- [ ] **Step 5: Create AgentRequest and AgentResult records**

```java
// AgentRequest.java
package io.github.samzhu.grimo.agent.provider;

import java.util.List;
import java.util.Map;

public record AgentRequest(
    String prompt,
    String systemInstruction,
    List<Map<String, String>> tools,
    Map<String, Object> options
) {
    public AgentRequest(String prompt) {
        this(prompt, null, List.of(), Map.of());
    }
}
```

```java
// AgentResult.java
package io.github.samzhu.grimo.agent.provider;

public record AgentResult(
    boolean success,
    String content
) {}
```

- [ ] **Step 6: Create AgentProvider interface**

```java
package io.github.samzhu.grimo.agent.provider;

public interface AgentProvider {
    String id();
    AgentType type();
    boolean isAvailable();
    AgentResult execute(AgentRequest request);
}
```

- [ ] **Step 7: Implement AgentProviderRegistry**

```java
package io.github.samzhu.grimo.agent.registry;

import io.github.samzhu.grimo.agent.provider.AgentProvider;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class AgentProviderRegistry {

    private final ConcurrentHashMap<String, AgentProvider> providers = new ConcurrentHashMap<>();

    public void register(String id, AgentProvider provider) {
        providers.put(id, provider);
    }

    public void remove(String id) {
        providers.remove(id);
    }

    public Optional<AgentProvider> get(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    public List<AgentProvider> listAll() {
        return List.copyOf(providers.values());
    }

    public List<AgentProvider> listAvailable() {
        return providers.values().stream()
            .filter(AgentProvider::isAvailable)
            .toList();
    }
}
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.registry.AgentProviderRegistryTest"`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/ src/test/java/io/github/samzhu/grimo/agent/
git commit -m "feat(agent): add AgentProvider interface and registry"
```

---

### Task 6: Agent Auto-Detection

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/detect/AgentDetector.java`
- Create: `src/test/java/io/github/samzhu/grimo/agent/detect/AgentDetectorTest.java`

- [ ] **Step 1: Write failing tests for AgentDetector**

```java
package io.github.samzhu.grimo.agent.detect;

import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDetectorTest {

    @Test
    void detectShouldReturnDetectionResults() {
        var registry = new AgentProviderRegistry();
        var detector = new AgentDetector(registry);

        var results = detector.detect();

        // Results is a list of DetectionResult records
        assertThat(results).isNotNull();
        // At minimum, it should check for claude CLI, env vars, ollama
        assertThat(results).isNotEmpty();
    }

    @Test
    void detectShouldCheckForAnthropicApiKey() {
        var registry = new AgentProviderRegistry();
        var detector = new AgentDetector(registry);

        var results = detector.detect();

        assertThat(results.stream()
            .anyMatch(r -> r.id().equals("anthropic"))).isTrue();
    }

    @Test
    void detectShouldCheckForClaudeCli() {
        var registry = new AgentProviderRegistry();
        var detector = new AgentDetector(registry);

        var results = detector.detect();

        assertThat(results.stream()
            .anyMatch(r -> r.id().equals("claude-cli"))).isTrue();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.detect.AgentDetectorTest"`
Expected: FAIL

- [ ] **Step 3: Implement AgentDetector**

```java
package io.github.samzhu.grimo.agent.detect;

import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AgentDetector {

    private final AgentProviderRegistry registry;

    public record DetectionResult(String id, String type, String detail, boolean available) {}

    public AgentDetector(AgentProviderRegistry registry) {
        this.registry = registry;
    }

    public List<DetectionResult> detect() {
        var results = new ArrayList<DetectionResult>();

        results.add(detectCliTool("claude-cli", "claude", "Claude Code CLI"));
        results.add(detectCliTool("codex-cli", "codex", "Codex CLI"));
        results.add(detectEnvKey("anthropic", "ANTHROPIC_API_KEY", "Anthropic API"));
        results.add(detectEnvKey("openai", "OPENAI_API_KEY", "OpenAI API"));
        results.add(detectOllama());

        return results;
    }

    private DetectionResult detectCliTool(String id, String command, String label) {
        try {
            var process = new ProcessBuilder("which", command)
                .redirectErrorStream(true)
                .start();
            int exit = process.waitFor();
            if (exit == 0) {
                String path = new String(process.getInputStream().readAllBytes()).trim();
                return new DetectionResult(id, "cli", label + " found at " + path, true);
            }
        } catch (IOException | InterruptedException _) {
            // not found
        }
        return new DetectionResult(id, "cli", label + " not found", false);
    }

    private DetectionResult detectEnvKey(String id, String envVar, String label) {
        String value = System.getenv(envVar);
        boolean available = value != null && !value.isBlank();
        String detail = available ? envVar + " detected" : envVar + " not set";
        return new DetectionResult(id, "api", label + ": " + detail, available);
    }

    private DetectionResult detectOllama() {
        try {
            var process = new ProcessBuilder("curl", "-s", "--connect-timeout", "2",
                    "http://localhost:11434/api/tags")
                .redirectErrorStream(true)
                .start();
            int exit = process.waitFor();
            boolean available = exit == 0;
            return new DetectionResult("ollama", "api",
                available ? "Ollama running (localhost:11434)" : "Ollama not reachable",
                available);
        } catch (IOException | InterruptedException _) {
            return new DetectionResult("ollama", "api", "Ollama not reachable", false);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.detect.AgentDetectorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/detect/ src/test/java/io/github/samzhu/grimo/agent/detect/
git commit -m "feat(agent): add AgentDetector for startup auto-detection"
```

---

### Task 7: Agent Router

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/router/AgentRouter.java`
- Create: `src/test/java/io/github/samzhu/grimo/agent/router/AgentRouterTest.java`

- [ ] **Step 1: Write failing tests for AgentRouter**

```java
package io.github.samzhu.grimo.agent.router;

import io.github.samzhu.grimo.agent.provider.*;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRouterTest {

    AgentProviderRegistry registry;
    AgentRouter router;

    @BeforeEach
    void setUp() {
        registry = new AgentProviderRegistry();
        router = new AgentRouter(registry);
    }

    @Test
    void routeByExplicitIdShouldReturnSpecifiedProvider() {
        registry.register("anthropic", stubProvider("anthropic", AgentType.API, true));
        registry.register("ollama", stubProvider("ollama", AgentType.API, true));

        var provider = router.route("anthropic");

        assertThat(provider.id()).isEqualTo("anthropic");
    }

    @Test
    void routeByExplicitIdShouldThrowWhenNotAvailable() {
        registry.register("offline", stubProvider("offline", AgentType.API, false));

        assertThatThrownBy(() -> router.route("offline"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void autoRouteShouldPreferCliOverApi() {
        registry.register("anthropic-api", stubProvider("anthropic-api", AgentType.API, true));
        registry.register("claude-cli", stubProvider("claude-cli", AgentType.CLI, true));

        var provider = router.route(null);

        assertThat(provider.type()).isEqualTo(AgentType.CLI);
    }

    @Test
    void autoRouteShouldFallbackToApiWhenNoCliAvailable() {
        registry.register("anthropic-api", stubProvider("anthropic-api", AgentType.API, true));

        var provider = router.route(null);

        assertThat(provider.type()).isEqualTo(AgentType.API);
    }

    @Test
    void autoRouteShouldThrowWhenNoProvidersAvailable() {
        assertThatThrownBy(() -> router.route(null))
            .isInstanceOf(IllegalStateException.class);
    }

    private AgentProvider stubProvider(String id, AgentType type, boolean available) {
        return new AgentProvider() {
            @Override public String id() { return id; }
            @Override public AgentType type() { return type; }
            @Override public boolean isAvailable() { return available; }
            @Override public AgentResult execute(AgentRequest request) {
                return new AgentResult(true, "stub");
            }
        };
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.router.AgentRouterTest"`
Expected: FAIL

- [ ] **Step 3: Implement AgentRouter**

```java
package io.github.samzhu.grimo.agent.router;

import io.github.samzhu.grimo.agent.provider.AgentProvider;
import io.github.samzhu.grimo.agent.provider.AgentType;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;

import java.util.Comparator;

public class AgentRouter {

    private final AgentProviderRegistry registry;

    public AgentRouter(AgentProviderRegistry registry) {
        this.registry = registry;
    }

    public AgentProvider route(String explicitAgentId) {
        if (explicitAgentId != null) {
            return registry.get(explicitAgentId)
                .filter(AgentProvider::isAvailable)
                .orElseThrow(() -> new IllegalStateException(
                    "Agent not available: " + explicitAgentId));
        }
        return autoSelect();
    }

    private AgentProvider autoSelect() {
        return registry.listAvailable().stream()
            .sorted(Comparator.comparingInt(p -> p.type() == AgentType.CLI ? 0 : 1))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No agent providers available. Run 'agent add' to configure one."));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.router.AgentRouterTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/router/ src/test/java/io/github/samzhu/grimo/agent/router/
git commit -m "feat(agent): add AgentRouter with CLI-preferred auto-selection and fallback"
```

---

### Task 8: Agent CLI Commands

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java`
- Create: `src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java`

- [ ] **Step 1: Write failing test for agent list command**

```java
package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.provider.*;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCommandsTest {

    AgentProviderRegistry registry;
    AgentCommands commands;

    @BeforeEach
    void setUp() {
        registry = new AgentProviderRegistry();
        commands = new AgentCommands(registry);
    }

    @Test
    void listShouldReturnFormattedTable() {
        registry.register("anthropic", stubProvider("anthropic", AgentType.API, true));
        registry.register("claude-cli", stubProvider("claude-cli", AgentType.CLI, true));

        String output = commands.list();

        assertThat(output).contains("anthropic");
        assertThat(output).contains("claude-cli");
        assertThat(output).contains("ready");
    }

    @Test
    void listShouldShowEmptyMessage() {
        String output = commands.list();
        assertThat(output).contains("No agents configured");
    }

    private AgentProvider stubProvider(String id, AgentType type, boolean available) {
        return new AgentProvider() {
            @Override public String id() { return id; }
            @Override public AgentType type() { return type; }
            @Override public boolean isAvailable() { return available; }
            @Override public AgentResult execute(AgentRequest request) {
                return new AgentResult(true, "stub");
            }
        };
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.AgentCommandsTest"`
Expected: FAIL

- [ ] **Step 3: Implement AgentCommands**

```java
package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.provider.AgentProvider;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

@ShellComponent
public class AgentCommands {

    private final AgentProviderRegistry registry;

    public AgentCommands(AgentProviderRegistry registry) {
        this.registry = registry;
    }

    @ShellMethod(key = "agent list", value = "List all configured agent providers")
    public String list() {
        var providers = registry.listAll();
        if (providers.isEmpty()) {
            return "No agents configured. Run 'agent add <provider>' to add one.";
        }

        var sb = new StringBuilder();
        sb.append(String.format("  %-15s %-6s %-8s%n", "ID", "TYPE", "STATUS"));
        for (AgentProvider p : providers) {
            String status = p.isAvailable() ? "ready" : "not available";
            sb.append(String.format("  %-15s %-6s %-8s%n", p.id(), p.type(), status));
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.AgentCommandsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java
git commit -m "feat(agent): add Spring Shell 'agent list' command"
```

---

### Task 8b: Anthropic Agent Provider Implementation

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/provider/AnthropicAgentProvider.java`
- Create: `src/test/java/io/github/samzhu/grimo/agent/provider/AnthropicAgentProviderTest.java`

At least one real provider is needed for the platform to be functional. This task implements the Anthropic API provider using Spring AI as a library (not starter).

- [ ] **Step 1: Write failing test for AnthropicAgentProvider**

```java
package io.github.samzhu.grimo.agent.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicAgentProviderTest {

    @Test
    void shouldReportCorrectIdAndType() {
        var provider = new AnthropicAgentProvider("test-key", "claude-sonnet-4");

        assertThat(provider.id()).isEqualTo("anthropic");
        assertThat(provider.type()).isEqualTo(AgentType.API);
    }

    @Test
    void isAvailableShouldReturnTrueWhenApiKeyProvided() {
        var provider = new AnthropicAgentProvider("sk-ant-test", "claude-sonnet-4");
        assertThat(provider.isAvailable()).isTrue();
    }

    @Test
    void isAvailableShouldReturnFalseWhenApiKeyBlank() {
        var provider = new AnthropicAgentProvider("", "claude-sonnet-4");
        assertThat(provider.isAvailable()).isFalse();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.provider.AnthropicAgentProviderTest"`
Expected: FAIL

- [ ] **Step 3: Implement AnthropicAgentProvider**

```java
package io.github.samzhu.grimo.agent.provider;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.prompt.Prompt;

public class AnthropicAgentProvider implements AgentProvider {

    private final String apiKey;
    private final String modelName;
    private final AnthropicChatModel chatModel;

    public AnthropicAgentProvider(String apiKey, String modelName) {
        this.apiKey = apiKey;
        this.modelName = modelName;

        if (apiKey != null && !apiKey.isBlank()) {
            var api = new AnthropicApi(apiKey);
            this.chatModel = AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder()
                    .model(modelName)
                    .build())
                .build();
        } else {
            this.chatModel = null;
        }
    }

    @Override
    public String id() { return "anthropic"; }

    @Override
    public AgentType type() { return AgentType.API; }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && chatModel != null;
    }

    @Override
    public AgentResult execute(AgentRequest request) {
        if (!isAvailable()) {
            return new AgentResult(false, "Anthropic provider not available — no API key configured");
        }
        try {
            var prompt = request.systemInstruction() != null
                ? new Prompt(request.systemInstruction() + "\n\n" + request.prompt())
                : new Prompt(request.prompt());
            var response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getText();
            return new AgentResult(true, content);
        } catch (Exception e) {
            return new AgentResult(false, "Anthropic API error: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.provider.AnthropicAgentProviderTest"`
Expected: PASS

- [ ] **Step 5: Update AgentDetector to auto-register Anthropic provider when API key is found**

In `AgentDetector.detect()`, after detecting the API key, also register the provider:

```java
// Add to AgentDetector.detect() after detectEnvKey("anthropic", ...)
if (results.stream().anyMatch(r -> r.id().equals("anthropic") && r.available())) {
    String key = System.getenv("ANTHROPIC_API_KEY");
    registry.register("anthropic", new AnthropicAgentProvider(key, "claude-sonnet-4"));
}
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/provider/AnthropicAgentProvider.java src/test/java/io/github/samzhu/grimo/agent/provider/AnthropicAgentProviderTest.java src/main/java/io/github/samzhu/grimo/agent/detect/AgentDetector.java
git commit -m "feat(agent): add AnthropicAgentProvider with Spring AI library integration"
```

---

## Phase 3: Task Module

### Task 9: Task Model

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/task/package-info.java`
- Create: `src/main/java/io/github/samzhu/grimo/task/model/Task.java`
- Create: `src/main/java/io/github/samzhu/grimo/task/model/TaskType.java`
- Create: `src/main/java/io/github/samzhu/grimo/task/model/TaskStatus.java`

- [ ] **Step 1: Create package-info.java for task module**

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "shared", "shared::event", "shared::config", "shared::workspace" }
)
package io.github.samzhu.grimo.task;
```

- [ ] **Step 2: Create TaskType and TaskStatus enums**

```java
// TaskType.java
package io.github.samzhu.grimo.task.model;

public enum TaskType {
    IMMEDIATE,
    DELAYED,
    CRON
}
```

```java
// TaskStatus.java
package io.github.samzhu.grimo.task.model;

public enum TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
```

- [ ] **Step 3: Create Task record**

```java
package io.github.samzhu.grimo.task.model;

import java.time.Instant;

public record Task(
    String id,
    TaskType type,
    TaskStatus status,
    String description,
    String cron,           // null for non-cron
    Long delaySeconds,     // null for non-delayed
    String channel,        // source channel
    Instant created,
    Instant lastRun,
    Instant nextRun,
    String body            // markdown body (instructions + execution log)
) {
    public Task withStatus(TaskStatus newStatus) {
        return new Task(id, type, newStatus, description, cron, delaySeconds,
            channel, created, lastRun, nextRun, body);
    }

    public Task withLastRun(Instant run) {
        return new Task(id, type, status, description, cron, delaySeconds,
            channel, created, run, nextRun, body);
    }

    public Task withNextRun(Instant next) {
        return new Task(id, type, status, description, cron, delaySeconds,
            channel, created, lastRun, next, body);
    }

    public Task withBody(String newBody) {
        return new Task(id, type, status, description, cron, delaySeconds,
            channel, created, lastRun, nextRun, newBody);
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/task/
git commit -m "feat(task): add Task model with TaskType and TaskStatus enums"
```

---

### Task 10: Markdown Task Store

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/task/store/MarkdownTaskStore.java`
- Create: `src/test/java/io/github/samzhu/grimo/task/store/MarkdownTaskStoreTest.java`

- [ ] **Step 1: Write failing tests for MarkdownTaskStore**

```java
package io.github.samzhu.grimo.task.store;

import io.github.samzhu.grimo.task.model.Task;
import io.github.samzhu.grimo.task.model.TaskStatus;
import io.github.samzhu.grimo.task.model.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownTaskStoreTest {

    @TempDir
    Path tasksDir;

    MarkdownTaskStore store;

    @BeforeEach
    void setUp() {
        store = new MarkdownTaskStore(tasksDir);
    }

    @Test
    void saveShouldCreateMarkdownFile() {
        var task = sampleTask("task-001", TaskType.CRON, "Check API health");
        store.save(task);

        assertThat(tasksDir.resolve("task-001.md")).exists();
    }

    @Test
    void saveShouldWriteYamlFrontmatter() throws Exception {
        var task = sampleTask("task-002", TaskType.CRON, "Check API health");
        store.save(task);

        String content = Files.readString(tasksDir.resolve("task-002.md"));
        assertThat(content).startsWith("---");
        assertThat(content).contains("id: task-002");
        assertThat(content).contains("type: cron");
        assertThat(content).contains("status: pending");
    }

    @Test
    void loadShouldParseMarkdownFile() {
        var task = sampleTask("task-003", TaskType.IMMEDIATE, "Quick task");
        store.save(task);

        var loaded = store.load("task-003");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().id()).isEqualTo("task-003");
        assertThat(loaded.get().description()).isEqualTo("Quick task");
        assertThat(loaded.get().type()).isEqualTo(TaskType.IMMEDIATE);
    }

    @Test
    void loadAllShouldReturnAllTasks() {
        store.save(sampleTask("task-a", TaskType.CRON, "Task A"));
        store.save(sampleTask("task-b", TaskType.IMMEDIATE, "Task B"));

        var tasks = store.loadAll();

        assertThat(tasks).hasSize(2);
    }

    @Test
    void appendExecutionLogShouldAddToBody() throws Exception {
        var task = sampleTask("task-004", TaskType.CRON, "Monitored task");
        store.save(task);

        store.appendExecutionLog("task-004", "2026-03-22 09:00", "API responded 200 OK");

        String content = Files.readString(tasksDir.resolve("task-004.md"));
        assertThat(content).contains("### 2026-03-22 09:00");
        assertThat(content).contains("API responded 200 OK");
    }

    @Test
    void deleteShouldRemoveFile() {
        store.save(sampleTask("task-005", TaskType.IMMEDIATE, "To delete"));
        store.delete("task-005");

        assertThat(tasksDir.resolve("task-005.md")).doesNotExist();
    }

    private Task sampleTask(String id, TaskType type, String description) {
        return new Task(
            id, type, TaskStatus.PENDING, description,
            type == TaskType.CRON ? "0 9 * * *" : null,
            null, "cli",
            Instant.parse("2026-03-22T02:30:00Z"),
            null, null,
            "# " + description
        );
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.task.store.MarkdownTaskStoreTest"`
Expected: FAIL

- [ ] **Step 3: Implement MarkdownTaskStore**

```java
package io.github.samzhu.grimo.task.store;

import io.github.samzhu.grimo.task.model.Task;
import io.github.samzhu.grimo.task.model.TaskStatus;
import io.github.samzhu.grimo.task.model.TaskType;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

public class MarkdownTaskStore {

    private final Path tasksDir;

    public MarkdownTaskStore(Path tasksDir) {
        this.tasksDir = tasksDir;
    }

    public void save(Task task) {
        var frontmatter = new LinkedHashMap<String, Object>();
        frontmatter.put("id", task.id());
        frontmatter.put("type", task.type().name().toLowerCase());
        frontmatter.put("status", task.status().name().toLowerCase());
        if (task.cron() != null) frontmatter.put("cron", task.cron());
        if (task.channel() != null) frontmatter.put("channel", task.channel());
        frontmatter.put("created", task.created().toString());
        if (task.lastRun() != null) frontmatter.put("last_run", task.lastRun().toString());
        if (task.nextRun() != null) frontmatter.put("next_run", task.nextRun().toString());

        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String yaml = new Yaml(options).dump(frontmatter);

        String content = "---\n" + yaml + "---\n\n" + (task.body() != null ? task.body() : "");

        try {
            Files.writeString(tasksDir.resolve(task.id() + ".md"), content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save task: " + task.id(), e);
        }
    }

    public Optional<Task> load(String taskId) {
        Path file = tasksDir.resolve(taskId + ".md");
        if (!Files.exists(file)) return Optional.empty();

        try {
            return Optional.of(parseMarkdown(Files.readString(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load task: " + taskId, e);
        }
    }

    public List<Task> loadAll() {
        try (Stream<Path> files = Files.list(tasksDir)) {
            return files
                .filter(p -> p.toString().endsWith(".md"))
                .map(p -> {
                    try {
                        return parseMarkdown(Files.readString(p));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list tasks", e);
        }
    }

    public void appendExecutionLog(String taskId, String timestamp, String log) {
        Path file = tasksDir.resolve(taskId + ".md");
        try {
            String content = Files.readString(file);
            String logSection = "\n\n## 執行紀錄\n";
            if (!content.contains("## 執行紀錄")) {
                content += logSection;
            }
            content += "\n### " + timestamp + "\n" + log + "\n";
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append log to task: " + taskId, e);
        }
    }

    public void delete(String taskId) {
        try {
            Files.deleteIfExists(tasksDir.resolve(taskId + ".md"));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete task: " + taskId, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Task parseMarkdown(String content) {
        // Split frontmatter and body
        String[] parts = content.split("---", 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid task markdown: missing frontmatter");
        }

        Yaml yaml = new Yaml();
        Map<String, Object> fm = yaml.load(parts[1]);
        String body = parts[2].strip();

        return new Task(
            (String) fm.get("id"),
            TaskType.valueOf(((String) fm.get("type")).toUpperCase()),
            TaskStatus.valueOf(((String) fm.get("status")).toUpperCase()),
            body.lines().findFirst().map(l -> l.replaceFirst("^#+\\s*", "")).orElse(""),
            (String) fm.get("cron"),
            fm.containsKey("delay_seconds") ? ((Number) fm.get("delay_seconds")).longValue() : null,
            (String) fm.get("channel"),
            fm.containsKey("created") ? Instant.parse((String) fm.get("created")) : null,
            fm.containsKey("last_run") ? Instant.parse((String) fm.get("last_run")) : null,
            fm.containsKey("next_run") ? Instant.parse((String) fm.get("next_run")) : null,
            body
        );
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.task.store.MarkdownTaskStoreTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/task/store/ src/test/java/io/github/samzhu/grimo/task/store/
git commit -m "feat(task): add MarkdownTaskStore for YAML frontmatter task persistence"
```

---

### Task 11: Task Scheduler Service

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/task/scheduler/TaskSchedulerService.java`
- Create: `src/test/java/io/github/samzhu/grimo/task/scheduler/TaskSchedulerServiceTest.java`

- [ ] **Step 1: Write failing tests for TaskSchedulerService**

```java
package io.github.samzhu.grimo.task.scheduler;

import io.github.samzhu.grimo.task.model.Task;
import io.github.samzhu.grimo.task.model.TaskStatus;
import io.github.samzhu.grimo.task.model.TaskType;
import io.github.samzhu.grimo.task.store.MarkdownTaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TaskSchedulerServiceTest {

    @TempDir
    Path tasksDir;

    MarkdownTaskStore store;
    ThreadPoolTaskScheduler scheduler;
    ApplicationEventPublisher publisher;
    TaskSchedulerService service;

    @BeforeEach
    void setUp() {
        store = new MarkdownTaskStore(tasksDir);
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        publisher = mock(ApplicationEventPublisher.class);
        service = new TaskSchedulerService(store, scheduler, publisher);
    }

    @Test
    void scheduleCronTaskShouldRegisterTask() {
        var task = cronTask("task-cron-1", "0 0 9 * * *");
        store.save(task);

        service.scheduleCron(task);

        assertThat(service.getScheduledTaskIds()).contains("task-cron-1");
    }

    @Test
    void cancelShouldRemoveScheduledTask() {
        var task = cronTask("task-cron-2", "0 0 9 * * *");
        store.save(task);
        service.scheduleCron(task);

        service.cancel("task-cron-2");

        assertThat(service.getScheduledTaskIds()).doesNotContain("task-cron-2");
    }

    @Test
    void restoreAllShouldScheduleExistingCronTasks() {
        store.save(cronTask("cron-a", "0 0 9 * * *"));
        store.save(cronTask("cron-b", "0 0 18 * * *"));
        store.save(immediateTask("imm-c"));

        service.restoreAll();

        assertThat(service.getScheduledTaskIds()).containsExactlyInAnyOrder("cron-a", "cron-b");
    }

    private Task cronTask(String id, String cron) {
        return new Task(id, TaskType.CRON, TaskStatus.PENDING, "Test cron",
            cron, null, "cli", Instant.now(), null, null, "# Test");
    }

    private Task immediateTask(String id) {
        return new Task(id, TaskType.IMMEDIATE, TaskStatus.PENDING, "Test immediate",
            null, null, "cli", Instant.now(), null, null, "# Test");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.task.scheduler.TaskSchedulerServiceTest"`
Expected: FAIL

- [ ] **Step 3: Implement TaskSchedulerService**

```java
package io.github.samzhu.grimo.task.scheduler;

import io.github.samzhu.grimo.shared.event.TaskExecutionEvent;
import io.github.samzhu.grimo.task.model.Task;
import io.github.samzhu.grimo.task.model.TaskType;
import io.github.samzhu.grimo.task.store.MarkdownTaskStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

public class TaskSchedulerService {

    private final MarkdownTaskStore store;
    private final TaskScheduler scheduler;
    private final ApplicationEventPublisher publisher;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public TaskSchedulerService(MarkdownTaskStore store, TaskScheduler scheduler,
                                 ApplicationEventPublisher publisher) {
        this.store = store;
        this.scheduler = scheduler;
        this.publisher = publisher;
    }

    public void scheduleCron(Task task) {
        var future = scheduler.schedule(
            () -> executeTask(task),
            new CronTrigger(task.cron())
        );
        scheduledTasks.put(task.id(), future);
    }

    public void cancel(String taskId) {
        var future = scheduledTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }

    public Set<String> getScheduledTaskIds() {
        return Set.copyOf(scheduledTasks.keySet());
    }

    public void restoreAll() {
        store.loadAll().stream()
            .filter(t -> t.type() == TaskType.CRON)
            .filter(t -> t.cron() != null)
            .forEach(this::scheduleCron);
    }

    private void executeTask(Task task) {
        publisher.publishEvent(new TaskExecutionEvent(
            task.id(), task.description(), Instant.now()
        ));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.task.scheduler.TaskSchedulerServiceTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/task/scheduler/ src/test/java/io/github/samzhu/grimo/task/scheduler/
git commit -m "feat(task): add TaskSchedulerService with cron scheduling and restore"
```

---

### Task 12: Task CLI Commands

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/task/TaskCommands.java`
- Create: `src/test/java/io/github/samzhu/grimo/task/TaskCommandsTest.java`

- [ ] **Step 1: Write failing tests for TaskCommands**

```java
package io.github.samzhu.grimo.task;

import io.github.samzhu.grimo.task.model.Task;
import io.github.samzhu.grimo.task.model.TaskStatus;
import io.github.samzhu.grimo.task.model.TaskType;
import io.github.samzhu.grimo.task.scheduler.TaskSchedulerService;
import io.github.samzhu.grimo.task.store.MarkdownTaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TaskCommandsTest {

    @TempDir
    Path tasksDir;

    MarkdownTaskStore store;
    TaskSchedulerService schedulerService;
    TaskCommands commands;

    @BeforeEach
    void setUp() {
        store = new MarkdownTaskStore(tasksDir);
        schedulerService = mock(TaskSchedulerService.class);
        commands = new TaskCommands(store, schedulerService);
    }

    @Test
    void createShouldSaveTaskAndReturnConfirmation() {
        String result = commands.create("Check API health", "0 0 9 * * *");

        assertThat(result).contains("Task created");
        assertThat(store.loadAll()).hasSize(1);
    }

    @Test
    void createCronShouldScheduleTask() {
        commands.create("Check API health", "0 0 9 * * *");

        verify(schedulerService).scheduleCron(any(Task.class));
    }

    @Test
    void listShouldShowTasks() {
        store.save(sampleTask("task-001", "Task A"));
        store.save(sampleTask("task-002", "Task B"));

        String result = commands.list();

        assertThat(result).contains("task-001");
        assertThat(result).contains("task-002");
    }

    @Test
    void showShouldDisplayTaskDetails() {
        store.save(sampleTask("task-001", "Check API"));

        String result = commands.show("task-001");

        assertThat(result).contains("task-001");
        assertThat(result).contains("Check API");
    }

    @Test
    void cancelShouldUpdateStatusAndCancelSchedule() {
        store.save(sampleTask("task-001", "To cancel"));

        String result = commands.cancel("task-001");

        assertThat(result).contains("cancelled");
        verify(schedulerService).cancel("task-001");
    }

    private Task sampleTask(String id, String desc) {
        return new Task(id, TaskType.CRON, TaskStatus.PENDING, desc,
            "0 9 * * *", null, "cli", Instant.now(), null, null, "# " + desc);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.task.TaskCommandsTest"`
Expected: FAIL

- [ ] **Step 3: Implement TaskCommands**

```java
package io.github.samzhu.grimo.task;

import io.github.samzhu.grimo.task.model.Task;
import io.github.samzhu.grimo.task.model.TaskStatus;
import io.github.samzhu.grimo.task.model.TaskType;
import io.github.samzhu.grimo.task.scheduler.TaskSchedulerService;
import io.github.samzhu.grimo.task.store.MarkdownTaskStore;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@ShellComponent
public class TaskCommands {

    private final MarkdownTaskStore store;
    private final TaskSchedulerService schedulerService;

    public TaskCommands(MarkdownTaskStore store, TaskSchedulerService schedulerService) {
        this.store = store;
        this.schedulerService = schedulerService;
    }

    @ShellMethod(key = "task create", value = "Create a new task")
    public String create(
            String description,
            @ShellOption(defaultValue = ShellOption.NULL) String cron) {

        TaskType type = cron != null ? TaskType.CRON : TaskType.IMMEDIATE;
        String id = "task-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                     + "-" + UUID.randomUUID().toString().substring(0, 8);

        var task = new Task(id, type, TaskStatus.PENDING, description,
            cron, null, "cli", Instant.now(), null, null, "# " + description);

        store.save(task);

        if (type == TaskType.CRON) {
            schedulerService.scheduleCron(task);
        }

        return "Task created: " + id;
    }

    @ShellMethod(key = "task list", value = "List all tasks")
    public String list() {
        var tasks = store.loadAll();
        if (tasks.isEmpty()) return "No tasks found.";

        var sb = new StringBuilder();
        sb.append(String.format("  %-25s %-10s %-10s %s%n", "ID", "TYPE", "STATUS", "DESCRIPTION"));
        for (Task t : tasks) {
            sb.append(String.format("  %-25s %-10s %-10s %s%n",
                t.id(), t.type(), t.status(), t.description()));
        }
        return sb.toString();
    }

    @ShellMethod(key = "task show", value = "Show task details")
    public String show(String taskId) {
        return store.load(taskId)
            .map(t -> String.format("""
                ID:          %s
                Type:        %s
                Status:      %s
                Cron:        %s
                Channel:     %s
                Created:     %s
                Last Run:    %s
                Next Run:    %s

                %s""",
                t.id(), t.type(), t.status(),
                t.cron() != null ? t.cron() : "N/A",
                t.channel(), t.created(),
                t.lastRun() != null ? t.lastRun() : "N/A",
                t.nextRun() != null ? t.nextRun() : "N/A",
                t.body()))
            .orElse("Task not found: " + taskId);
    }

    @ShellMethod(key = "task cancel", value = "Cancel a task")
    public String cancel(String taskId) {
        return store.load(taskId).map(task -> {
            store.save(task.withStatus(TaskStatus.CANCELLED));
            schedulerService.cancel(taskId);
            return "Task cancelled: " + taskId;
        }).orElse("Task not found: " + taskId);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.task.TaskCommandsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/task/TaskCommands.java src/test/java/io/github/samzhu/grimo/task/TaskCommandsTest.java
git commit -m "feat(task): add Spring Shell task commands (create/list/show/cancel)"
```

---

## Phase 4: Skill Module

### Task 13: Skill Loader and Registry

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/skill/package-info.java`
- Create: `src/main/java/io/github/samzhu/grimo/skill/loader/SkillDefinition.java`
- Create: `src/main/java/io/github/samzhu/grimo/skill/loader/SkillLoader.java`
- Create: `src/main/java/io/github/samzhu/grimo/skill/registry/SkillRegistry.java`
- Create: `src/test/java/io/github/samzhu/grimo/skill/loader/SkillLoaderTest.java`
- Create: `src/test/java/io/github/samzhu/grimo/skill/registry/SkillRegistryTest.java`

- [ ] **Step 1: Create package-info.java for skill module**

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "shared", "shared::event", "shared::config", "shared::workspace" }
)
package io.github.samzhu.grimo.skill;
```

- [ ] **Step 2: Write failing tests for SkillLoader**

```java
package io.github.samzhu.grimo.skill.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillLoaderTest {

    @TempDir
    Path skillsDir;

    @Test
    void loadShouldParseSkillMdFrontmatter() throws Exception {
        var skillDir = skillsDir.resolve("healthcheck");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: healthcheck
            description: Check service health status
            version: 1.0.0
            author: grimo-builtin
            executor: api
            triggers:
              - cron
              - command
            ---

            # Health Check

            Check the specified URL HTTP status.
            """);

        var loader = new SkillLoader(skillsDir);
        List<SkillDefinition> skills = loader.loadAll();

        assertThat(skills).hasSize(1);
        var skill = skills.getFirst();
        assertThat(skill.name()).isEqualTo("healthcheck");
        assertThat(skill.description()).isEqualTo("Check service health status");
        assertThat(skill.version()).isEqualTo("1.0.0");
        assertThat(skill.author()).isEqualTo("grimo-builtin");
        assertThat(skill.executor()).isEqualTo("api");
        assertThat(skill.triggers()).containsExactly("cron", "command");
        assertThat(skill.body()).contains("# Health Check");
    }

    @Test
    void loadShouldSkipDirectoriesWithoutSkillMd() throws Exception {
        Files.createDirectories(skillsDir.resolve("empty-dir"));
        Files.createDirectories(skillsDir.resolve("valid"));
        Files.writeString(skillsDir.resolve("valid/SKILL.md"), """
            ---
            name: valid
            description: A valid skill
            version: 1.0.0
            author: test
            executor: api
            triggers: []
            ---

            # Valid
            """);

        var loader = new SkillLoader(skillsDir);
        assertThat(loader.loadAll()).hasSize(1);
    }

    @Test
    void loadShouldReturnEmptyForEmptyDirectory() {
        var loader = new SkillLoader(skillsDir);
        assertThat(loader.loadAll()).isEmpty();
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.skill.loader.SkillLoaderTest"`
Expected: FAIL

- [ ] **Step 4: Implement SkillDefinition**

```java
package io.github.samzhu.grimo.skill.loader;

import java.util.List;

public record SkillDefinition(
    String name,
    String description,
    String version,
    String author,
    String executor,
    List<String> triggers,
    String body
) {}
```

- [ ] **Step 5: Implement SkillLoader**

```java
package io.github.samzhu.grimo.skill.loader;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class SkillLoader {

    private final Path skillsDir;

    public SkillLoader(Path skillsDir) {
        this.skillsDir = skillsDir;
    }

    public List<SkillDefinition> loadAll() {
        if (!Files.isDirectory(skillsDir)) return List.of();

        var skills = new ArrayList<SkillDefinition>();
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory)
                .forEach(dir -> {
                    Path skillMd = dir.resolve("SKILL.md");
                    if (Files.exists(skillMd)) {
                        skills.add(parseSkillMd(skillMd));
                    }
                });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan skills directory", e);
        }
        return skills;
    }

    public SkillDefinition load(Path skillMdPath) {
        return parseSkillMd(skillMdPath);
    }

    @SuppressWarnings("unchecked")
    private SkillDefinition parseSkillMd(Path path) {
        try {
            String content = Files.readString(path);
            String[] parts = content.split("---", 3);
            if (parts.length < 3) {
                throw new IllegalArgumentException("Invalid SKILL.md: missing frontmatter in " + path);
            }

            Yaml yaml = new Yaml();
            Map<String, Object> fm = yaml.load(parts[1]);
            String body = parts[2].strip();

            List<String> triggers = fm.containsKey("triggers")
                ? ((List<Object>) fm.get("triggers")).stream().map(Object::toString).toList()
                : List.of();

            return new SkillDefinition(
                (String) fm.get("name"),
                (String) fm.get("description"),
                (String) fm.get("version"),
                (String) fm.get("author"),
                (String) fm.get("executor"),
                triggers,
                body
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read SKILL.md: " + path, e);
        }
    }
}
```

- [ ] **Step 6: Run SkillLoader tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.skill.loader.SkillLoaderTest"`
Expected: PASS

- [ ] **Step 7: Write failing tests for SkillRegistry**

```java
package io.github.samzhu.grimo.skill.registry;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRegistryTest {

    SkillRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
    }

    @Test
    void registerAndGetSkill() {
        var skill = sampleSkill("healthcheck");
        registry.register(skill);

        assertThat(registry.get("healthcheck")).isPresent();
        assertThat(registry.get("healthcheck").get().name()).isEqualTo("healthcheck");
    }

    @Test
    void removeShouldUnregisterSkill() {
        registry.register(sampleSkill("to-remove"));
        registry.remove("to-remove");

        assertThat(registry.get("to-remove")).isEmpty();
    }

    @Test
    void listAllShouldReturnAllSkills() {
        registry.register(sampleSkill("a"));
        registry.register(sampleSkill("b"));

        assertThat(registry.listAll()).hasSize(2);
    }

    @Test
    void registerShouldOverwriteExisting() {
        registry.register(new SkillDefinition("x", "old", "1.0", "a", "api", List.of(), "old body"));
        registry.register(new SkillDefinition("x", "new", "2.0", "a", "api", List.of(), "new body"));

        assertThat(registry.get("x").get().description()).isEqualTo("new");
    }

    private SkillDefinition sampleSkill(String name) {
        return new SkillDefinition(name, "Test skill", "1.0.0", "test", "api", List.of(), "# Test");
    }
}
```

- [ ] **Step 8: Implement SkillRegistry**

```java
package io.github.samzhu.grimo.skill.registry;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class SkillRegistry {

    private final ConcurrentHashMap<String, SkillDefinition> skills = new ConcurrentHashMap<>();

    public void register(SkillDefinition skill) {
        skills.put(skill.name(), skill);
    }

    public void remove(String name) {
        skills.remove(name);
    }

    public Optional<SkillDefinition> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public List<SkillDefinition> listAll() {
        return List.copyOf(skills.values());
    }
}
```

- [ ] **Step 9: Run all skill tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.skill.*"`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/skill/ src/test/java/io/github/samzhu/grimo/skill/
git commit -m "feat(skill): add SkillLoader for SKILL.md parsing and SkillRegistry"
```

---

### Task 14: Skill CLI Commands

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java`
- Create: `src/test/java/io/github/samzhu/grimo/skill/SkillCommandsTest.java`

- [ ] **Step 1: Write failing tests for SkillCommands**

```java
package io.github.samzhu.grimo.skill;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillCommandsTest {

    @TempDir
    Path skillsDir;

    SkillRegistry registry;
    SkillCommands commands;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
        commands = new SkillCommands(registry, skillsDir);
    }

    @Test
    void listShouldShowRegisteredSkills() {
        registry.register(new SkillDefinition("healthcheck", "Check health", "1.0.0",
            "grimo-builtin", "api", List.of("cron"), "# HC"));

        String output = commands.list();

        assertThat(output).contains("healthcheck");
        assertThat(output).contains("1.0.0");
        assertThat(output).contains("loaded");
    }

    @Test
    void listShouldShowEmptyMessage() {
        String output = commands.list();
        assertThat(output).contains("No skills loaded");
    }

    @Test
    void removeShouldUnregisterSkill() {
        registry.register(new SkillDefinition("test", "Test", "1.0.0",
            "test", "api", List.of(), "# Test"));

        String output = commands.remove("test");

        assertThat(output).contains("removed");
        assertThat(registry.get("test")).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.skill.SkillCommandsTest"`
Expected: FAIL

- [ ] **Step 3: Implement SkillCommands**

```java
package io.github.samzhu.grimo.skill;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;

import java.nio.file.Path;

@ShellComponent
public class SkillCommands {

    private final SkillRegistry registry;
    private final Path skillsDir;

    public SkillCommands(SkillRegistry registry, Path skillsDir) {
        this.registry = registry;
        this.skillsDir = skillsDir;
    }

    @ShellMethod(key = "skill list", value = "List all loaded skills")
    public String list() {
        var skills = registry.listAll();
        if (skills.isEmpty()) return "No skills loaded.";

        var sb = new StringBuilder();
        sb.append(String.format("  %-15s %-10s %-15s %-8s%n", "NAME", "VERSION", "AUTHOR", "STATUS"));
        for (SkillDefinition s : skills) {
            sb.append(String.format("  %-15s %-10s %-15s %-8s%n",
                s.name(), s.version(), s.author(), "loaded"));
        }
        return sb.toString();
    }

    @ShellMethod(key = "skill remove", value = "Remove a loaded skill")
    public String remove(String name) {
        if (registry.get(name).isEmpty()) {
            return "Skill not found: " + name;
        }
        registry.remove(name);
        return "Skill removed: " + name;
    }

    @ShellMethod(key = "skill install", value = "Install a skill from a Git repository URL")
    public String install(String url) {
        String skillName = url.substring(url.lastIndexOf('/') + 1)
            .replace("grimo-skill-", "");
        Path targetDir = skillsDir.resolve(skillName);

        try {
            var process = new ProcessBuilder("git", "clone", url, targetDir.toString())
                .redirectErrorStream(true)
                .start();
            int exit = process.waitFor();
            if (exit != 0) {
                String error = new String(process.getInputStream().readAllBytes());
                return "Failed to install skill: " + error;
            }

            // Load and register the newly installed skill
            var loader = new io.github.samzhu.grimo.skill.loader.SkillLoader(skillsDir);
            Path skillMd = targetDir.resolve("SKILL.md");
            if (java.nio.file.Files.exists(skillMd)) {
                var skill = loader.load(skillMd);
                registry.register(skill);
                return skillName + " skill installed to skills/" + skillName + "/";
            } else {
                return "Warning: skill installed but no SKILL.md found in " + targetDir;
            }
        } catch (Exception e) {
            return "Failed to install skill: " + e.getMessage();
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.skill.SkillCommandsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java src/test/java/io/github/samzhu/grimo/skill/SkillCommandsTest.java
git commit -m "feat(skill): add Spring Shell skill commands (list/install/remove)"
```

---

## Phase 5: MCP Module

### Task 15: MCP Client Registry and Manager

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/mcp/package-info.java`
- Create: `src/main/java/io/github/samzhu/grimo/mcp/client/McpClientRegistry.java`
- Create: `src/main/java/io/github/samzhu/grimo/mcp/client/McpClientManager.java`
- Create: `src/main/java/io/github/samzhu/grimo/mcp/client/McpConnectionInfo.java`
- Create: `src/test/java/io/github/samzhu/grimo/mcp/client/McpClientRegistryTest.java`

- [ ] **Step 1: Create package-info.java**

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "shared", "shared::event", "shared::config", "shared::workspace" }
)
package io.github.samzhu.grimo.mcp;
```

- [ ] **Step 2: Write failing tests for McpClientRegistry**

```java
package io.github.samzhu.grimo.mcp.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpClientRegistryTest {

    McpClientRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new McpClientRegistry();
    }

    @Test
    void registerAndGetConnection() {
        var info = new McpConnectionInfo("github", "stdio", "npx @modelcontextprotocol/server-github", 0);
        registry.register("github", info);

        assertThat(registry.get("github")).isPresent();
        assertThat(registry.get("github").get().name()).isEqualTo("github");
    }

    @Test
    void removeConnection() {
        registry.register("github", new McpConnectionInfo("github", "stdio", "cmd", 0));
        registry.remove("github");

        assertThat(registry.get("github")).isEmpty();
    }

    @Test
    void listAllConnections() {
        registry.register("a", new McpConnectionInfo("a", "stdio", "cmd-a", 0));
        registry.register("b", new McpConnectionInfo("b", "sse", "http://localhost:3001/sse", 5));

        assertThat(registry.listAll()).hasSize(2);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.mcp.client.McpClientRegistryTest"`
Expected: FAIL

- [ ] **Step 4: Create McpConnectionInfo record**

```java
package io.github.samzhu.grimo.mcp.client;

public record McpConnectionInfo(
    String name,
    String transport,   // "stdio" or "sse"
    String command,     // stdio command or SSE URL
    int toolCount
) {}
```

- [ ] **Step 5: Implement McpClientRegistry**

```java
package io.github.samzhu.grimo.mcp.client;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class McpClientRegistry {

    private final ConcurrentHashMap<String, McpConnectionInfo> connections = new ConcurrentHashMap<>();

    public void register(String name, McpConnectionInfo info) {
        connections.put(name, info);
    }

    public void remove(String name) {
        connections.remove(name);
    }

    public Optional<McpConnectionInfo> get(String name) {
        return Optional.ofNullable(connections.get(name));
    }

    public List<McpConnectionInfo> listAll() {
        return List.copyOf(connections.values());
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.mcp.client.McpClientRegistryTest"`
Expected: PASS

- [ ] **Step 7: Implement McpClientManager**

This class handles the actual MCP SDK interactions. It creates/destroys `McpSyncClient` instances at runtime.

```java
package io.github.samzhu.grimo.mcp.client;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.ServerParameters;

import java.util.concurrent.ConcurrentHashMap;

public class McpClientManager {

    private final McpClientRegistry registry;
    private final ConcurrentHashMap<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    public McpClientManager(McpClientRegistry registry) {
        this.registry = registry;
    }

    public McpConnectionInfo addStdio(String name, String command) {
        String[] parts = command.split("\\s+");
        String executable = parts[0];
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);

        var params = ServerParameters.builder(executable)
            .args(args)
            .build();
        var transport = new StdioClientTransport(params);
        var client = McpClient.sync(transport).build();
        client.initialize();

        clients.put(name, client);

        var tools = client.listTools();
        int toolCount = tools.tools().size();
        var info = new McpConnectionInfo(name, "stdio", command, toolCount);
        registry.register(name, info);

        return info;
    }

    public void remove(String name) {
        var client = clients.remove(name);
        if (client != null) {
            client.close();
        }
        registry.remove(name);
    }

    public void closeAll() {
        clients.forEach((name, client) -> {
            try { client.close(); } catch (Exception _) {}
        });
        clients.clear();
    }
}
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/mcp/ src/test/java/io/github/samzhu/grimo/mcp/
git commit -m "feat(mcp): add McpClientRegistry and McpClientManager for runtime MCP management"
```

---

### Task 16: MCP CLI Commands

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/mcp/McpCommands.java`
- Create: `src/test/java/io/github/samzhu/grimo/mcp/McpCommandsTest.java`

- [ ] **Step 1: Write failing tests for McpCommands**

```java
package io.github.samzhu.grimo.mcp;

import io.github.samzhu.grimo.mcp.client.McpClientRegistry;
import io.github.samzhu.grimo.mcp.client.McpConnectionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class McpCommandsTest {

    McpClientRegistry registry;
    McpCommands commands;

    @BeforeEach
    void setUp() {
        registry = new McpClientRegistry();
        commands = new McpCommands(registry);
    }

    @Test
    void listShouldShowConnections() {
        registry.register("github", new McpConnectionInfo("github", "stdio", "npx server-github", 12));

        String output = commands.list();

        assertThat(output).contains("github");
        assertThat(output).contains("stdio");
        assertThat(output).contains("12");
    }

    @Test
    void listShouldShowEmptyMessage() {
        assertThat(commands.list()).contains("No MCP connections");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.mcp.McpCommandsTest"`
Expected: FAIL

- [ ] **Step 3: Implement McpCommands**

```java
package io.github.samzhu.grimo.mcp;

import io.github.samzhu.grimo.mcp.client.McpClientRegistry;
import io.github.samzhu.grimo.mcp.client.McpConnectionInfo;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

@ShellComponent
public class McpCommands {

    private final McpClientRegistry registry;

    public McpCommands(McpClientRegistry registry) {
        this.registry = registry;
    }

    @ShellMethod(key = "mcp list", value = "List MCP server connections")
    public String list() {
        var connections = registry.listAll();
        if (connections.isEmpty()) return "No MCP connections configured.";

        var sb = new StringBuilder();
        sb.append(String.format("  %-12s %-10s %-6s%n", "NAME", "TRANSPORT", "TOOLS"));
        for (McpConnectionInfo c : connections) {
            sb.append(String.format("  %-12s %-10s %-6d%n", c.name(), c.transport(), c.toolCount()));
        }
        return sb.toString();
    }

    @ShellMethod(key = "mcp remove", value = "Remove an MCP server connection")
    public String remove(String name) {
        if (registry.get(name).isEmpty()) {
            return "MCP connection not found: " + name;
        }
        registry.remove(name);
        return "MCP connection removed: " + name;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.mcp.McpCommandsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/mcp/McpCommands.java src/test/java/io/github/samzhu/grimo/mcp/McpCommandsTest.java
git commit -m "feat(mcp): add Spring Shell mcp commands (list/remove)"
```

---

## Phase 6: Channel Module

### Task 17: Channel Adapter Interface and Registry

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/channel/package-info.java`
- Create: `src/main/java/io/github/samzhu/grimo/channel/ChannelAdapter.java`
- Create: `src/main/java/io/github/samzhu/grimo/channel/ChannelRegistry.java`
- Create: `src/main/java/io/github/samzhu/grimo/channel/OutgoingMessage.java`
- Create: `src/test/java/io/github/samzhu/grimo/channel/ChannelRegistryTest.java`

- [ ] **Step 1: Create package-info.java**

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "shared", "shared::event", "shared::config", "shared::workspace" }
)
package io.github.samzhu.grimo.channel;
```

- [ ] **Step 2: Write failing tests for ChannelRegistry**

```java
package io.github.samzhu.grimo.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelRegistryTest {

    ChannelRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ChannelRegistry();
    }

    @Test
    void registerAndGetAdapter() {
        var adapter = stubAdapter("telegram", true);
        registry.register("telegram", adapter);

        assertThat(registry.get("telegram")).isPresent();
    }

    @Test
    void listEnabledAdapters() {
        registry.register("telegram", stubAdapter("telegram", true));
        registry.register("line", stubAdapter("line", false));

        assertThat(registry.listEnabled()).hasSize(1);
        assertThat(registry.listEnabled().getFirst().channelType()).isEqualTo("telegram");
    }

    @Test
    void removeAdapter() {
        registry.register("telegram", stubAdapter("telegram", true));
        registry.remove("telegram");

        assertThat(registry.get("telegram")).isEmpty();
    }

    private ChannelAdapter stubAdapter(String type, boolean enabled) {
        return new ChannelAdapter() {
            @Override public String channelType() { return type; }
            @Override public void send(OutgoingMessage msg) {}
            @Override public boolean isEnabled() { return enabled; }
        };
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.channel.ChannelRegistryTest"`
Expected: FAIL

- [ ] **Step 4: Create ChannelAdapter interface and OutgoingMessage record**

```java
// ChannelAdapter.java
package io.github.samzhu.grimo.channel;

public interface ChannelAdapter {
    String channelType();
    void send(OutgoingMessage msg);
    boolean isEnabled();
}
```

```java
// OutgoingMessage.java
package io.github.samzhu.grimo.channel;

import java.util.List;
import io.github.samzhu.grimo.shared.event.Attachment;

public record OutgoingMessage(
    String conversationId,
    String text,
    List<Attachment> attachments
) {}
```

- [ ] **Step 5: Implement ChannelRegistry**

```java
package io.github.samzhu.grimo.channel;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class ChannelRegistry {

    private final ConcurrentHashMap<String, ChannelAdapter> adapters = new ConcurrentHashMap<>();

    public void register(String channelType, ChannelAdapter adapter) {
        adapters.put(channelType, adapter);
    }

    public void remove(String channelType) {
        adapters.remove(channelType);
    }

    public Optional<ChannelAdapter> get(String channelType) {
        return Optional.ofNullable(adapters.get(channelType));
    }

    public List<ChannelAdapter> listAll() {
        return List.copyOf(adapters.values());
    }

    public List<ChannelAdapter> listEnabled() {
        return adapters.values().stream()
            .filter(ChannelAdapter::isEnabled)
            .toList();
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.channel.ChannelRegistryTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/channel/ src/test/java/io/github/samzhu/grimo/channel/
git commit -m "feat(channel): add ChannelAdapter interface and ChannelRegistry"
```

---

### Task 18: Channel Event Listener

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/channel/ChannelEventListener.java`
- Create: `src/test/java/io/github/samzhu/grimo/channel/ChannelEventListenerTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.github.samzhu.grimo.channel;

import io.github.samzhu.grimo.shared.event.OutgoingMessageEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelEventListenerTest {

    @Test
    void shouldRouteMessageToCorrectChannel() {
        var sentMessages = new ArrayList<OutgoingMessage>();
        var registry = new ChannelRegistry();
        registry.register("telegram", new ChannelAdapter() {
            @Override public String channelType() { return "telegram"; }
            @Override public void send(OutgoingMessage msg) { sentMessages.add(msg); }
            @Override public boolean isEnabled() { return true; }
        });

        var listener = new ChannelEventListener(registry);
        listener.onOutgoingMessage(new OutgoingMessageEvent("telegram", "conv-1", "Hello!", List.of()));

        assertThat(sentMessages).hasSize(1);
        assertThat(sentMessages.getFirst().text()).isEqualTo("Hello!");
    }

    @Test
    void shouldIgnoreMessageForUnknownChannel() {
        var registry = new ChannelRegistry();
        var listener = new ChannelEventListener(registry);

        // Should not throw
        listener.onOutgoingMessage(new OutgoingMessageEvent("unknown", "conv-1", "Hello!", List.of()));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.channel.ChannelEventListenerTest"`
Expected: FAIL

- [ ] **Step 3: Implement ChannelEventListener**

```java
package io.github.samzhu.grimo.channel;

import io.github.samzhu.grimo.shared.event.OutgoingMessageEvent;
import org.springframework.modulith.events.ApplicationModuleListener;

public class ChannelEventListener {

    private final ChannelRegistry registry;

    public ChannelEventListener(ChannelRegistry registry) {
        this.registry = registry;
    }

    @ApplicationModuleListener
    public void onOutgoingMessage(OutgoingMessageEvent event) {
        registry.get(event.channelType()).ifPresent(adapter -> {
            adapter.send(new OutgoingMessage(
                event.conversationId(),
                event.text(),
                event.attachments()
            ));
        });
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.channel.ChannelEventListenerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/channel/ChannelEventListener.java src/test/java/io/github/samzhu/grimo/channel/ChannelEventListenerTest.java
git commit -m "feat(channel): add ChannelEventListener to route OutgoingMessageEvent to adapters"
```

---

### Task 19: Channel CLI Commands

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/channel/ChannelCommands.java`
- Create: `src/test/java/io/github/samzhu/grimo/channel/ChannelCommandsTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.github.samzhu.grimo.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelCommandsTest {

    ChannelRegistry registry;
    ChannelCommands commands;

    @BeforeEach
    void setUp() {
        registry = new ChannelRegistry();
        commands = new ChannelCommands(registry);
    }

    @Test
    void listShouldShowChannels() {
        registry.register("telegram", stubAdapter("telegram", true));

        String output = commands.list();

        assertThat(output).contains("telegram");
        assertThat(output).contains("enabled");
    }

    @Test
    void listShouldShowEmptyMessage() {
        assertThat(commands.list()).contains("No channels configured");
    }

    private ChannelAdapter stubAdapter(String type, boolean enabled) {
        return new ChannelAdapter() {
            @Override public String channelType() { return type; }
            @Override public void send(OutgoingMessage msg) {}
            @Override public boolean isEnabled() { return enabled; }
        };
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.channel.ChannelCommandsTest"`
Expected: FAIL

- [ ] **Step 3: Implement ChannelCommands**

```java
package io.github.samzhu.grimo.channel;

import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;

@ShellComponent
public class ChannelCommands {

    private final ChannelRegistry registry;

    public ChannelCommands(ChannelRegistry registry) {
        this.registry = registry;
    }

    @ShellMethod(key = "channel list", value = "List configured communication channels")
    public String list() {
        var adapters = registry.listAll();
        if (adapters.isEmpty()) return "No channels configured.";

        var sb = new StringBuilder();
        sb.append(String.format("  %-12s %-10s%n", "CHANNEL", "STATUS"));
        for (ChannelAdapter a : adapters) {
            String status = a.isEnabled() ? "enabled" : "disabled";
            sb.append(String.format("  %-12s %-10s%n", a.channelType(), status));
        }
        return sb.toString();
    }

    @ShellMethod(key = "channel remove", value = "Remove a channel")
    public String remove(String channelType) {
        if (registry.get(channelType).isEmpty()) {
            return "Channel not found: " + channelType;
        }
        registry.remove(channelType);
        return "Channel removed: " + channelType;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.channel.ChannelCommandsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/channel/ChannelCommands.java src/test/java/io/github/samzhu/grimo/channel/ChannelCommandsTest.java
git commit -m "feat(channel): add Spring Shell channel commands (list/remove)"
```

---

## Phase 7: Integration

### Task 20: Startup Runner

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoApplication.java`

- [ ] **Step 1: Implement GrimoStartupRunner**

This `ApplicationRunner` orchestrates the startup sequence described in the spec (section 9):

```java
package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.detect.AgentDetector;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import io.github.samzhu.grimo.shared.config.GrimoProperties;
import io.github.samzhu.grimo.shared.workspace.WorkspaceManager;
import io.github.samzhu.grimo.skill.loader.SkillLoader;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import io.github.samzhu.grimo.task.scheduler.TaskSchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(GrimoProperties.class)
public class GrimoStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GrimoStartupRunner.class);

    private final GrimoProperties properties;
    private final WorkspaceManager workspaceManager;
    private final GrimoConfig grimoConfig;
    private final AgentProviderRegistry agentRegistry;
    private final AgentDetector agentDetector;
    private final SkillLoader skillLoader;
    private final SkillRegistry skillRegistry;
    private final TaskSchedulerService taskSchedulerService;
    private final io.github.samzhu.grimo.mcp.client.McpClientManager mcpClientManager;

    public GrimoStartupRunner(GrimoProperties properties,
                               WorkspaceManager workspaceManager,
                               GrimoConfig grimoConfig,
                               AgentProviderRegistry agentRegistry,
                               AgentDetector agentDetector,
                               SkillLoader skillLoader,
                               SkillRegistry skillRegistry,
                               TaskSchedulerService taskSchedulerService,
                               io.github.samzhu.grimo.mcp.client.McpClientManager mcpClientManager) {
        this.properties = properties;
        this.workspaceManager = workspaceManager;
        this.grimoConfig = grimoConfig;
        this.agentRegistry = agentRegistry;
        this.agentDetector = agentDetector;
        this.skillLoader = skillLoader;
        this.skillRegistry = skillRegistry;
        this.taskSchedulerService = taskSchedulerService;
        this.mcpClientManager = mcpClientManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 1. Initialize workspace
        log.info("Loading workspace: {}", workspaceManager.root());
        if (!workspaceManager.isInitialized()) {
            workspaceManager.initialize();
            log.info("Workspace initialized at {}", workspaceManager.root());
        }

        // 2. Detect available agents
        log.info("Detecting available agents...");
        var results = agentDetector.detect();
        results.forEach(r -> log.info("  {} — {}", r.id(), r.detail()));

        // 3. Load skills
        log.info("Loading skills...");
        var skills = skillLoader.loadAll();
        skills.forEach(skillRegistry::register);
        log.info("Loaded {} skills", skills.size());

        // 4. Reconnect MCP servers from config.yaml
        log.info("Connecting MCP servers...");
        var config = grimoConfig.load();
        if (config.containsKey("mcp")) {
            @SuppressWarnings("unchecked")
            var mcpServers = (java.util.Map<String, java.util.Map<String, String>>) config.get("mcp");
            mcpServers.forEach((name, settings) -> {
                try {
                    String transport = settings.get("transport");
                    String command = settings.get("command");
                    if ("stdio".equals(transport)) {
                        mcpClientManager.addStdio(name, command);
                        log.info("  Connected MCP server: {}", name);
                    }
                } catch (Exception e) {
                    log.warn("  Failed to connect MCP server {}: {}", name, e.getMessage());
                }
            });
        }

        // 5. Restore scheduled tasks
        log.info("Restoring scheduled tasks...");
        taskSchedulerService.restoreAll();
        log.info("Scheduled tasks: {}", taskSchedulerService.getScheduledTaskIds().size());

        log.info("Grimo is ready.");
    }

    // Bean definitions for non-Spring-bean runtime objects
    @Bean
    WorkspaceManager workspaceManager(GrimoProperties properties) {
        return new WorkspaceManager(Path.of(properties.workspace()));
    }

    @Bean
    GrimoConfig grimoConfig(WorkspaceManager workspaceManager) {
        return new GrimoConfig(workspaceManager.configFile());
    }

    @Bean
    AgentProviderRegistry agentProviderRegistry() {
        return new AgentProviderRegistry();
    }

    @Bean
    AgentDetector agentDetector(AgentProviderRegistry registry) {
        return new AgentDetector(registry);
    }

    @Bean
    AgentRouter agentRouter(AgentProviderRegistry registry) {
        return new AgentRouter(registry);
    }

    @Bean
    SkillLoader skillLoader(WorkspaceManager workspaceManager) {
        return new SkillLoader(workspaceManager.skillsDir());
    }

    @Bean
    SkillRegistry skillRegistry() {
        return new SkillRegistry();
    }

    @Bean
    MarkdownTaskStore markdownTaskStore(WorkspaceManager workspaceManager) {
        return new MarkdownTaskStore(workspaceManager.tasksDir());
    }

    @Bean
    TaskSchedulerService taskSchedulerService(MarkdownTaskStore store,
                                               org.springframework.scheduling.TaskScheduler taskScheduler,
                                               org.springframework.context.ApplicationEventPublisher publisher) {
        return new TaskSchedulerService(store, taskScheduler, publisher);
    }

    @Bean
    io.github.samzhu.grimo.channel.ChannelRegistry channelRegistry() {
        return new io.github.samzhu.grimo.channel.ChannelRegistry();
    }

    @Bean
    io.github.samzhu.grimo.mcp.client.McpClientRegistry mcpClientRegistry() {
        return new io.github.samzhu.grimo.mcp.client.McpClientRegistry();
    }

    @Bean
    io.github.samzhu.grimo.mcp.client.McpClientManager mcpClientManager(
            io.github.samzhu.grimo.mcp.client.McpClientRegistry mcpRegistry) {
        return new io.github.samzhu.grimo.mcp.client.McpClientManager(mcpRegistry);
    }
}

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java
git commit -m "feat: add GrimoStartupRunner for ordered startup sequence"
```

---

### Task 21: Top-Level CLI Commands (chat, status)

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/GrimoCommands.java`
- Create: `src/test/java/io/github/samzhu/grimo/GrimoCommandsTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.provider.AgentProvider;
import io.github.samzhu.grimo.agent.provider.AgentRequest;
import io.github.samzhu.grimo.agent.provider.AgentResult;
import io.github.samzhu.grimo.agent.provider.AgentType;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import io.github.samzhu.grimo.agent.router.AgentRouter;
import io.github.samzhu.grimo.channel.ChannelRegistry;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrimoCommandsTest {

    AgentProviderRegistry agentRegistry;
    AgentRouter router;
    ChannelRegistry channelRegistry;
    SkillRegistry skillRegistry;
    GrimoCommands commands;

    @BeforeEach
    void setUp() {
        agentRegistry = new AgentProviderRegistry();
        agentRegistry.register("stub", new AgentProvider() {
            @Override public String id() { return "stub"; }
            @Override public AgentType type() { return AgentType.API; }
            @Override public boolean isAvailable() { return true; }
            @Override public AgentResult execute(AgentRequest request) {
                return new AgentResult(true, "Echo: " + request.prompt());
            }
        });
        router = new AgentRouter(agentRegistry);
        channelRegistry = new ChannelRegistry();
        skillRegistry = new SkillRegistry();
        commands = new GrimoCommands(router, agentRegistry, channelRegistry, skillRegistry);
    }

    @Test
    void chatShouldReturnAgentResponse() {
        String result = commands.chat("Hello", null);
        assertThat(result).contains("Echo: Hello");
    }

    @Test
    void statusShouldShowSystemOverview() {
        String result = commands.status();
        assertThat(result).contains("Agents:");
        assertThat(result).contains("Channels:");
        assertThat(result).contains("Skills:");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.samzhu.grimo.GrimoCommandsTest"`
Expected: FAIL

- [ ] **Step 3: Implement GrimoCommands**

```java
package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.provider.AgentRequest;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import io.github.samzhu.grimo.agent.router.AgentRouter;
import io.github.samzhu.grimo.channel.ChannelRegistry;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

@ShellComponent
public class GrimoCommands {

    private final AgentRouter router;
    private final AgentProviderRegistry agentRegistry;
    private final ChannelRegistry channelRegistry;
    private final SkillRegistry skillRegistry;

    public GrimoCommands(AgentRouter router,
                          AgentProviderRegistry agentRegistry,
                          ChannelRegistry channelRegistry,
                          SkillRegistry skillRegistry) {
        this.router = router;
        this.agentRegistry = agentRegistry;
        this.channelRegistry = channelRegistry;
        this.skillRegistry = skillRegistry;
    }

    @ShellMethod(key = "chat", value = "Send a message to the agent")
    public String chat(
            String message,
            @ShellOption(defaultValue = ShellOption.NULL) String agent) {
        var provider = router.route(agent);
        var result = provider.execute(new AgentRequest(message));
        return result.success() ? result.content() : "Error: " + result.content();
    }

    @ShellMethod(key = "status", value = "Show system status")
    public String status() {
        var agents = agentRegistry.listAll();
        var channels = channelRegistry.listAll();
        var skills = skillRegistry.listAll();

        return String.format("""
            Agents: %d configured (%d available)
            Channels: %d configured (%d enabled)
            Skills: %d loaded""",
            agents.size(),
            agents.stream().filter(a -> a.isAvailable()).count(),
            channels.size(),
            channelRegistry.listEnabled().size(),
            skills.size());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.samzhu.grimo.GrimoCommandsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoCommands.java src/test/java/io/github/samzhu/grimo/GrimoCommandsTest.java
git commit -m "feat: add top-level chat and status Shell commands"
```

---

### Task 22: Spring Modulith Verification Test

**Files:**
- Create: `src/test/java/io/github/samzhu/grimo/ModulithStructureTest.java`

- [ ] **Step 1: Write Modulith structure verification test**

```java
package io.github.samzhu.grimo;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithStructureTest {

    @Test
    void verifyModuleStructure() {
        var modules = ApplicationModules.of(GrimoApplication.class);
        modules.verify();
    }

    @Test
    void printModuleStructure() {
        var modules = ApplicationModules.of(GrimoApplication.class);
        modules.forEach(System.out::println);
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests "io.github.samzhu.grimo.ModulithStructureTest"`
Expected: PASS — if module dependencies are correctly declared in `package-info.java` files. If it fails, fix the dependency declarations.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/github/samzhu/grimo/ModulithStructureTest.java
git commit -m "test: add Spring Modulith structure verification test"
```

---

### Task 23: Full Build Verification

- [ ] **Step 1: Run full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run application smoke test**

Run: `./gradlew bootRun` (verify startup, then Ctrl+C)
Expected: Application starts, prints "Grimo is ready.", shows `grimo>` prompt

- [ ] **Step 3: Fix any issues found during smoke test**

Address any wiring issues, missing beans, or startup errors.

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "chore: fix wiring and ensure full build passes"
```

---

## Dependency Order Summary

```
Phase 1 (Foundation)     → no dependencies
Phase 2 (Agent)          → depends on Phase 1 (shared events)
Phase 3 (Task)           → depends on Phase 1 (shared events, workspace)
Phase 4 (Skill)          → depends on Phase 1 (shared workspace)
Phase 5 (MCP)            → depends on Phase 1 (shared config)
Phase 6 (Channel)        → depends on Phase 1 (shared events)
Phase 7 (Integration)    → depends on all above
```

Phases 2–6 can be worked on in parallel after Phase 1 is complete. Phase 7 must be last.
