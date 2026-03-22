# Interactive Shell V1 — 對話模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 將 Grimo Shell 從純指令模式升級為對話式互動介面，支援 Smart Defaults、自訂 Prompt、Agent 設定管理、`@` 檔案引用。

**Architecture:** 在現有 Spring Shell `@Command` 架構上擴展。Smart Defaults 透過為每個模組加入裸指令別名（`agent` = `agent list`）實現。自訂 Prompt 用 `PromptProvider` bean 顯示目前 agent。Agent 設定管理擴展 `GrimoConfig` 和 `AgentRouter`。所有新功能都有對應的單元測試。

**Tech Stack:** Spring Shell 4.0.1, JLine 3.30.6, Spring Boot 4.0.4

**Spec:** `docs/superpowers/specs/2026-03-22-grimo-interactive-shell-design.md`

---

## File Structure

### Smart Defaults（各模組裸指令別名）

| File | Responsibility |
|------|----------------|
| `src/main/java/.../agent/AgentCommands.java` | Modify: 加入裸指令 `agent` 別名指向 `list()` |
| `src/main/java/.../task/TaskCommands.java` | Modify: 加入裸指令 `task` 別名指向 `list()` |
| `src/main/java/.../skill/SkillCommands.java` | Modify: 加入裸指令 `skill` 別名指向 `list()` |
| `src/main/java/.../mcp/McpCommands.java` | Modify: 加入裸指令 `mcp` 別名指向 `list()` |
| `src/main/java/.../channel/ChannelCommands.java` | Modify: 加入裸指令 `channel` 別名指向 `list()` |

### Custom Prompt

| File | Responsibility |
|------|----------------|
| `src/main/java/.../GrimoPromptProvider.java` | Create: PromptProvider 顯示 `[agent-id] grimo:>` |
| `src/main/java/.../GrimoStartupRunner.java` | Modify: 加入 PromptProvider bean |
| `src/test/java/.../GrimoPromptProviderTest.java` | Create: 測試 prompt 格式 |

### Agent 設定管理

| File | Responsibility |
|------|----------------|
| `src/main/java/.../shared/config/GrimoConfig.java` | Modify: 加入 getDefaultAgent(), getDefaultModel(), setDefaultAgent(), setDefaultModel() |
| `src/main/java/.../agent/router/AgentRouter.java` | Modify: 支援從 config 讀取預設 agent |
| `src/main/java/.../agent/AgentCommands.java` | Modify: 加入 `agent use`, `agent model` 指令 |
| `src/test/java/.../shared/config/GrimoConfigTest.java` | Modify: 加入 agent 設定測試 |
| `src/test/java/.../agent/router/AgentRouterTest.java` | Modify: 加入 config-based routing 測試 |

---

## Task 1: Smart Defaults — 所有模組裸指令別名

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java`
- Modify: `src/main/java/io/github/samzhu/grimo/task/TaskCommands.java`
- Modify: `src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java`
- Modify: `src/main/java/io/github/samzhu/grimo/mcp/McpCommands.java`
- Modify: `src/main/java/io/github/samzhu/grimo/channel/ChannelCommands.java`

設計說明：為每個模組的 Commands 類別加入一個裸指令方法（如 `@Command(name = "agent")`），直接委派到 `list()` 方法。這樣 `/agent` 等同 `/agent list`，符合漸進式揭露原則。

- [ ] **Step 1: AgentCommands 加入裸指令**

在 `AgentCommands.java` 的 `list()` 方法前加入：

```java
@Command(name = "agent", description = "List all configured agent providers (alias for 'agent list')")
public String agentDefault() {
    return list();
}
```

- [ ] **Step 2: TaskCommands 加入裸指令**

在 `TaskCommands.java` 加入：

```java
@Command(name = "task", description = "List all tasks (alias for 'task list')")
public String taskDefault() {
    return list();
}
```

- [ ] **Step 3: SkillCommands 加入裸指令**

在 `SkillCommands.java` 加入：

```java
@Command(name = "skill", description = "List all loaded skills (alias for 'skill list')")
public String skillDefault() {
    return list();
}
```

- [ ] **Step 4: McpCommands 加入裸指令**

在 `McpCommands.java` 加入：

```java
@Command(name = "mcp", description = "List MCP server connections (alias for 'mcp list')")
public String mcpDefault() {
    return list();
}
```

- [ ] **Step 5: ChannelCommands 加入裸指令**

在 `ChannelCommands.java` 加入：

```java
@Command(name = "channel", description = "List configured channels (alias for 'channel list')")
public String channelDefault() {
    return list();
}
```

- [ ] **Step 6: 驗證編譯**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 驗證測試**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java \
        src/main/java/io/github/samzhu/grimo/task/TaskCommands.java \
        src/main/java/io/github/samzhu/grimo/skill/SkillCommands.java \
        src/main/java/io/github/samzhu/grimo/mcp/McpCommands.java \
        src/main/java/io/github/samzhu/grimo/channel/ChannelCommands.java
git commit -m "feat: add Smart Default bare commands for all modules (agent/task/skill/mcp/channel)"
```

