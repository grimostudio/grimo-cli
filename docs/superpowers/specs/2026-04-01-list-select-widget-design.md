# ListSelect Widget + /agent-use 互動化

> Sub-project 3 of 4: TUI 重構系列。設計通用單選 widget，並應用於 `/agent-use` 互動指令。

## 目標

1. 設計 `ListSelect<T>` 通用單選 widget（`tui/widget/`）
2. `/agent-use`（無參數）觸發互動選擇器
3. 為 SP3 後續擴充（GroupedSelect、其他互動指令）建立基礎

## 背景

### 現有 widget 缺口

| 現有 widget | 限制 |
|------------|------|
| `Selector` | 純靜態渲染，無狀態管理、無 viewport scrolling |
| `SlashMenu` | 有狀態但跟斜線指令綁死，不可復用 |
| `McpPanel` | 有狀態但跟 MCP 管理綁死 |

需要一個**通用的、有狀態的、可復用的**選擇 widget。

### Claude Code 研究啟發

參考 Claude Code `CustomSelect` 組件的設計模式：

| 特性 | Claude Code 做法 | Grimo 採用 |
|------|-----------------|-----------|
| Viewport scrolling | 列表超過 maxVisible 時 viewport 跟隨游標 | ✅ 採用 |
| Scroll hints | `↑ N more` / `↓ N more` 指示上下方有更多選項 | ✅ 採用 |
| Linear navigation | 到頂/底不循環（不 wrap） | ✅ 採用（跟 SlashMenu 的 wrap 行為不同） |
| Label + Description | `OptionWithDescription<T>` | ✅ 採用（label 左、description 右灰色） |
| Selection indicator | 選中項高亮 | ✅ 採用（`>` 前綴 + BRAND_COLOR） |

> 參考來源：`/Users/samzhu/workspace/claudecode/claude-code-main/src/components/CustomSelect/select.tsx`

## 設計

### 1. ListSelect\<T\> Widget

**位置：** `tui/widget/ListSelect.java`

```java
public class ListSelect<T> implements Renderable {

    /** 選項：顯示用 label + 說明 + 邏輯值 */
    public record Item<T>(String label, String description, T value) {}

    private final List<Item<T>> items;
    private final int maxVisible;
    private int selectedIndex = 0;
    private int viewportStart = 0;

    public ListSelect(List<Item<T>> items, int maxVisible);

    // === 導航 ===
    /** 選中項上移。到頂不循環（linear）。viewport 跟隨。 */
    public void moveUp();
    /** 選中項下移。到底不循環（linear）。viewport 跟隨。 */
    public void moveDown();

    // === 查詢 ===
    /** 取得目前選中的 Item，列表空回傳 null */
    public Item<T> getSelected();
    public boolean isEmpty();
    /** 實際可見行數（含 scroll hints），用於 overlay 高度計算 */
    public int getVisibleCount();

    // === 渲染 ===
    /** 渲染選擇列表。每行 columnLength == width。含 scroll hints。 */
    @Override
    public List<AttributedString> render(int width);
}
```

### 2. 渲染規格

**渲染範例（maxVisible=7，共 8 項，selectedIndex=3，viewportStart=1）：**

```
  ↑ 1 more                          ← scroll hint（灰色，上方有 1 項隱藏）
  gemini        Gemini 2.5 Pro      ← 一般項（預設色）
> claude        Claude Sonnet 4.6   ← 選中項（BRAND_COLOR 67 + `>` 前綴）
  codex         OpenAI Codex        ← 一般項
  ollama        Local Ollama        ← 一般項
  local-llm     Local LLM           ← 一般項
  ↓ 1 more                          ← scroll hint（下方有 1 項隱藏）
```

**渲染規則：**
- 每行格式：`前綴(2) + label(固定寬) + gap(2) + description(填滿，灰色 ANSI 245)`
  - 選中項前綴 `> `，其他 `  `
  - label 欄寬用 `Layout.horizontal(width, 2, Fixed(labelMaxWidth+2), Fill())` 計算
- Scroll hint 格式：`  ↑ N more` / `  ↓ N more`，灰色（ANSI 245）
- Scroll hint **佔用 maxVisible 行數**（不是額外的）— overlay 高度固定
- `scrollHintCount` = 上下隱藏項的 hint 數量之和（0、1 或 2）：
  - 上方有隱藏項 → 顯示 `↑ N more`，佔 1 行
  - 下方有隱藏項 → 顯示 `↓ N more`，佔 1 行
  - 都沒有隱藏 → 不顯示 hint，0 行
- `visibleItemSlots = maxVisible - scrollHintCount`（item 實際可用行數）
- 空列表渲染 0 行
- `getVisibleCount()` 回傳 `min(items.size(), maxVisible)` — overlay 高度計算用

**Viewport scrolling 邏輯：**

```
每次 moveUp/moveDown 後重新計算 scrollHintCount 和 visibleItemSlots：

scrollHintCount:
  hasAbove = (viewportStart > 0)
  hasBelow = (viewportStart + visibleItemSlots < items.size())
  scrollHintCount = (hasAbove ? 1 : 0) + (hasBelow ? 1 : 0)

visibleItemSlots = maxVisible - scrollHintCount

moveDown():
  selectedIndex = min(selectedIndex + 1, items.size() - 1)
  重新計算 scrollHintCount / visibleItemSlots
  if selectedIndex >= viewportStart + visibleItemSlots:
      viewportStart = selectedIndex - visibleItemSlots + 1

moveUp():
  selectedIndex = max(selectedIndex - 1, 0)
  if selectedIndex < viewportStart:
      viewportStart = selectedIndex
  重新計算 scrollHintCount / visibleItemSlots
```

### 3. Overlay 整合

