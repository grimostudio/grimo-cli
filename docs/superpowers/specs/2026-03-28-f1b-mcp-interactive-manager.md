# F1-b: MCP 互動式管理畫面

> Date: 2026-03-28
> Status: Done
> Phase: 1（基礎設施）
> Parent: [F1 Spec](2026-03-27-f1-mcp-catalog-wiring.md)
> Depends on: [F1-a](2026-03-28-f1a-mcp-command-restructure.md)（`/mcp`、`/mcp-add`、`/mcp-remove` 指令已實作）

## 問題

`/mcp` 目前只輸出純文字列表到 Content 區，使用者無法在列表中直接操作（刪除、新增）。需要回到 Input 區手動輸入 `/mcp-remove deepwiki`。

## 目標

`/mcp` 改為開啟 Overlay Modal 管理畫面，使用者可以 ↑↓ 導航 server 列表、按 `d` 直接刪除、按 `a` 快速進入新增流程，體驗對齊 Claude Code 的 `/mcp` 管理介面。

## 設計

### 互動流程

```
使用者輸入 /mcp
    │
    └── 開啟 MCP Manager Overlay（覆蓋 Content 區底部）
            │
            ├── ↑↓  ── 導航選中 server
            ├── d   ── 刪除選中 server → 列表即時更新 + catalog rebuild
            ├── a   ── 關閉 overlay → Input 區自動填入 "/mcp-add "
            └── Esc ── 關閉 overlay → 回到 Normal Mode
```

### 畫面佈局

```
┌──────────────────────────────────────────────────┐
│                                                  │
│   ...之前的對話內容...                             │  ← Content 區
│   ...                                            │
│   ┌──────────────────────────────────────────┐   │
│   │  Manage MCP servers                      │   │  ← MCP Manager Overlay
│   │  2 servers                               │   │    （覆蓋 Content 區底部）
│   │                                          │   │
│   │  ❯ deepwiki          sse   https://...   │   │  ← 選中項（品牌色）
│   │    filesystem        stdio  npx          │   │
│   │                                          │   │
│   │  ↑↓ navigate · [a]dd · [d]elete · Esc   │   │
│   └──────────────────────────────────────────┘   │
├──────────────────────────────────────────────────┤
│ ❯ █                                              │  ← Input 區（不變）
├──────────────────────────────────────────────────┤
│ claude · unknown │ ~/.grimo │ 1 agent · 2 mcp    │  ← Status 區（不變）
└──────────────────────────────────────────────────┘
```

### 按鍵操作

| 按鍵 | 動作 | 說明 |
|------|------|------|
| `↑` / `↓` | 移動選中項 | 循環到頭尾時停止（不 wrap） |
| `d` | 刪除選中 server | 呼叫 `config.removeMcpServer()` + `catalogBuilder.rebuild()`，overlay 內列表即時更新。若刪到空，顯示 "No MCP servers configured." |
| `a` | 進入新增流程 | 關閉 overlay，Input 區自動填入 `/mcp-add `（含尾端空格），使用者接著輸入 name 和 url |
| `Esc` / `Ctrl+C` | 關閉 overlay | 回到 Normal Mode。需在 `GrimoEventLoop` 新增 `OP_ESC`（`\033`）binding |
| `d`（列表空時） | 無動作 | 空列表時 `d` 為 no-op |

### 模式切換

TUI 現有兩個模式：Normal Mode 和 Slash Menu Mode。新增第三個 MCP Manager Mode：

```
handleKey(operation, lastBinding)
    │
    ├── mcpManagerVisible?  → handleMcpManagerKey(operation, lastBinding)
    ├── slashMenuVisible?   → handleSlashMenuKey(operation, lastBinding)
    └── else                → handleNormalKey(operation, lastBinding)
```

優先級：MCP Manager > Slash Menu > Normal（兩個 overlay 不會同時開）。

**互斥保證**：`openMcpManager()` 必須先 `screen.setSlashMenuVisible(false)` 再 `screen.setMcpManagerVisible(true)`，確保不會兩個 overlay 同時顯示。

### `/mcp` 指令觸發方式

現有的 `McpCommands.list()` 回傳純文字。改為：
- `McpCommands.list()` 不變（仍回傳文字，用於未來 CLI 非互動模式）
- `GrimoTuiRunner.processInput()` 攔截 `/mcp`（無子指令時），直接開啟 overlay 而不呼叫 `CommandExecutor`
- `/mcp-add` 和 `/mcp-remove` 仍走 `CommandExecutor`（不受影響）