---

## Task 2: Custom Prompt — 顯示目前 Agent

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/GrimoPromptProvider.java`
- Create: `src/test/java/io/github/samzhu/grimo/GrimoPromptProviderTest.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`

設計說明：實作 `PromptProvider` 介面，根據 `AgentRouter` 的狀態動態顯示目前使用的 agent ID。
無 agent 可用時顯示 `[no agent] grimo:>`。
Reference: https://docs.spring.io/spring-shell/reference/customization/custom-prompt.html

- [ ] **Step 1: 寫失敗測試**

```java
package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.provider.*;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrimoPromptProviderTest {

    @Test
    void promptShouldShowAgentIdWhenAvailable() {
        var registry = new AgentProviderRegistry();
        registry.register("anthropic", stubProvider("anthropic", true));
        var provider = new GrimoPromptProvider(registry);

        var prompt = provider.getPrompt();

        assertThat(prompt.toAnsi()).contains("anthropic");
        assertThat(prompt.toAnsi()).contains("grimo:>");
    }

    @Test
    void promptShouldShowNoAgentWhenNoneAvailable() {
        var registry = new AgentProviderRegistry();
        var provider = new GrimoPromptProvider(registry);

        var prompt = provider.getPrompt();

        assertThat(prompt.toAnsi()).contains("no agent");
        assertThat(prompt.toAnsi()).contains("grimo:>");
    }

    private AgentProvider stubProvider(String id, boolean available) {
        return new AgentProvider() {
            @Override public String id() { return id; }
            @Override public AgentType type() { return AgentType.API; }
            @Override public boolean isAvailable() { return available; }
            @Override public AgentResult execute(AgentRequest request) {
                return new AgentResult(true, "stub");
            }
        };
    }
}
```

- [ ] **Step 2: 執行測試驗證失敗**

Run: `./gradlew test --tests "io.github.samzhu.grimo.GrimoPromptProviderTest"`
Expected: FAIL — class does not exist

- [ ] **Step 3: 實作 GrimoPromptProvider**

```java
package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.springframework.shell.jline.PromptProvider;

/**
 * 自訂 Shell prompt，顯示目前可用的預設 agent。
 * 格式：[agent-id] grimo:> 或 [no agent] grimo:>
 *
 * 設計說明：透過 AgentProviderRegistry 查詢第一個可用的 agent，
 * 動態顯示在 prompt 中，讓使用者隨時知道目前的 AI 後端。
 *
 * Reference: https://docs.spring.io/spring-shell/reference/customization/custom-prompt.html
 */
public class GrimoPromptProvider implements PromptProvider {

    private final AgentProviderRegistry registry;

