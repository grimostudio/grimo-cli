# Grimo TUI Framework — 統一字元寬度計算與元件化排版

> Date: 2026-03-30
> Status: Draft

## 問題

TUI 中各 View 各自處理字串寬度，使用 `String.format("%-Ns")` 或 `text.substring(0, cols)`，不考慮 CJK 字（佔 2 columns）。每次新增 command 或調整佈局都會歪斜。缺少統一的寬度計算層和可重用的 UI 元件。

## 現況分析

| View | 現行做法 | CJK 正確 |
|------|---------|----------|
| `GrimoContentView` | JLine `columnLength()` + `columnSubSequence()` | Yes |
| `GrimoInputView` | JLine `WCWidth.wcwidth()` | Yes |
| `GrimoStatusView` | `text.substring(0, cols)` | **No** |
| `GrimoSlashMenuView` | `String.format("%-20s")` + `substring` | **No** |
| `BannerRenderer` | hardcoded 空白 | **No** |
| `AgentCommands.list()` | `String.format("%-12s")` | **No** |

## 研究基礎

### JLine SDK（已在 classpath）

```java
// 單一 code point display width（0/1/2/-1）
org.jline.utils.WCWidth.wcwidth(int codePoint)

// AttributedCharSequence 方法
int columnLength()                                    // 字串 display columns
AttributedString columnSubSequence(int start, int end) // column-based 子字串
List<AttributedString> columnSplitLength(int width)    // column-based 換行
```

### 參考架構

