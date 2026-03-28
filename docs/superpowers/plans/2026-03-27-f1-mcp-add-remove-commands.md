# F1 附加：MCP Add/Remove Commands — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提供 `/mcp-add` 和 `/mcp-remove` 斜線指令，讓使用者在 TUI/CLI 管理 MCP server，新增後立即生效。

**Architecture:** `GrimoConfig` 新增 MCP 寫入/移除方法（synchronized）。`McpCatalogBuilder` 從無狀態改為自管快取（volatile），提供 `rebuild()`/`getCatalog()`/`getServerNames()`。`McpCommands` 新增兩個 `@Command`。`GrimoTuiRunner` 移除自管快取，改用 Builder 的 getter。

**Tech Stack:** Spring Shell 4.0 `@Command`, SnakeYAML, `volatile` 可見性保證

**Spec:** [docs/superpowers/specs/2026-03-27-f1-mcp-add-remove-commands.md](../specs/2026-03-27-f1-mcp-add-remove-commands.md)

---

## 流程圖

```
/mcp-add --name deepwiki --type sse --url https://mcp.deepwiki.com/sse
    │
    ├── McpCommands.add()
    │     ├── 驗證 name 格式（[a-zA-Z0-9_-]+）
    │     ├── 驗證 type（stdio/sse/http）
    │     ├── 驗證 url 格式（sse/http 時）
    │     ├── 驗證 command 存在（stdio 時）
    │     ├── 檢查 name 不重複
    │     │
    │     ├── config.setMcpServer(name, serverMap)  ← synchronized load→modify→save
    │     └── catalogBuilder.rebuild()               ← volatile 快取替換
    │
    └── 下一次 AgentClient.builder(model)
            .mcpServerCatalog(catalogBuilder.getCatalog())   ← 讀到新快取
            .defaultMcpServers(catalogBuilder.getServerNames())
            .build()
```

---

## File Map

| 動作 | 檔案 | 職責 |
|------|------|------|
| Modify | `src/main/java/.../shared/config/GrimoConfig.java` | 新增 `setMcpServer()`, `removeMcpServer()`；load/save 加 `synchronized` |
| Test | `src/test/java/.../shared/config/GrimoConfigTest.java` | 新增 setMcpServer/removeMcpServer 測試 |
| Modify | `src/main/java/.../mcp/McpCatalogBuilder.java` | 新增 volatile 快取 + `rebuild()`, `getCatalog()`, `getServerNames()` |
| Test | `src/test/java/.../mcp/McpCatalogBuilderTest.java` | 新增 rebuild/getCatalog/getServerNames 測試 |
| Modify | `src/main/java/.../mcp/McpCommands.java` | 新增 `mcp-add`, `mcp-remove` @Command；注入 `McpCatalogBuilder` |
| Test | `src/test/java/.../mcp/McpCommandsTest.java` | 新增 add/remove 指令測試 |
| Modify | `src/main/java/.../GrimoTuiRunner.java` | 移除自管 `mcpCatalog`/`mcpServerNames` 快取，改用 `mcpCatalogBuilder.getCatalog()`/`getServerNames()` |

---

### Task 1: GrimoConfig — 新增 synchronized + MCP 寫入/移除方法

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java`
- Test: `src/test/java/io/github/samzhu/grimo/shared/config/GrimoConfigTest.java`

- [ ] **Step 1: 寫 setMcpServer 的 failing test**

在 `GrimoConfigTest.java` 新增：

```java
@Test
void setMcpServerShouldPersistSseServer() {
    var configFile = tempDir.resolve("config.yaml");
    var config = new GrimoConfig(configFile);

    config.setMcpServer("deepwiki", Map.of(
            "type", "sse",
            "url", "https://mcp.deepwiki.com/sse"
    ));

    var reloaded = new GrimoConfig(configFile);
    var servers = reloaded.getMcpServers();
    assertThat(servers).containsKey("deepwiki");
    assertThat(servers.get("deepwiki").get("type")).isEqualTo("sse");
    assertThat(servers.get("deepwiki").get("url")).isEqualTo("https://mcp.deepwiki.com/sse");
}

