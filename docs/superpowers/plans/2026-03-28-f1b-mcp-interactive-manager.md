# F1-b: MCP 互動式管理畫面 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/mcp` 改為開啟 Overlay Modal，使用者可 ↑↓ 導航 server 列表、按 `d` 刪除、按 `a` 進入新增流程。

**Architecture:** 新增 `GrimoMcpManagerView`（純資料模型 + render()）跟隨 `GrimoSlashMenuView` 的 pattern。`GrimoScreen` 新增第二個 overlay 渲染邏輯。`GrimoTuiRunner` 新增 `handleMcpManagerKey()` 模式。`GrimoEventLoop` 新增 `OP_ESC` binding。

**Tech Stack:** JLine Display, AttributedString/AttributedStyle, Spring Shell `@Command`

**Spec:** [docs/superpowers/specs/2026-03-28-f1b-mcp-interactive-manager.md](../specs/2026-03-28-f1b-mcp-interactive-manager.md)

---

## 現有 API 確認

> 以下均從原始碼確認（非猜測）。

### GrimoSlashMenuView（參考 pattern）
```java
// Constructor: 接收 List<MenuItem>
new GrimoSlashMenuView(List<MenuItem> items)

// API
filter(String prefix)          // 過濾
filterAll()                    // 重設
moveUp() / moveDown()          // 導航
getSelected() → String|null    // 取得選中名稱
hasItems() → boolean           // 是否有項目
render(int cols) → List<AttributedString>  // 渲染
```

### GrimoScreen
```java
// Constructor 5 參數
new GrimoScreen(Terminal, GrimoContentView, GrimoInputView, GrimoStatusView, GrimoSlashMenuView)

// Overlay 渲染 pattern（render() 內）
if (slashMenuVisible) {
    List<AttributedString> menuLines = slashMenuView.render(cols);
    int overlayStart = contentLines.size() - menuLines.size();
    for (int i = 0; i < menuHeight; i++) {
        contentLines.set(overlayStart + i, menuLines.get(i));
    }
}

// 狀態控制
setSlashMenuVisible(boolean) / isSlashMenuVisible()
requestFullRedraw()
```

### GrimoEventLoop
```java
// 現有 OP 常量（無 OP_ESC）
static final String OP_ENTER, OP_UP, OP_DOWN, OP_LEFT, OP_RIGHT,
    OP_BACKSPACE, OP_DELETE, OP_TAB, OP_CTRL_C, OP_CTRL_U, OP_CTRL_D, OP_CHAR;

// KeyMap binding pattern
keyMap.bind(OP_CTRL_C, "\003");   // Ctrl+C = 0x03
// ESC = 0x1B = "\033" ← 需要新增
```

### GrimoInputView
```java
setText(String text)            // 設定 input buffer（用於按 a 自動填入 /mcp-add）
clear()                        // 清空
insertSlashCommand(String name) // 插入斜線指令
```

### GrimoTuiRunner
```java
// Phase 4 建構 TUI 元件（line 148-165）
screen = new GrimoScreen(terminal, contentView, inputView, statusView, slashMenuView);

// processInput() 處理斜線指令（line 372-395）
if (text.startsWith("/")) { ... commandExecutor.execute(ctx); ... }

// handleKey 路由（line 194-199）
if (screen.isSlashMenuVisible()) { handleSlashMenuKey(...) }
else { handleNormalKey(...) }
```

---

## File Map

| 動作 | 檔案 | 職責 |
|------|------|------|
| Create | `src/main/java/.../GrimoMcpManagerView.java` | Server 列表資料模型 + render() overlay |
| Create | `src/test/java/.../GrimoMcpManagerViewTest.java` | render、moveUp/Down、refresh、clamp index |
| Modify | `src/main/java/.../GrimoEventLoop.java` | 新增 `OP_ESC` binding |
| Modify | `src/main/java/.../GrimoScreen.java` | 注入 View、volatile 修正、overlay 渲染、mcpManagerVisible 狀態 |
| Modify | `src/main/java/.../GrimoTuiRunner.java` | 攔截 `/mcp`、建構 View、handleMcpManagerKey()、按 a 自動填入 |

---

