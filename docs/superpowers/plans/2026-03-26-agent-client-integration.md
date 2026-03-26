# Agent Client Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Grimo's custom `AgentProvider` + `AgentDetector` with Spring AI Community's `AgentClient` + `AgentModel`, enabling unified CLI agent management (Claude, Gemini, Codex) via library mode.

**Architecture:** Remove custom agent/MCP abstractions, add `spring-ai-agent-client` + per-CLI agent libraries as dependencies (not starters). Create `AgentModelFactory` for parallel CLI detection via `isAvailable()`, reuse existing registry/router patterns with new types, wire conversation flow through `AgentClient.run()` on Virtual Threads.

**Tech Stack:** Spring AI Community Agent Client 0.10.0-SNAPSHOT, Java 25 Virtual Threads, JLine 3 TUI, Spring Shell 4.0 commands

**Spec:** `docs/superpowers/specs/2026-03-26-agent-client-integration-design.md`

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `src/main/java/io/github/samzhu/grimo/agent/registry/AgentModelRegistry.java` | Thread-safe `ConcurrentHashMap<String, AgentModel>` registry |
| `src/main/java/io/github/samzhu/grimo/agent/detect/AgentModelFactory.java` | Build all AgentModel instances, parallel `isAvailable()` detection, register to registry |
| `src/main/java/io/github/samzhu/grimo/agent/advisor/GrimoSessionAdvisor.java` | `AgentCallAdvisor` — logs goal + result to session JSONL |
| `src/main/java/io/github/samzhu/grimo/agent/advisor/GoalValidationAdvisor.java` | `AgentCallAdvisor` — blocks dangerous operations |
| `src/main/java/io/github/samzhu/grimo/agent/AgentConfiguration.java` | `@Configuration` — wires Claude/Gemini/Codex AgentSpecs + AgentModelFactory bean |
| `src/main/java/io/github/samzhu/grimo/mcp/McpCatalogBuilder.java` | Reads config.yaml MCP section → builds `McpServerCatalog` |
| `src/test/java/io/github/samzhu/grimo/agent/registry/AgentModelRegistryTest.java` | Unit tests for new registry |
| `src/test/java/io/github/samzhu/grimo/agent/detect/AgentModelFactoryTest.java` | Unit tests for factory detection logic |
| `src/test/java/io/github/samzhu/grimo/agent/advisor/GrimoSessionAdvisorTest.java` | Unit tests for session advisor |
| `src/test/java/io/github/samzhu/grimo/mcp/McpCatalogBuilderTest.java` | Unit tests for config → McpServerCatalog conversion |

### Modified Files
| File | Changes |
|------|---------|
| `build.gradle.kts` | Remove spring-ai-anthropic/openai/ollama + mcp SDK, add agent-client libraries + snapshot repo |
| `src/main/java/io/github/samzhu/grimo/agent/router/AgentRouter.java` | Change dependency from `AgentProviderRegistry` → `AgentModelRegistry`, return `AgentModel` |
| `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java` | Change dependency to `AgentModelRegistry`, update display logic |
| `src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java` | Add `getAgentOptions(agentId)` for per-agent config, update MCP config format |
| `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java` | Replace `AgentDetector` with `AgentModelFactory`, wire `AgentClient.run()` in `processInput()` |
| `src/main/java/io/github/samzhu/grimo/mcp/McpCommands.java` | Update to read from config.yaml MCP definitions (no live connections) |
| `src/test/java/io/github/samzhu/grimo/agent/router/AgentRouterTest.java` | Rewrite with `AgentModel` mocks |
| `src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java` | Rewrite with `AgentModelRegistry` |

### Removed Files
| File | Reason |
|------|--------|
| `src/main/java/io/github/samzhu/grimo/agent/provider/AgentProvider.java` | Replaced by `AgentModel` |
| `src/main/java/io/github/samzhu/grimo/agent/provider/AgentRequest.java` | Replaced by `AgentTaskRequest` |
| `src/main/java/io/github/samzhu/grimo/agent/provider/AgentResult.java` | Replaced by `AgentResponse` |
| `src/main/java/io/github/samzhu/grimo/agent/provider/AgentType.java` | All CLI now, no distinction needed |
| `src/main/java/io/github/samzhu/grimo/agent/provider/AnthropicAgentProvider.java` | No API key mode |
| `src/main/java/io/github/samzhu/grimo/agent/detect/AgentDetector.java` | Replaced by `AgentModelFactory` |
| `src/main/java/io/github/samzhu/grimo/agent/registry/AgentProviderRegistry.java` | Replaced by `AgentModelRegistry` |
| `src/main/java/io/github/samzhu/grimo/mcp/client/McpClientManager.java` | No direct MCP connections |
| `src/main/java/io/github/samzhu/grimo/mcp/client/McpClientRegistry.java` | No direct MCP connections |
| `src/main/java/io/github/samzhu/grimo/mcp/client/McpConnectionInfo.java` | No direct MCP connections |
| `src/test/java/io/github/samzhu/grimo/agent/detect/AgentDetectorTest.java` | Replaced by `AgentModelFactoryTest` |
| `src/test/java/io/github/samzhu/grimo/agent/provider/AnthropicAgentProviderTest.java` | No API key mode |
| `src/test/java/io/github/samzhu/grimo/agent/registry/AgentProviderRegistryTest.java` | Replaced by `AgentModelRegistryTest` |
| `src/test/java/io/github/samzhu/grimo/mcp/client/McpClientRegistryTest.java` | No direct MCP connections |

---

## Task 0: Verify Dependencies Resolve

**Goal:** Confirm `spring-ai-agent-client` 0.10.0-SNAPSHOT resolves and `AgentClient` / `AgentClientResponse` classes exist on classpath. This is a **gate** — if it fails, we must adjust the plan.

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add snapshot repository and one dependency to build.gradle.kts**

