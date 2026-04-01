# ListSelect + GroupedSelect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 ListSelect + GroupedSelect 可復用 widget，並將 `/agent-use` 改為 GroupedSelect 互動選擇器。

**Architecture:** ListSelect 是有狀態的泛型單選 widget（含 viewport scrolling、scroll hints、RowRenderer）。GroupedSelect 組合 ListSelect，用 tmux tree→flat pattern 實現 accordion 展開/收合。Screen 以 Renderable 介面統一管理 select overlay。TDD — 先寫測試再實作。

**Tech Stack:** Java 25, Spring Boot 4.0.x, JLine (AttributedString/AttributedStyle), JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-04-01-list-select-widget-design.md`

---

### Task 1: ListSelect\<T\> — 核心導航 + 渲染

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/tui/widget/ListSelect.java`
- Create: `src/test/java/io/github/samzhu/grimo/tui/widget/ListSelectTest.java`

- [ ] **Step 1: 寫 ListSelectTest（全部 17 個測試）**

建立 `src/test/java/io/github/samzhu/grimo/tui/widget/ListSelectTest.java`。

完整測試清單：

```java
package io.github.samzhu.grimo.tui.widget;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ListSelectTest {

    private static <T> ListSelect.Item<T> item(String label, String desc, T val) {
        return new ListSelect.Item<>(label, desc, val);
    }

    @Test void renderEmptyList() {
        var ls = new ListSelect<String>(List.of(), 7);
        assertThat(ls.render(40)).isEmpty();
        assertThat(ls.isEmpty()).isTrue();
        assertThat(ls.getSelected()).isNull();
    }

    @Test void renderSingleItem() {
        var ls = new ListSelect<>(List.of(item("claude", "Sonnet", "c")), 7);
        var lines = ls.render(40);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().toString()).startsWith(">");
        assertThat(lines.getFirst().toString()).contains("claude");
    }

    @Test void renderWithinMaxVisible() {
        var items = List.of(item("a","d1","1"), item("b","d2","2"), item("c","d3","3"));
        var ls = new ListSelect<>(items, 7);
        var lines = ls.render(40);
        assertThat(lines).hasSize(3);
        // No scroll hints
        assertThat(lines.stream().noneMatch(l -> l.toString().contains("more"))).isTrue();
    }

    @Test void renderExceedMaxVisible() {
        var items = List.of(
            item("a","","1"), item("b","","2"), item("c","","3"),
            item("d","","4"), item("e","","5"), item("f","","6"),
            item("g","","7"), item("h","","8"), item("i","","9"));
        var ls = new ListSelect<>(items, 7);
        var lines = ls.render(40);
        assertThat(lines).hasSize(7);
    }

    @Test void moveDownUpdatesSelection() {
        var items = List.of(item("a","","1"), item("b","","2"));
        var ls = new ListSelect<>(items, 7);
        assertThat(ls.getSelectedIndex()).isEqualTo(0);
        ls.moveDown();
        assertThat(ls.getSelectedIndex()).isEqualTo(1);
    }

    @Test void moveUpUpdatesSelection() {
        var items = List.of(item("a","","1"), item("b","","2"));
        var ls = new ListSelect<>(items, 7);
        ls.moveDown();
        ls.moveUp();
        assertThat(ls.getSelectedIndex()).isEqualTo(0);
    }

    @Test void linearNavigationNoWrap() {
        var items = List.of(item("a","","1"), item("b","","2"));
        var ls = new ListSelect<>(items, 7);
        ls.moveUp(); // already at top
        assertThat(ls.getSelectedIndex()).isEqualTo(0);
        ls.moveDown();
        ls.moveDown(); // already at bottom
        assertThat(ls.getSelectedIndex()).isEqualTo(1);
    }

    @Test void moveDownScrollsViewport() {
        var items = List.of(
            item("a","","1"), item("b","","2"), item("c","","3"),
            item("d","","4"), item("e","","5"), item("f","","6"),
            item("g","","7"), item("h","","8"));
        var ls = new ListSelect<>(items, 4);
        // Move to bottom of viewport
        for (int i = 0; i < 7; i++) ls.moveDown();
        assertThat(ls.getSelectedIndex()).isEqualTo(7);
        var lines = ls.render(40);
        assertThat(lines).hasSize(4);
        // Last item should be visible (selected)
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("h"))).isTrue();
    }

    @Test void moveUpScrollsViewport() {
        var items = List.of(
            item("a","","1"), item("b","","2"), item("c","","3"),
            item("d","","4"), item("e","","5"));
        var ls = new ListSelect<>(items, 4);
        for (int i = 0; i < 4; i++) ls.moveDown();
        for (int i = 0; i < 4; i++) ls.moveUp();
        assertThat(ls.getSelectedIndex()).isEqualTo(0);
        var lines = ls.render(40);
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("a"))).isTrue();
    }

    @Test void scrollHintShowsCount() {
        var items = List.of(
            item("a","","1"), item("b","","2"), item("c","","3"),
            item("d","","4"), item("e","","5"));
        var ls = new ListSelect<>(items, 3);
        ls.moveDown(); // select b, should have ↑ 1 and ↓ hints
        var lines = ls.render(40);
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("↑"))).isTrue();
    }

    @Test void scrollHintOnlyBottomWhenAtTop() {
        var items = List.of(
            item("a","","1"), item("b","","2"), item("c","","3"),
            item("d","","4"), item("e","","5"));
        var ls = new ListSelect<>(items, 3);
        // At top, should only have ↓ hint
        var lines = ls.render(40);
        assertThat(lines.stream().noneMatch(l -> l.toString().contains("↑"))).isTrue();
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("↓"))).isTrue();
    }

    @Test void descriptionRenderedInGray() {
        var ls = new ListSelect<>(List.of(item("agent", "desc here", "v")), 7);
        var lines = ls.render(60);
        // description should be present
        assertThat(lines.getFirst().toString()).contains("desc here");
    }

    @Test void moveToFirstJumpsToTop() {
        var items = List.of(item("a","","1"), item("b","","2"), item("c","","3"));
        var ls = new ListSelect<>(items, 7);
        ls.moveDown(); ls.moveDown();
        ls.moveToFirst();
        assertThat(ls.getSelectedIndex()).isEqualTo(0);
    }

    @Test void moveToLastJumpsToBottom() {
        var items = List.of(item("a","","1"), item("b","","2"), item("c","","3"));
        var ls = new ListSelect<>(items, 7);
        ls.moveToLast();
        assertThat(ls.getSelectedIndex()).isEqualTo(2);
    }

    @Test void setItemsPreservesIndex() {
        var ls = new ListSelect<>(List.of(item("a","","1"), item("b","","2")), 7);
        ls.moveDown(); // index = 1
        ls.setItems(List.of(item("x","","1"), item("y","","2"), item("z","","3")));
        assertThat(ls.getSelectedIndex()).isEqualTo(1);
    }

    @Test void setItemsClampsIndex() {
        var ls = new ListSelect<>(List.of(item("a","","1"), item("b","","2"), item("c","","3")), 7);
        ls.moveDown(); ls.moveDown(); // index = 2
        ls.setItems(List.of(item("x","","1"))); // shrink
        assertThat(ls.getSelectedIndex()).isEqualTo(0);
    }

    @Test void customRowRenderer() {
        var ls = new ListSelect<>(List.of(item("test", "desc", "v")), 7);
        ls.setRowRenderer((item, selected, width) ->
            new AttributedString("CUSTOM:" + item.label()));
        var lines = ls.render(40);
        assertThat(lines.getFirst().toString()).startsWith("CUSTOM:test");
    }
}
```

