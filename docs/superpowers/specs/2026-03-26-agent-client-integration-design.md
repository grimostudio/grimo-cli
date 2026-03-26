# Agent Client 整合設計規格：統一 CLI Agent 抽象層

- **Date**: 2026-03-26
- **Status**: Design Complete
- **Scope**: agent module 重構 + MCP 改造 + 對話流程串接

## 動機

Grimo 現有的 agent 架構（`AgentProvider` 介面 + 自製 `AgentDetector`）是為了同時支援 API 和 CLI 兩種 agent 類型而設計。但實務上：

1. **CLI agent 是主要使用場景** — Claude Code、Gemini CLI、Codex CLI 等 CLI agent 功能完整，能自主讀檔、改碼、執行命令
2. **Spring AI Community 已提供統一抽象** — [Agent Client](https://spring-ai-community.github.io/agent-client/) 專案提供 `AgentClient` + `AgentModel` 統一 API，支援所有主流 CLI agent
3. **自製偵測邏輯重複** — 各 SDK 內建 `*CliDiscovery` 已實作完整的 CLI 偵測（PATH、nvm、homebrew、env var），不需自己寫 `which` 偵測
4. **Advisor 模式提供強大擴展點** — 跟 Spring AI ChatClient 相同的 around-advice 模式，可用於 logging、validation、context engineering

## 設計決策

### 選擇 Library 模式（非 Starter）

| 考量 | Starter 模式 | Library 模式（選定）|
|------|-------------|-------------------|
| CLI 不存在時 | App 正常啟動，但 `@ConditionalOnClass` 只看 classpath，無法動態管理 | 手動建立 AgentModel，`isAvailable()` 偵測，不可用就不註冊 |
| Runtime 動態切換 | bean 固定，難切換 | `ConcurrentHashMap` 自由增刪 |
| 多 agent 共存 | 每個 starter 各自建 bean，`AgentClient.Builder` 只綁一個 | 任意數量 AgentModel 並存 |
| 符合 Grimo 設計哲學 | 不符合「Library over Starter」 | 完全符合 |

### 暫不實作 API Key 模式

移除 `AnthropicAgentProvider`、`spring-ai-anthropic/openai/ollama` 依賴。全部透過 CLI agent 操作。未來需要時可透過 `AgentModel` 介面擴展。

## 架構變更總覽

### 移除的元件

| 元件 | 原因 |
|------|------|
| `AgentProvider` 介面 | 被 `AgentModel`（Spring AI Community）取代 |
| `AgentType` enum (API/CLI) | 全部都是 CLI |
| `AgentRequest` / `AgentResult` | 被 `AgentTaskRequest` / `AgentResponse` 取代 |
| `AnthropicAgentProvider` | 暫不做 API key 模式 |
| `AgentDetector` | 改用各 `AgentModel.isAvailable()` 官方偵測 |
| `McpClientManager` | 不再自己連 MCP server |
| `McpClientRegistry` | 同上 |
| `io.modelcontextprotocol.sdk:mcp` 依賴 | 不再直接連 MCP |
| `spring-ai-anthropic/openai/ollama` 依賴 | 暫不做 API key 模式 |

### 保留但重構的元件

| 元件 | 變更 |
|------|------|
| `AgentProviderRegistry` → `AgentModelRegistry` | 型別從 `AgentProvider` 換成 `AgentModel` |
| `AgentRouter` | 依賴改為 `AgentModelRegistry`，移除 CLI/API 優先邏輯 |
| `AgentCommands` | `/agent-list` 改讀 `AgentModelRegistry` |

### 新增的元件

| 元件 | 說明 |
|------|------|
| `AgentModelFactory` | 啟動時建立所有 AgentModel，並行偵測，註冊可用的 |
| `GrimoSessionAdvisor` | AgentCallAdvisor，記錄 goal + result 到 session JSONL |

## 依賴變更

### Gradle

```groovy
// 移除
- implementation("org.springframework.ai:spring-ai-anthropic")
- implementation("org.springframework.ai:spring-ai-openai")
- implementation("org.springframework.ai:spring-ai-ollama")
- implementation("io.modelcontextprotocol.sdk:mcp:1.1.0")

// 新增（Library 模式，非 starter）
+ implementation("org.springaicommunity.agents:spring-ai-agent-model:0.10.0-SNAPSHOT")
+ implementation("org.springaicommunity.agents:spring-ai-agent-client:0.10.0-SNAPSHOT")
+ implementation("org.springaicommunity.agents:spring-ai-claude-agent:0.10.0-SNAPSHOT")
+ implementation("org.springaicommunity.agents:spring-ai-gemini:0.10.0-SNAPSHOT")
+ implementation("org.springaicommunity.agents:spring-ai-codex-agent:0.10.0-SNAPSHOT")
```

### Snapshot Repository

```groovy
repositories {
    maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
}
```

### 注意事項

- 全部 `0.10.0-SNAPSHOT`（incubating），版本可能變動，實作時需確認實際可用版本
- `spring-ai-agent-model` 依賴 `org.springframework.ai:spring-ai-model`，需確認跟現有 Spring AI 2.0.0-M3 版本相容性
- 各 SDK 底層用 **zt-exec**（`org.zeroturnaround:zt-exec`），會被 transitive 帶入

## 詳細設計

### 1. AgentModelFactory — 動態偵測與註冊

#### 職責

啟動時建立所有已知 CLI AgentModel，用官方 `isAvailable()` 偵測，可用的註冊到 registry。

#### 支援的 Agent

| ID | AgentModel 類別 | CLI 命令 | 建立方式 | 自動執行 flag |
|----|-----------------|---------|---------|--------------|
| `claude` | `ClaudeAgentModel` | `claude` | `new ClaudeAgentModel(ClaudeAgentClient.create(path), options)` | `yolo(true)` |
| `gemini` | `GeminiAgentModel` | `gemini` | `new GeminiAgentModel(GeminiClient.create(path), options)` | `yolo(true)` |
| `codex` | `CodexAgentModel` | `codex` | `new CodexAgentModel(CodexClient.create(path), options, null)` | `fullAuto(true)` |

#### 偵測流程

```
AgentModelFactory.detectAndRegister(workingDirectory)
  │
  ├─ [Virtual Thread 1] ClaudeAgentModel → isAvailable()? → ✓ register
  ├─ [Virtual Thread 2] GeminiAgentModel → isAvailable()? → ✗ skip
  ├─ [Virtual Thread 3] CodexAgentModel  → isAvailable()? → ✗ skip
  │
  └─ 回傳 List<DetectionResult> 供 banner/status bar 顯示
```

#### 官方 isAvailable() 偵測機制

各 SDK 內建完整偵測鏈（Grimo 不需自己實作）：

```
AgentModel.isAvailable()
  → SDK Client 內部偵測
    → *CliDiscovery 類別
      → 1. System property / 環境變數（用戶自訂路徑）
      → 2. PATH 查找（bare command / which）
      → 3. 常見安裝路徑掃描（nvm、homebrew、/usr/local/bin...）
      → 4. 驗證：執行 --version 確認可用
```

| SDK | 偵測類別 | 優先檢查 | 驗證方式 |
|-----|---------|---------|---------|
| Claude | `ClaudeCliDiscovery` | `claude.cli.path` system property | `claude --version` |
| Gemini | `GeminiCliDiscovery` | `gemini.cli.path` system property | `gemini --version` |
| Codex | `CodexCliDiscovery` | `CODEX_CLI_PATH` env var | `Files.isExecutable()` |

#### 並行偵測（Virtual Thread）

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var futures = agentSpecs.stream()
        .map(spec -> executor.submit(() -> {
            try {
                AgentModel model = spec.create(workDir);
                boolean available = model.isAvailable();
                return new DetectionResult(spec.id(), spec.type(), spec.detail(), available, model);
            } catch (Exception e) {
                log.warn("Agent {} creation failed: {}", spec.id(), e.getMessage());
                return new DetectionResult(spec.id(), spec.type(), spec.detail(), false, null);
            }
        }))
        .toList();
    // 收集結果，可用的註冊到 registry
}
```

設計說明：每個 AgentModel 的建立包在 try-catch 裡，某個 SDK 的 class 版本不合不影響其他 agent。

### 2. AgentModelRegistry（原 AgentProviderRegistry）

```java
public class AgentModelRegistry {
    private final ConcurrentHashMap<String, AgentModel> models = new ConcurrentHashMap<>();