### Task 1: GrimoMcpManagerView — 純資料模型 + render()

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/GrimoMcpManagerView.java`
- Create: `src/test/java/io/github/samzhu/grimo/GrimoMcpManagerViewTest.java`

- [ ] **Step 1: 寫 render 基本測試**

```java
package io.github.samzhu.grimo;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GrimoMcpManagerViewTest {

    private Map<String, Map<String, Object>> twoServers() {
        var servers = new LinkedHashMap<String, Map<String, Object>>();
        servers.put("deepwiki", Map.of("type", "sse", "url", "https://mcp.deepwiki.com/sse"));
        servers.put("filesystem", Map.of("type", "stdio", "command", "npx"));
        return servers;
    }

    @Test
    void renderShouldShowTitleAndServers() {
        var view = new GrimoMcpManagerView();
        view.load(twoServers());

        List<AttributedString> lines = view.render(80);

        String joined = lines.stream().map(AttributedString::toString).reduce("", (a, b) -> a + "\n" + b);
        assertThat(joined).contains("Manage MCP servers");
        assertThat(joined).contains("2 servers");
        assertThat(joined).contains("deepwiki");
        assertThat(joined).contains("filesystem");
        assertThat(joined).contains("[a]dd");
        assertThat(joined).contains("[d]elete");
    }

    @Test
    void renderEmptyListShouldShowMessage() {
        var view = new GrimoMcpManagerView();
        view.load(Map.of());

        List<AttributedString> lines = view.render(80);

        String joined = lines.stream().map(AttributedString::toString).reduce("", (a, b) -> a + "\n" + b);
        assertThat(joined).contains("No MCP servers configured");
        assertThat(joined).contains("[a]");
    }

    @Test
    void selectedNameShouldReturnFirstByDefault() {
        var view = new GrimoMcpManagerView();
        view.load(twoServers());

        assertThat(view.getSelectedName()).isEqualTo("deepwiki");
    }

    @Test
    void moveDownShouldChangeSelection() {
        var view = new GrimoMcpManagerView();
        view.load(twoServers());

        view.moveDown();

        assertThat(view.getSelectedName()).isEqualTo("filesystem");
    }

    @Test
    void moveUpAtTopShouldStay() {
        var view = new GrimoMcpManagerView();
        view.load(twoServers());

        view.moveUp(); // already at 0

        assertThat(view.getSelectedName()).isEqualTo("deepwiki");
    }

    @Test
    void moveDownAtBottomShouldStay() {
        var view = new GrimoMcpManagerView();
        view.load(twoServers());

        view.moveDown(); // index 1
        view.moveDown(); // should stay at 1 (not wrap)

        assertThat(view.getSelectedName()).isEqualTo("filesystem");
    }

    @Test
    void refreshShouldClampIndex() {
        var view = new GrimoMcpManagerView();
        view.load(twoServers());
        view.moveDown(); // index 1

        // Reload with only 1 server — index should clamp to 0
        view.load(Map.of("deepwiki", Map.of("type", "sse", "url", "https://mcp.deepwiki.com/sse")));

        assertThat(view.getSelectedName()).isEqualTo("deepwiki");
    }

    @Test
    void getSelectedNameOnEmptyListShouldReturnNull() {
        var view = new GrimoMcpManagerView();
        view.load(Map.of());

        assertThat(view.getSelectedName()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.samzhu.grimo.GrimoMcpManagerViewTest"`
Expected: FAIL — class not found

- [ ] **Step 3: 實作 GrimoMcpManagerView**

```java
package io.github.samzhu.grimo;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 管理畫面的 overlay 資料模型 + render()。
 *
 * 設計說明：
 * - 跟隨 GrimoSlashMenuView 的 pattern（純資料 + render() 產出 List<AttributedString>）
 * - 不負責按鍵處理（由 GrimoTuiRunner.handleMcpManagerKey 負責）
 * - 不負責 config 寫入或 catalog 重建（由上層呼叫者負責）
 * - load() 重新載入 server 列表時自動 clamp selectedIndex
 */
public class GrimoMcpManagerView {

    /** 品牌標誌色 steel blue（ANSI 256 色碼 67），與 GrimoSlashMenuView 一致 */
    private static final int BRAND_COLOR = 67;
    private static final AttributedStyle BRAND_STYLE = AttributedStyle.DEFAULT.foreground(BRAND_COLOR);
    private static final AttributedStyle DIM_STYLE = AttributedStyle.DEFAULT.foreground(245);

    /** 有序的 server 名稱列表（LinkedHashMap 保持 config.yaml 定義順序） */
    private List<String> serverNames = List.of();
    /** server 定義（name → {type, url/command, ...}） */
    private Map<String, Map<String, Object>> servers = Map.of();
    /** 目前選中的索引 */
    private int selectedIndex = 0;

    /**
     * 載入 server 列表。呼叫時自動 clamp selectedIndex。
     * @param mcpServers 從 GrimoConfig.getMcpServers() 取得的 server 定義
     */
    public void load(Map<String, Map<String, Object>> mcpServers) {
        this.servers = mcpServers;
        this.serverNames = new ArrayList<>(mcpServers.keySet());
        // clamp：刪除最後一項後 index 不超出邊界
        if (!serverNames.isEmpty()) {
            selectedIndex = Math.min(selectedIndex, serverNames.size() - 1);
        } else {
            selectedIndex = 0;
        }
    }

    public void moveUp() {
        if (selectedIndex > 0) {
            selectedIndex--;
        }
    }

    public void moveDown() {
        if (selectedIndex < serverNames.size() - 1) {
            selectedIndex++;
        }
    }

    /**
     * 取得選中 server 的名稱。
     * @return server 名稱，或 null 若列表為空
     */
    public String getSelectedName() {
        if (serverNames.isEmpty()) return null;
        return serverNames.get(selectedIndex);
    }

    public boolean isEmpty() {
        return serverNames.isEmpty();
    }

    /**
     * 渲染 overlay 行。
     * @param cols 終端機寬度
     * @return overlay 的所有行
     */
    public List<AttributedString> render(int cols) {
        var lines = new ArrayList<AttributedString>();

        // 標題
        lines.add(new AttributedString("  Manage MCP servers", BRAND_STYLE));

        if (serverNames.isEmpty()) {
            lines.add(AttributedString.EMPTY);
            lines.add(new AttributedString("  No MCP servers configured. Press [a] to add.", DIM_STYLE));
            lines.add(AttributedString.EMPTY);
            lines.add(new AttributedString("  [a]dd · Esc close", DIM_STYLE));
            return lines;
        }

        // 數量
        String countText = "  " + serverNames.size()
                + (serverNames.size() == 1 ? " server" : " servers");
        lines.add(new AttributedString(countText, DIM_STYLE));
        lines.add(AttributedString.EMPTY);

        // Server 列表
        for (int i = 0; i < serverNames.size(); i++) {
            String name = serverNames.get(i);
            Map<String, Object> cfg = servers.get(name);
            String type = (String) cfg.getOrDefault("type", "stdio");
            String detail = type.equals("stdio")
                    ? (String) cfg.getOrDefault("command", "")
                    : (String) cfg.getOrDefault("url", "");

            String prefix = (i == selectedIndex) ? "  ❯ " : "    ";
            String line = prefix + String.format("%-20s %-10s %s", name, type, detail);
            if (line.length() > cols) line = line.substring(0, cols);

            AttributedStyle style = (i == selectedIndex) ? BRAND_STYLE : AttributedStyle.DEFAULT;
            lines.add(new AttributedString(line, style));
        }

        // 快捷鍵提示
        lines.add(AttributedString.EMPTY);
        lines.add(new AttributedString("  ↑↓ navigate · [a]dd · [d]elete · Esc close", DIM_STYLE));

        return lines;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "io.github.samzhu.grimo.GrimoMcpManagerViewTest"`
Expected: ALL PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoMcpManagerView.java \
        src/test/java/io/github/samzhu/grimo/GrimoMcpManagerViewTest.java
git commit -m "feat(f1b): add GrimoMcpManagerView with render and navigation"
```

---

### Task 2: GrimoEventLoop — 新增 OP_ESC

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoEventLoop.java`

- [ ] **Step 1: 新增 OP_ESC 常量和 binding**

在 OP 常量區（line 44 後）新增：

```java
static final String OP_ESC = "ESC";
```

在 `buildKeyMap()` 方法中，`OP_CTRL_D` binding 之後新增：

```java
keyMap.bind(OP_ESC, "\033");     // ESC = 0x1B
```

- [ ] **Step 2: 確認編譯通過**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

> ⚠️ 注意：ESC 是 ANSI escape 序列的開頭。JLine 的 KeyMap 會等待後續字元判斷是 arrow key 還是單獨 ESC。如果 KeyMap 已經有 `\033[A`（UP）等 binding，單獨的 `\033` 會在 timeout 後被識別為 ESC。這是 JLine 的標準行為。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoEventLoop.java
git commit -m "feat(f1b): add OP_ESC binding to event loop"
```

---

### Task 3: GrimoScreen — 新增 MCP Manager overlay 渲染

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoScreen.java`

- [ ] **Step 1: 新增 mcpManagerView 欄位和 constructor 參數**

修改 constructor 為 6 參數：

```java
private final GrimoMcpManagerView mcpManagerView;

// 既有 slashMenuVisible 改為 volatile（pre-existing thread safety fix）
private volatile boolean slashMenuVisible = false;
// 新增
private volatile boolean mcpManagerVisible = false;

public GrimoScreen(Terminal terminal, GrimoContentView contentView,
                    GrimoInputView inputView, GrimoStatusView statusView,
                    GrimoSlashMenuView slashMenuView,
                    GrimoMcpManagerView mcpManagerView) {
    this.display = new Display(terminal, true);
    this.contentView = contentView;
    this.inputView = inputView;
    this.statusView = statusView;
    this.slashMenuView = slashMenuView;
    this.mcpManagerView = mcpManagerView;
}
```

- [ ] **Step 2: 新增 mcpManagerVisible 控制方法**

```java
public void setMcpManagerVisible(boolean visible) {
    this.mcpManagerVisible = visible;
}

public boolean isMcpManagerVisible() {
    return mcpManagerVisible;
}
```

- [ ] **Step 3: 在 render() 新增 MCP Manager overlay 渲染**

在 slash menu overlay 之後（`allLines.addAll(contentLines)` 之前），新增：

```java
// 3. MCP Manager overlay（覆蓋 content 底部，與 slash menu 互斥）
if (mcpManagerVisible) {
    List<AttributedString> managerLines = mcpManagerView.render(cols);
    int managerHeight = managerLines.size();
    int overlayStart = contentLines.size() - managerHeight;
    for (int i = 0; i < managerHeight; i++) {
        int targetRow = overlayStart + i;
        if (targetRow >= 0 && targetRow < contentLines.size()) {
            contentLines.set(targetRow, managerLines.get(i));
        }
    }
}
```

- [ ] **Step 4: 不 commit**（等 Task 4 一起修改 GrimoTuiRunner 後才能編譯通過，避免 broken commit）

---

### Task 4: GrimoTuiRunner — MCP Manager 模式完整接通

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`

- [ ] **Step 1: 新增 mcpManagerView 欄位和 import**

在 import 區新增：

```java
import io.github.samzhu.grimo.shared.config.GrimoConfig;
```

（`GrimoConfig` 已在 constructor 可用：`grimoConfig` 欄位）

在 field 區新增：

```java
private GrimoMcpManagerView mcpManagerView;
```

- [ ] **Step 2: Phase 4 建構 mcpManagerView 並傳入 GrimoScreen**

在 Phase 4 的 `slashMenuView = new GrimoSlashMenuView(menuItems)` 之後，`screen = new GrimoScreen(...)` 之前，新增：

```java
mcpManagerView = new GrimoMcpManagerView();
```

修改 `screen` 建構為 6 參數：

```java
screen = new GrimoScreen(terminal, contentView, inputView, statusView, slashMenuView, mcpManagerView);
```

- [ ] **Step 3: 確認編譯通過**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 修改 handleKey 路由——MCP Manager > Slash Menu > Normal**

修改 `TuiKeyHandler.handleKey()`：

```java
@Override
public void handleKey(String operation, String lastBinding) {
    if (screen.isMcpManagerVisible()) {
        handleMcpManagerKey(operation, lastBinding);
    } else if (screen.isSlashMenuVisible()) {
        handleSlashMenuKey(operation, lastBinding);
    } else {
        handleNormalKey(operation, lastBinding);
    }
}
```

- [ ] **Step 5: 實作 handleMcpManagerKey()**

在 `handleSlashMenuKey()` 之後新增：

```java
/**
 * MCP Manager overlay 模式的鍵盤處理。
 *
 * 設計說明：
 * - ↑↓ 導航 server 列表（不 wrap）
 * - d 直接刪除選中 server，即時重建 catalog
 * - a 關閉 overlay，Input 區自動填入 "/mcp-add "
 * - Esc/Ctrl+C 關閉 overlay
 */
private void handleMcpManagerKey(String operation, String lastBinding) {
    switch (operation) {
        case GrimoEventLoop.OP_UP -> mcpManagerView.moveUp();
        case GrimoEventLoop.OP_DOWN -> mcpManagerView.moveDown();
        case GrimoEventLoop.OP_ESC, GrimoEventLoop.OP_CTRL_C -> {
            screen.setMcpManagerVisible(false);
            screen.requestFullRedraw();
        }
        case GrimoEventLoop.OP_CHAR -> {
            if (lastBinding != null && lastBinding.length() == 1) {
                char c = lastBinding.charAt(0);
                if (c == 'd' || c == 'D') {
                    // 刪除選中 server
                    String name = mcpManagerView.getSelectedName();
                    if (name != null) {
                        grimoConfig.removeMcpServer(name);
                        mcpCatalogBuilder.rebuild();
                        mcpManagerView.load(grimoConfig.getMcpServers());
                        // 若列表空了，view 自動顯示 empty message
                    }
                } else if (c == 'a' || c == 'A') {
                    // 關閉 overlay，自動填入 /mcp-add
                    screen.setMcpManagerVisible(false);
                    screen.requestFullRedraw();
                    inputView.setText("/mcp-add ");
                }
            }
        }
    }
    // 每次按鍵都觸發重繪（導航、刪除、關閉都需要即時更新畫面）
    eventLoop.setDirty();
}
```

- [ ] **Step 6: 攔截 `/mcp` 開啟 overlay**

在 `processInput()` 方法的 `if (text.equals("/exit"))` 之後，`if (text.startsWith("/"))` 之前，新增：

```java
// /mcp 無子指令時開啟互動 overlay（不走 CommandExecutor）
if (text.equals("/mcp")) {
    openMcpManager();
    return;
}
```

- [ ] **Step 7: 實作 openMcpManager()**

在 `tryReopenSlashMenu()` 之後新增：

```java
/**
 * 開啟 MCP Manager overlay。
 * 互斥保證：先關閉 slash menu 再開啟 MCP Manager。
 */
private void openMcpManager() {
    screen.setSlashMenuVisible(false);
    mcpManagerView.load(grimoConfig.getMcpServers());
    screen.setMcpManagerVisible(true);
}
```

- [ ] **Step 8: 確認編譯通過**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoScreen.java \
        src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "feat(f1b): add MCP Manager overlay mode to Screen and TuiRunner"
```

---

### Task 5: E2E 驗證

- [ ] **Step 1: 啟動 app，確認基本流程**

Run: `./gradlew bootRun`

先新增一個 MCP server：
```
/mcp-add deepwiki https://mcp.deepwiki.com/sse
```

- [ ] **Step 2: 測試 /mcp overlay**

輸入 `/mcp` → overlay 應出現，顯示：
```
  Manage MCP servers
  1 server

  ❯ deepwiki             sse        https://mcp.deepwiki.com/sse

  ↑↓ navigate · [a]dd · [d]elete · Esc close
```

- [ ] **Step 3: 測試 Esc 關閉**

按 `Esc` → overlay 關閉，回到 Normal Mode

- [ ] **Step 4: 測試 a 自動填入**

再次 `/mcp` → overlay 開啟 → 按 `a`
Expected: overlay 關閉，Input 區顯示 `/mcp-add `（含尾端空格）

- [ ] **Step 5: 測試 d 刪除**

先新增第二個 server：
```
/mcp-add fs --exec "npx -y @modelcontextprotocol/server-filesystem /tmp"
```

輸入 `/mcp` → overlay 顯示 2 servers
按 `↓` → 選中 filesystem
按 `d` → filesystem 被刪除，overlay 即時更新為 1 server

- [ ] **Step 6: 測試空列表**

在 overlay 中按 `d` 刪除最後一個 server
Expected: overlay 顯示 "No MCP servers configured. Press [a] to add."
按 `a` → overlay 關閉，Input 區顯示 `/mcp-add `

- [ ] **Step 7: 確認斜線選單不受影響**

輸入 `/` → 斜線選單正常顯示（不含 MCP Manager overlay）

---

## 設計決策記錄

### Q1: 為什麼 load() 而不是 constructor 注入 servers？

`GrimoMcpManagerView` 在 Phase 4 建構，但 server 列表在每次 `/mcp` 開啟時都要重讀（使用者可能已經用 `/mcp-add` 新增了）。所以用 `load()` 而非 constructor 注入，每次開啟 overlay 時呼叫 `load(grimoConfig.getMcpServers())`。

### Q2: 為什麼 MCP Manager 的 ↑↓ 不 wrap？

跟隨 spec 設計：到頂/底時停止。斜線選單的 ↑↓ 是 wrap 的（用 `%` modulo），但 MCP Manager 的列表通常只有幾個 server，wrap 容易讓使用者迷失位置。

### Q3: ESC binding 是否影響 arrow key？

ESC (`\033`) 是 ANSI escape 序列的開頭（如 `\033[A` = UP）。JLine 的 KeyMap 有 timeout 機制：收到 `\033` 後等待短暫時間，如果沒有後續字元才判定為單獨 ESC。已有 `\033[A` 等 binding 的情況下不會衝突。