- [ ] **Step 2: 執行測試確認全部 FAIL**

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.widget.ListSelectTest"`
Expected: FAIL — ListSelect class does not exist

- [ ] **Step 3: 實作 ListSelect.java**

建立 `src/main/java/io/github/samzhu/grimo/tui/widget/ListSelect.java`。

完整實作要點：
- `Item<T>` record（label, description, value）
- `RowRenderer<T>` functional interface
- 建構子：`ListSelect(List<Item<T>> items, int maxVisible)`
- `moveUp/Down/ToFirst/ToLast` — linear navigation + viewport 跟隨
- `setItems()` — 保留 index，clamp if out of range，reset viewportStart if needed
- `getSelected()`, `getSelectedIndex()`, `isEmpty()`, `getVisibleCount()`
- `render(int width)` — viewport + scroll hints + 預設/自訂 RowRenderer
- 預設 RowRenderer：`前綴(2) + label(fixed) + gap(2) + description(fill, gray 245)`
- 選中項整行 BRAND_COLOR (67)，一般項 label 預設色 + description gray
- Scroll hint 用灰色 (245)，格式 `  ↑ N more` / `  ↓ N more`
- 每行 columnLength == width（用 DisplayWidth.padRight）

- [ ] **Step 4: 執行測試**

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.widget.ListSelectTest"`
Expected: 17 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/tui/widget/ListSelect.java \
        src/test/java/io/github/samzhu/grimo/tui/widget/ListSelectTest.java