    void register(String id, AgentModel model);
    void remove(String id);
    AgentModel get(String id);
    Map<String, AgentModel> listAll();
    Map<String, AgentModel> listAvailable();  // re-check isAvailable()
}
```

設計說明：`listAvailable()` 每次呼叫會 re-check `isAvailable()`，因為用戶可能中途安裝/移除 CLI。

### 3. AgentRouter 重構

```java
public class AgentRouter {
    private final AgentModelRegistry registry;
    private final GrimoConfig config;

    AgentModel route(@Nullable String agentId);
}
```

路由邏輯：
1. 明確指定 → `registry.get(agentId)`
2. config default → `registry.get(config.getDefaultAgent())`
3. 自動選擇 → `registry.listAvailable()` 取第一個

原本的「CLI 優先於 API」邏輯移除（全部都是 CLI）。

### 4. `/agent-model` 切換

各 CLI 的 model 參數語義不同（Claude: `claude-sonnet-4-0`, Gemini: `gemini-1.5-pro`, Codex: `gpt-5-codex`）。切 model 時需要重建 AgentModel 實例（options 是建構時傳入的）：

```
/agent-model claude-opus-4-0
  → AgentModelFactory.recreate("claude", newModelName)
    → registry.remove("claude")
    → 重建 ClaudeAgentModel（新 ClaudeAgentOptions）
    → registry.register("claude", newModel)
