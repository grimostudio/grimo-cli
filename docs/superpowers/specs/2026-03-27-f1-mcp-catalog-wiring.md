# F1: MCP Catalog 接通 AgentClient

> Date: 2026-03-27
> Status: Done
> Phase: 1（基礎設施）
> Parent: [PRD](2026-03-27-grimo-orchestration-platform-prd.md)

## 問題

McpCatalogBuilder 已實作（`io.github.samzhu.grimo.mcp.McpCatalogBuilder`），能從 config.yaml 的 `mcp` section 建出 `McpServerCatalog`。但 GrimoTuiRunner 建立 AgentClient 時沒有傳入這個 catalog，導致 agent 無法使用定義好的 MCP tools。

## 目標

Agent 透過 AgentClient 執行時，自動帶上使用者在 config.yaml 定義的所有 MCP server。

## 設計

### 現狀（斷路）

```java
// GrimoTuiRunner.java - 目前的寫法
AgentClient.create(model)
    .goal(text)
    .workingDirectory(path)
    .run();
// McpServerCatalog 沒有傳入
```

### 目標（接通）

> **⚠ API 待驗證**：以下 builder API 來自 Spring AI Community Agent Client 文件，
> 實作前需上網確認 0.10.0-SNAPSHOT 實際 API 簽名（CLAUDE.md：「需要的開發資訊 SDK 都上網再確認過一次」）。
> 如果 builder 不存在，需查看 `AgentClient.create()` 是否有 overload 接受 McpServerCatalog。

```java
// 方案 A：如果 builder pattern 存在
AgentClient.builder(model)
    .mcpServerCatalog(mcpCatalog)
    .defaultMcpServers(allServerNames)
    .defaultAdvisors(advisors)
    .build()
    .goal(text)
    .workingDirectory(path)
    .run();

// 方案 B：如果只有 create()，可能需要在 goal 層或 AgentModel 層傳入 MCP
AgentClient.create(model)
    .goal(text)
    .workingDirectory(path)
    .mcpServers(mcpCatalog)     // 待驗證是否存在
    .run();
```

### 變更範圍

1. **GrimoTuiRunner 注入 McpCatalogBuilder**
   - Constructor 新增 `McpCatalogBuilder` 參數
   - 在 Phase 2 初始化時呼叫 `mcpCatalogBuilder.build()` 取得 catalog
   - 快取 catalog 實例（config 不變就不重建）

2. **AgentClient 建立方式**
   - 實作前先用 `javap` 或 Javadoc 確認 AgentClient 的實際 API
   - 依實際 API 選擇方案 A 或方案 B

3. **config.yaml MCP 區段**（已支援，不需改動）
   ```yaml
   mcp:
     brave-search:
       type: stdio
       command: npx
       args: [-y, "@modelcontextprotocol/server-brave-search"]
       env:
         BRAVE_API_KEY: ${BRAVE_API_KEY}
     weather:
       type: sse
       url: http://localhost:8080/sse
   ```

### Portable MCP 自動轉換

Spring AI Agent Client 的 Portable MCP 會自動將 `McpServerDefinition` 轉換成各 CLI 的原生格式：
- Claude Code → `--mcp-config` JSON 格式
- Gemini CLI → gemini 原生 MCP 設定
- Codex CLI → codex 原生 MCP 設定

Grimo 不需要處理轉換邏輯。

## 影響範圍

| 檔案 | 變更 |
|------|------|
| `GrimoTuiRunner.java` | 注入 McpCatalogBuilder，AgentClient 改用 builder |
| `GrimoStartupRunner.java` | 確認 McpCatalogBuilder bean 已註冊 |

## 驗證方式

1. config.yaml 設定一個 stdio MCP server
2. 啟動 Grimo，對 agent 下一個需要 MCP tool 的 goal
3. 確認 agent 能呼叫 MCP tool 並回傳結果
