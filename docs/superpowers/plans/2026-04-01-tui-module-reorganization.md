# TUI 模組重組 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 將 shared/tui/ + root package 的 TUI 類搬入獨立 `tui/` top-level 模組，去掉 Grimo/Tui 前綴，用子 package 分群。

**Architecture:** 20 個源碼檔 + 10 個測試檔搬遷至 `tui/{core,view,overlay,widget,selection,screen}` 子 package。分 3 階段搬遷（module 結構 → shared/tui 12 檔 → root 8 檔），每階段保持可編譯。外部消費者（GrimoTuiRunner, GrimoStartupRunner, AgentCommands）最後統一更新 field 名。

**Tech Stack:** Java 25, Spring Boot 4.0.x, Spring Modulith 2.0.x, Gradle, JUnit 5

**Spec:** `docs/superpowers/specs/2026-04-01-tui-module-reorganization-design.md`

---

### Task 1: 建立 tui/ 模組結構（package-info.java）

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/tui/package-info.java`
- Create: `src/main/java/io/github/samzhu/grimo/tui/core/package-info.java`
- Create: `src/main/java/io/github/samzhu/grimo/tui/view/package-info.java`
- Create: `src/main/java/io/github/samzhu/grimo/tui/overlay/package-info.java`
- Create: `src/main/java/io/github/samzhu/grimo/tui/widget/package-info.java`
- Create: `src/main/java/io/github/samzhu/grimo/tui/selection/package-info.java`
- Create: `src/main/java/io/github/samzhu/grimo/tui/screen/package-info.java`

- [ ] **Step 1: 建立 7 個 package-info.java**

```java
// tui/package-info.java
@org.springframework.modulith.ApplicationModule
package io.github.samzhu.grimo.tui;

// tui/core/package-info.java
@org.springframework.modulith.NamedInterface("core")
package io.github.samzhu.grimo.tui.core;

// tui/view/package-info.java
@org.springframework.modulith.NamedInterface("view")
package io.github.samzhu.grimo.tui.view;

// tui/overlay/package-info.java
@org.springframework.modulith.NamedInterface("overlay")
package io.github.samzhu.grimo.tui.overlay;

// tui/widget/package-info.java
@org.springframework.modulith.NamedInterface("widget")
package io.github.samzhu.grimo.tui.widget;

// tui/selection/package-info.java
@org.springframework.modulith.NamedInterface("selection")
package io.github.samzhu.grimo.tui.selection;

// tui/screen/package-info.java
@org.springframework.modulith.NamedInterface("screen")
package io.github.samzhu.grimo.tui.screen;
```

- [ ] **Step 2: 編譯驗證**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A src/main/java/io/github/samzhu/grimo/tui/
git commit -m "refactor: create tui/ module structure with 6 named interfaces"
```

---

### Task 2: 搬移 shared/tui/ → tui/ 子 packages（12 檔）

**搬遷對照：**

| 原檔（shared/tui/） | 新位置 | 新類名 |
|---------------------|--------|--------|
| TuiComponent.java | tui/core/Renderable.java | Renderable |
| Layout.java | tui/core/Layout.java | (不改) |
| DisplayWidth.java | tui/core/DisplayWidth.java | (不改) |
| TuiSelector.java | tui/widget/Selector.java | Selector |
| TuiStatusBar.java | tui/widget/StatusBar.java | StatusBar |
| TuiTable.java | tui/widget/Table.java | Table |
| TuiMessage.java | tui/widget/Message.java | Message |
| TextSelection.java | tui/selection/TextSelection.java | (不改) |
| SelectionRange.java | tui/selection/SelectionRange.java | (不改) |
| ClipboardWriter.java | tui/selection/Clipboard.java | Clipboard |
| AutoScroller.java | tui/selection/AutoScroller.java | (不改) |
| BufferLine.java | tui/screen/BufferLine.java | (不改) |

**同步修改的外部檔案（更新 import + implements）：**

將所有 `import io.github.samzhu.grimo.shared.tui.X` → `import io.github.samzhu.grimo.tui.{subpkg}.X`，
將所有 `implements TuiComponent` → `implements Renderable`，
將所有 `TuiSelector.render(` → `Selector.render(`，
將所有 `TuiStatusBar.of(` → `StatusBar.of(`，
將所有 `TuiTable` → `Table` 等。