Add only `spring-ai-agent-model` first to test resolution. Find the `repositories` block and add:

```kotlin
maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
```

In `dependencies`, add:

```kotlin
implementation("org.springaicommunity.agents:spring-ai-agent-model:0.10.0-SNAPSHOT")
```

- [ ] **Step 2: Run dependency resolution**

```bash
./gradlew dependencies --configuration compileClasspath 2>&1 | grep -i "springaicommunity"
```

Expected: Shows resolved `spring-ai-agent-model:0.10.0-SNAPSHOT` with transitive deps.

If FAIL: Try `0.1.0-SNAPSHOT`. Check https://central.sonatype.com/repository/maven-snapshots/org/springaicommunity/agents/ for available versions.

- [ ] **Step 3: Add all agent-client dependencies**

```kotlin
implementation("org.springaicommunity.agents:spring-ai-agent-model:0.10.0-SNAPSHOT")
implementation("org.springaicommunity.agents:spring-ai-agent-client:0.10.0-SNAPSHOT")
implementation("org.springaicommunity.agents:spring-ai-claude-agent:0.10.0-SNAPSHOT")
implementation("org.springaicommunity.agents:spring-ai-gemini:0.10.0-SNAPSHOT")
implementation("org.springaicommunity.agents:spring-ai-codex-agent:0.10.0-SNAPSHOT")
```

- [ ] **Step 4: Verify AgentClient class exists**

```bash
./gradlew compileJava 2>&1 | tail -20
```

Create a temporary test file to check:

```java
// Temp verification — delete after confirming
import org.springaicommunity.agents.client.AgentClient;
import org.springaicommunity.agents.client.AgentClientResponse;
import org.springaicommunity.agents.model.AgentModel;
import org.springaicommunity.agents.model.mcp.McpServerCatalog;
import org.springaicommunity.agents.model.mcp.McpServerDefinition;
```

If `AgentClient` doesn't exist, the fallback is to use `AgentModel.call()` directly. Update all subsequent tasks accordingly.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add Spring AI Community agent-client 0.10.0-SNAPSHOT dependencies"
```

---

## Task 1: Create AgentModelRegistry

**Goal:** Thread-safe registry for `AgentModel` instances, replacing `AgentProviderRegistry`.

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/registry/AgentModelRegistry.java`
- Create: `src/test/java/io/github/samzhu/grimo/agent/registry/AgentModelRegistryTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.github.samzhu.grimo.agent.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.model.AgentModel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentModelRegistryTest {

    private AgentModelRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentModelRegistry();
    }

    @Test
    void registerAndGet() {
        var model = mock(AgentModel.class);
        registry.register("claude", model);
        assertThat(registry.get("claude")).isSameAs(model);
    }

    @Test
    void getUnknownReturnsNull() {
        assertThat(registry.get("nonexistent")).isNull();
    }

    @Test
    void removeUnregisters() {
        var model = mock(AgentModel.class);
        registry.register("claude", model);
        registry.remove("claude");
        assertThat(registry.get("claude")).isNull();
    }

    @Test
    void listAllReturnsAllRegistered() {
        var m1 = mock(AgentModel.class);
        var m2 = mock(AgentModel.class);
        registry.register("claude", m1);
        registry.register("gemini", m2);
        assertThat(registry.listAll()).hasSize(2).containsKeys("claude", "gemini");
    }

    @Test
    void listAvailableFiltersUnavailable() {
        var available = mock(AgentModel.class);
        when(available.isAvailable()).thenReturn(true);
        var unavailable = mock(AgentModel.class);
        when(unavailable.isAvailable()).thenReturn(false);
        registry.register("claude", available);
        registry.register("gemini", unavailable);
        assertThat(registry.listAvailable()).hasSize(1).containsKey("claude");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "io.github.samzhu.grimo.agent.registry.AgentModelRegistryTest" 2>&1 | tail -10
```

Expected: FAIL — class `AgentModelRegistry` not found.

- [ ] **Step 3: Write implementation**

```java
package io.github.samzhu.grimo.agent.registry;

import org.springaicommunity.agents.model.AgentModel;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe registry for AgentModel instances.
 *
 * 設計說明：
 * - 取代 AgentProviderRegistry，型別從 AgentProvider 換成 AgentModel
 * - 使用 ConcurrentHashMap 支援 runtime 動態增刪（Library over Starter 原則）
 * - listAvailable() 每次呼叫 re-check isAvailable()，因為用戶可能中途安裝/移除 CLI
 */
@Component
@NamedInterface("registry")
public class AgentModelRegistry {

    private final ConcurrentHashMap<String, AgentModel> models = new ConcurrentHashMap<>();

    public void register(String id, AgentModel model) {
        models.put(id, model);
    }

    public void remove(String id) {
        models.remove(id);
    }

    public AgentModel get(String id) {
        return models.get(id);
    }

    public Map<String, AgentModel> listAll() {
        return Map.copyOf(models);
    }

    public Map<String, AgentModel> listAvailable() {
        return models.entrySet().stream()
                .filter(e -> e.getValue().isAvailable())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "io.github.samzhu.grimo.agent.registry.AgentModelRegistryTest" 2>&1 | tail -10
```

Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/registry/AgentModelRegistry.java \
        src/test/java/io/github/samzhu/grimo/agent/registry/AgentModelRegistryTest.java