**遵循現有 overlay 模式**（SlashMenu / McpPanel 的做法）：

**Screen.java 新增：**
- field: `private ListSelect<?> listSelect`
- setter: `setListSelect(ListSelect<?> listSelect)` — 設定要顯示的 ListSelect 實例
- `setListSelectVisible(boolean visible)` — 設定為 true 時**同時關閉 slashMenu/mcpPanel**（overlay 互斥）
- `isListSelectVisible()`
- render() 中新增 overlay 分支（跟 slashMenu/mcpPanel 並行，互斥）

**GrimoTuiRunner.java 新增：**
- field: `private ListSelect<String> agentListSelect`（agent 選擇用）
- `TuiKeyHandler` 新增 overlay 分支：

```java
// 在現有的 slash menu / mcp panel 分支之後
if (screen.isListSelectVisible()) {
    handleListSelectInput(op);
    return;
}
```

```java
// EventLoop.KeyHandler 傳入 String operation（不是 int）
private void handleListSelectInput(String operation) {
    switch (operation) {
        case EventLoop.OP_UP -> agentListSelect.moveUp();
        case EventLoop.OP_DOWN -> agentListSelect.moveDown();
        case EventLoop.OP_ENTER -> {
            var selected = agentListSelect.getSelected();
            screen.setListSelectVisible(false);
            if (selected != null) {
                // 直接呼叫 AgentCommands.use()，等同 /agent-use <agentId>
                String result = agentCommands.use(selected.value());
                appendCommandOutput(result);
            }
        }
        case EventLoop.OP_ESC, EventLoop.OP_CTRL_C -> {
            screen.setListSelectVisible(false);
        }
    }
    eventLoop.setDirty();
}
```

> 設計說明：`agentCommands` 是已注入的 `AgentCommands` bean（GrimoTuiRunner 建構子已有）。`appendCommandOutput()` 是現有的輸出方法。不需要新增 `processAgentUse()` helper。

### 4. /agent-use 互動化

**修改 GrimoTuiRunner.processInput()：**

```java
// 偵測 /agent-use 無參數
if (trimmed.equals("/agent-use") || trimmed.equals("agent-use")) {
    showAgentPicker();
    return;
}
// 有參數 → 走原有 CommandExecutor 流程（不變）
```

```java
private void showAgentPicker() {
    var agents = agentModelRegistry.listAvailable();
    var items = agents.entrySet().stream()
        .map(e -> new ListSelect.Item<>(
            e.getKey(),                          // label: "claude"
            getAgentDescription(e.getKey()),     // description: "Claude Sonnet 4.6"
            e.getKey()))                         // value: "claude"
        .toList();
    agentListSelect = new ListSelect<>(items, 5);
    screen.setListSelect(agentListSelect);
    screen.setListSelectVisible(true);
    eventLoop.setDirty();
}
```

**`/agent-use` 有參數行為完全不變** — CommandExecutor 照常解析。

### 5. 測試

**ListSelectTest.java（`tui/widget/`）：**

| 測試 | 驗證 |
|------|------|
| `renderEmptyList` | 空 items → 0 行 |
| `renderSingleItem` | 1 項 → 1 行，有 `>` 前綴和 brand color |
| `renderWithinMaxVisible` | items ≤ maxVisible → 全部顯示，無 scroll hints |
| `renderExceedMaxVisible` | items > maxVisible → 顯示 maxVisible 行含 scroll hints |
| `moveDownUpdatesSelection` | selectedIndex 遞增，不超出範圍 |
| `moveUpUpdatesSelection` | selectedIndex 遞減，不低於 0 |
| `moveDownScrollsViewport` | 選到底部邊界 → viewport 下移 |
| `moveUpScrollsViewport` | 選到頂部邊界 → viewport 上移 |
| `scrollHintShowsCount` | `↑ 2 more` 數字正確 |
| `scrollHintOnlyBottomWhenAtTop` | 列表頂部只顯示 `↓ N more`，不顯示 `↑` |
| `descriptionRenderedInGray` | description 使用 ANSI 245 灰色 |
| `getSelectedReturnsCorrectItem` | getSelected() 回傳 selectedIndex 的 Item |
| `linearNavigationNoWrap` | 到頂再 moveUp 不變，到底再 moveDown 不變 |
| `cjkLabelRendersCorrectWidth` | CJK label 寬度正確（2 column per char） |

**不測 overlay 整合** — GrimoTuiRunner 的 integration test 太重，mock 太多。

## 不在範圍內

- `GroupedSelect`（兩層選擇器）— 未來 SP
- `SlashMenu` / `McpPanel` 重構為使用 ListSelect — 未來 SP
- `/agent model`、`/tier` 等其他互動指令 — 未來按需加
- GrimoTuiRunner 拆解（SP4）
- 滑鼠點擊選擇（目前只支援鍵盤 ↑↓/Enter/Esc）

## 風險與緩解

| 風險 | 緩解 |
|------|------|
| ListSelect 的 Generic `<T>` 導致 Screen 簽名複雜 | Screen 用 `ListSelect<?>` wildcard |
| overlay 互斥邏輯漏洞（同時顯示兩個 overlay） | `setListSelectVisible(true)` 時關閉 slashMenu/mcpPanel |
| /agent-use 無參數行為改變 | 有參數行為完全不變，無參數從「顯示 usage」改為「互動選擇」 |

## 驗收標準

1. `./gradlew build` 全部通過
2. `ListSelectTest` 12+ 測試通過
3. `/agent-use`（無參數）顯示 agent 列表 overlay
4. ↑↓ 選擇、Enter 確認切換 agent、Esc 取消
5. 超過 maxVisible 的列表有 scroll hints
6. CJK agent 名稱寬度渲染正確