影響的外部檔案（尚未搬遷的 root package 類 + agent）：
- `GrimoContentView.java` — BufferLine, TuiComponent→Renderable
- `GrimoInputView.java` — TuiComponent→Renderable
- `GrimoStatusView.java` — DisplayWidth, TuiComponent→Renderable, TuiStatusBar→StatusBar
- `GrimoSlashMenuView.java` — DisplayWidth, TuiComponent→Renderable
- `GrimoMcpManagerView.java` — DisplayWidth
- `GrimoScreen.java` — BufferLine, Layout, SelectionRange, TextSelection
- `GrimoTuiRunner.java` — AutoScroller, ClipboardWriter→Clipboard, TextSelection
- `BannerRenderer.java` — DisplayWidth, Layout, TuiComponent→Renderable
- `agent/AgentCommands.java` — TuiTable→Table

- [ ] **Step 1: 搬移 12 個檔案到新位置**

使用 `git mv` 搬移，然後更新每個檔案的：
- package 聲明
- 類名（有改名的）
- Logger（有改名的類需更新）
- 內部 cross-import（新的 tui.* 子 package 間 import）

**重要：** 搬移後 `shared/tui/` 目錄只剩 `package-info.java`（暫時保留，Task 4 刪除）。`git add -u` 不會誤刪它，因為它沒被 `git mv`。

- [ ] **Step 2: 更新所有外部引用**

用 `grep -r "shared\.tui\." src/main/` 找出所有殘留引用，逐一更新為新路徑。
用 `grep -r "TuiComponent\|TuiSelector\|TuiStatusBar\|TuiTable\|TuiMessage\|ClipboardWriter" src/main/` 找出所有舊類名引用，更新為新類名。

- [ ] **Step 3: 搬移 9 個測試檔**

| 原測試 | 新位置 | 新類名 |
|--------|--------|--------|
| shared/tui/DisplayWidthTest.java | tui/core/DisplayWidthTest.java | (不改) |
| shared/tui/LayoutTest.java | tui/core/LayoutTest.java | (不改) |
| shared/tui/TuiSelectorTest.java | tui/widget/SelectorTest.java | SelectorTest |
| shared/tui/TuiTableTest.java | tui/widget/TableTest.java | TableTest |
| shared/tui/TuiMessageTest.java | tui/widget/MessageTest.java | MessageTest |
| shared/tui/TextSelectionTest.java | tui/selection/TextSelectionTest.java | (不改) |
| shared/tui/SelectionRangeTest.java | tui/selection/SelectionRangeTest.java | (不改) |
| shared/tui/ClipboardWriterTest.java | tui/selection/ClipboardTest.java | ClipboardTest |
| shared/tui/AutoScrollerTest.java | tui/selection/AutoScrollerTest.java | (不改) |

更新每個測試的 package 聲明、類名、import。

- [ ] **Step 4: 編譯驗證**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/io/github/samzhu/grimo/tui/ \
         src/test/java/io/github/samzhu/grimo/tui/
git add -u  # stages deletions of old shared/tui/ files
git add src/main/java/io/github/samzhu/grimo/GrimoContentView.java \
        src/main/java/io/github/samzhu/grimo/GrimoInputView.java \
        src/main/java/io/github/samzhu/grimo/GrimoStatusView.java \
        src/main/java/io/github/samzhu/grimo/GrimoSlashMenuView.java \
        src/main/java/io/github/samzhu/grimo/GrimoMcpManagerView.java \
        src/main/java/io/github/samzhu/grimo/GrimoScreen.java \
        src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java \
        src/main/java/io/github/samzhu/grimo/BannerRenderer.java \
        src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java