git commit -m "feat: add AgentModelRegistry replacing AgentProviderRegistry"
```

---

## Task 2: Create AgentModelFactory

**Goal:** Build all known AgentModel instances at startup, detect availability in parallel using Virtual Threads, register available ones.

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/detect/AgentModelFactory.java`
- Create: `src/test/java/io/github/samzhu/grimo/agent/detect/AgentModelFactoryTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.github.samzhu.grimo.agent.detect;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.model.AgentModel;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentModelFactoryTest {

    @Test
    void detectRegistersAvailableModels() {
        var registry = new AgentModelRegistry();
        var availableModel = mock(AgentModel.class);
        when(availableModel.isAvailable()).thenReturn(true);

        var spec = new AgentModelFactory.AgentSpec("test-agent", "cli", "Test Agent",
                path -> availableModel);

        var factory = new AgentModelFactory(registry, List.of(spec));
        var results = factory.detectAndRegister(Path.of("."));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().available()).isTrue();
        assertThat(registry.get("test-agent")).isSameAs(availableModel);
    }

    @Test
    void detectSkipsUnavailableModels() {
        var registry = new AgentModelRegistry();
        var unavailableModel = mock(AgentModel.class);
        when(unavailableModel.isAvailable()).thenReturn(false);

        var spec = new AgentModelFactory.AgentSpec("missing-agent", "cli", "Missing",
                path -> unavailableModel);

        var factory = new AgentModelFactory(registry, List.of(spec));
        var results = factory.detectAndRegister(Path.of("."));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().available()).isFalse();
        assertThat(registry.get("missing-agent")).isNull();
    }

    @Test
    void detectIsolatesCreationFailures() {
        var registry = new AgentModelRegistry();
        var goodModel = mock(AgentModel.class);
        when(goodModel.isAvailable()).thenReturn(true);

        var specs = List.of(
                new AgentModelFactory.AgentSpec("good", "cli", "Good Agent", path -> goodModel),
                new AgentModelFactory.AgentSpec("broken", "cli", "Broken Agent", path -> {
                    throw new RuntimeException("SDK missing");
                })
        );

        var factory = new AgentModelFactory(registry, specs);
        var results = factory.detectAndRegister(Path.of("."));

        assertThat(results).hasSize(2);
        assertThat(registry.get("good")).isSameAs(goodModel);
        assertThat(registry.get("broken")).isNull();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "io.github.samzhu.grimo.agent.detect.AgentModelFactoryTest" 2>&1 | tail -10
```

Expected: FAIL — class `AgentModelFactory` not found.

- [ ] **Step 3: Write implementation**

```java
package io.github.samzhu.grimo.agent.detect;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agents.model.AgentModel;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * 啟動時建立所有已知 CLI AgentModel，用 Virtual Thread 並行偵測，可用的註冊到 registry。
 *
 * 設計說明：
 * - 取代 AgentDetector，改用各 AgentModel.isAvailable() 官方偵測
 * - 每個 AgentModel 的建立包在 try-catch 裡，某個 SDK 版本不合不影響其他 agent
 * - 使用 Virtual Thread 並行偵測，最慢的那個決定總耗時（而非累加）
 *
 * @see <a href="https://spring-ai-community.github.io/agent-client/">Spring AI Community Agent Client</a>
 */
public class AgentModelFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentModelFactory.class);

    private final AgentModelRegistry registry;
    private final List<AgentSpec> specs;

    public AgentModelFactory(AgentModelRegistry registry, List<AgentSpec> specs) {
        this.registry = registry;
        this.specs = specs;
    }

    /**
     * 並行偵測所有已知 CLI agent，可用的註冊到 registry。
     *
     * @param workingDirectory CLI agent 的工作目錄
     * @return 偵測結果列表（供 banner/status bar 顯示）
     */
    public List<DetectionResult> detectAndRegister(Path workingDirectory) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<DetectionResult>> futures = specs.stream()
                    .map(spec -> executor.submit(() -> detectOne(spec, workingDirectory)))
                    .toList();

            return futures.stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            log.warn("Detection future failed: {}", e.getMessage());
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    private DetectionResult detectOne(AgentSpec spec, Path workingDirectory) {
        try {
            AgentModel model = spec.creator().apply(workingDirectory);
            boolean available = model.isAvailable();
            if (available) {
                registry.register(spec.id(), model);
                log.info("Agent '{}' detected and registered", spec.id());
            } else {
                log.debug("Agent '{}' not available", spec.id());
            }
            return new DetectionResult(spec.id(), spec.type(), spec.detail(), available);
        } catch (Exception e) {
            log.warn("Agent '{}' creation failed: {}", spec.id(), e.getMessage());
            return new DetectionResult(spec.id(), spec.type(), spec.detail(), false);
        }
    }

    /**
     * 重建指定 agent 的 AgentModel（用於 /agent-model 切換 model）。
     */
    public void recreate(String agentId, Path workingDirectory) {
        specs.stream()
                .filter(s -> s.id().equals(agentId))
                .findFirst()
                .ifPresent(spec -> {
                    try {
                        AgentModel model = spec.creator().apply(workingDirectory);
                        registry.remove(agentId);
                        registry.register(agentId, model);
                        log.info("Agent '{}' recreated", agentId);
                    } catch (Exception e) {
                        log.error("Agent '{}' recreation failed: {}", agentId, e.getMessage());
                    }
                });
    }

    /** Agent 規格定義：id、類型、描述、建構函數。 */
    public record AgentSpec(String id, String type, String detail,
                            Function<Path, AgentModel> creator) {}

    /** 偵測結果（供 banner/status bar 顯示）。 */
    public record DetectionResult(String id, String type, String detail, boolean available) {}
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "io.github.samzhu.grimo.agent.detect.AgentModelFactoryTest" 2>&1 | tail -10
```

Expected: All 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/detect/AgentModelFactory.java \
        src/test/java/io/github/samzhu/grimo/agent/detect/AgentModelFactoryTest.java
git commit -m "feat: add AgentModelFactory with parallel CLI detection via Virtual Threads"
```

---

## Task 3: Refactor AgentRouter

**Goal:** Change `AgentRouter` to use `AgentModelRegistry` and return `AgentModel` instead of `AgentProvider`.

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/router/AgentRouter.java`
- Modify: `src/test/java/io/github/samzhu/grimo/agent/router/AgentRouterTest.java`

