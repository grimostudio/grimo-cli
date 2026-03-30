# TUI Display Width 模組化 — 統一字元寬度計算與排版

> Date: 2026-03-30
> Status: Draft

## 問題

TUI 中使用 `String.format("%-Ns", ...)` 做欄位對齊，但 Java 的 `%s` 用字元數而非 terminal display width。CJK 字（佔 2 columns）和 emoji 導致表格、Banner、Status bar 歪斜。每次加新 command 都要處理對齊問題。

**根本原因：** 缺少統一的 display width 計算層。

## 設計

### Core: DisplayWidth 靜態工具

封裝 JLine `WCWidth`，提供 terminal column 寬度感知的字串操作。所有 View 和 Command 共用。

```java
package io.github.samzhu.grimo.shared.tui;

public final class DisplayWidth {
    /** 字串的 terminal column 寬度（CJK=2, ASCII=1, combining=0） */
    public static int of(String s);

    /** 右補空白到指定欄寬（左對齊） */
    public static String padRight(String s, int columns);

    /** 左補空白（右對齊） */
    public static String padLeft(String s, int columns);

    /** 置中（左右均分空白） */
    public static String center(String s, int columns);

    /** 截斷到 maxColumns，超過加 "…"（不切半個 CJK） */
    public static String truncate(String s, int maxColumns);

    /** 依 column 寬度 word-wrap，回傳多行 */
    public static List<String> wrap(String s, int maxColumns);
}
```

實作基礎：`org.jline.utils.WCWidth.wcwidth(int codePoint)` — 已在 classpath（Spring Shell → JLine）。

參考：
- [JLine WCWidth](https://github.com/jline/jline3/blob/master/terminal/src/main/java/org/jline/utils/WCWidth.java) — Unicode 16.0 East Asian Width
- [Pretext](https://github.com/chenglou/pretext) — 瀏覽器版的 measure → layout 兩階段架構啟發

### TuiTable: 表格 Builder

Command 輸出的標準化表格元件，自動處理 column 寬度對齊。

```java
package io.github.samzhu.grimo.shared.tui;

public class TuiTable {
    public enum Align { LEFT, RIGHT, CENTER }

    public static Builder builder() { ... }

    public static class Builder {
        /** 定義欄位：名稱、最小寬度、對齊方式 */
        Builder column(String name, int minWidth, Align align);
        Builder column(String name, int minWidth); // 預設 LEFT

        /** 新增一列資料 */
        Builder row(String... values);

        /** 建構並回傳格式化後的字串 */
        String build();
    }
}
```

使用範例：
```java
TuiTable.builder()
    .column("", 2)
    .column("ID", 10)
    .column("STATUS", 10)
    .column("MODEL", 20)
    .row("> ", "claude", "ready", "claude-sonnet-4-6")
    .row("  ", "gemini", "ready", "gemini-2.5-pro")
    .row("  ", "codex",  "ready", "o4-mini")
    .build();
```

輸出：
```
> claude     ready      claude-sonnet-4-6
  gemini     ready      gemini-2.5-pro
  codex      ready      o4-mini
```

### 現有 View 重構對照

| View | 現行做法 | 改用 |
|------|---------|------|
| `AgentCommands.list()` | `String.format("%-12s", ...)` | `TuiTable` |
| `BannerRenderer` | 手算空白置中 | `DisplayWidth.center()` |
| `GrimoStatusView` | 直接截字串 | `DisplayWidth.truncate()` |
| `GrimoContentView` | JLine Display 處理 wrap | `DisplayWidth.wrap()` 輔助 |
| `GrimoInputView` | `cursor` 用 char index | `DisplayWidth.of(text.substring(0, cursor))` |
| `GrimoSlashMenuView` | `String.format` padding | `DisplayWidth.padRight()` |

## 實作分期

### Phase 1：DisplayWidth + TuiTable + AgentCommands（本次）
- 建立 `DisplayWidth` 工具 + 完整測試
- 建立 `TuiTable` builder + 測試
- 修正 `AgentCommands.list()` 用 `TuiTable`
- 立即解決表格對齊問題

### Phase 2：Banner + Status + SlashMenu 重構
- `BannerRenderer` 用 `DisplayWidth.center()`
- `GrimoStatusView` 用 `DisplayWidth.truncate()`
- `GrimoSlashMenuView` 用 `DisplayWidth.padRight()`

### Phase 3：ContentView + InputView
- `GrimoContentView` wrap 加入 `DisplayWidth.wrap()`
- `GrimoInputView` cursor 計算改用 display width
- 中文輸入/顯示完全正確

## 影響範圍（Phase 1）

| 動作 | 檔案 |
|------|------|
| Create | `src/main/java/io/github/samzhu/grimo/shared/tui/DisplayWidth.java` |
| Create | `src/main/java/io/github/samzhu/grimo/shared/tui/TuiTable.java` |
| Create | `src/test/java/io/github/samzhu/grimo/shared/tui/DisplayWidthTest.java` |
| Create | `src/test/java/io/github/samzhu/grimo/shared/tui/TuiTableTest.java` |
| Modify | `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java` |
