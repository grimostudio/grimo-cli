# F1: MCP Catalog 接通 AgentClient — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 讓 AgentClient 在執行時自動帶上使用者在 `~/.grimo/config.yaml` 定義的所有 MCP server，使 CLI agent 能使用 Portable MCP tools。

**Architecture:** GrimoTuiRunner 注入 `McpCatalogBuilder`，在啟動 Phase 2 建構並快取 `McpServerCatalog`。AgentClient 從 `create()` 改用 `builder()` pattern，傳入 catalog 和 default server 名稱列表。各 CLI agent（Claude/Gemini/Codex）由 SDK 自動將 Portable MCP 定義轉成原生格式。

**Tech Stack:** Spring AI Community Agent Client 0.10.0-SNAPSHOT（`AgentClient.Builder.mcpServerCatalog()` + `defaultMcpServers()`）

**Spec:** [docs/superpowers/specs/2026-03-27-f1-mcp-catalog-wiring.md](../specs/2026-03-27-f1-mcp-catalog-wiring.md)

---

## SDK API 驗證結果

> 以下 API 已透過 `javap` 對 `spring-ai-agent-client-0.10.0-SNAPSHOT.jar` 確認（非猜測）。

### AgentClient（interface）

```java
// 靜態工廠方法
static AgentClient create(AgentModel model);           // 現有用法（無 MCP）
static AgentClient.Builder builder(AgentModel model);  // ← 改用這個

// 實例方法
AgentClientRequestSpec goal();
AgentClientRequestSpec goal(String text);
AgentClientRequestSpec goal(Goal goal);
AgentClientResponse run(String text);
AgentClientResponse run(String text, AgentOptions options);
Builder mutate();
```

### AgentClient.Builder（interface）

```java
Builder defaultOptions(AgentOptions options);
Builder defaultWorkingDirectory(Path path);
Builder defaultTimeout(Duration timeout);
Builder defaultAdvisors(List<AgentCallAdvisor> advisors);
Builder defaultAdvisor(AgentCallAdvisor advisor);
Builder mcpServerCatalog(McpServerCatalog catalog);     // ← 傳入 catalog
Builder defaultMcpServers(String... serverNames);       // ← 預設啟用的 server 名稱
Builder defaultMcpServers(List<String> serverNames);
AgentClient build();
```

### AgentClientRequestSpec（interface）

```java
AgentClientRequestSpec goal(String text);
AgentClientRequestSpec workingDirectory(Path path);
AgentClientRequestSpec advisors(AgentCallAdvisor... advisors);
AgentClientRequestSpec advisors(List<AgentCallAdvisor> advisors);
AgentClientRequestSpec mcpServers(String... serverNames);  // per-request 覆蓋
AgentClientRequestSpec mcpServers(List<String> serverNames);
AgentClientResponse run();
```