```java
// GrimoTuiRunner.processInput()
if (text.equals("/mcp")) {
    openMcpManager();  // 開啟 overlay，不走 CommandExecutor
    return;
}
```

### 新增元件：GrimoMcpManagerView

```
職責：
- 持有 server 列表（從 GrimoConfig.getMcpServers() 讀取）
- 管理選中項 index
- render() 產出 List<AttributedString>（overlay 行）
- 提供 moveUp() / moveDown() / getSelectedName() / load()
- load(mcpServers) 接收 server map，更新列表並 clamp selectedIndex：`selectedIndex = min(selectedIndex, list.size() - 1)`

不負責：
- 按鍵處理（由 GrimoTuiRunner.handleMcpManagerKey 負責）
- config 寫入（由 GrimoConfig 負責）
- catalog 重建（由 McpCatalogBuilder 負責）
```

### render() 輸出格式

```
Manage MCP servers
2 servers

  ❯ deepwiki             sse        https://mcp.deepwiki.com/sse
    filesystem            stdio      npx

  ↑↓ navigate · [a]dd · [d]elete · Esc close
```

- 標題行：品牌色
- 選中行：品牌色 + `❯` 前綴
- 未選中行：預設色 + 空格前綴
- 快捷鍵提示行：灰色
- 空列表時：顯示 "No MCP servers configured. Press [a] to add."

### GrimoScreen 修改

```java
// 新增狀態（volatile：input thread 寫、render thread 讀）
// 註：既有 slashMenuVisible 也應改為 volatile（pre-existing issue，一併修正）
private volatile boolean mcpManagerVisible = false;

// render() 中，與斜線選單相同的 overlay 渲染邏輯
if (mcpManagerVisible) {
    List<AttributedString> managerLines = mcpManagerView.render(cols);
    // 覆蓋 content 區底部（與 slash menu overlay 相同模式）
    int overlayStart = contentLines.size() - managerLines.size();
    for (int i = 0; i < managerLines.size(); i++) {
        int targetRow = overlayStart + i;
        if (targetRow >= 0 && targetRow < contentLines.size()) {
            contentLines.set(targetRow, managerLines.get(i));
        }
    }
}
```

### 刪除後即時更新

```
按 d
    │
    ├── mcpManagerView.getSelectedName() → "deepwiki"
    ├── config.removeMcpServer("deepwiki")
    ├── catalogBuilder.rebuild()
    ├── mcpManagerView.load(config.getMcpServers())  ← 重載 server 列表
    ├── eventLoop.setDirty()       ← 觸發重繪
    │
    └── 若列表空了：
        mcpManagerView 顯示 "No MCP servers configured. Press [a] to add."
```

## 影響範圍

| 動作 | 檔案 | 職責 |
|------|------|------|
| Create | `GrimoMcpManagerView.java` | Overlay 渲染 + 選中項管理 + refresh |
| Modify | `GrimoScreen.java` | 新增 `volatile mcpManagerVisible` + 注入 `GrimoMcpManagerView`（第 6 個 constructor 參數）+ overlay 渲染；既有 `slashMenuVisible` 改為 `volatile` |
| Modify | `GrimoTuiRunner.java` | 攔截 `/mcp` 開 overlay + `handleMcpManagerKey()` + 按 `a` 自動填入 |
| Modify | `GrimoEventLoop.java` | 新增 `OP_ESC`（`\033`）binding |
| Test | `GrimoMcpManagerViewTest.java` | render 輸出、選中項移動、refresh、delete clamp index、empty list |

不需修改：
- `McpCommands.java` — `/mcp` 的文字輸出保留（用於非互動場景）
- `GrimoConfig.java` — `removeMcpServer()` 已實作
- `McpCatalogBuilder.java` — `rebuild()` 已實作

## 不做的事（YAGNI）

- Enter 查看 server 詳情（列表已顯示 type 和 url）
- Overlay 內文字輸入（按 `a` 回到 Input 區）
- 刪除前確認（加回來一行指令）
- Server 狀態顯示（connected / needs authentication）— 未來 F1-b+ 範疇
- `/mcp` 在非互動模式下的行為（保留純文字輸出）

## 驗證方式

1. `/mcp` → overlay 出現，顯示 server 列表
2. ↑↓ → 選中項移動，品牌色跟隨
3. `d` → 選中 server 刪除，列表即時更新
4. `a` → overlay 關閉，Input 區自動填入 `/mcp-add `
5. Esc → overlay 關閉，回到 Normal Mode
6. 無 server 時 `/mcp` → overlay 顯示 "No MCP servers configured. Press [a] to add."
7. 斜線選單仍正常（兩個 overlay 不衝突）
