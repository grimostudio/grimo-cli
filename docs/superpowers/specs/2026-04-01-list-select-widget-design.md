# ListSelect + GroupedSelect Widget 設計

> Sub-project 3 of 4: TUI 重構系列。設計可復用的選擇 widget — 平面清單和分群清單共享統一操作體驗。

## 目標

1. `ListSelect<T>` — 通用單選列表（平面）
2. `GroupedSelect<T>` — 分群選擇（category → item，tmux tree → flat pattern）
3. 統一操作體驗 — 相同的視覺語言、導航方式、scroll hints
4. `/agent-use` 使用 GroupedSelect（agent → model 兩層選擇）

## 背景

### 終端 UI 研究

| 來源 | 關鍵設計模式 | 採用 |
|------|-------------|------|
| **tmux** `mode-tree` | callback-driven、tree → flat list → render visible、state preservation across rebuilds | ✅ `setItems()` + `RowRenderer` + GroupedSelect flatten |
| **Claude Code** `CustomSelect` | viewport scrolling、scroll hints、`OptionWithDescription<T>`、linear nav | ✅ 全部採用 |
| **Charm Bubbles** `list` | Model-Update-View、composable features | ✅ 純狀態+渲染、input routing 在 controller |

> 參考來源：
> - tmux: [mode-tree.c](https://github.com/tmux/tmux/blob/master/mode-tree.c)
> - Charm Bubbles: [github.com/charmbracelet/bubbles](https://github.com/charmbracelet/bubbles)
> - Claude Code: `CustomSelect/select.tsx`

### 現有 widget 缺口

| 現有 widget | 限制 |
|------------|------|
| `Selector` | 純靜態渲染，無狀態管理、無 viewport |
| `SlashMenu` | 有狀態但跟斜線指令綁死 |
| `McpPanel` | 有狀態但跟 MCP 管理綁死 |

## 設計

### 1. 統一操作體驗

不論 ListSelect 或 GroupedSelect，使用者看到**一致的視覺和操作**：

| 元素 | 規格 |
|------|------|
| 選中項 | `>` 前綴 + BRAND_COLOR (67) steel blue |
| 一般項 | 空格前綴 + 預設色，description 灰色 (245) |
| Scroll hint | `↑ N more` / `↓ N more` 灰色 (245)，佔 maxVisible 行 |
| 導航 | ↑↓ 移動、Enter 確認/展開、Esc 取消 |
| Navigation | Linear（不循環）、Home/End 跳頭尾 |
| maxVisible | 預設 7 |
| Viewport | 跟隨 selectedIndex 滾動 |

### 2. ListSelect\<T\>

**位置：** `tui/widget/ListSelect.java`

```java
/**
 * 通用單選列表 widget。
 *
 * 設計說明：
 * - Generic <T> 讓 consumer 攜帶任意值
 * - RowRenderer 可自訂每行渲染（tmux drawcb pattern）
 * - setItems() 動態更新（tmux rebuild pattern），保留 selectedIndex
 * - 純狀態 + 渲染，不處理鍵盤（controller 層轉發）
 *
 * @see <a href="https://github.com/tmux/tmux/blob/master/mode-tree.c">tmux mode-tree</a>
 */
public class ListSelect<T> implements Renderable {

    public record Item<T>(String label, String description, T value) {}

    @FunctionalInterface
    public interface RowRenderer<T> {
        AttributedString render(Item<T> item, boolean selected, int width);
    }

    private List<Item<T>> items;
    private final int maxVisible;       // 預設 7
    private int selectedIndex = 0;
    private int viewportStart = 0;
    private RowRenderer<T> rowRenderer;  // null = 預設渲染

    public ListSelect(List<Item<T>> items, int maxVisible);

    // === 自訂渲染（tmux drawcb）===
    public void setRowRenderer(RowRenderer<T> renderer);

    // === 動態更新（tmux rebuild）===
    public void setItems(List<Item<T>> items);  // 保留 index，clamp if needed

    // === 導航 ===
    public void moveUp();       // linear，到頂不循環
    public void moveDown();     // linear，到底不循環
    public void moveToFirst();  // Home
    public void moveToLast();   // End

    // === 查詢 ===
    public Item<T> getSelected();
    public int getSelectedIndex();
    public boolean isEmpty();
    public int getVisibleCount();  // min(items.size(), maxVisible)

    // === 渲染 ===
    @Override
    public List<AttributedString> render(int width);
}
```

**預設渲染格式：**
```
  label          description      ← 一般項（description 灰色）
> label          description      ← 選中項（整行 BRAND_COLOR）
```

Layout：`前綴(2) + label(固定寬) + gap(2) + description(填滿)`

**Scroll hints（佔 maxVisible 行）：**
- `scrollHintCount` = 0、1 或 2（上/下各看是否有隱藏項）
- `visibleItemSlots = maxVisible - scrollHintCount`
- hints 佔位是動態的：列表頂部只有 `↓`，中間兩個，底部只有 `↑`

**Viewport scrolling：**
```
moveDown():
  selectedIndex = min(selectedIndex + 1, items.size() - 1)
  recalc scrollHintCount
  if selectedIndex >= viewportStart + visibleItemSlots:
      viewportStart = selectedIndex - visibleItemSlots + 1

moveUp():
  selectedIndex = max(selectedIndex - 1, 0)
  if selectedIndex < viewportStart:
      viewportStart = selectedIndex
  recalc scrollHintCount
```

### 3. GroupedSelect\<T\>

**位置：** `tui/widget/GroupedSelect.java`

```java
/**
 * 分群選擇 widget — category → item 兩層。
 *
 * 設計說明：
 * - 組合 ListSelect（不繼承），注入自訂 RowRenderer
 * - tmux tree → flat pattern：展開/收合 = rebuild flat list → setItems()
 * - 同一時間只展開一個 group（accordion 模式）
 * - Enter on group → toggle；Enter on leaf → select
 *
 * @see <a href="https://github.com/tmux/tmux/blob/master/mode-tree.c">tmux mode-tree</a>
 */
public class GroupedSelect<T> implements Renderable {

    public record Group<T>(String label, List<ListSelect.Item<T>> children) {}

    private final List<Group<T>> groups;
    private final ListSelect<T> listSelect;  // 內部組合
    private int expandedGroupIndex = -1;      // -1 = 全部收合

    // 追蹤 flat list 中哪些行是 group header
    private List<Boolean> isGroupRow;

    public GroupedSelect(List<Group<T>> groups, int maxVisible);

    // === 導航（委派）===
    public void moveUp();
    public void moveDown();
    public void moveToFirst();
    public void moveToLast();

    // === Group 操作 ===
    /** Enter 鍵：在 group → toggle expand/collapse；在 leaf → 不做事（consumer 取 getSelected） */
    public void toggle();

    // === 查詢 ===
    public boolean isOnGroup();           // 游標在 group header 上？
    public ListSelect.Item<T> getSelected();  // leaf item，null if on group
    public boolean isEmpty();
    public int getVisibleCount();

    // === 渲染（委派 + 自訂 RowRenderer）===
    @Override
    public List<AttributedString> render(int width);
}
```

**Accordion 模式：** 同一時間只展開一個 group。展開 B 時自動收合 A。這讓 maxVisible=7 的空間足以顯示展開的 children。

**Flatten 邏輯（rebuild）：**
```
for each group:
  add group header item (▶ or ▼ prefix)
  if expanded:
    add all children items (indented)

→ setItems() to ListSelect
→ ListSelect handles navigation + viewport + scroll hints
```

**自訂 RowRenderer：**
```
if isGroupRow:
  collapsed: "  ▶ groupLabel"        (預設色，粗體)
  expanded:  "  ▼ groupLabel"        (預設色，粗體)
  selected:  "> ▶ groupLabel"        (BRAND_COLOR)

if leaf:
  normal:    "      label    desc"   (indent + 灰色 desc)
  selected:  "    > label    desc"   (indent + BRAND_COLOR)
```

### 4. 使用場景

| 場景 | Widget | Item\<T\> |
|------|--------|-----------|
| `/agent-use` | `GroupedSelect<String>` | Group=agent，Item=model（label=model name, value=agentId:model） |
| `/tier`（未來） | `ListSelect<Tier>` | label="pro"，desc="深度推理"，value=Tier.PRO |
| `/skill-install`（未來） | `ListSelect<SkillDef>` | label="code-review"，desc="Code review" |
| 任何設定選擇（未來） | `ListSelect<String>` | 通用 |

### 5. Overlay 整合

**Screen.java 新增：**
- field: `private Renderable selectOverlay` — 用 Renderable 介面，ListSelect 和 GroupedSelect 都適用
- `setSelectOverlay(Renderable overlay)` — 設定時**自動關閉 slashMenu/mcpPanel**（overlay 互斥）
- `clearSelectOverlay()`
- `hasSelectOverlay()`
- render() 中新增 overlay 分支

**GrimoTuiRunner.java 新增：**
- `/agent-use`（無參數）→ 建立 GroupedSelect → 顯示 overlay
- `/agent-use claude`（有參數）→ 原行為不變

```java
private void showAgentPicker() {
    var groups = agentModelRegistry.listAvailable().entrySet().stream()
        .map(e -> new GroupedSelect.Group<>(
            e.getKey(),                                    // "claude"
            buildModelItems(e.getKey(), e.getValue())))    // model list
        .toList();
    var select = new GroupedSelect<String>(groups, 7);
    screen.setSelectOverlay(select);
    eventLoop.setDirty();
}
```

**鍵盤處理（String operation）：**
```java
private void handleSelectOverlayInput(String operation) {
    if (selectOverlay instanceof GroupedSelect<?> grouped) {
        switch (operation) {
            case EventLoop.OP_UP -> grouped.moveUp();
            case EventLoop.OP_DOWN -> grouped.moveDown();
            case EventLoop.OP_ENTER -> {
                if (grouped.isOnGroup()) {
                    grouped.toggle();  // 展開/收合
                } else {
                    var selected = grouped.getSelected();
                    screen.clearSelectOverlay();
                    if (selected != null) {
                        // selected.value() = "claude:claude-opus-4-6" 或自訂格式
                        String result = agentCommands.use(selected.value());
                        appendCommandOutput(result);
                    }
                }
            }
            case EventLoop.OP_ESC, EventLoop.OP_CTRL_C -> screen.clearSelectOverlay();
        }
    } else if (selectOverlay instanceof ListSelect<?> list) {
        // 同理，但沒有 toggle
        switch (operation) {
            case EventLoop.OP_UP -> list.moveUp();
            case EventLoop.OP_DOWN -> list.moveDown();
            case EventLoop.OP_ENTER -> {
                var selected = list.getSelected();
                screen.clearSelectOverlay();
                // consumer 處理 selected
            }
            case EventLoop.OP_ESC, EventLoop.OP_CTRL_C -> screen.clearSelectOverlay();
        }
    }
    eventLoop.setDirty();
}
```

### 6. 測試

**ListSelectTest.java（`tui/widget/`）— 17 cases：**

| 測試 | 驗證 |
|------|------|
| `renderEmptyList` | 空 items → 0 行 |
| `renderSingleItem` | 1 項 → 1 行，`>` 前綴 + brand color |
| `renderWithinMaxVisible` | items ≤ maxVisible → 全部顯示，無 scroll hints |
| `renderExceedMaxVisible` | items > maxVisible → maxVisible 行含 scroll hints |
| `moveDownUpdatesSelection` | selectedIndex 遞增 |
| `moveUpUpdatesSelection` | selectedIndex 遞減 |
| `moveDownScrollsViewport` | 底部邊界 → viewport 下移 |
| `moveUpScrollsViewport` | 頂部邊界 → viewport 上移 |
| `scrollHintShowsCount` | `↑ 2 more` 數字正確 |
| `scrollHintOnlyBottomWhenAtTop` | 頂部只顯示 `↓` |
| `descriptionRenderedInGray` | description ANSI 245 |
| `linearNavigationNoWrap` | 頂/底不循環 |
| `moveToFirstJumpsToTop` | Home → index=0 |
| `moveToLastJumpsToBottom` | End → index=last |
| `setItemsPreservesIndex` | 更新列表保留 index |
| `setItemsClampsIndex` | 列表縮短 → clamp |
| `customRowRenderer` | 自訂 RowRenderer 被呼叫 |

**GroupedSelectTest.java（`tui/widget/`）— 12 cases：**

| 測試 | 驗證 |
|------|------|
| `renderCollapsedGroups` | 全部收合 → 只顯示 group headers with ▶ |
| `toggleExpandsGroup` | Enter on group → 展開，顯示 children with ▼ |
| `toggleCollapsesGroup` | 再次 Enter → 收合 |
| `accordionMode` | 展開 B → A 自動收合 |
| `isOnGroupReturnsTrueForGroupHeader` | 游標在 group 上 |
| `isOnGroupReturnsFalseForLeaf` | 游標在 leaf 上 |
| `getSelectedReturnsLeafItem` | leaf 上取得 Item |
| `getSelectedReturnsNullOnGroup` | group 上回傳 null |
| `navigationThroughExpandedGroup` | ↓ 穿過展開的 children |
| `scrollHintsWithExpandedGroup` | 展開後超過 maxVisible → scroll hints |
| `emptyGroupRendersCorrectly` | 空 group 展開後無 children |
| `moveDownFromLastChildToNextGroup` | 從最後一個 child ↓ → 到下一個 group |

### 7. Glossary 更新

新增到 TUI 框架術語表：

| 名詞 | 英文 | 說明 |
|------|------|------|
| **ListSelect** | List Select | 通用單選列表 widget。支援 viewport scrolling、scroll hints、linear navigation、自訂 RowRenderer。位於 `tui/widget/`。 |
| **GroupedSelect** | Grouped Select | 分群選擇 widget。組合 ListSelect，用 tmux tree → flat pattern 實現展開/收合。Accordion 模式（同時只展開一個 group）。位於 `tui/widget/`。 |

## 不在範圍內

- SlashMenu / McpPanel 重構為使用 ListSelect — 未來 SP
- `/tier`、`/skill-install` 等其他互動指令 — 未來按需加
- GrimoTuiRunner 拆解（SP4）
- 滑鼠點擊選擇
- 多選（multi-select）

## 風險與緩解

| 風險 | 緩解 |
|------|------|
| GroupedSelect 的 flatten + rebuild 複雜度 | 單元測試 12 cases 覆蓋 |
| RowRenderer 增加 ListSelect 複雜度 | 預設 renderer 內建，consumer 不必自訂 |
| overlay 互斥漏洞 | `setSelectOverlay()` 自動關閉其他 overlay |
| /agent-use 行為改變 | 有參數行為不變，無參數從 usage text → 互動選擇 |
| Accordion 模式 UX 不直覺 | 跟 tmux choose-tree 和 IDE 檔案樹一致 |

## 驗收標準

1. `./gradlew build` 全部通過
2. `ListSelectTest` 17 cases 通過
3. `GroupedSelectTest` 12 cases 通過
4. `/agent-use`（無參數）顯示 GroupedSelect overlay
5. ↑↓ 導航、Enter 展開 group / 選擇 model、Esc 取消
6. 超過 maxVisible=7 的列表有 scroll hints
7. Accordion 模式：展開 B 時 A 自動收合