```

### 5. MCP — Portable MCP Server

#### 設計說明

移除 Grimo 自連 MCP 的機制，改用 AgentClient 的 Portable MCP 定義。Grimo 只負責定義 MCP server，由各 CLI agent 在自己的 subprocess 中使用。

#### config.yaml 格式

```yaml
mcp:
  brave-search:
    type: stdio
    command: npx
    args: ["-y", "@modelcontextprotocol/server-brave-search"]
    env:
      BRAVE_API_KEY: "${BRAVE_API_KEY}"
  weather:
    type: sse
    url: "http://localhost:8080/sse"
  api-server:
    type: http
    url: "http://localhost:3000/mcp"
    headers:
      X-Api-Key: "${API_KEY}"
```

對齊 AgentClient `McpServerDefinition` 結構：
- `type`: `stdio` | `sse` | `http`（預設 `stdio`）
- `${ENV_VAR}` 佔位符從 `System.getenv()` 解析

#### 轉換流程

```java
McpServerCatalog catalog = McpServerCatalog.builder()
    .add("brave-search", new StdioDefinition("npx", List.of("-y", "..."), Map.of("BRAVE_API_KEY", key)))
    .add("weather", new SseDefinition("http://localhost:8080/sse"))
    .build();

// 所有 AgentClient 共享同一份 catalog（immutable, thread-safe）
AgentClient client = AgentClient.builder(model)
    .mcpServerCatalog(catalog)
    .defaultMcpServers("brave-search", "weather")
    .build();
```

#### 各 CLI 自動轉換

| CLI | 轉換目標 | 機制 |
|-----|---------|------|
| Claude | temp JSON → `--mcp-config <path>` | 執行後自動清除 |
| Gemini | `.gemini/settings.json` | 執行後自動清除 |
| Codex | native format | SDK 處理 |

### 6. 對話流程 — GrimoTuiRunner 整合

#### processInput 改造

```
用戶輸入 "幫我寫一個 hello world"
  │
  ├─ text.startsWith("/") → 斜線指令（不變）
  │
  └─ 一般文字 → AI 對話
       │
       ├─ AgentRouter.route(null) → AgentModel
       │    └─ 沒有可用 agent → appendError("No agent available")
       │
       ├─ AgentClient.builder(model)
       │      .defaultAdvisor(sessionAdvisor)
       │      .defaultAdvisor(validationAdvisor)
       │      .build()
       │      .goal(text)
       │      .workingDirectory(workspaceRoot)
       │      .run()
       │
       ├─ AgentClientResponse
       │    ├─ isSuccessful() → contentView.appendAiReply(response.getResult())
       │    └─ !isSuccessful() → contentView.appendError(response.getResult())
       │
       └─ sessionWriter.writeAssistantMessage(result)