git commit -m "feat: add ListSelect<T> widget with viewport scrolling and RowRenderer"
```

---

### Task 2: GroupedSelect\<T\> — 組合 ListSelect

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/tui/widget/GroupedSelect.java`
- Create: `src/test/java/io/github/samzhu/grimo/tui/widget/GroupedSelectTest.java`

- [ ] **Step 1: 寫 GroupedSelectTest（全部 12 個測試）**

建立 `src/test/java/io/github/samzhu/grimo/tui/widget/GroupedSelectTest.java`。

測試要點：
- 用 helper 建立 groups：`group("claude", items("sonnet", "opus", "haiku"))` 等
- `renderCollapsedGroups` — 全收合只顯示 `▶` group headers
- `toggleExpandsGroup` — toggle 後顯示 `▼` + children
- `toggleCollapsesGroup` — 再次 toggle 收合
- `accordionMode` — 展開 B 自動收合 A
- `isOnGroupReturnsTrueForGroupHeader` / `ReturnsFalseForLeaf`
- `getSelectedReturnsLeafItem` / `ReturnsNullOnGroup`
- `navigationThroughExpandedGroup` — ↓ 穿過 children
- `scrollHintsWithExpandedGroup` — 展開後超 maxVisible
- `emptyGroupRendersCorrectly`
- `moveDownFromLastChildToNextGroup`

- [ ] **Step 2: 執行測試確認 FAIL**

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.widget.GroupedSelectTest"`
Expected: FAIL

- [ ] **Step 3: 實作 GroupedSelect.java**

實作要點：
- `Group<T>` record（label, children: List<Item<T>>）
- 內部持有 `ListSelect<T> listSelect`
- `expandedGroupIndex` 追蹤展開的 group（-1 = 全收合）
- `isGroupRow` list 追蹤 flat list 中每行是 group 還是 leaf
- `rebuildFlatList()` — tree → flat，設定自訂 RowRenderer 到 listSelect
- RowRenderer 邏輯：
  - group row + collapsed: `  ▶ label` (預設色)
  - group row + expanded: `  ▼ label` (預設色)
  - group row + selected: `> ▶ label` or `> ▼ label` (BRAND_COLOR)
  - leaf row: `      label    desc` (indent 6)
  - leaf row + selected: `    > label    desc` (indent 4 + `>`, BRAND_COLOR)
- `toggle()` — on group → expand/collapse → rebuild → setItems。accordion: 展開新的時收合舊的
- `moveUp/Down/ToFirst/ToLast` 委派給 listSelect
- `isOnGroup()` — 查 isGroupRow[selectedIndex]
- `getSelected()` — !isOnGroup ? listSelect.getSelected() : null
- `render()` 委派給 listSelect.render()

- [ ] **Step 4: 執行測試**

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.widget.GroupedSelectTest"`
Expected: 12 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/tui/widget/GroupedSelect.java \
        src/test/java/io/github/samzhu/grimo/tui/widget/GroupedSelectTest.java
git commit -m "feat: add GroupedSelect<T> widget — accordion tree→flat with ListSelect"
```

---

### Task 3: Screen overlay 支援

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/tui/screen/Screen.java`

- [ ] **Step 1: 新增 selectOverlay 相關 field 和方法**

在 Screen.java 新增：

