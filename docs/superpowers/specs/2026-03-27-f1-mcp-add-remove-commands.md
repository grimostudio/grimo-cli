# F1 附加：MCP 斜線指令（add/remove）

> Date: 2026-03-27
> Status: Draft
> Phase: 1（基礎設施）
> Parent: [F1 Spec](2026-03-27-f1-mcp-catalog-wiring.md)

## 問題

MCP server 設定目前只能手動編輯 `~/.grimo/config.yaml`，沒有 CLI/TUI 指令可以新增或移除。使用者需要知道 YAML 格式才能設定 MCP server。

## 目標

提供 `/mcp-add` 和 `/mcp-remove` 指令，讓使用者在 TUI 或 CLI 模式下直接管理 MCP server，新增後立即生效（不需重啟）。

## 設計

### 指令定義

| 指令 | 參數 | 說明 |
|------|------|------|
| `/mcp-list` | 無 | 列出所有 MCP server（已有） |
| `/mcp-add` | `--name`(必填), `--type`(必填: stdio/sse/http), `--url`(sse/http 必填), `--command`(stdio 必填), `--args`(stdio 可選, 逗號分隔) | 新增 MCP server 到 config.yaml |
| `/mcp-remove` | `--name`(必填) | 從 config.yaml 移除 MCP server |

### 使用範例

```bash
# SSE 類型
/mcp-add --name deepwiki --type sse --url https://mcp.deepwiki.com/sse

# HTTP 類型
/mcp-add --name semgrep --type http --url https://mcp.semgrep.ai/mcp

# stdio 類型
/mcp-add --name fs --type stdio --command npx --args "-y,@modelcontextprotocol/server-filesystem,/tmp"

# 移除
/mcp-remove --name deepwiki

# CLI 直接執行（同一個 @Command，Spring Shell 兩種入口共用）
./grimo mcp-add --name deepwiki --type sse --url https://mcp.deepwiki.com/sse
```

### 即時生效機制

```
/mcp-add 或 /mcp-remove
    │
    ├── 1. GrimoConfig 寫入/移除 config.yaml mcp 區段
    │
    └── 2. McpCatalogBuilder.rebuild()   ← 同模組直接呼叫
            │
            └── 重讀 config → 重建 McpServerCatalog（快取在 Builder 內）

GrimoTuiRunner 建立 AgentClient 時：
    mcpCatalogBuilder.getCatalog()      ← 每次取最新快取，不需事件通知
    mcpCatalogBuilder.getServerNames()
```

設計說明：
- **不用 Spring Cloud @RefreshScope**：MCP 設定在自訂 `config.yaml`（SnakeYAML 讀取），不在 Spring Environment 中。@RefreshScope 綁定 Spring Environment，需加 `spring-cloud-context` 依賴且要橋接自訂 config — 殺雞用牛刀。
- **不用 Spring Modulith Domain Event**：`McpCommands` 和 `McpCatalogBuilder` 在同一個 `mcp` 模組內，不需跨模組事件通知。
- **McpCatalogBuilder 自管快取**：最簡單且符合現有架構。`rebuild()` 重讀 config 重建 catalog，`getCatalog()` 回傳最新快取。`GrimoTuiRunner` 每次建立 AgentClient 時取最新快取，自動帶上新增/移除後的 MCP server。

### 執行緒安全

- **McpCatalogBuilder 快取欄位使用 `volatile`**：`catalog` 和 `serverNames` 由 command thread（Shell 指令）寫入、由 agent virtual thread 讀取，需要 `volatile` 保證可見性。
- **GrimoConfig load/save 加 `synchronized`**：防止 agent virtual thread 讀取 config 時與 `/mcp-add` 寫入衝突（部分寫入的 YAML 檔案）。
- **正在執行的 agent 不受影響**：`AgentClient.builder()` 在建立時已取得 catalog 快照（immutable `McpServerCatalog`），rebuild 替換的是快取參照，不影響已持有舊參照的 agent。新 catalog 在下一次 agent 呼叫時生效。

### config.yaml mcp 區段不存在時的處理

`GrimoConfig.setMcpServer()` 使用 `computeIfAbsent("mcp", k -> new LinkedHashMap<>())` 建立 mcp 區段，與既有的 `setNestedValue()` 模式一致。首次 `/mcp-add` 時 config.yaml 可能完全沒有 `mcp` key，此方法安全處理。

### 參數驗證

| 條件 | 錯誤訊息 |
|------|---------|
| `--name` 含非法字元（只允許 `[a-zA-Z0-9_-]+`） | `Invalid name 'X'. Use only letters, digits, hyphens, underscores.` |
| `--type` 不是 stdio/sse/http | `Invalid type 'X'. Supported: stdio, sse, http` |
| type=sse/http 但缺少 `--url` | `--url is required for type 'sse'` |
| type=sse/http 的 `--url` 格式不合法 | `Invalid URL: 'X'` |
| type=stdio 但缺少 `--command` | `--command is required for type 'stdio'` |
| `--name` 已存在（add） | `MCP server 'X' already exists. Remove it first.` |
| `--name` 不存在（remove） | `MCP server 'X' not found.` |

### config.yaml 寫入格式

`/mcp-add --name deepwiki --type sse --url https://mcp.deepwiki.com/sse` 寫入：

```yaml
mcp:
  deepwiki:
    type: sse
    url: https://mcp.deepwiki.com/sse
```

`/mcp-add --name fs --type stdio --command npx --args "-y,@modelcontextprotocol/server-filesystem,/tmp"` 寫入：

```yaml
mcp:
  fs:
    type: stdio
    command: npx
    args:
      - -y
      - "@modelcontextprotocol/server-filesystem"
      - /tmp
```

## 影響範圍

| 檔案 | 變更 |
|------|------|
| `McpCatalogBuilder.java` | 新增 `rebuild()`, `getCatalog()`, `getServerNames()` 快取管理；快取欄位使用 `volatile` |
| `McpCommands.java` | 新增 `mcp-add`, `mcp-remove` @Command 方法；constructor 新增注入 `McpCatalogBuilder` |
| `GrimoConfig.java` | 新增 `setMcpServer()`, `removeMcpServer()` 方法；load/save 加 `synchronized` |
| `GrimoTuiRunner.java` | 移除自管 `mcpCatalog`/`mcpServerNames` 快取，改用 `mcpCatalogBuilder.getCatalog()`/`getServerNames()` |

## 不做的事（YAGNI）

- `--env` / `--headers` 參數 — 需要的人手動編輯 config.yaml
- 刪除前確認互動 — 加回來只要一行指令
- TUI 互動 wizard（`/mcp` 進入列表、按 `a` 新增）— 未來需求

## 驗證方式

1. `/mcp-add --name deepwiki --type sse --url https://mcp.deepwiki.com/sse` → 顯示成功
2. `/mcp-list` → 顯示 deepwiki
3. 對 agent 下一個 goal → agent 呼叫時帶上 deepwiki MCP server
4. `/mcp-remove --name deepwiki` → 顯示成功
5. `/mcp-list` → deepwiki 消失
6. `cat ~/.grimo/config.yaml` → 確認 YAML 格式正確