```

#### 非阻塞設計（Virtual Thread）

`AgentClient.run()` 是 blocking（可能 30 秒到數分鐘）。用 Virtual Thread 避免 TUI 凍結：

```
Input Thread                    Virtual Thread
───────────                    ──────────────
用戶按 Enter
  → spawn virtual thread ──→  AgentClient.goal(...).run()
  → contentView.appendStatus("thinking...")    │
  → 繼續處理鍵盤/滑鼠事件                       │
                                               ↓
                                 response 回來
                                 contentView.appendAiReply(result)
                                 eventLoop.setDirty()  ← 觸發重繪
```

設計說明：
- Input thread 不阻塞 — 滾動、Ctrl+C 正常運作
- Ctrl+C 取消：設 `volatile boolean cancelled` flag + `Thread.interrupt()`
- 正在等待回覆時，禁止送出新的對話（避免並發問題）

#### Streaming（後續迭代）

第一版使用 blocking `AgentClient.run()`。後續可針對 Claude（唯一支援 `StreamingAgentModel`）加入逐字顯示：

```java
if (model instanceof StreamingAgentModel streamingModel) {
    streamingModel.stream(request)
        .doOnNext(chunk -> {
            contentView.appendStreamChunk(chunk.getResult().getOutput());
            eventLoop.setDirty();
        })
        .subscribe();
}
```

### 7. Advisor 模式

#### 設計說明

AgentClient 的 Advisor chain 是 around-advice 模式（跟 Spring AI ChatClient 相同設計）。Grimo 用它實作橫切關注點，避免在業務邏輯中混雜 logging、validation 等。

#### 第一版 Advisors

| Advisor | Order | 用途 |
|---------|-------|------|
| `GoalValidationAdvisor` | `HIGHEST_PRECEDENCE` | 阻擋危險操作（rm -rf, DROP DATABASE 等） |
| `GrimoSessionAdvisor` | `HIGHEST_PRECEDENCE + 500` | 記錄 goal + result 到 session JSONL |

#### GrimoSessionAdvisor 設計

```java
public class GrimoSessionAdvisor implements AgentCallAdvisor {

    private final SessionWriter sessionWriter;

    @Override
    public AgentClientResponse adviseCall(AgentClientRequest request, AgentCallAdvisorChain chain) {
        // 記錄 goal
        sessionWriter.writeUserGoal(request.goal().getContent());

        // 執行 agent
        AgentClientResponse response = chain.nextCall(request);

        // 記錄 result
        sessionWriter.writeAgentResult(response.getResult(), response.isSuccessful());

        return response;
    }

    @Override
    public String getName() { return "GrimoSession"; }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 500; }
}
```

#### 後續可擴展的 Advisors

| Advisor | 用途 |
|---------|------|
| `MetricsAdvisor` | Micrometer 計時/計數 |
| `VendirContextAdvisor` | 執行前自動拉取外部文件（git repo, API spec） |
| `WorkspaceContextAdvisor` | 注入 workspace 資訊 |

## 三個 CLI Agent SDK API 參考

### 統一呼叫模式

```java
AgentClient agentClient = AgentClient.create(agentModel);
AgentClientResponse response = agentClient
    .goal("用戶的問題")
    .workingDirectory("/project/path")
    .run();