| 專案 | 語言 | TUI 模式 | 寬度處理 | 啟發 |
|------|------|---------|---------|------|
| [OpenCode](https://github.com/anomalyco/opencode) | TS/SolidJS | @opentui Flexbox | `Bun.stringWidth()` | box+flexGrow 佈局、InlineTool/BlockTool 模式 |
| [Bubbletea](https://github.com/charmbracelet/bubbletea) | Go | MVU | Lipgloss `Width()` | Model-Update-View 元件模式 |
| [Ratatui](https://github.com/ratatui/ratatui) | Rust | Widget trait | 內建 wcwidth | `render(area)` + Constraint 佈局 |
| [Lipgloss](https://github.com/charmbracelet/lipgloss) | Go | String-in/out | wcwidth | `JoinHorizontal/Vertical` + padding/border |
| [AI SDK Elements](https://elements.ai-sdk.dev) | React | Component lib | Browser | Message/PromptInput/ModelSelector 元件模式 |
| [Pretext](https://github.com/chenglou/pretext) | TS | Measure→Layout | Canvas pixel | 兩階段分離啟發（但是瀏覽器，不適用 terminal） |

## 設計

### 設計原則

**任何 TuiComponent 只要接收 `width`，render 出來的每一行保證剛好那個寬度。元件永遠不直接碰 `String.format` 或 `substring`。**

### 架構總覽

```
Layer 1: DisplayWidth（寬度計算）
  └─ of(), padRight(), padLeft(), center(), truncate(), fill(), wrap()

Layer 2: TuiComponent 契約 + Layout
  ├─ TuiComponent interface: render(width) → List<AttributedString>
  └─ Layout: vertical/horizontal split with Slot(fixed/fill) + gap

Layer 3: Building Blocks（可重用元件）
  ├─ TuiTable      — 表格（agent-list, skill-list）
  ├─ TuiSelector   — 選擇器（slash menu）
  ├─ TuiMessage    — 對話訊息（inline/block 兩種模式）
  └─ TuiStatusBar  — 狀態列（截斷感知）

Layer 4: View 重構
  ├─ GrimoContentView  → 用 TuiMessage
  ├─ GrimoStatusView   → 用 TuiStatusBar
  ├─ GrimoSlashMenuView → 用 TuiSelector
  ├─ BannerRenderer     → 用 DisplayWidth.center()
  └─ GrimoScreen        → 用 Layout.vertical()
```

### Layer 1: DisplayWidth

```java
package io.github.samzhu.grimo.shared.tui;

/**
 * Terminal column 寬度感知的字串操作。
 * 封裝 JLine WCWidth，所有 TUI 元件共用。
 * CJK 字 = 2 columns, ASCII = 1 column, combining = 0。
 */
public final class DisplayWidth {
    /** 字串的 terminal column 寬度 */
    public static int of(String s);

    /** 右補空白到指定欄寬（左對齊） */
    public static String padRight(String s, int columns);

    /** 左補空白（右對齊） */
    public static String padLeft(String s, int columns);

    /** 置中（左右均分空白） */
    public static String center(String s, int columns);

    /** 截斷到 maxColumns，超過加 "…"（不切半個 CJK） */
    public static String truncate(String s, int maxColumns);

    /** 產生指定寬度的空白 */
    public static String fill(int columns);

    /** 依 column 寬度 word-wrap，回傳多行 */
    public static List<String> wrap(String s, int maxColumns);
}
```

### Layer 2: TuiComponent + Layout

**元件不管高度，只管寬度。** 借鑑 OpenCode `<scrollbox>` 模式：元件 render 自然高度，容器負責捲動/截斷。

```
渲染流程（借鑑 OpenCode）：
1. 元件 render(width) → 自然內容（可能 100 行，每行剛好 width columns）
2. Layout 分配空間 → 這個區域有 30 行高
3. 容器（GrimoContentView）→ 只顯示 [scrollOffset..scrollOffset+30]
```

```java
package io.github.samzhu.grimo.shared.tui;

/**
 * TUI 元件契約。
 * 保證：回傳的每行 columnLength == width。
 * 行數 = 自然高度（不限），由容器決定顯示多少。
 *
 * @see <a href="https://github.com/anomalyco/opencode">OpenCode — scrollbox 容器模式</a>
 */
public interface TuiComponent {
    List<AttributedString> render(int width);
}
```

```java
package io.github.samzhu.grimo.shared.tui;

/**
 * 佈局切分計算。類似 Ratatui Constraint + OpenCode flexGrow。
 * 純算術，不持有狀態。
 */
public final class Layout {
    /** 垂直切分（行數分配） */
    public static int[] vertical(int totalRows, int gap, Slot... slots);

    /** 水平切分（欄寬分配） */
    public static int[] horizontal(int totalCols, int gap, Slot... slots);

    public sealed interface Slot {
        /** 固定值 */
        record Fixed(int size) implements Slot {}
        /** 填滿剩餘空間（多個 Fill 均分） */
        record Fill() implements Slot {}
    }
}
```

### Layer 3: Building Blocks

#### TuiTable

```java
/**
 * 寬度感知的表格。Command 輸出用。
 * Slot.Fixed 固定欄寬，Slot.Fill 填滿剩餘。
 */
TuiTable.builder()
    .column("", 2)                          // indicator, fixed 2
    .column("ID", 12)                       // fixed 12
    .column("STATUS", 10)                   // fixed 10
    .column("MODEL", 0)                     // 0 = fill remaining
    .row("> ", "claude", "ready", "claude-sonnet-4-6")
    .build(width);  // → String，每行寬度 == width
```

#### TuiSelector

```java
/**
 * 可捲動選擇器。Slash menu、agent 選擇用。
 * 借鑑 OpenCode 的 InlineTool 模式。
 */
TuiSelector.builder()
    .items(menuItems)
    .selected(selectedIndex)
    .visible(maxVisible)
    .format((item, active, w) -> {
        String prefix = active ? "> " : "  ";
        return DisplayWidth.padRight(prefix + item.name(), w);
    })
    .build(width);  // → List<AttributedString>
```

#### TuiMessage

```java
/**
 * 對話訊息格式化。
 * inline: 單行系統訊息（Skill loaded, Worktree created）
 * block: 多行內容（agent 回覆、錯誤）
 */
public final class TuiMessage {
    /** 單行：● Skill(code-review) └ loaded */
    public static List<AttributedString> inline(
        String icon, String title, String detail, int width);

    /** 多行：agent 回覆、錯誤訊息 */
    public static List<AttributedString> block(
        Role role, String content, int width);

    public enum Role { USER, AGENT, ERROR, SYSTEM }
}
```

#### TuiStatusBar

```java
/**
 * 單行狀態列，寬度感知截斷。
 */
public final class TuiStatusBar {
    /** 產生一行，寬度精確 == width */
    public static AttributedString of(
        String text, AttributedStyle style, int width);
}
```

### Layer 4: View 重構

**容器 vs 元件的職責分離：**

```
GrimoScreen（Layout 分配高度）
  ├─ BannerRenderer.render(width)      → N 行 → 全顯示（固定區）
  ├─ ContentView（scrollable 容器）
  │    ├─ 內部持有所有 TuiMessage 渲染結果
  │    ├─ Layout 告訴它有 height 行空間
  │    └─ 只輸出 [scrollOffset..scrollOffset+height] 切片
  ├─ InputView.render(width)           → 2 行 → 全顯示（固定區）
  └─ StatusView.render(width)          → 1 行 → 全顯示（固定區）
```

| 現有 View | 改動 |
|-----------|------|
| `GrimoScreen` | `Layout.vertical()` 計算各區域高度，取代手算 |
| `GrimoContentView` | 已正確，加 `implements TuiComponent`，用 `TuiMessage` 格式化新訊息。作為 scrollable 容器，內部管理 scrollOffset |
| `GrimoInputView` | 已正確，加 `implements TuiComponent` |
| `GrimoStatusView` | `substring` → `TuiStatusBar.of()`，加 `implements TuiComponent` |
| `GrimoSlashMenuView` | `String.format` → `TuiSelector`，加 `implements TuiComponent`。作為 scrollable 容器，內部管理 visible window |
| `BannerRenderer` | hardcoded 空白 → `DisplayWidth.center()`，加 `implements TuiComponent` |
| `AgentCommands.list()` | `String.format` → `TuiTable` |

## 影響範圍

| 動作 | 檔案 | Package |
|------|------|---------|
| Create | `DisplayWidth.java` | `shared.tui` |
| Create | `TuiComponent.java` | `shared.tui` |
| Create | `Layout.java` | `shared.tui` |
| Create | `TuiTable.java` | `shared.tui` |
| Create | `TuiSelector.java` | `shared.tui` |
| Create | `TuiMessage.java` | `shared.tui` |
| Create | `TuiStatusBar.java` | `shared.tui` |
| Create | `DisplayWidthTest.java` | test |
| Create | `LayoutTest.java` | test |
| Create | `TuiTableTest.java` | test |
| Create | `TuiSelectorTest.java` | test |
| Create | `TuiMessageTest.java` | test |
| Modify | `GrimoScreen.java` | root |
| Modify | `GrimoStatusView.java` | root |
| Modify | `GrimoSlashMenuView.java` | root |
| Modify | `BannerRenderer.java` | root |
| Modify | `AgentCommands.java` | `agent` |

## 實作順序

```
Task 1: DisplayWidth + tests              ← 基礎，所有人依賴
Task 2: TuiComponent + Layout + tests     ← 契約 + 佈局
Task 3: TuiTable + tests + AgentCommands  ← 立即可見效果
Task 4: TuiStatusBar + tests + StatusView ← 狀態列修正
Task 5: TuiSelector + tests + SlashMenu   ← 選單修正
Task 6: TuiMessage + tests                ← 訊息元件
Task 7: BannerRenderer 重構              ← Banner 修正
Task 8: GrimoScreen + 全 View 加 TuiComponent 介面 ← 統一介面
```