- [ ] **Step 1: Rewrite test file**

Read current `AgentRouterTest.java` first to understand existing test cases. Then rewrite using `AgentModel` mocks:

```java
package io.github.samzhu.grimo.agent.router;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.model.AgentModel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRouterTest {

    private AgentModelRegistry registry;
    private GrimoConfig config;
    private AgentRouter router;

    @BeforeEach
    void setUp() {
        registry = new AgentModelRegistry();
        config = mock(GrimoConfig.class);
        router = new AgentRouter(registry, config);
    }

    @Test
    void routeByExplicitId() {
        var model = mock(AgentModel.class);
        when(model.isAvailable()).thenReturn(true);
        registry.register("claude", model);
        assertThat(router.route("claude")).isSameAs(model);
    }

    @Test
    void routeByExplicitIdThrowsWhenNotFound() {
        assertThatThrownBy(() -> router.route("nonexistent"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void routeUsesConfigDefault() {
        var model = mock(AgentModel.class);
        when(model.isAvailable()).thenReturn(true);
        registry.register("gemini", model);
        when(config.getDefaultAgent()).thenReturn("gemini");
        assertThat(router.route(null)).isSameAs(model);
    }

    @Test
    void routeFallsBackToFirstAvailable() {
        var model = mock(AgentModel.class);
        when(model.isAvailable()).thenReturn(true);
        registry.register("claude", model);
        when(config.getDefaultAgent()).thenReturn(null);
        assertThat(router.route(null)).isSameAs(model);
    }

    @Test
    void routeThrowsWhenNoAgentsAvailable() {
        when(config.getDefaultAgent()).thenReturn(null);
        assertThatThrownBy(() -> router.route(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "io.github.samzhu.grimo.agent.router.AgentRouterTest" 2>&1 | tail -10
```

Expected: FAIL — constructor mismatch.

- [ ] **Step 3: Rewrite AgentRouter implementation**

Read current `AgentRouter.java`, then rewrite:

```java
package io.github.samzhu.grimo.agent.router;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.springframework.lang.Nullable;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;
import org.springaicommunity.agents.model.AgentModel;

/**
 * 路由邏輯：選擇要使用的 AgentModel。
 *
 * 設計說明：
 * - 取代舊版使用 AgentProviderRegistry 的路由
 * - 移除 CLI/API 優先邏輯（全部都是 CLI）
 * - 路由順序：明確指定 > config default > 第一個可用的
 */
@Component
@NamedInterface("router")
public class AgentRouter {

    private final AgentModelRegistry registry;
    private final GrimoConfig config;

    public AgentRouter(AgentModelRegistry registry, GrimoConfig config) {
        this.registry = registry;
        this.config = config;
    }

    public AgentModel route(@Nullable String agentId) {
        // 1. 明確指定
        if (agentId != null) {
            AgentModel model = registry.get(agentId);
            if (model == null) {
                throw new IllegalStateException("Agent not found: " + agentId);
            }
            return model;
        }

        // 2. config default
        String defaultAgent = config.getDefaultAgent();
        if (defaultAgent != null) {
            AgentModel model = registry.get(defaultAgent);
            if (model != null) {
                return model;
            }
        }

        // 3. 第一個可用的
        var available = registry.listAvailable();
        if (available.isEmpty()) {
            throw new IllegalStateException("No agents available. Install a CLI agent (claude, gemini, or codex).");
        }
        return available.values().iterator().next();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "io.github.samzhu.grimo.agent.router.AgentRouterTest" 2>&1 | tail -10
```

Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/router/AgentRouter.java \
        src/test/java/io/github/samzhu/grimo/agent/router/AgentRouterTest.java
git commit -m "refactor: AgentRouter uses AgentModelRegistry instead of AgentProviderRegistry"
```

---

## Task 4: Create Advisors

**Goal:** Create `GrimoSessionAdvisor` (session JSONL logging) and `GoalValidationAdvisor` (dangerous operation blocking).

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/advisor/GrimoSessionAdvisor.java`
- Create: `src/main/java/io/github/samzhu/grimo/agent/advisor/GoalValidationAdvisor.java`
- Create: `src/test/java/io/github/samzhu/grimo/agent/advisor/GrimoSessionAdvisorTest.java`

- [ ] **Step 1: Write GrimoSessionAdvisor test**

Note: Exact `AgentCallAdvisor` API (method names, parameter types) must be verified from Task 0 resolution. Adjust if the actual API differs.

```java
package io.github.samzhu.grimo.agent.advisor;

import io.github.samzhu.grimo.SessionWriter;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.client.AgentClientRequest;
import org.springaicommunity.agents.client.AgentClientResponse;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisorChain;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GrimoSessionAdvisorTest {

    @Test
    void adviseCallLogsGoalAndResult() {
        var sessionWriter = mock(SessionWriter.class);
        var advisor = new GrimoSessionAdvisor(sessionWriter);

        var request = mock(AgentClientRequest.class, RETURNS_DEEP_STUBS);
        when(request.goal().getContent()).thenReturn("test goal");

        var response = mock(AgentClientResponse.class);
        when(response.getResult()).thenReturn("test result");
        when(response.isSuccessful()).thenReturn(true);

        var chain = mock(AgentCallAdvisorChain.class);
        when(chain.nextCall(request)).thenReturn(response);

        var result = advisor.adviseCall(request, chain);

        assertThat(result).isSameAs(response);
        verify(chain).nextCall(request);
        verify(sessionWriter).writeUserMessage(contains("test goal"));
        verify(sessionWriter).writeAssistantMessage("test result");
    }

    @Test
    void getNameReturnsGrimoSession() {
        var advisor = new GrimoSessionAdvisor(mock(SessionWriter.class));
        assertThat(advisor.getName()).isEqualTo("GrimoSession");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "io.github.samzhu.grimo.agent.advisor.GrimoSessionAdvisorTest" 2>&1 | tail -10
```