String result = response.getResult();
boolean success = response.isSuccessful();
```

### Claude Code SDK

- **來源**: https://spring-ai-community.github.io/agent-client/api/claude-code-sdk.html
- **建立**: `ClaudeAgentClient.create(path)` → `new ClaudeAgentModel(client, options)`
- **Options**: `.model("claude-sonnet-4-0")`, `.yolo(true)`, `.timeout(Duration)`, `.maxTokens(int)`, `.verbose(boolean)`
- **Models**: `claude-sonnet-4-0`, `claude-haiku-4-0`, `claude-opus-4-0`
- **Exceptions**: `ClaudeCodeExecutionException`, `ClaudeCodeNotFoundException`, `AgentTimeoutException`, `AgentAuthenticationException`
- **Streaming**: 唯一支援 `StreamingAgentModel` + `IterableAgentModel`

### Gemini CLI SDK

- **來源**: https://spring-ai-community.github.io/agent-client/api/gemini-cli-sdk.html
- **建立**: `GeminiClient.create(path)` → `new GeminiAgentModel(client, options)`
- **Options**: `.model("gemini-1.5-pro")`, `.yolo(true)`, `.timeout(Duration)`, `.temperature(double)`, `.candidateCount(int)`
- **Models**: `gemini-1.5-pro`, `gemini-1.5-flash`
- **Exceptions**: `GeminiExecutionException`, `GeminiNotFoundException`, `GeminiQuotaExceededException`, `AgentTimeoutException`
- **限制**: 只能在 workspace 目錄內建立/修改檔案

### Codex CLI SDK

- **來源**: https://spring-ai-community.github.io/agent-client/api/codex-cli-sdk.html
- **建立**: `CodexClient.create(path)` → `new CodexAgentModel(client, options, null)`
- **Options**: `.model("gpt-5-codex")`, `.fullAuto(true)`（非 yolo）, `.timeout(Duration)`, `.sandboxMode(SandboxMode)`, `.approvalPolicy(ApprovalPolicy)`
- **Models**: `gpt-5-codex`
- **Exceptions**: `CodexSDKException`
- **特有**: Session 恢復（`resume(sessionId, goal)`）、`SandboxMode` enum（READ_ONLY, WORKSPACE_WRITE, DANGER_FULL_ACCESS）

## 風險與待驗證事項

### API 名稱驗證（實作前必須確認）

官方文件描述的 `AgentClient` 和 `AgentClientResponse` 在 GitHub search 中未找到（可能在 `spring-ai-agent-client` 模組中，search 未覆蓋）。**實作第一步必須先確認 dependency resolve 成功**：

```bash
./gradlew dependencies --configuration compileClasspath | grep springaicommunity
```

如果 `AgentClient` 不存在，替代方案是直接使用 `AgentModel.call(AgentTaskRequest)` → `AgentResponse`，跳過 client facade 層。

### `0.10.0-SNAPSHOT` 版本風險

Incubating 版本可能有 breaking changes。降低風險的方式：
- 鎖定 snapshot timestamp（Gradle `changing = true` + resolution strategy）
- 保留舊 `AgentProvider` 介面作為 fallback（但不主動維護）

## 錯誤處理策略

### Exception 分類與用戶提示

| Exception | 用戶提示 | 處理 |
|-----------|---------|------|
| `*NotFoundException` | `"⚠ Claude CLI not found. Install: npm install -g @anthropic-ai/claude-code"` | 顯示安裝指引 |
| `AgentAuthenticationException` | `"⚠ Authentication failed. Run: claude auth login"` | 顯示 auth 指引 |
| `AgentTimeoutException` | `"⚠ Agent timed out (10m). Try a simpler goal or increase timeout."` | 不自動 retry |
| `*ExecutionException` | `"⚠ Agent error: {message}"` | 顯示原始錯誤 |
| `GeminiQuotaExceededException` | `"⚠ Gemini API quota exceeded. Wait and retry."` | 顯示等待提示 |

### Ctrl+C 取消機制

```
用戶按 Ctrl+C（agent 執行中）
  → cancelled = true
  → agentThread.interrupt()
  → 底層 zt-exec ProcessExecutor 檢測 interrupt
    → 呼叫 Process.destroyForcibly() 殺掉 CLI subprocess
  → contentView.appendStatus("cancelled")
  → eventLoop.setDirty()