git commit -m "refactor: move shared/tui/ → tui/ sub-packages, rename TuiComponent→Renderable"
```

---

### Task 3: 搬移 root package Views/Screen/Banner → tui/（8 檔）

**搬遷對照：**

| 原檔（root package） | 新位置 | 新類名 |
|----------------------|--------|--------|
| GrimoContentView.java | tui/view/ContentView.java | ContentView |
| GrimoInputView.java | tui/view/InputView.java | InputView |
| GrimoStatusView.java | tui/view/StatusView.java | StatusView |
| GrimoSlashMenuView.java | tui/overlay/SlashMenu.java | SlashMenu |
| GrimoMcpManagerView.java | tui/overlay/McpPanel.java | McpPanel |
| BannerRenderer.java | tui/widget/Banner.java | Banner |
| GrimoScreen.java | tui/screen/Screen.java | Screen |
| GrimoEventLoop.java | tui/screen/EventLoop.java | EventLoop |

- [ ] **Step 1: 搬移 8 個檔案**

使用 `git mv` 搬移，然後更新每個檔案的：
- package 聲明（`package io.github.samzhu.grimo.tui.{subpkg};`）
- 類名
- Logger（有改名的類）
- import（shared.tui → tui.* 的 import 在 Task 2 已更新，現在都變成 tui 模組內部 import）
- 內部引用（如 Screen 建構子 `new GrimoEventLoop(` → `new EventLoop(`）

**Screen.java（原 GrimoScreen）特別注意：** 建構子參數和 field 類型都要更新：
- `GrimoContentView contentView` → `ContentView contentView`
- `GrimoInputView inputView` → `InputView inputView`
- `GrimoStatusView statusView` → `StatusView statusView`
- `GrimoSlashMenuView slashMenuView` → `SlashMenu slashMenu`
- `GrimoMcpManagerView mcpManagerView` → `McpPanel mcpPanel`
- 加上對應 tui 子 package 的 import

**EventLoop.java（原 GrimoEventLoop）：** 內部介面 `KeyHandler` 搬移後變成 `EventLoop.KeyHandler`。GrimoTuiRunner 中的 `implements GrimoEventLoop.KeyHandler` 必須更新為 `implements EventLoop.KeyHandler`。

**SlashMenu.java（原 GrimoSlashMenuView）：** 有內部類 `MenuItem`。GrimoTuiRunner 中 3 處引用必須更新：
- `List<GrimoSlashMenuView.MenuItem>` → `List<SlashMenu.MenuItem>`
- `new GrimoSlashMenuView.MenuItem(...)` → `new SlashMenu.MenuItem(...)`（2 處）

- [ ] **Step 2: 搬移 BannerRendererTest**

`BannerRendererTest.java` → `tui/widget/BannerTest.java`：
- package: `io.github.samzhu.grimo.tui.widget`
- 類名: `BannerTest`
- import: `Banner` 不需顯式 import（同 package）

- [ ] **Step 3: 更新 GrimoTuiRunner.java imports**

GrimoTuiRunner 是最大消費者，需更新所有搬走的類別的 import：

```java
// 新 import（取代舊的 root package 同 package 引用和 shared.tui imports）
import io.github.samzhu.grimo.tui.view.ContentView;
import io.github.samzhu.grimo.tui.view.InputView;
import io.github.samzhu.grimo.tui.view.StatusView;
import io.github.samzhu.grimo.tui.overlay.SlashMenu;
import io.github.samzhu.grimo.tui.overlay.McpPanel;
import io.github.samzhu.grimo.tui.widget.Banner;
import io.github.samzhu.grimo.tui.screen.Screen;
import io.github.samzhu.grimo.tui.screen.EventLoop;
import io.github.samzhu.grimo.tui.selection.AutoScroller;
import io.github.samzhu.grimo.tui.selection.Clipboard;
import io.github.samzhu.grimo.tui.selection.TextSelection;
```

同時更新 GrimoTuiRunner 中所有使用舊類名的地方：
- `new GrimoContentView()` → `new ContentView()`
- `new GrimoInputView()` → `new InputView()`
- `new GrimoStatusView(...)` → `new StatusView(...)`
- `new GrimoSlashMenuView(...)` → `new SlashMenu(...)`
- `new GrimoMcpManagerView()` → `new McpPanel()`
- `new GrimoScreen(...)` → `new Screen(...)`
- `new GrimoEventLoop(...)` → `new EventLoop(...)`
- `new ClipboardWriter()` → `new Clipboard()`
- Field types: `GrimoContentView contentView` → `ContentView contentView` 等
- `GrimoEventLoop.KeyHandler` → `EventLoop.KeyHandler`（已確認存在於 TuiKeyHandler inner class）
- `GrimoSlashMenuView.MenuItem` → `SlashMenu.MenuItem`（3 處：return type + 2 個 new）
- `bannerRenderer.render(...)` → `banner.render(...)`

- [ ] **Step 4: 更新 GrimoStartupRunner.java**

```java
// import
import io.github.samzhu.grimo.tui.widget.Banner;

// bean method
@Bean
Banner banner() {
    return new Banner();
}
```

- [ ] **Step 5: 更新 GrimoTuiRunner field names**

依 spec Section 5 更新 field 名稱：
- `slashMenuView` → `slashMenu`
- `mcpManagerView` → `mcpPanel`
- `bannerRenderer` → `banner`
- `clipboardWriter` → `clipboard`

`contentView`、`inputView`、`statusView`、`screen`、`eventLoop`、`textSelection`、`autoScroller` 保持不變。

grep 所有舊 field 名在 GrimoTuiRunner 中的使用位置，全部更新。

- [ ] **Step 6: 編譯驗證**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A src/main/java/io/github/samzhu/grimo/tui/ \
         src/test/java/io/github/samzhu/grimo/tui/
git add -u  # stages deletions
git add src/main/java/io/github/samzhu/grimo/GrimoTuiRunner.java \
        src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java
git commit -m "refactor: move root Views/Screen/Banner → tui/, rename fields"
```

---

### Task 4: 更新 agent allowedDependencies + 清理 shared/tui/

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/agent/package-info.java`
- Delete: `src/main/java/io/github/samzhu/grimo/shared/tui/package-info.java`
- Delete: `src/test/java/io/github/samzhu/grimo/shared/tui/` (directory)

- [ ] **Step 1: 更新 agent/package-info.java**

將 `"shared::tui"` 替換為 `"tui::widget"`：

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared", "shared::event", "shared::session", "shared::sandbox",
        "config", "mcp", "skill::registry", "skill::loader", "tui::widget"
    }
)
package io.github.samzhu.grimo.agent;
```

- [ ] **Step 2: 刪除 shared/tui/ 殘留**

```bash
rm src/main/java/io/github/samzhu/grimo/shared/tui/package-info.java
rmdir src/main/java/io/github/samzhu/grimo/shared/tui/
rmdir src/test/java/io/github/samzhu/grimo/shared/tui/ 2>/dev/null
```

- [ ] **Step 3: 執行 ModulithStructureTest**

Run: `./gradlew test --tests "*.ModulithStructureTest"`
Expected: PASS

若失敗，讀錯誤訊息調整 allowedDependencies。

- [ ] **Step 4: Commit**

```bash
git add -u
git add src/main/java/io/github/samzhu/grimo/agent/package-info.java
git commit -m "refactor: update agent deps shared::tui → tui::widget, delete shared/tui/"
```

---

### Task 5: 更新 Glossary 和 CLAUDE.md

**Files:**
- Modify: `docs/glossary.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: 更新 CLAUDE.md Architecture 表格**

`shared/` 行移除 "TUI framework"：
```markdown
| `shared/` | Domain events, session persistence, sandbox |
```

新增 `tui/` 行（在 shared 之後）：
```markdown
| `tui/` | Terminal UI framework — core (Renderable, Layout, DisplayWidth), views, overlays, widgets, selection, screen |
```

- [ ] **Step 2: 更新 glossary.md 技術元件對應表**

所有舊類名 → 新類名：
- `GrimoContentView` → `ContentView`
- `GrimoInputView` → `InputView`
- `GrimoStatusView` → `StatusView`
- `GrimoSlashMenuView` → `SlashMenu`
- `GrimoMcpManagerView` → `McpPanel`
- `GrimoScreen` → `Screen`
- `GrimoEventLoop` → `EventLoop`

- [ ] **Step 3: 更新 glossary.md TUI 框架術語表**

所有舊類名 → 新類名：
- `TuiComponent` → `Renderable`
- `TuiTable` → `Table`
- `TuiStatusBar` → `StatusBar`
- `TuiSelector` → `Selector`
- `TuiMessage` → `Message`
- `ClipboardWriter` → `Clipboard`

- [ ] **Step 4: Commit**

```bash
git add docs/glossary.md CLAUDE.md
git commit -m "docs: update glossary and CLAUDE.md for tui/ module structure"
```

---

### Task 6: 全量驗證

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 2: 驗證殘留**

Run: `grep -r "shared\.tui\|shared::tui" src/`
Expected: 無輸出

Run: `grep -r "GrimoContentView\|GrimoInputView\|GrimoStatusView\|GrimoSlashMenuView\|GrimoMcpManagerView\|GrimoScreen\|GrimoEventLoop\|BannerRenderer\|TuiComponent\|TuiSelector\|TuiStatusBar\|TuiTable\|TuiMessage\|ClipboardWriter" src/`
Expected: 無輸出

- [ ] **Step 3: 驗證目錄已清理**

Run: `ls src/main/java/io/github/samzhu/grimo/shared/tui/ 2>&1`
Expected: No such file or directory

Run: `ls src/test/java/io/github/samzhu/grimo/shared/tui/ 2>&1`
Expected: No such file or directory

- [ ] **Step 4: Commit（若有遺漏修正）**

```bash
git add -A
git commit -m "fix: address remaining TUI rename residuals"
```