- [ ] **Step 3: Write GrimoSessionAdvisor**

```java
package io.github.samzhu.grimo.agent.advisor;

import io.github.samzhu.grimo.SessionWriter;
import org.springaicommunity.agents.client.AgentClientRequest;
import org.springaicommunity.agents.client.AgentClientResponse;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisor;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisorChain;
import org.springframework.core.Ordered;

/**
 * 記錄 agent goal 和 result 到 session JSONL 檔案。
 *
 * 設計說明：
 * - 實作 AgentCallAdvisor（Spring AI Community 的 around-advice 模式）
 * - 在 chain.nextCall() 前後分別記錄 goal 和 result
 * - Order 設為 HIGHEST_PRECEDENCE + 500，在 validation 之後、其他 advisor 之前
 */
public class GrimoSessionAdvisor implements AgentCallAdvisor {

    private final SessionWriter sessionWriter;

    public GrimoSessionAdvisor(SessionWriter sessionWriter) {
        this.sessionWriter = sessionWriter;
    }

    @Override
    public AgentClientResponse adviseCall(AgentClientRequest request, AgentCallAdvisorChain chain) {
        sessionWriter.writeUserMessage(request.goal().getContent());
        AgentClientResponse response = chain.nextCall(request);
        sessionWriter.writeAssistantMessage(response.getResult());
        return response;
    }

    @Override
    public String getName() {
        return "GrimoSession";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 500;
    }
}
```

- [ ] **Step 4: Write GoalValidationAdvisor**

```java
package io.github.samzhu.grimo.agent.advisor;

import org.springaicommunity.agents.client.AgentClientRequest;
import org.springaicommunity.agents.client.AgentClientResponse;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisor;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisorChain;
import org.springaicommunity.agents.model.AgentGeneration;
import org.springaicommunity.agents.model.AgentGenerationMetadata;
import org.springaicommunity.agents.model.AgentResponse;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.Map;

/**
 * 阻擋危險操作的 advisor。
 *
 * 設計說明：
 * - 在 agent 執行前檢查 goal 內容是否包含危險操作
 * - 如果命中，直接回傳 blocked response，不呼叫 chain.nextCall()
 * - Order 設為 HIGHEST_PRECEDENCE，第一個執行
 */
public class GoalValidationAdvisor implements AgentCallAdvisor {

    private static final List<String> BANNED_OPERATIONS = List.of(
            "rm -rf /", "DROP DATABASE", "DELETE FROM", "format disk",
            "mkfs", "> /dev/sda", "dd if=/dev/zero"
    );

    @Override
    public AgentClientResponse adviseCall(AgentClientRequest request, AgentCallAdvisorChain chain) {
        String goal = request.goal().getContent().toLowerCase();
        for (String banned : BANNED_OPERATIONS) {
            if (goal.contains(banned.toLowerCase())) {
                var blocked = new AgentResponse(List.of(
                        new AgentGeneration("Goal blocked: contains dangerous operation '" + banned + "'",
                                new AgentGenerationMetadata("BLOCKED", Map.of()))
                ));
                return new AgentClientResponse(blocked);
            }
        }
        return chain.nextCall(request);
    }

    @Override
    public String getName() {
        return "GoalValidation";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
```

- [ ] **Step 5: Run all advisor tests**

```bash
./gradlew test --tests "io.github.samzhu.grimo.agent.advisor.*" 2>&1 | tail -10
```

Expected: All tests PASS. Note: `AgentClientResponse` constructor may need adjustment based on actual API.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/advisor/ \
        src/test/java/io/github/samzhu/grimo/agent/advisor/
git commit -m "feat: add GrimoSessionAdvisor and GoalValidationAdvisor"
```

---

## Task 5: Refactor AgentCommands

**Goal:** Update Spring Shell commands to use `AgentModelRegistry`.

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java`
- Modify: `src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java`

- [ ] **Step 1: Read current AgentCommands.java and AgentCommandsTest.java**

Understand the current command format and test patterns.

- [ ] **Step 2: Rewrite AgentCommands to use AgentModelRegistry**

Key changes:
- Constructor: `AgentProviderRegistry` → `AgentModelRegistry`
- `/agent-list`: iterate `registry.listAll()`, show id + `isAvailable()` status
- `/agent-use`: same logic, uses `registry.get(agentId)` to validate
- `/agent-model`: reads `config.getDefaultAgent()` to determine which agent to update

- [ ] **Step 3: Rewrite AgentCommandsTest**

Use `AgentModel` mocks instead of `AgentProvider` mocks.

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests "io.github.samzhu.grimo.agent.AgentCommandsTest" 2>&1 | tail -10
```

Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java \
        src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java
git commit -m "refactor: AgentCommands uses AgentModelRegistry"
```

---

## Task 6: Update GrimoConfig for Per-Agent Options

**Goal:** Add per-agent config support (`agent-options.<id>.model`) and update MCP config format.

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java`

- [ ] **Step 1: Read current GrimoConfig.java**

Understand the YAML reading/writing pattern.

- [ ] **Step 2: Add per-agent options methods**

Add methods:
- `getAgentOption(String agentId, String key): String` — reads `agent-options.<agentId>.<key>`
- `setAgentOption(String agentId, String key, String value)` — writes and persists
- `getMcpServers(): Map<String, Map<String, Object>>` — reads new MCP format

Keep `getDefaultAgent()` / `setDefaultAgent()` unchanged.
Deprecate or remove `getDefaultModel()` / `setDefaultModel()`.

- [ ] **Step 3: Run full test suite to check for regressions**

```bash
./gradlew test 2>&1 | tail -20
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java
git commit -m "feat: GrimoConfig supports per-agent options and new MCP format"
```

---

## Task 7: Wire GrimoTuiRunner with AgentClient

**Goal:** Replace `AgentDetector` usage with `AgentModelFactory`, wire `AgentClient.run()` for conversation, use Virtual Thread for non-blocking execution.

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`