> 參考來源：
> - `javap -public spring-ai-agent-client-0.10.0-SNAPSHOT.jar org.springaicommunity.agents.client.AgentClient`
> - `javap -public ... AgentClient$Builder`
> - `javap -public ... AgentClient$AgentClientRequestSpec`
> - [Spring AI Community Agent Client](https://spring-ai-community.github.io/agent-client/)

---

## 流程圖

### 現狀（斷路）

```
GrimoTuiRunner.processInput(text)
    │
    ├── agentRouter.route(null) → AgentModel
    │
    └── AgentClient.create(model)          ← 無 MCP、無 Advisor
            .goal(text)
            .workingDirectory(cwd)
            .run()
```

### 目標（接通）

```
GrimoTuiRunner.run()
    │
    ├── Phase 2: mcpCatalogBuilder.build() → McpServerCatalog (快取)
    │             catalog.getAll().keySet() → serverNames (快取)
    │
    └── processInput(text)
            │
            ├── agentRouter.route(null) → AgentModel
            │
            └── AgentClient.builder(model)          ← 新 builder pattern
                    .mcpServerCatalog(mcpCatalog)    ← 傳入完整 catalog
                    .defaultMcpServers(serverNames)  ← 所有 server 預設啟用
                    .build()
                    .goal(text)
                    .workingDirectory(cwd)
                    .run()
```

### 循序圖（Agent 呼叫 MCP Tool）

```
User           GrimoTuiRunner    AgentClient.Builder   AgentClient     CLI Agent (Claude/Gemini/Codex)    MCP Server
  │                  │                   │                  │                    │                            │
  │──"search X"─────>│                   │                  │                    │                            │
  │                  │──builder(model)──>│                  │                    │                            │
  │                  │  .mcpServerCatalog(catalog)          │                    │                            │
  │                  │  .defaultMcpServers(names)           │                    │                            │
  │                  │  .build()────────>│                  │                    │                            │
  │                  │                   │                  │                    │                            │
  │                  │──────────────────goal(text)─────────>│                    │                            │
  │                  │                   │                  │──subprocess────────>│                            │
  │                  │                   │                  │  (with MCP config) │                            │
  │                  │                   │                  │                    │──tool_call────────────────>│
  │                  │                   │                  │                    │<─────────result────────────│
  │                  │                   │                  │<──response─────────│                            │
  │                  │<─────────AgentClientResponse─────────│                    │                            │
  │<──AI Reply───────│                   │                  │                    │                            │
```

> SDK 的 Portable MCP 負責將 `McpServerDefinition` 自動轉成各 CLI 原生格式：
> - Claude Code → `--mcp-config` JSON
> - Gemini CLI → gemini 原生 MCP 設定
> - Codex CLI → codex 原生 MCP 設定

---

## File Map

| 動作 | 檔案 | 職責 |
|------|------|------|
| Modify | `src/main/java/.../GrimoTuiRunner.java` | 注入 McpCatalogBuilder，Phase 2 建構 catalog，AgentClient 改用 builder pattern |
| Modify | `docs/glossary.md` | 補充 Portable MCP 術語 |

> **不需修改的檔案：**
> - `McpCatalogBuilder.java` — 已是 `@Component`，已有完整實作和測試
> - `GrimoStartupRunner.java` — McpCatalogBuilder 透過 `@Component` 自動註冊，不需額外 @Bean
> - `AgentConfiguration.java` — AgentModel 建構不涉及 MCP（MCP 在 AgentClient 層處理）
> - `config.yaml` MCP 區段 — 已支援（McpCatalogBuilder 已能讀取）

---

### Task 1: GrimoTuiRunner — 注入 McpCatalogBuilder 並快取 catalog

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java:8,55-56,80-106,115-118`

- [ ] **Step 1: 新增 import 和欄位**

在 import 區加入：
```java
import io.github.samzhu.grimo.mcp.McpCatalogBuilder;
import org.springaicommunity.agents.model.mcp.McpServerCatalog;
```

新增欄位（在 `agentThread` 宣告之後）：
```java
/** 快取的 MCP catalog，Phase 2 建構後不再變動（config 不變就不重建） */
private McpServerCatalog mcpCatalog;
private List<String> mcpServerNames;
```

> 注意：`mcpServerNames` 使用 `List.copyOf()` 建立 immutable list，對齊 catalog 的 immutable 設計意圖。

- [ ] **Step 2: 修改 constructor，注入 McpCatalogBuilder**

在 constructor 參數列加入 `McpCatalogBuilder mcpCatalogBuilder`：

```java
private final McpCatalogBuilder mcpCatalogBuilder;

public GrimoTuiRunner(Terminal terminal,
                       WorkspaceManager workspaceManager,
                       GrimoConfig grimoConfig,
                       AgentModelFactory agentModelFactory,
                       AgentModelRegistry agentModelRegistry,
                       AgentRouter agentRouter,
                       SkillLoader skillLoader,
                       SkillRegistry skillRegistry,
                       TaskSchedulerService taskSchedulerService,
                       BannerRenderer bannerRenderer,
                       CommandParser commandParser,
                       CommandExecutor commandExecutor,
                       CommandRegistry commandRegistry,
                       McpCatalogBuilder mcpCatalogBuilder) {
    // ... existing assignments ...
    this.mcpCatalogBuilder = mcpCatalogBuilder;
}
```

- [ ] **Step 3: Phase 2 建構 MCP catalog**

在 `run()` 方法的 Phase 2 區塊（`loadSkills()` 之後、`restoreTasks()` 之前）加入：

```java
// Phase 2: MCP catalog 建構（快取，避免每次 agent 呼叫都重建）
mcpCatalog = mcpCatalogBuilder.build();
mcpServerNames = List.copyOf(mcpCatalog.getAll().keySet());
log.debug("MCP catalog built: {} servers [{}]", mcpServerNames.size(),
        String.join(", ", mcpServerNames));
```

- [ ] **Step 4: 確認編譯通過**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "feat(f1): inject McpCatalogBuilder into GrimoTuiRunner and cache catalog at startup"
```

---

### Task 2: AgentClient 改用 builder pattern 傳入 MCP catalog

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java:379-386`

- [ ] **Step 1: 新增 import 並簡化類名引用**

在 import 區加入：
```java
import org.springaicommunity.agents.client.AgentClient;
```

- [ ] **Step 2: 修改 AgentClient 建立方式**

將 `processInput()` 中的 agent 呼叫從：

```java
var client = org.springaicommunity.agents.client.AgentClient.create(model);
var response = client
        .goal(text)
        .workingDirectory(java.nio.file.Path.of(System.getProperty("user.dir")))
        .run();
```

改為（使用短類名）：

```java
// 設計說明：使用 builder pattern 傳入 MCP catalog，讓 CLI agent 自動帶上 MCP tools
// McpServerCatalog 由 Portable MCP 機制自動轉成各 CLI 原生格式
// 參考：javap AgentClient$Builder → mcpServerCatalog() + defaultMcpServers()
var client = AgentClient.builder(model)
        .mcpServerCatalog(mcpCatalog)
        .defaultMcpServers(mcpServerNames)
        .build();
var response = client
        .goal(text)
        .workingDirectory(java.nio.file.Path.of(System.getProperty("user.dir")))
        .run();
```

- [ ] **Step 3: 確認編譯通過**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "feat(f1): wire McpServerCatalog into AgentClient via builder pattern"
```

---

### Task 3: 更新 glossary — 補充 Portable MCP 術語

**Files:**
- Modify: `docs/glossary.md`

- [ ] **Step 1: 在「Agent 技術元件對應」表格之後新增 Portable MCP 說明**

在 glossary.md 的「調度系統術語」表格中新增：

```markdown
| **Portable MCP** | Portable MCP | Spring AI Community Agent Client 的 MCP 轉換機制。在 `config.yaml` 統一定義 MCP server（stdio/sse/http），SDK 自動轉成各 CLI agent 的原生格式（Claude: `--mcp-config` JSON、Gemini: settings.json、Codex: 原生格式）。Grimo 不需處理轉換邏輯。 |
| **McpServerCatalog** | MCP Server Catalog | 所有 MCP server 定義的 immutable 集合。由 `McpCatalogBuilder` 從 `config.yaml` 建構，傳入 `AgentClient.Builder.mcpServerCatalog()` 後由 SDK 處理分發。 |
```

- [ ] **Step 2: 更新「Agent 技術元件對應」表格的 MCP 定義行**

將現有的 MCP 定義行更新為更精確的描述：

```markdown
| MCP 定義 | `McpCatalogBuilder` | `McpServerCatalog`（Portable MCP，config.yaml → AgentClient.Builder） |
```

- [ ] **Step 3: Commit**

```bash
git add docs/glossary.md
git commit -m "docs: add Portable MCP and McpServerCatalog to glossary"
```

---

### Task 4: E2E 驗證

- [ ] **Step 1: 無 MCP 設定時啟動正常**

確認 `~/.grimo/config.yaml` 沒有 `mcp` 區段（或不存在）時，啟動不會報錯：

Run: `./gradlew bootRun`
Expected: 正常啟動，log 顯示 `MCP catalog built: 0 servers []`

- [ ] **Step 2: 有 MCP 設定時 catalog 正確建構**

在 `~/.grimo/config.yaml` 加入測試用 MCP 設定：

```yaml
mcp:
  brave-search:
    type: stdio
    command: npx
    args: [-y, "@modelcontextprotocol/server-brave-search"]
    env:
      BRAVE_API_KEY: "test-key"
```

Run: `./gradlew bootRun`
Expected: log 顯示 `MCP catalog built: 1 servers [brave-search]`

- [ ] **Step 3: 確認 /mcp-list 指令仍正常**

在 TUI 中輸入 `/mcp-list`
Expected: 顯示 brave-search stdio npx 等資訊

- [ ] **Step 4: 確認 agent 呼叫不報錯**

在 TUI 中輸入一段對話文字（不需要實際用到 MCP tool）
Expected: agent 正常回覆，不因 MCP catalog 傳入而報錯

---

## 設計決策記錄

### Q1: 為什麼用 `AgentClient.builder()` 而不是在 `AgentClientRequestSpec` 層傳 MCP？

`AgentClientRequestSpec.mcpServers(String...)` 是 per-request 覆蓋，用於特定 goal 只需部分 MCP server 的場景。
`AgentClient.Builder.mcpServerCatalog()` + `.defaultMcpServers()` 設定一次，所有 goal 預設帶上全部 MCP server。
Grimo 的設計是「定義一次，所有 agent 呼叫都帶上」，所以用 Builder 層設定。

### Q2: 為什麼不把 AgentClient build 結果快取？

`agentRouter.route()` 每次可能回傳不同的 `AgentModel`（使用者可透過 `/agent-use` 切換），所以 AgentClient 必須 per-request 建立。但 `McpServerCatalog` 是 immutable 且不隨 agent 切換變動，所以快取 catalog 不快取 client。

### Q3: 為什麼不需要修改 AgentConfiguration？

MCP 在 `AgentClient` 層處理（Builder 接收 catalog），不在 `AgentModel` 層。`AgentModel` 只負責 CLI executable 偵測和基本選項（model、timeout），MCP 分發是 AgentClient 的責任。這是 SDK 的設計：AgentModel 是底層抽象，AgentClient 是上層調用者。
