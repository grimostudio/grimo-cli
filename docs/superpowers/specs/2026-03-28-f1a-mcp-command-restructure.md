# F1-a: MCP 指令結構對齊業界語法

> Date: 2026-03-28
> Status: Draft
> Phase: 1（基礎設施）
> Parent: [F1 Spec](2026-03-27-f1-mcp-catalog-wiring.md)

## 問題

現有的 `/mcp-list`、`/mcp-add`、`/mcp-remove` 三個平坦指令不符合業界慣例。Claude Code 使用 `claude mcp add`、Codex 使用 `codex mcp add`，都是統一入口 + 子指令模式。且 `/mcp-add --name deepwiki --type sse --url ...` 的 `--name`/`--type`/`--url` 全部用 flag 的語法比業界 verbose。

## 目標

1. 統一為 `/mcp` 單一入口，子指令用位置參數路由（`add`、`remove`，或無子指令顯示列表）
2. 語法對齊 Claude Code / Codex CLI（name 和 url 用位置參數，transport 用 flag）
3. 斜線選單只顯示一個 `/mcp — Manage MCP servers`

## 設計

### 指令語法

```bash
# 列表（無子指令）
/mcp

# 新增 SSE
/mcp add deepwiki --transport sse https://mcp.deepwiki.com/sse

# 新增 HTTP
/mcp add semgrep --transport http https://mcp.semgrep.ai/mcp

# 新增 stdio
/mcp add fs --transport stdio --exec "npx -y @modelcontextprotocol/server-filesystem /tmp"

# 移除
/mcp remove deepwiki

# 無參數的 add/remove → 顯示 usage
/mcp add
/mcp remove
```

### 業界對比

| | Claude Code | Codex CLI | Grimo（本設計） |
|---|---|---|---|
| 列表 | `claude mcp list` | TUI `/mcp` | `/mcp` |
| 新增 SSE | `claude mcp add name --transport sse <url>` | N/A | `/mcp add name --transport sse <url>` |
| 新增 stdio | `claude mcp add name --transport stdio -- cmd args` | `codex mcp add name -- cmd args` | `/mcp add name --transport stdio --exec "cmd args"` |
| 移除 | `claude mcp remove name` | N/A | `/mcp remove name` |

### transport 自動推斷

省略 `--transport` 時，依據其他參數自動推斷：
- 有 url 位置參數（`@Argument(index=2)`）→ 推斷為 `sse`
- 有 `--exec` → 推斷為 `stdio`
- 都沒有 → 顯示 Usage 提示

```bash
# 以下兩行等價：
/mcp add deepwiki --transport sse https://mcp.deepwiki.com/sse
/mcp add deepwiki https://mcp.deepwiki.com/sse   # 自動推斷 sse
```

### stdio `--exec` 設計說明

業界用 `-- npx -y @pkg /tmp`（雙 dash 分隔符），但 Spring Shell 的 `@Option`/`@Argument` 不支援 `--` 後的 variadic args。改用 `--exec "npx -y @pkg /tmp"` 替代，效果相同：

- `--exec` 值以空格切割，第一個 token 為 `command`，其餘為 `args`
- 寫入 config.yaml 時自動拆分
- **限制**：路徑含空格時無法正確切割（如 `/Users/My Folder/`），此為業界 CLI 共通限制

```yaml
# /mcp add fs --transport stdio --exec "npx -y @modelcontextprotocol/server-filesystem /tmp"
mcp:
  fs:
    type: stdio
    command: npx
    args:
      - -y
      - "@modelcontextprotocol/server-filesystem"
      - /tmp
```

### Spring Shell 實作

單一 `@Command(name = "mcp")` 方法，用 `@Argument(index = 0)` 做子指令路由：

```java
@Command(name = "mcp", description = "Manage MCP servers")
public String mcp(
    @Argument(index = 0, description = "Subcommand: add, remove",
              defaultValue = "") String action,
    @Argument(index = 1, description = "Server name",
              defaultValue = "") String name,
    @Argument(index = 2, description = "URL (for sse/http)",
              defaultValue = "") String url,
    @Option(longName = "transport", shortName = 't',
            description = "Transport: stdio, sse, http") String transport,
    @Option(longName = "exec", shortName = 'e',
            description = "Stdio command (space-separated)") String exec)
```

路由邏輯：
```
action == ""       → list()
action == "add"    → add(name, transport, url, exec)
action == "remove" → remove(name)
other              → "Unknown subcommand: ..."
```

### 參數驗證

| 條件 | 回應 |
|------|------|
| `/mcp add`（缺 name） | Usage 提示 + 範例 |
| `/mcp remove`（缺 name） | Usage 提示 |
| name 含非法字元 | `Invalid name 'X'. Use only letters, digits, hyphens, underscores.` |
| add 缺 `--transport` | 自動推斷：有 url → `sse`，有 `--exec` → `stdio`，都沒有 → Usage 提示 |
| transport 不是 stdio/sse/http | `Invalid transport 'X'. Supported: stdio, sse, http` |
| sse/http 缺 url | `URL is required for transport 'sse'` |
| sse/http 的 url 格式不合法 | `Invalid URL: 'X'` |
| stdio 缺 --exec | `--exec is required for transport 'stdio'` |
| name 已存在（add） | `MCP server 'X' already exists. Remove it first.` |
| name 不存在（remove） | `MCP server 'X' not found.` |
| 未知子指令 | `Unknown subcommand: 'X'. Usage: /mcp [add\|remove]` |

### 列表輸出格式

```
Manage MCP servers
2 servers

  NAME                 TYPE       COMMAND/URL
  deepwiki             sse        https://mcp.deepwiki.com/sse
  filesystem           stdio      npx
```

### 斜線選單

斜線選單只顯示一個項目：
```
  /mcp                 Manage MCP servers
```

取代原本的三個：~~`/mcp-list`~~、~~`/mcp-add`~~、~~`/mcp-remove`~~

## 影響範圍

| 檔案 | 變更 |
|------|------|
| `McpCommands.java` | 三個 `@Command` 合併為一個 `@Command(name = "mcp")`；constructor 不變（已注入 GrimoConfig + McpCatalogBuilder） |
| `McpCommandsTest.java` | 重寫測試，呼叫 `mcp(action, name, url, transport, exec)` |

不需修改：
- `GrimoConfig.java` — `setMcpServer()` / `removeMcpServer()` 已完成
- `McpCatalogBuilder.java` — `rebuild()` / `getCatalog()` / `getServerNames()` 已完成
- `GrimoTuiRunner.java` — 已使用 `mcpCatalogBuilder.getCatalog()` 取最新快取

## 不做的事（F1-b 範疇）

- `/mcp` 進入互動式管理畫面（↑↓ 導航、Enter 選取、Esc 返回）
- Server 狀態顯示（connected / needs authentication）
- `--env KEY=VALUE` 支援
- `--header` 支援

## 驗證方式

1. `/mcp` → 顯示 "No MCP servers configured." 或 server 列表
2. `/mcp add deepwiki --transport sse https://mcp.deepwiki.com/sse` → `Added: deepwiki (sse)`
3. `/mcp` → 顯示 deepwiki
4. `/mcp add` → 顯示 Usage 提示
5. `/mcp remove deepwiki` → `Removed: deepwiki`
6. `/mcp remove` → 顯示 Usage 提示
7. 斜線選單只顯示 `/mcp — Manage MCP servers`