- [ ] **Step 1: Read current GrimoTuiRunner.java**

Understand all agent/MCP touchpoints (constructor injection, Phase 2 loading, processInput, resolveAgentId).

- [ ] **Step 2: Update constructor — replace old dependencies**

Remove: `AgentDetector`, `McpClientManager`, `McpClientRegistry`
Add: `AgentModelFactory`, `AgentModelRegistry`, `AgentRouter`

- [ ] **Step 3: Update Phase 2 — use AgentModelFactory**

Replace `detectAgents()` with:

```java
var agentResults = agentModelFactory.detectAndRegister(
        Path.of(System.getProperty("user.dir")));
```

Remove `connectMcp()` call (MCP handled by CLI agents now).

- [ ] **Step 4: Update Phase 3 — resolve agent info from registry**

```java
String agentId = resolveAgentId(agentResults);
// agentCount from registry instead of detection results
long agentCount = agentModelRegistry.listAvailable().size();
int mcpCount = 0; // MCP now managed by CLI agents
```

- [ ] **Step 5: Update processInput — wire AgentClient.run()**

Replace the placeholder AI reply with actual agent invocation:

```java
} else {
    // AI 對話 — 透過 AgentClient 呼叫 CLI agent
    try {
        AgentModel model = agentRouter.route(null);
        // Blocking call on Virtual Thread to avoid freezing TUI
        Thread.startVirtualThread(() -> {
            try {
                contentView.appendLine(new AttributedString("⏳ thinking...",
                        AttributedStyle.DEFAULT.foreground(245)));
                eventLoop.setDirty();

                AgentClient client = AgentClient.builder(model)
                        .defaultAdvisor(new GrimoSessionAdvisor(sessionWriter))
                        .defaultAdvisor(new GoalValidationAdvisor())
                        .build();
                AgentClientResponse response = client
                        .goal(text)
                        .workingDirectory(System.getProperty("user.dir"))
                        .run();

                if (response.isSuccessful()) {
                    contentView.appendAiReply(response.getResult());
                } else {
                    contentView.appendError(response.getResult());
                }
                eventLoop.setDirty();
            } catch (Exception e) {
                contentView.appendError("⚠ " + formatAgentError(e));
                eventLoop.setDirty();
            }
        });
    } catch (IllegalStateException e) {
        contentView.appendError(e.getMessage());
    }
}
```

- [ ] **Step 6: Add error formatting method**

```java
private String formatAgentError(Exception e) {
    String name = e.getClass().getSimpleName();
    return switch (name) {
        case String s when s.contains("NotFoundException") ->
                "CLI not found. Install the agent CLI and try again.";
        case String s when s.contains("AuthenticationException") ->
                "Authentication failed. Run the agent's login command.";
        case String s when s.contains("TimeoutException") ->
                "Agent timed out. Try a simpler goal.";
        default -> "Agent error: " + e.getMessage();
    };
}
```

- [ ] **Step 7: Add concurrency guard and Ctrl+C cancellation**

Add fields to `GrimoTuiRunner`:

```java
private volatile boolean agentRunning = false;
private volatile Thread agentThread = null;
```

In `processInput` AI branch, wrap with guard:

```java
if (agentRunning) {
    contentView.appendError("Agent is still running. Wait or press Ctrl+C to cancel.");
    return;
}
```

In the virtual thread spawn, set flag:

```java
agentRunning = true;
agentThread = Thread.startVirtualThread(() -> {
    try { /* ... agent call ... */ }
    finally { agentRunning = false; agentThread = null; }
});
```

In `TuiKeyHandler.handleNormalKey` `OP_CTRL_C` case, add:

```java
if (agentRunning && agentThread != null) {
    agentThread.interrupt();
    contentView.appendError("Agent cancelled.");
}
```

- [ ] **Step 9: Remove unused imports and methods**

Remove: `detectAgents()`, `connectMcp()`, old import statements for `AgentDetector`, `McpClientManager`, `McpClientRegistry`.

- [ ] **Step 10: Run full build and verify**

```bash
./gradlew build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "feat: wire AgentClient conversation flow with Virtual Thread non-blocking execution"
```

---

## Task 8: Build AgentSpec Wiring (Claude, Gemini, Codex)

**Goal:** Create the actual `AgentSpec` list that builds `ClaudeAgentModel`, `GeminiAgentModel`, `CodexAgentModel` and register `AgentModelFactory` as a Spring bean.

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/agent/AgentConfiguration.java`

- [ ] **Step 1: Create configuration class**

```java
package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.detect.AgentModelFactory;
import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.springaicommunity.agents.claude.ClaudeAgentModel;
import org.springaicommunity.agents.claude.ClaudeAgentOptions;
import org.springaicommunity.agents.claude.sdk.ClaudeAgentClient;
import org.springaicommunity.agents.codex.CodexAgentModel;
import org.springaicommunity.agents.codex.CodexAgentOptions;
import org.springaicommunity.agents.codexsdk.CodexClient;
import org.springaicommunity.agents.gemini.GeminiAgentModel;
import org.springaicommunity.agents.gemini.GeminiAgentOptions;
import org.springaicommunity.agents.gemini.sdk.GeminiClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * CLI Agent 配置：定義所有支援的 AgentSpec 並建立 AgentModelFactory。
 *
 * 設計說明：
 * - 每個 AgentSpec 的 creator 接收 workingDirectory，回傳對應的 AgentModel
 * - options 從 GrimoConfig 的 agent-options.<id> 區段讀取
 * - 使用 Library 模式（不用 Starter），所有 AgentModel 手動建立
 *
 * @see <a href="https://spring-ai-community.github.io/agent-client/api/claude-code-sdk.html">Claude Code SDK</a>
 * @see <a href="https://spring-ai-community.github.io/agent-client/api/gemini-cli-sdk.html">Gemini CLI SDK</a>
 * @see <a href="https://spring-ai-community.github.io/agent-client/api/codex-cli-sdk.html">Codex CLI SDK</a>
 */