    public GrimoPromptProvider(AgentProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AttributedString getPrompt() {
        String agentLabel = registry.listAvailable().stream()
            .findFirst()
            .map(a -> a.id())
            .orElse("no agent");

        String prompt = "[" + agentLabel + "] grimo:> ";
        return new AttributedString(prompt,
            AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
    }
}
```

- [ ] **Step 4: 執行測試驗證通過**

Run: `./gradlew test --tests "io.github.samzhu.grimo.GrimoPromptProviderTest"`
Expected: PASS

- [ ] **Step 5: 在 GrimoStartupRunner 註冊 bean**

在 `GrimoStartupRunner.java` 加入 bean 定義：

```java
@Bean
GrimoPromptProvider grimoPromptProvider(AgentProviderRegistry registry) {
    return new GrimoPromptProvider(registry);
}
```

加入 import：
```java
import org.springframework.shell.jline.PromptProvider;
```

注意：需要上網確認 `PromptProvider` 在 Spring Shell 4.0.1 中的正確 package path。
可能是 `org.springframework.shell.jline.PromptProvider` 或其他路徑。

- [ ] **Step 6: 驗證編譯和測試**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoPromptProvider.java \
        src/test/java/io/github/samzhu/grimo/GrimoPromptProviderTest.java \
        src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java
git commit -m "feat: add custom PromptProvider showing current agent in prompt"
```

---

## Task 3: Agent 設定管理 — config.yaml 讀寫

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java`
- Modify: `src/test/java/io/github/samzhu/grimo/shared/config/GrimoConfigTest.java`

設計說明：擴展 `GrimoConfig`，加入 agent 設定的便利方法（getDefaultAgent, setDefaultAgent 等），
操作 config.yaml 中的 `agents.default` 和 `agents.model` 欄位。

- [ ] **Step 1: 寫失敗測試**

在 `GrimoConfigTest.java` 加入：

```java
@Test
void getDefaultAgentShouldReturnConfiguredValue() throws IOException {
    var configFile = tempDir.resolve("config.yaml");
    Files.writeString(configFile, """
        agents:
          default: anthropic
          model: claude-sonnet-4
        """);

    var config = new GrimoConfig(configFile);
    assertThat(config.getDefaultAgent()).isEqualTo("anthropic");
    assertThat(config.getDefaultModel()).isEqualTo("claude-sonnet-4");
}

@Test
void getDefaultAgentShouldReturnNullWhenNotConfigured() {
    var config = new GrimoConfig(tempDir.resolve("config.yaml"));
    assertThat(config.getDefaultAgent()).isNull();
    assertThat(config.getDefaultModel()).isNull();
}

@Test
void setDefaultAgentShouldPersist() {
    var configFile = tempDir.resolve("config.yaml");
    var config = new GrimoConfig(configFile);

    config.setDefaultAgent("openai");
    config.setDefaultModel("gpt-4o");

    // Re-read
    var reloaded = new GrimoConfig(configFile);
    assertThat(reloaded.getDefaultAgent()).isEqualTo("openai");
    assertThat(reloaded.getDefaultModel()).isEqualTo("gpt-4o");
}
```

- [ ] **Step 2: 執行測試驗證失敗**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.config.GrimoConfigTest"`
Expected: FAIL — methods don't exist

- [ ] **Step 3: 實作 GrimoConfig 的 agent 設定方法**

在 `GrimoConfig.java` 加入：

```java
/**
 * 取得 config.yaml 中設定的預設 agent ID。
 * @return agent ID，未設定時返回 null
 */
public String getDefaultAgent() {
    return getNestedString("agents", "default");
}

/**
 * 取得 config.yaml 中設定的預設模型名稱。
 * @return model name，未設定時返回 null
 */
public String getDefaultModel() {
    return getNestedString("agents", "model");
}

/**
 * 設定預設 agent ID 並持久化到 config.yaml。
 */
public void setDefaultAgent(String agentId) {
    setNestedValue("agents", "default", agentId);
}

/**
 * 設定預設模型並持久化到 config.yaml。
 */
public void setDefaultModel(String model) {
    setNestedValue("agents", "model", model);
}

@SuppressWarnings("unchecked")
private String getNestedString(String section, String key) {
    var data = load();
    var sectionMap = (Map<String, Object>) data.get(section);
    if (sectionMap == null) return null;
    var value = sectionMap.get(key);
    return value != null ? value.toString() : null;
}

@SuppressWarnings("unchecked")
private void setNestedValue(String section, String key, String value) {
    var data = load();
    var sectionMap = (Map<String, Object>) data.computeIfAbsent(section, k -> new LinkedHashMap<>());
    sectionMap.put(key, value);
    save(data);
}
```

- [ ] **Step 4: 執行測試驗證通過**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.config.GrimoConfigTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java \
        src/test/java/io/github/samzhu/grimo/shared/config/GrimoConfigTest.java
git commit -m "feat(shared): add agent config management to GrimoConfig (default agent/model)"
```

---

## Task 4: AgentRouter 支援 Config-based Default

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/router/AgentRouter.java`
- Modify: `src/test/java/io/github/samzhu/grimo/agent/router/AgentRouterTest.java`
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`

設計說明：修改 `AgentRouter` 接受 `GrimoConfig` 依賴，`route(null)` 時先查 config.yaml 的預設 agent，
找不到才用原本的自動選擇邏輯（CLI 優先）。

- [ ] **Step 1: 寫失敗測試**

在 `AgentRouterTest.java` 加入：

```java
@Test
void autoRouteShouldPreferConfigDefault() {
    registry.register("anthropic-api", stubProvider("anthropic-api", AgentType.API, true));
    registry.register("claude-cli", stubProvider("claude-cli", AgentType.CLI, true));

    // Config 指定 anthropic-api 為預設
    var configFile = tempDir.resolve("config.yaml");
    try {
        java.nio.file.Files.writeString(configFile, "agents:\n  default: anthropic-api\n");
    } catch (Exception e) { throw new RuntimeException(e); }
    var config = new io.github.samzhu.grimo.shared.config.GrimoConfig(configFile);
    var routerWithConfig = new AgentRouter(registry, config);

    var provider = routerWithConfig.route(null);

    // 應該用 config 指定的，不是 CLI 優先
    assertThat(provider.id()).isEqualTo("anthropic-api");
}
```

需要加入 `@TempDir Path tempDir;` 到測試類別。

- [ ] **Step 2: 執行測試驗證失敗**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.router.AgentRouterTest"`
Expected: FAIL — constructor signature changed

- [ ] **Step 3: 修改 AgentRouter 支援 GrimoConfig**

```java
package io.github.samzhu.grimo.agent.router;

import io.github.samzhu.grimo.agent.provider.AgentProvider;
import io.github.samzhu.grimo.agent.provider.AgentType;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;

import java.util.Comparator;

/**
 * 路由器負責根據指定的 agent ID 或自動選擇策略來決定使用哪個 AgentProvider。
 *
 * 自動選擇優先級：
 * 1. config.yaml 中的 agents.default 設定
 * 2. CLI 類型 provider（如 claude-cli）
 * 3. API 類型 provider
 */
public class AgentRouter {

    private final AgentProviderRegistry registry;
    private final GrimoConfig config;

    public AgentRouter(AgentProviderRegistry registry) {
        this(registry, null);
    }

    public AgentRouter(AgentProviderRegistry registry, GrimoConfig config) {
        this.registry = registry;
        this.config = config;
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
        // 1. 嘗試 config.yaml 中的預設 agent
        if (config != null) {
            String defaultAgent = config.getDefaultAgent();
            if (defaultAgent != null) {
                var configured = registry.get(defaultAgent)
                    .filter(AgentProvider::isAvailable);
                if (configured.isPresent()) {
                    return configured.get();
                }
            }
        }

        // 2. Fallback: CLI 優先，再 API
        return registry.listAvailable().stream()
            .sorted(Comparator.comparingInt(p -> p.type() == AgentType.CLI ? 0 : 1))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No agent providers available. Run 'agent add' to configure one."));
    }
}
```

- [ ] **Step 4: 更新既有測試的 setUp（保持向後相容）**

既有測試使用 `new AgentRouter(registry)` — 單參數建構子保持不變，所以既有測試不用改。

- [ ] **Step 5: 執行全部測試**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.router.AgentRouterTest"`
Expected: PASS

- [ ] **Step 6: 更新 GrimoStartupRunner bean 定義**

修改 `agentRouter` bean 加入 `GrimoConfig`：

```java
@Bean
AgentRouter agentRouter(AgentProviderRegistry registry, GrimoConfig grimoConfig) {
    return new AgentRouter(registry, grimoConfig);
}
```

- [ ] **Step 7: 驗證完整測試**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/router/AgentRouter.java \
        src/test/java/io/github/samzhu/grimo/agent/router/AgentRouterTest.java \
        src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java
git commit -m "feat(agent): AgentRouter supports config-based default agent selection"
```

---

## Task 5: Agent 指令擴展 — use / model

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java`
- Modify: `src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java`

設計說明：加入 `agent use <id>` 和 `agent model <name>` 指令，
透過 `GrimoConfig` 持久化設定到 config.yaml。

- [ ] **Step 1: 寫失敗測試**

在 `AgentCommandsTest.java` 加入（需要注入 `GrimoConfig`）：

```java
@TempDir
Path tempDir;

@Test
void useShouldSwitchDefaultAgent() {
    var config = new io.github.samzhu.grimo.shared.config.GrimoConfig(tempDir.resolve("config.yaml"));
    registry.register("openai", stubProvider("openai", AgentType.API, true));
    commands = new AgentCommands(registry, config);

    String result = commands.use("openai");

    assertThat(result).contains("openai");
    assertThat(config.getDefaultAgent()).isEqualTo("openai");
}

@Test
void useShouldRejectUnknownAgent() {
    var config = new io.github.samzhu.grimo.shared.config.GrimoConfig(tempDir.resolve("config.yaml"));
    commands = new AgentCommands(registry, config);

    String result = commands.use("nonexistent");

    assertThat(result).contains("not found");
}

@Test
void modelShouldSwitchDefaultModel() {
    var config = new io.github.samzhu.grimo.shared.config.GrimoConfig(tempDir.resolve("config.yaml"));
    commands = new AgentCommands(registry, config);

    String result = commands.model("gpt-4o");

    assertThat(result).contains("gpt-4o");
    assertThat(config.getDefaultModel()).isEqualTo("gpt-4o");
}
```

- [ ] **Step 2: 執行測試驗證失敗**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.AgentCommandsTest"`
Expected: FAIL

- [ ] **Step 3: 修改 AgentCommands**

修改建構子加入 `GrimoConfig`，加入新指令：

```java
private final AgentProviderRegistry registry;
private final GrimoConfig config;

public AgentCommands(AgentProviderRegistry registry, GrimoConfig config) {
    this.registry = registry;
    this.config = config;
}

@Command(name = {"agent", "use"}, description = "Switch default agent provider")
public String use(String agentId) {
    if (registry.get(agentId).isEmpty()) {
        return "Agent not found: " + agentId + ". Run 'agent' to see available agents.";
    }
    config.setDefaultAgent(agentId);
    return "Default agent switched to: " + agentId;
}

@Command(name = {"agent", "model"}, description = "Switch default model")
public String model(String modelName) {
    config.setDefaultModel(modelName);
    return "Default model switched to: " + modelName;
}
```

注意：需要更新既有測試的 `setUp()` 方法，建構 `AgentCommands` 時傳入 `GrimoConfig`。上網確認 Spring Shell 4.0.1 的 `@Command` 是否支援單一位置參數（不用 `@Option`）。

- [ ] **Step 4: 更新既有測試的 setUp**

既有測試需要加入 `GrimoConfig` 參數。使用 `@TempDir` 建立臨時 config。

- [ ] **Step 5: 執行測試驗證通過**

Run: `./gradlew test --tests "io.github.samzhu.grimo.agent.AgentCommandsTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java \
        src/test/java/io/github/samzhu/grimo/agent/AgentCommandsTest.java
git commit -m "feat(agent): add 'agent use' and 'agent model' commands for config management"
```

---

## Task 6: Prompt 整合 Config Default Agent

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoPromptProvider.java`
- Modify: `src/test/java/io/github/samzhu/grimo/GrimoPromptProviderTest.java`

設計說明：修改 `GrimoPromptProvider`，優先顯示 config.yaml 中設定的預設 agent，
而不是 registry 中第一個可用的 agent。

- [ ] **Step 1: 寫失敗測試**

```java
@Test
void promptShouldShowConfigDefaultAgent() throws IOException {
    var registry = new AgentProviderRegistry();
    registry.register("claude-cli", stubProvider("claude-cli", true));
    registry.register("anthropic", stubProvider("anthropic", true));

    var configFile = tempDir.resolve("config.yaml");
    Files.writeString(configFile, "agents:\n  default: anthropic\n");
    var config = new GrimoConfig(configFile);

    var provider = new GrimoPromptProvider(registry, config);
    var prompt = provider.getPrompt();

    assertThat(prompt.toAnsi()).contains("anthropic");
}
```

需要加入 `@TempDir Path tempDir;` 和相關 import。

- [ ] **Step 2: 修改 GrimoPromptProvider 接受 GrimoConfig**

```java
public class GrimoPromptProvider implements PromptProvider {

    private final AgentProviderRegistry registry;
    private final GrimoConfig config;

    public GrimoPromptProvider(AgentProviderRegistry registry) {
        this(registry, null);
    }

    public GrimoPromptProvider(AgentProviderRegistry registry, GrimoConfig config) {
        this.registry = registry;
        this.config = config;
    }

    @Override
    public AttributedString getPrompt() {
        String agentLabel = resolveAgentLabel();
        String prompt = "[" + agentLabel + "] grimo:> ";
        return new AttributedString(prompt,
            AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
    }

    private String resolveAgentLabel() {
        // 1. 優先用 config 預設
        if (config != null) {
            String defaultAgent = config.getDefaultAgent();
            if (defaultAgent != null && registry.get(defaultAgent)
                    .filter(a -> a.isAvailable()).isPresent()) {
                return defaultAgent;
            }
        }
        // 2. Fallback: 第一個可用 agent
        return registry.listAvailable().stream()
            .findFirst()
            .map(a -> a.id())
            .orElse("no agent");
    }
}
```

- [ ] **Step 3: 更新 GrimoStartupRunner bean**

```java
@Bean
GrimoPromptProvider grimoPromptProvider(AgentProviderRegistry registry, GrimoConfig grimoConfig) {
    return new GrimoPromptProvider(registry, grimoConfig);
}
```

- [ ] **Step 4: 執行測試驗證通過**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoPromptProvider.java \
        src/test/java/io/github/samzhu/grimo/GrimoPromptProviderTest.java \
        src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java
git commit -m "feat: PromptProvider shows config-based default agent"
```

---

## Task 7: Full Build Verification

- [ ] **Step 1: 執行完整建置**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 建置 jar 並測試啟動**

Run: `./gradlew bootJar && java -jar build/libs/grimo-0.0.1-SNAPSHOT.jar`

Expected:
1. 看到自訂 prompt `[agent-id] grimo:>` 或 `[no agent] grimo:>`
2. 輸入 `agent` 顯示 agent 列表
3. 輸入 `task` 顯示任務列表
4. 輸入 `status` 顯示系統狀態
5. 輸入 `help` 顯示所有可用指令（包含裸指令別名）