```java
// field（在 mcpManagerVisible 後面）
private volatile Renderable selectOverlay;

// methods
public void setSelectOverlay(Renderable overlay) {
    // overlay 互斥：關閉其他 overlay
    this.slashMenuVisible = false;
    this.mcpManagerVisible = false;
    this.selectOverlay = overlay;
}

public void clearSelectOverlay() {
    this.selectOverlay = null;
}

public boolean hasSelectOverlay() {
    return selectOverlay != null;
}
```

- [ ] **Step 2: render() 新增 selectOverlay 分支**

在 render() 中，mcpPanel overlay 區塊之後，`allLines.addAll(contentLines)` 之前，新增：

```java
// 4. Select overlay（覆蓋 content 底部，與 slash menu / mcp panel 互斥）
if (selectOverlay != null) {
    List<AttributedString> selectLines = selectOverlay.render(cols);
    int selectHeight = selectLines.size();
    int overlayStart = contentLines.size() - selectHeight;
    for (int i = 0; i < selectHeight; i++) {
        int targetRow = overlayStart + i;
        if (targetRow >= 0 && targetRow < contentLines.size()) {
            contentLines.set(targetRow, selectLines.get(i));
        }
    }
}
```

- [ ] **Step 3: 編譯驗證**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/tui/screen/Screen.java
git commit -m "feat: add selectOverlay support to Screen (Renderable-based, mutex)"
```

---

### Task 4: /agent-use 互動化 — GroupedSelect 整合

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java`

- [ ] **Step 1: 新增 imports 和 field**

```java
import io.github.samzhu.grimo.tui.widget.ListSelect;
import io.github.samzhu.grimo.tui.widget.GroupedSelect;
```

新增 field（在 `clipboardWriter` 附近）：
```java
private Renderable activeSelectOverlay;  // 追蹤 overlay 類型
```

- [ ] **Step 2: 新增 showAgentPicker() 方法**

```java
/**
 * 顯示 agent → model 互動選擇器（GroupedSelect overlay）。
 * /agent-use 無參數時觸發。
 */
private void showAgentPicker() {
    var availableAgents = agentModelRegistry.listAvailable();
    if (availableAgents.isEmpty()) {
        contentView.appendLine("No agents available.");
        eventLoop.setDirty();
        return;
    }
    var groups = availableAgents.entrySet().stream()
        .map(e -> {
            String agentId = e.getKey();
            // 建立 model 選項：從推薦模型 + 記憶模型
            var modelItems = buildModelItems(agentId);
            return new GroupedSelect.Group<>(agentId, modelItems);
        })
        .toList();
    var select = new GroupedSelect<String>(groups, 7);
    activeSelectOverlay = select;
    screen.setSelectOverlay(select);
    eventLoop.setDirty();
}
```

`buildModelItems()` 輔助方法：
```java
private List<ListSelect.Item<String>> buildModelItems(String agentId) {
    var items = new java.util.ArrayList<ListSelect.Item<String>>();
    // 推薦模型
    String recommended = AgentCommands.RECOMMENDED_MODELS.get(agentId);
    if (recommended != null) {
        items.add(new ListSelect.Item<>(recommended, "推薦", agentId + " " + recommended));
    }
    // 記憶的模型（如果不同於推薦）
    String remembered = grimoConfig.getAgentOption(agentId, "model");
    if (remembered != null && !remembered.equals(recommended)) {
        items.add(new ListSelect.Item<>(remembered, "上次使用", agentId + " " + remembered));
    }
    return items;
}
```

> Item value 格式：`"agentId modelHint"` — 直接傳給 `agentCommands.use()`。

- [ ] **Step 3: 修改 processInput() — 攔截 /agent-use 無參數**

在 `processInput()` 中，commandExecutor 執行之前，加入：

```java
// 攔截 /agent-use 無參數 → 互動選擇器
String stripped = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
if (stripped.equals("agent-use")) {
    showAgentPicker();
    return;
}
```

- [ ] **Step 4: 新增 handleSelectOverlayInput() 方法**