@Configuration
public class AgentConfiguration {

    @Bean
    public AgentModelFactory agentModelFactory(AgentModelRegistry registry, GrimoConfig config) {
        var specs = List.of(
                new AgentModelFactory.AgentSpec("claude", "cli", "Claude Code CLI", path -> {
                    String model = config.getAgentOption("claude", "model");
                    if (model == null) model = "claude-sonnet-4-0";
                    var client = ClaudeAgentClient.create(path);
                    var options = ClaudeAgentOptions.builder()
                            .model(model)
                            .yolo(true)
                            .timeout(Duration.ofMinutes(10))
                            .build();
                    return new ClaudeAgentModel(client, options);
                }),
                new AgentModelFactory.AgentSpec("gemini", "cli", "Gemini CLI", path -> {
                    String model = config.getAgentOption("gemini", "model");
                    if (model == null) model = "gemini-1.5-pro";
                    var client = GeminiClient.create(path);
                    var options = GeminiAgentOptions.builder()
                            .model(model)
                            .yolo(true)
                            .timeout(Duration.ofMinutes(10))
                            .build();
                    return new GeminiAgentModel(client, options);
                }),
                new AgentModelFactory.AgentSpec("codex", "cli", "Codex CLI", path -> {
                    String model = config.getAgentOption("codex", "model");
                    if (model == null) model = "gpt-5-codex";
                    var client = CodexClient.create(path);
                    var options = CodexAgentOptions.builder()
                            .model(model)
                            .fullAuto(true)
                            .timeout(Duration.ofMinutes(10))
                            .build();
                    return new CodexAgentModel(client, options, null);
                })
        );
        return new AgentModelFactory(registry, specs);
    }
}
```

Note: Constructor/builder patterns must match actual SDK API. Verify against resolved classes. If `ClaudeAgentOptions` doesn't have a `builder()`, use constructor directly.

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileJava 2>&1 | tail -20
```

Fix any API mismatches (constructor vs builder, method names).

- [ ] **Step 3: Run full test suite**

```bash
./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/AgentConfiguration.java
git commit -m "feat: wire Claude, Gemini, Codex AgentSpecs in AgentConfiguration"
```

---

## Task 9: Build MCP Catalog from Config

**Goal:** Create `McpCatalogBuilder` that reads config.yaml MCP section and builds `McpServerCatalog` for passing to `AgentClient`.

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/mcp/McpCatalogBuilder.java`
- Create: `src/test/java/io/github/samzhu/grimo/mcp/McpCatalogBuilderTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.github.samzhu.grimo.mcp;

