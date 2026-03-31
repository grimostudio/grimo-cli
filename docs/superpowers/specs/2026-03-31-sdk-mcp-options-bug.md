# SDK Bug Report: ClaudeAgentOptions 被 MCP Resolution 覆蓋

> Date: 2026-03-31
> Status: Open
> Affected: spring-ai-agent-client 0.10.0-SNAPSHOT
> Repo: https://github.com/spring-ai-community/agent-client

## 問題摘要

有 MCP server 配置時，`ClaudeAgentOptions` 的 per-request 設定（disallowedTools、allowedTools、systemPrompt 等）全部丟失，fallback 到建構時的 defaultOptions。

## 根因分析

透過 depx skill 反編譯 JAR 原始碼發現：

### 1. `DefaultAgentClient.resolveMcpServers()` 包裝型別

```java
// DefaultAgentClient.java line 192
private AgentOptions resolveMcpServers(AgentOptions options) {
    // ... resolve MCP server names to definitions ...
    Map resolved = mcpServerCatalog.resolve(allNames);
    return DefaultAgentOptions.builder()
            .from(options)                    // ← 複製欄位
            .mcpServerDefinitions(resolved)   // ← 加上 MCP definitions
            .build();                         // ← 回傳 DefaultAgentOptions！
}
```

`from(options)` 只複製 `AgentOptions` interface 的通用欄位（model、timeout、workingDirectory、environmentVariables、extras、mcpServerDefinitions）。**不複製** `ClaudeAgentOptions` 特有欄位。

### 2. `ClaudeAgentModel.getEffectiveOptions()` instanceof 檢查失敗

```java
// ClaudeAgentModel.java line 401-408
private ClaudeAgentOptions getEffectiveOptions(AgentTaskRequest request) {
    AgentOptions agentOptions = request.options();
    if (agentOptions instanceof ClaudeAgentOptions) {  // ← DefaultAgentOptions → false！
        return (ClaudeAgentOptions) agentOptions;
    }
    return this.defaultOptions;  // ← fallback 到建構時的預設，丟失 per-request 設定
}
```

### 3. 完整呼叫鏈

```
AgentClient.run(goal, ClaudeAgentOptions{disallowedTools=[Edit,Write...]})
  → DefaultAgentClient.run(goalText, agentOptions)
    → goal = new Goal(goalText, null, agentOptions)  // ClaudeAgentOptions 還在
    → DefaultAgentClientRequestSpec.run()
      → mergeOptions(goal.getOptions(), defaultOptions)  // OK: 回傳 ClaudeAgentOptions
      → resolveMcpServers(effectiveOptions)
        → DefaultAgentOptions.builder().from(options).build()  // ❌ 型別轉換！
      → new AgentClientRequest(goal, workDir, effectiveOptions, ...)  // effectiveOptions = DefaultAgentOptions
      → chain.nextCall(request)
        → AgentModelCallAdvisor → agentModel.call(agentTaskRequest)
          → ClaudeAgentModel.call(request)
            → getEffectiveOptions(request)
              → request.options() instanceof ClaudeAgentOptions → false ❌
              → return this.defaultOptions  // 丟失所有 per-request 設定
```

## 丟失的欄位

以下 `ClaudeAgentOptions` 特有欄位在有 MCP 時全部丟失：

| 欄位 | 用途 |
|------|------|
| `disallowedTools` | 禁止 agent 使用的工具（Plan Mode 核心） |
| `allowedTools` | 允許 agent 使用的工具 |
| `maxThinkingTokens` | 思考 token 上限 |
| `systemPrompt` | 自訂系統提示詞 |
| `jsonSchema` | JSON 輸出 schema |
| `maxTurns` | 最大回合數 |
| `maxBudgetUsd` | 預算上限 |
| `fallbackModel` | 備援模型 |
| `appendSystemPrompt` | 追加系統提示詞 |
| `mcpServers` | Claude 原生 MCP 設定 |
| `yolo` | 免確認模式 |

## 重現條件

```java
// 有 MCP server 時才會觸發
var client = AgentClient.builder(claudeModel)
        .mcpServerCatalog(catalog)           // ← 設定 MCP catalog
        .defaultMcpServers("deepwiki")       // ← 指定 MCP server
        .build();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
        .model("claude-sonnet-4-6")
        .disallowedTools(List.of("Edit", "Write"))  // ← 這個會丟失
        .build();

client.run("hello", options);  // disallowedTools 不會傳到 Claude CLI
```

**無 MCP server 時不觸發**（`resolveMcpServers` 直接 return 原 options）。

## Workaround

把重要設定放在 `ClaudeAgentModel.builder().defaultOptions()` 而非 per-request options：

```java
// Workaround: 設定在 model 建構時，不受 MCP resolution 影響
ClaudeAgentModel.builder()
        .defaultOptions(ClaudeAgentOptions.builder()
                .disallowedTools(List.of("Edit", "Write"))
                .build())
        .build();
```

缺點：無法 per-request 覆寫 options（所有 request 共用同一組設定）。

## 建議修正

### 方案 A：`resolveMcpServers` 保留原始型別

```java
private AgentOptions resolveMcpServers(AgentOptions options) {
    // ... resolve ...
    if (options instanceof ClaudeAgentOptions claude) {
        return claude.toBuilder().mcpServerDefinitions(resolved).build();
    }
    // 同理 GeminiAgentOptions, CodexAgentOptions
    return DefaultAgentOptions.builder().from(options).mcpServerDefinitions(resolved).build();
}
```

### 方案 B：`ClaudeAgentModel` 改為讀通用欄位

不依賴 `instanceof`，改用 `AgentOptions` interface 的通用方法 + extras map 傳遞 Claude 特有欄位。

## 發現方式

使用 depx skill 反編譯 `spring-ai-agent-client-0.10.0-SNAPSHOT.jar` 和 `spring-ai-claude-agent-0.10.0-SNAPSHOT.jar`，追蹤完整呼叫鏈。