@Test
void setMcpServerShouldCreateMcpSectionWhenMissing() {
    var configFile = tempDir.resolve("config.yaml");
    var config = new GrimoConfig(configFile);

    // config.yaml 不存在時也能正常寫入
    config.setMcpServer("test-server", Map.of("type", "http", "url", "http://localhost/mcp"));

    assertThat(configFile).exists();
    var servers = new GrimoConfig(configFile).getMcpServers();
    assertThat(servers).containsKey("test-server");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.config.GrimoConfigTest.setMcpServerShouldPersistSseServer"`
Expected: FAIL — `setMcpServer` method not found

- [ ] **Step 3: 實作 setMcpServer**

在 `GrimoConfig.java` 新增：

```java
/**
 * 新增或更新一個 MCP server 定義到 config.yaml 的 mcp 區段。
 *
 * 設計說明：
 * - 使用 computeIfAbsent 處理 mcp 區段不存在的情況
 * - synchronized 防止 agent virtual thread 讀取時與寫入衝突
 *
 * @param name      server 名稱（YAML key）
 * @param serverDef server 定義（type, url/command, args 等）
 */
@SuppressWarnings("unchecked")
public synchronized void setMcpServer(String name, Map<String, Object> serverDef) {
    var data = load();
    var mcp = (Map<String, Map<String, Object>>) data.computeIfAbsent("mcp", k -> new LinkedHashMap<>());
    mcp.put(name, new LinkedHashMap<>(serverDef));
    save(data);
}
```

同時將 `load()` 和 `save()` 加上 `synchronized`：

```java
public synchronized Map<String, Object> load() { ... }
public synchronized void save(Map<String, Object> data) { ... }
```

以及所有呼叫 load/save 的方法（`setNestedValue`, `getNestedString`, `getMcpServers`, `getAgentOption`）也加上 `synchronized`。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.config.GrimoConfigTest"`
Expected: ALL PASS

- [ ] **Step 5: 寫 removeMcpServer 的 failing test**

```java
@Test
void removeMcpServerShouldDeleteFromConfig() throws IOException {
    var configFile = tempDir.resolve("config.yaml");
    Files.writeString(configFile, """
        mcp:
          deepwiki:
            type: sse
            url: https://mcp.deepwiki.com/sse
          filesystem:
            type: stdio
            command: npx
        """);

    var config = new GrimoConfig(configFile);
    boolean removed = config.removeMcpServer("deepwiki");

    assertThat(removed).isTrue();
    var servers = new GrimoConfig(configFile).getMcpServers();
    assertThat(servers).doesNotContainKey("deepwiki");
    assertThat(servers).containsKey("filesystem");
}

@Test
void removeMcpServerShouldReturnFalseWhenNotFound() {
    var configFile = tempDir.resolve("config.yaml");
    var config = new GrimoConfig(configFile);

    boolean removed = config.removeMcpServer("nonexistent");

    assertThat(removed).isFalse();
}
```

- [ ] **Step 6: 實作 removeMcpServer**

```java
/**
 * 從 config.yaml 的 mcp 區段移除指定 server。
 *
 * @param name server 名稱
 * @return true 表示成功移除，false 表示 server 不存在
 */
@SuppressWarnings("unchecked")
public synchronized boolean removeMcpServer(String name) {
    var data = load();
    var mcp = (Map<String, Map<String, Object>>) data.get("mcp");
    if (mcp == null || !mcp.containsKey(name)) {
        return false;
    }
    mcp.remove(name);
    save(data);
    return true;
}
```

- [ ] **Step 7: Run all GrimoConfig tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.shared.config.GrimoConfigTest"`
Expected: ALL PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/shared/config/GrimoConfig.java \
        src/test/java/io/github/samzhu/grimo/shared/config/GrimoConfigTest.java
git commit -m "feat(f1): add synchronized setMcpServer/removeMcpServer to GrimoConfig"
```

---

### Task 2: McpCatalogBuilder — 自管 volatile 快取 + rebuild

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/mcp/McpCatalogBuilder.java`
- Test: `src/test/java/io/github/samzhu/grimo/mcp/McpCatalogBuilderTest.java`

- [ ] **Step 1: 寫 rebuild + getCatalog 的 failing test**

在 `McpCatalogBuilderTest.java` 新增：

```java
@Test
void rebuildShouldUpdateCachedCatalog() {
    var config = mock(GrimoConfig.class);
    // 第一次 build：空
    when(config.getMcpServers()).thenReturn(Map.of());
    var builder = new McpCatalogBuilder(config);
    builder.rebuild();
    assertThat(builder.getCatalog().getAll()).isEmpty();
    assertThat(builder.getServerNames()).isEmpty();

    // 第二次 rebuild：有一個 server
    when(config.getMcpServers()).thenReturn(Map.of(
            "deepwiki", Map.of("type", "sse", "url", "https://mcp.deepwiki.com/sse")
    ));
    builder.rebuild();
    assertThat(builder.getCatalog().getAll()).hasSize(1);
    assertThat(builder.getServerNames()).containsExactly("deepwiki");
}

@Test
void getCatalogBeforeRebuildShouldReturnEmpty() {
    var config = mock(GrimoConfig.class);
    when(config.getMcpServers()).thenReturn(Map.of());
    var builder = new McpCatalogBuilder(config);

    // 未呼叫 rebuild 前，getCatalog 回傳空 catalog
    assertThat(builder.getCatalog().getAll()).isEmpty();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.samzhu.grimo.mcp.McpCatalogBuilderTest.rebuildShouldUpdateCachedCatalog"`
Expected: FAIL — `rebuild()` method not found

- [ ] **Step 3: 實作 volatile 快取 + rebuild/getCatalog/getServerNames**

修改 `McpCatalogBuilder.java`：

```java
@Component
public class McpCatalogBuilder {

    private final GrimoConfig config;

    /**
     * 設計說明：volatile 保證 command thread 寫入後 agent virtual thread 能看到最新值。
     * McpServerCatalog 本身是 immutable，替換參照即可安全切換。
     */
    private volatile McpServerCatalog cachedCatalog = McpServerCatalog.of(Map.of());
    private volatile List<String> cachedServerNames = List.of();

    public McpCatalogBuilder(GrimoConfig config) {
        this.config = config;
    }

    /**
     * 重讀 config.yaml 並重建 catalog 快取。
     * 由 /mcp-add、/mcp-remove 呼叫以達到即時生效。
     * 也由 GrimoTuiRunner Phase 2 啟動時呼叫。
     */
    public void rebuild() {
        McpServerCatalog newCatalog = build();
        cachedServerNames = List.copyOf(newCatalog.getAll().keySet());
        cachedCatalog = newCatalog;
    }

    /** 取得目前快取的 catalog（immutable，thread-safe）。 */
    public McpServerCatalog getCatalog() {
        return cachedCatalog;
    }

    /** 取得目前快取的 server 名稱列表（immutable）。 */
    public List<String> getServerNames() {
        return cachedServerNames;
    }

    // build() 方法保持不變（仍是 public，rebuild 內部呼叫）
    // ...
}
```

- [ ] **Step 4: Run all McpCatalogBuilder tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.mcp.McpCatalogBuilderTest"`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/mcp/McpCatalogBuilder.java \
        src/test/java/io/github/samzhu/grimo/mcp/McpCatalogBuilderTest.java
git commit -m "feat(f1): add volatile cached catalog with rebuild/getCatalog/getServerNames"
```

---

### Task 3: McpCommands — 新增 mcp-add 和 mcp-remove 指令

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/mcp/McpCommands.java`
- Test: `src/test/java/io/github/samzhu/grimo/mcp/McpCommandsTest.java`

- [ ] **Step 1: 寫 mcp-add SSE 的 failing test**

在 `McpCommandsTest.java` 中，修改 `setUp()` 並新增測試：

```java
McpCatalogBuilder catalogBuilder;

@BeforeEach
void setUp() {
    config = mock(GrimoConfig.class);
    catalogBuilder = mock(McpCatalogBuilder.class);
    commands = new McpCommands(config, catalogBuilder);
}

@Test
void addSseShouldPersistAndRebuild() {
    when(config.getMcpServers()).thenReturn(Map.of());

    String result = commands.add("deepwiki", "sse", "https://mcp.deepwiki.com/sse", null, null);

    assertThat(result).contains("Added").contains("deepwiki");
    verify(config).setMcpServer(eq("deepwiki"), argThat(map ->
            "sse".equals(map.get("type")) && "https://mcp.deepwiki.com/sse".equals(map.get("url"))
    ));
    verify(catalogBuilder).rebuild();
}

@Test
void addStdioShouldPersistWithArgs() {
    when(config.getMcpServers()).thenReturn(Map.of());

    String result = commands.add("fs", "stdio", null, "npx", "-y,@modelcontextprotocol/server-filesystem,/tmp");

    assertThat(result).contains("Added").contains("fs");
    verify(config).setMcpServer(eq("fs"), argThat(map ->
            "stdio".equals(map.get("type"))
            && "npx".equals(map.get("command"))
            && map.get("args") instanceof List<?> argsList
            && argsList.size() == 3
            && argsList.get(0).equals("-y")
    ));
}

@Test
void addShouldRejectInvalidName() {
    String result = commands.add("bad name!", "sse", "https://example.com", null, null);
    assertThat(result).contains("Invalid name");
}

@Test
void addShouldRejectInvalidType() {
    String result = commands.add("test", "websocket", null, null, null);
    assertThat(result).contains("Invalid type");
}

@Test
void addShouldRejectMissingUrlForSse() {
    String result = commands.add("test", "sse", null, null, null);
    assertThat(result).contains("--url is required");
}

@Test
void addShouldRejectMissingCommandForStdio() {
    String result = commands.add("test", "stdio", null, null, null);
    assertThat(result).contains("--command is required");
}

@Test
void addShouldRejectDuplicateName() {
    when(config.getMcpServers()).thenReturn(Map.of(
            "deepwiki", Map.of("type", "sse", "url", "https://mcp.deepwiki.com/sse")
    ));

    String result = commands.add("deepwiki", "sse", "https://other.com", null, null);
    assertThat(result).contains("already exists");
}

@Test
void addShouldRejectInvalidUrl() {
    when(config.getMcpServers()).thenReturn(Map.of());

    String result = commands.add("test", "sse", "not-a-url", null, null);
    assertThat(result).contains("Invalid URL");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.samzhu.grimo.mcp.McpCommandsTest.addSseShouldPersistAndRebuild"`
Expected: FAIL — constructor signature mismatch or `add` method not found

- [ ] **Step 3: 實作 mcp-add**

修改 `McpCommands.java`：

```java
import io.github.samzhu.grimo.mcp.McpCatalogBuilder;
import org.springframework.shell.core.command.annotation.Option;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class McpCommands {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]+");
    private static final Set<String> VALID_TYPES = Set.of("stdio", "sse", "http");

    private final GrimoConfig config;
    private final McpCatalogBuilder catalogBuilder;

    public McpCommands(GrimoConfig config, McpCatalogBuilder catalogBuilder) {
        this.config = config;
        this.catalogBuilder = catalogBuilder;
    }

    /**
     * 新增 MCP server 到 config.yaml 並即時重建 catalog。
     *
     * 設計說明：
     * - 驗證通過後寫入 config.yaml，再呼叫 catalogBuilder.rebuild() 即時生效
     * - args 以逗號分隔傳入，轉為 List<String> 存入 YAML
     * - env/headers 不支援（YAGNI），需要時手動編輯 config.yaml
     */
    @Command(name = "mcp-add", description = "Add an MCP server")
    public String add(
            @Option(longName = "name", shortName = 'n', description = "Server name", required = true)
            String name,
            @Option(longName = "type", shortName = 't', description = "Server type: stdio, sse, http", required = true)
            String type,
            @Option(longName = "url", shortName = 'u', description = "Server URL (for sse/http)")
            String url,
            @Option(longName = "command", shortName = 'c', description = "Command (for stdio)")
            String command,
            @Option(longName = "args", shortName = 'a', description = "Comma-separated args (for stdio)")
            String args) {

        // === 參數驗證 ===
        if (!NAME_PATTERN.matcher(name).matches()) {
            return "Invalid name '" + name + "'. Use only letters, digits, hyphens, underscores.";
        }
        if (!VALID_TYPES.contains(type)) {
            return "Invalid type '" + type + "'. Supported: stdio, sse, http";
        }
        if (("sse".equals(type) || "http".equals(type)) && (url == null || url.isBlank())) {
            return "--url is required for type '" + type + "'";
        }
        if (("sse".equals(type) || "http".equals(type)) && url != null) {
            try {
                URI.create(url).toURL();
            } catch (Exception e) {
                return "Invalid URL: '" + url + "'";
            }
        }
        if ("stdio".equals(type) && (command == null || command.isBlank())) {
            return "--command is required for type 'stdio'";
        }
        if (config.getMcpServers().containsKey(name)) {
            return "MCP server '" + name + "' already exists. Remove it first.";
        }

        // === 組裝 server 定義 ===
        var serverDef = new LinkedHashMap<String, Object>();
        serverDef.put("type", type);
        if ("stdio".equals(type)) {
            serverDef.put("command", command);
            if (args != null && !args.isBlank()) {
                serverDef.put("args", Arrays.asList(args.split(",")));
            }
        } else {
            serverDef.put("url", url);
        }

        // === 寫入 + 重建 ===
        config.setMcpServer(name, serverDef);
        catalogBuilder.rebuild();

        return "Added MCP server: " + name + " (" + type + ")";
    }

    // list() 方法保持不變
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.mcp.McpCommandsTest"`
Expected: ALL PASS

- [ ] **Step 5: 寫 mcp-remove 的 failing test**

```java
@Test
void removeShouldDeleteAndRebuild() {
    when(config.removeMcpServer("deepwiki")).thenReturn(true);

    String result = commands.remove("deepwiki");

    assertThat(result).contains("Removed").contains("deepwiki");
    verify(config).removeMcpServer("deepwiki");
    verify(catalogBuilder).rebuild();
}

@Test
void removeShouldReportNotFound() {
    when(config.removeMcpServer("nonexistent")).thenReturn(false);

    String result = commands.remove("nonexistent");

    assertThat(result).contains("not found");
    verify(catalogBuilder, never()).rebuild();
}
```

- [ ] **Step 6: 實作 mcp-remove**

在 `McpCommands.java` 新增：

```java
/**
 * 從 config.yaml 移除 MCP server 並即時重建 catalog。
 */
@Command(name = "mcp-remove", description = "Remove an MCP server")
public String remove(
        @Option(longName = "name", shortName = 'n', description = "Server name", required = true)
        String name) {

    boolean removed = config.removeMcpServer(name);
    if (!removed) {
        return "MCP server '" + name + "' not found.";
    }

    catalogBuilder.rebuild();
    return "Removed MCP server: " + name;
}
```

- [ ] **Step 7: Run all McpCommands tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.mcp.McpCommandsTest"`
Expected: ALL PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/mcp/McpCommands.java \
        src/test/java/io/github/samzhu/grimo/mcp/McpCommandsTest.java
git commit -m "feat(f1): add /mcp-add and /mcp-remove commands with validation"
```

---

### Task 4: GrimoTuiRunner — 移除自管快取，改用 Builder getter

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`

- [ ] **Step 1: 移除自管快取欄位**

刪除 GrimoTuiRunner.java 中的：

```java
/** 快取的 MCP catalog，Phase 2 建構後不再變動（config 不變就不重建） */
private McpServerCatalog mcpCatalog;
private List<String> mcpServerNames;
```

以及移除不再需要的 import：

```java
import org.springaicommunity.agents.model.mcp.McpServerCatalog;
```

- [ ] **Step 2: Phase 2 改用 rebuild()**

將 `run()` 方法中的：

```java
// Phase 2: MCP catalog 建構（快取，避免每次 agent 呼叫都重建）
mcpCatalog = mcpCatalogBuilder.build();
mcpServerNames = List.copyOf(mcpCatalog.getAll().keySet());
log.debug("MCP catalog built: {} servers [{}]", mcpServerNames.size(),
        String.join(", ", mcpServerNames));
```

改為：

```java
// Phase 2: MCP catalog 初始建構（快取在 McpCatalogBuilder 內，/mcp-add 時自動 rebuild）
mcpCatalogBuilder.rebuild();
log.debug("MCP catalog built: {} servers [{}]",
        mcpCatalogBuilder.getServerNames().size(),
        String.join(", ", mcpCatalogBuilder.getServerNames()));
```

- [ ] **Step 3: AgentClient 建立改用 getter**

將 `processInput()` 中的：

```java
var client = AgentClient.builder(model)
        .mcpServerCatalog(mcpCatalog)
        .defaultMcpServers(mcpServerNames)
        .build();
```

改為：

```java
// 設計說明：每次取 McpCatalogBuilder 最新快取，/mcp-add 後即時生效
// volatile 保證 command thread 寫入後 virtual thread 能看到新值
var client = AgentClient.builder(model)
        .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
        .defaultMcpServers(mcpCatalogBuilder.getServerNames())
        .build();
```

- [ ] **Step 4: 確認編譯通過**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run all affected tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.mcp.*"`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "refactor(f1): replace self-managed MCP cache with McpCatalogBuilder getters"
```

---

### Task 5: E2E 驗證

- [ ] **Step 1: 啟動 app（無 MCP 設定）**

Run: `./gradlew bootRun`
Expected: 正常啟動，log 顯示 `MCP catalog built: 0 servers []`

- [ ] **Step 2: 在 TUI 中新增 SSE MCP server**

輸入: `/mcp-add --name deepwiki --type sse --url https://mcp.deepwiki.com/sse`
Expected: `Added MCP server: deepwiki (sse)`

- [ ] **Step 3: 確認 /mcp-list**

輸入: `/mcp-list`
Expected: 顯示 deepwiki sse https://mcp.deepwiki.com/sse

- [ ] **Step 4: 確認 config.yaml 已寫入**

Run: `cat ~/.grimo/config.yaml`
Expected: 包含 `deepwiki` 的 `mcp` 區段

- [ ] **Step 5: 移除 MCP server**

輸入: `/mcp-remove --name deepwiki`
Expected: `Removed MCP server: deepwiki`

- [ ] **Step 6: 確認移除成功**

輸入: `/mcp-list`
Expected: `No MCP servers configured.`

---

## 設計決策記錄

### Q1: 為什麼 GrimoConfig 的 load/save 要加 synchronized？

Agent 在 virtual thread 上執行，會透過 `mcpCatalogBuilder.getCatalog()` → `build()` → `config.getMcpServers()` 間接讀取 config。同時使用者可能在 Shell command thread 執行 `/mcp-add`，觸發 `config.setMcpServer()` 寫入。沒有 synchronized 時，reader 可能讀到部分寫入的 YAML 檔案。

### Q2: 為什麼用 volatile 而不是 AtomicReference？

只有一個寫入者（command thread）、多個讀取者（agent virtual threads）。`volatile` 足以保證可見性，不需要 CAS 語意。`AtomicReference` 在此場景無額外好處但增加複雜度。

### Q3: 正在執行的 agent 會受 rebuild 影響嗎？

不會。`AgentClient.builder()` 在建立時已持有 `McpServerCatalog` 的參照（immutable object）。`rebuild()` 替換的是 `cachedCatalog` volatile 欄位的參照，不影響舊物件。新 catalog 在下一次 `AgentClient.builder()` 呼叫時才生效。