import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.model.mcp.McpServerCatalog;
import org.springaicommunity.agents.model.mcp.McpServerDefinition;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpCatalogBuilderTest {

    @Test
    void buildsCatalogFromConfig() {
        var config = mock(GrimoConfig.class);
        when(config.getMcpServers()).thenReturn(Map.of(
                "brave-search", Map.of(
                        "type", "stdio",
                        "command", "npx",
                        "args", java.util.List.of("-y", "@modelcontextprotocol/server-brave-search")
                ),
                "weather", Map.of(
                        "type", "sse",
                        "url", "http://localhost:8080/sse"
                )
        ));

        var builder = new McpCatalogBuilder(config);
        McpServerCatalog catalog = builder.build();

        assertThat(catalog.getAll()).hasSize(2);
        assertThat(catalog.contains("brave-search")).isTrue();
        assertThat(catalog.contains("weather")).isTrue();
    }

    @Test
    void returnsEmptyCatalogWhenNoMcpConfig() {
        var config = mock(GrimoConfig.class);
        when(config.getMcpServers()).thenReturn(Map.of());

        var builder = new McpCatalogBuilder(config);
        McpServerCatalog catalog = builder.build();

        assertThat(catalog.getAll()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "io.github.samzhu.grimo.mcp.McpCatalogBuilderTest" 2>&1 | tail -10
```

- [ ] **Step 3: Write implementation**

```java
package io.github.samzhu.grimo.mcp;

import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.springaicommunity.agents.model.mcp.McpServerCatalog;
import org.springaicommunity.agents.model.mcp.McpServerDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 從 config.yaml 的 mcp 區段建構 McpServerCatalog。
 *
 * 設計說明：
 * - 將 Grimo config 格式轉成 AgentClient 的 Portable MCP 定義
 * - 產出的 McpServerCatalog 是 immutable 且 thread-safe，可共享給所有 AgentClient
 * - 各 CLI agent 會自動將定義轉成原生格式（Claude: --mcp-config, Gemini: settings.json）
 *
 * @see <a href="https://springaicommunity.mintlify.app/projects/incubating/agent-client#portable-mcp-servers">Portable MCP Servers</a>
 */
@Component
public class McpCatalogBuilder {

    private final GrimoConfig config;

    public McpCatalogBuilder(GrimoConfig config) {
        this.config = config;
    }

    @SuppressWarnings("unchecked")
    public McpServerCatalog build() {
        Map<String, Map<String, Object>> mcpServers = config.getMcpServers();
        if (mcpServers.isEmpty()) {
            return McpServerCatalog.of(Map.of());
        }

        var builder = McpServerCatalog.builder();
        for (var entry : mcpServers.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> serverDef = entry.getValue();
            String type = (String) serverDef.getOrDefault("type", "stdio");

            McpServerDefinition definition = switch (type) {
                case "sse" -> new McpServerDefinition.SseDefinition(
                        (String) serverDef.get("url"),
                        (Map<String, String>) serverDef.getOrDefault("headers", Map.of()));
                case "http" -> new McpServerDefinition.HttpDefinition(
                        (String) serverDef.get("url"),
                        (Map<String, String>) serverDef.getOrDefault("headers", Map.of()));
                default -> new McpServerDefinition.StdioDefinition(
                        (String) serverDef.get("command"),
                        (List<String>) serverDef.getOrDefault("args", List.of()),
                        (Map<String, String>) serverDef.getOrDefault("env", Map.of()));
            };
            builder.add(name, definition);
        }
        return builder.build();
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests "io.github.samzhu.grimo.mcp.McpCatalogBuilderTest" 2>&1 | tail -10
```

Expected: All tests PASS. Note: `McpServerCatalog.builder()` API must match actual SDK — adjust if needed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/mcp/McpCatalogBuilder.java \
        src/test/java/io/github/samzhu/grimo/mcp/McpCatalogBuilderTest.java
git commit -m "feat: add McpCatalogBuilder for config.yaml to McpServerCatalog conversion"
```

---

## Task 10: Remove Old Agent + MCP Files and Dependencies

**Goal:** Now that all consumers are updated (Tasks 5-9), safely delete replaced files, tests, and old dependencies.

**Files:**
- Remove: All files listed in "Removed Files" section above
- Modify: `build.gradle.kts` (remove old deps)

- [ ] **Step 1: Remove old agent provider files**

```bash
git rm src/main/java/io/github/samzhu/grimo/agent/provider/AgentProvider.java
git rm src/main/java/io/github/samzhu/grimo/agent/provider/AgentRequest.java
git rm src/main/java/io/github/samzhu/grimo/agent/provider/AgentResult.java
git rm src/main/java/io/github/samzhu/grimo/agent/provider/AgentType.java
git rm src/main/java/io/github/samzhu/grimo/agent/provider/AnthropicAgentProvider.java
git rm src/main/java/io/github/samzhu/grimo/agent/detect/AgentDetector.java
git rm src/main/java/io/github/samzhu/grimo/agent/registry/AgentProviderRegistry.java
```

- [ ] **Step 2: Remove old MCP client files**

```bash
git rm src/main/java/io/github/samzhu/grimo/mcp/client/McpClientManager.java
git rm src/main/java/io/github/samzhu/grimo/mcp/client/McpClientRegistry.java
git rm src/main/java/io/github/samzhu/grimo/mcp/client/McpConnectionInfo.java
```

- [ ] **Step 3: Remove old test files**

```bash
git rm src/test/java/io/github/samzhu/grimo/agent/detect/AgentDetectorTest.java
git rm src/test/java/io/github/samzhu/grimo/agent/provider/AnthropicAgentProviderTest.java
git rm src/test/java/io/github/samzhu/grimo/agent/registry/AgentProviderRegistryTest.java
git rm src/test/java/io/github/samzhu/grimo/mcp/client/McpClientRegistryTest.java
```

- [ ] **Step 4: Remove old dependencies from build.gradle.kts**

Remove these lines:
```kotlin
implementation("org.springframework.ai:spring-ai-anthropic")
implementation("org.springframework.ai:spring-ai-openai")
implementation("org.springframework.ai:spring-ai-ollama")
implementation("io.modelcontextprotocol.sdk:mcp:1.1.0")
```

- [ ] **Step 5: Fix any remaining compilation errors**

```bash
./gradlew compileJava 2>&1 | head -40
```

Check `McpCommands.java`, `package-info.java` files for remaining references to deleted classes.

- [ ] **Step 6: Update package-info.java**

Update named interfaces for `agent/registry/` (exposes `AgentModelRegistry`), `agent/detect/` (exposes `AgentModelFactory`). Remove references to deleted packages.

- [ ] **Step 7: Update McpCommands to read from config**

`/mcp-list` now shows MCP server definitions from config.yaml (via `GrimoConfig.getMcpServers()`). Remove `/mcp-remove` or change it to edit config.

- [ ] **Step 8: Run full build**

```bash
./gradlew build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 9: Commit**

```bash
git add -A  # Safe here since all old files are git rm'd and all new files already committed
git commit -m "refactor: remove old AgentProvider, AgentDetector, MCP client, and Spring AI API dependencies"
```

---

## Task 11: End-to-End Manual Test

**Goal:** Verify the full flow works: startup detection → banner → conversation → error handling.

- [ ] **Step 1: Build and run**

```bash
./gradlew bootRun
```

- [ ] **Step 2: Verify banner shows detected agents**

Expected: Banner displays detected CLI agents (e.g., `claude-cli · claude-sonnet-4-0`). Agents not installed show count as 0.

- [ ] **Step 3: Test `/agent-list` command**

Type `/agent-list`. Expected: Table showing agent IDs and availability status.

- [ ] **Step 4: Test conversation (if any CLI agent installed)**

Type a message like `hello`. Expected:
- "⏳ thinking..." appears
- Agent response appears after a few seconds
- No TUI freeze during wait

- [ ] **Step 5: Test conversation (no CLI agent installed)**

If no CLI agents available, type a message. Expected: Error message "No agents available."

- [ ] **Step 6: Test `/exit`**

Type `/exit`. Expected: Returns to shell prompt cleanly.

- [ ] **Step 7: Update glossary**

Update `docs/glossary.md` technical component mapping table to reflect new architecture.

- [ ] **Step 8: Commit**

```bash
git add docs/glossary.md
git commit -m "docs: update glossary for agent-client integration"
```