```java
private void handleSelectOverlayInput(String operation) {
    if (activeSelectOverlay instanceof GroupedSelect<?> grouped) {
        switch (operation) {
            case EventLoop.OP_UP -> grouped.moveUp();
            case EventLoop.OP_DOWN -> grouped.moveDown();
            case EventLoop.OP_ENTER -> {
                if (grouped.isOnGroup()) {
                    grouped.toggle();
                } else {
                    var selected = grouped.getSelected();
                    screen.clearSelectOverlay();
                    activeSelectOverlay = null;
                    if (selected != null) {
                        String result = agentCommands.use((String) selected.value());
                        contentView.appendCommandOutput(result);
                    }
                }
            }
            case EventLoop.OP_ESC, EventLoop.OP_CTRL_C -> {
                screen.clearSelectOverlay();
                activeSelectOverlay = null;
            }
        }
    } else if (activeSelectOverlay instanceof ListSelect<?> list) {
        switch (operation) {
            case EventLoop.OP_UP -> list.moveUp();
            case EventLoop.OP_DOWN -> list.moveDown();
            case EventLoop.OP_ENTER -> {
                screen.clearSelectOverlay();
                activeSelectOverlay = null;
            }
            case EventLoop.OP_ESC, EventLoop.OP_CTRL_C -> {
                screen.clearSelectOverlay();
                activeSelectOverlay = null;
            }
        }
    }
    eventLoop.setDirty();
}
```

- [ ] **Step 5: 修改 TuiKeyHandler — 加入 overlay 分支**

在 `TuiKeyHandler.handleKey()` 中，現有的 slash menu / mcp panel 分支之後，加入：

```java
if (screen.hasSelectOverlay()) {
    handleSelectOverlayInput(operation);
    return;
}
```

- [ ] **Step 6: 確認 agentCommands field 可用**

`GrimoTuiRunner` 可能沒有直接持有 `AgentCommands` 的 field。檢查建構子參數。如果沒有，需要新增注入。

替代方案：直接使用 `commandExecutor` 執行 `/agent-use <value>`：
```java
// 替代不注入 AgentCommands 的做法：
var context = commandParser.parse("agent-use " + selected.value());
commandExecutor.execute(context);
```

用 grep 確認 `agentCommands` 或 `AgentCommands` 是否已在 GrimoTuiRunner 中。如果沒有，使用 commandExecutor 方式。

- [ ] **Step 7: 編譯驗證**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java
git commit -m "feat: integrate GroupedSelect into /agent-use interactive picker"
```

---

### Task 5: Glossary 更新

**Files:**
- Modify: `docs/glossary.md`

- [ ] **Step 1: 新增 ListSelect 和 GroupedSelect 到 TUI 框架術語表**

```markdown
| **ListSelect** | List Select | 通用單選列表 widget。支援 viewport scrolling、scroll hints、linear navigation、自訂 RowRenderer（tmux drawcb pattern）。位於 `tui/widget/`。 |
| **GroupedSelect** | Grouped Select | 分群選擇 widget。組合 ListSelect，用 tmux tree → flat pattern 實現展開/收合。Accordion 模式（同時只展開一個 group）。位於 `tui/widget/`。 |
```

- [ ] **Step 2: Commit**

```bash
git add docs/glossary.md
git commit -m "docs: add ListSelect and GroupedSelect to glossary"
```

---

### Task 6: 全量驗證

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 2: 驗證測試數量**

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.widget.ListSelectTest" --info 2>&1 | grep "tests completed"`
Expected: 17 tests completed

Run: `./gradlew test --tests "io.github.samzhu.grimo.tui.widget.GroupedSelectTest" --info 2>&1 | grep "tests completed"`
Expected: 12 tests completed

- [ ] **Step 3: 驗證新檔案存在**

```bash
ls src/main/java/io/github/samzhu/grimo/tui/widget/ListSelect.java
ls src/main/java/io/github/samzhu/grimo/tui/widget/GroupedSelect.java
ls src/test/java/io/github/samzhu/grimo/tui/widget/ListSelectTest.java
ls src/test/java/io/github/samzhu/grimo/tui/widget/GroupedSelectTest.java
```
Expected: all exist

- [ ] **Step 4: Commit（若有遺漏修正）**

```bash
git add -A
git commit -m "fix: address remaining issues from verification"
```