```

注意：需要驗證 `Thread.interrupt()` 是否能傳播到 zt-exec 的 subprocess。如果不行，需要保存 `Process` 引用直接 `destroyForcibly()`。這是**實作時需驗證的細節**。

## Config 遷移

### config.yaml 變更

```yaml
# 保留（不變）
agents:
  default: "claude"           # default agent ID

# 移除
# agents.model 不再使用全域 model — 各 agent 有自己的 model 設定

# 新增：各 agent 的 model 設定
agent-options:
  claude:
    model: "claude-sonnet-4-0"
    yolo: true
    timeout: "PT10M"
  gemini:
    model: "gemini-1.5-pro"
    yolo: true
    timeout: "PT10M"
  codex:
    model: "gpt-5-codex"
    full-auto: true
    timeout: "PT10M"

# MCP 格式變更
# 舊格式：
#   mcp:
#     brave-search:
#       transport: stdio
#       command: "npx -y @modelcontextprotocol/server-brave-search"
#
# 新格式（對齊 McpServerDefinition）：
mcp:
  brave-search:
    type: stdio
    command: npx
    args: ["-y", "@modelcontextprotocol/server-brave-search"]
    env:
      BRAVE_API_KEY: "${BRAVE_API_KEY}"
```

### `/agent-model` 行為變更

`/agent-model <model-name>` 改為只影響當前 default agent 的 model：

```
/agent-model claude-opus-4-0
  → 讀取 agents.default → "claude"
  → 更新 agent-options.claude.model → "claude-opus-4-0"
  → 重建 ClaudeAgentModel
  → registry 更新
```

## 測試策略

### 新增測試

| 測試類別 | 測試內容 |
|---------|---------|
| `AgentModelFactoryTest` | Mock 各 AgentModel，驗證 isAvailable() 過濾邏輯、並行偵測、error isolation |
| `AgentModelRegistryTest` | register/remove/listAll/listAvailable（跟原 RegistryTest 類似） |
| `AgentRouterTest` | 路由邏輯遷移（移除 CLI/API 優先邏輯） |
| `GrimoSessionAdvisorTest` | 驗證 advisor chain 呼叫、session 寫入 |
| `McpCatalogBuilderTest` | config.yaml → McpServerCatalog 轉換 |

### Mock 策略

CLI agent 測試不需要真正的 CLI 工具。Mock `AgentModel`：

```java
AgentModel mockClaude = mock(AgentModel.class);
when(mockClaude.isAvailable()).thenReturn(true);
when(mockClaude.call(any())).thenReturn(new AgentResponse(...));
```

### 移除的測試

| 測試 | 原因 |
|------|------|
| `AnthropicAgentProviderTest` | 移除 AnthropicAgentProvider |
| `AgentDetectorTest` | 移除 AgentDetector |

## 參考來源

- [Spring AI Community Agent Client — 主站](https://springaicommunity.mintlify.app/projects/incubating/agent-client)
- [Agent Client Reference Docs](https://spring-ai-community.github.io/agent-client/index.html)
- [AgentClient API](https://spring-ai-community.github.io/agent-client/api/agentclient.html)
- [Agent Advisors API](https://spring-ai-community.github.io/agent-client/api/advisors.html)
- [Context Engineering](https://spring-ai-community.github.io/agent-client/api/context-engineering.html)
- [AgentClient vs ChatClient](https://spring-ai-community.github.io/agent-client/api/agentclient-vs-chatclient.html)
- [Claude Code SDK](https://spring-ai-community.github.io/agent-client/api/claude-code-sdk.html)
- [Gemini CLI SDK](https://spring-ai-community.github.io/agent-client/api/gemini-cli-sdk.html)
- [Codex CLI SDK](https://spring-ai-community.github.io/agent-client/api/codex-cli-sdk.html)
- [GitHub: spring-ai-community/agent-client](https://github.com/spring-ai-community/agent-client)
- [GitHub: spring-ai-community/claude-agent-sdk-java](https://github.com/spring-ai-community/claude-agent-sdk-java)
