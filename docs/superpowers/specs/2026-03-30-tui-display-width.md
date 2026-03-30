# TUI Display Width 模組化 — 統一字元寬度計算與排版

> Date: 2026-03-30
> Status: Draft

## 問題

TUI 中使用 `String.format("%-Ns", ...)` 做欄位對齊，但 Java 的 `%s` 用字元數而非 terminal display width。CJK 字（佔 2 columns）和 emoji 導致表格、Status bar、選單歪斜。每次加新 command 都要處理對齊問題。

**根本原因：** 缺少統一的 display width 計算層。

## 現況分析

| View | 現行做法 | CJK 正確 |
|------|---------|----------|
| `GrimoContentView` | JLine `columnLength()` + `columnSubSequence()` | Yes |
| `GrimoInputView` | JLine `WCWidth.wcwidth()` 計算 cursor | Yes |
| `GrimoStatusView` | `text.substring(0, cols)` 截斷 | **No** — 會切壞 CJK |
| `GrimoSlashMenuView` | `String.format("%-20s")` + `substring` | **No** — 歪斜 |
| `BannerRenderer` | hardcoded 8 空白置中 | **No** — 假設 ASCII |
| `AgentCommands.list()` | `String.format("%-12s")` | **No** — CJK 歪斜 |

## JLine SDK API（已驗證）

JLine 3.30.6，已在 classpath（Spring Shell 依賴）：

```java
// 單一 code point 的 display width（0/1/2/-1）
org.jline.utils.WCWidth.wcwidth(int codePoint)

// AttributedCharSequence（AttributedString 繼承）
int columnLength()                                    // 字串 display columns
AttributedString columnSubSequence(int start, int end) // column-based 子字串
List<AttributedString> columnSplitLength(int width)    // column-based 換行
```

參考：
- [JLine WCWidth](https://github.com/jline/jline3/blob/master/terminal/src/main/java/org/jline/utils/WCWidth.java) — Unicode 16.0 East Asian Width
- [Pretext](https://github.com/chenglou/pretext) — measure → layout 兩階段架構啟發

## 設計

### Core: `DisplayWidth` 靜態工具

封裝 JLine `WCWidth`，提供 terminal column 寬度感知的字串操作。

```java
package io.github.samzhu.grimo.shared.tui;

public final class DisplayWidth {
    /** 字串的 terminal column 寬度 */
    public static int of(String s);

    /** 右補空白到指定欄寬（左對齊） */
    public static String padRight(String s, int columns);

    /** 左補空白（右對齊） */
    public static String padLeft(String s, int columns);

    /** 置中 */
    public static String center(String s, int columns);

    /** 截斷到 maxColumns，超過加 "…"（不切半個 CJK） */
    public static String truncate(String s, int maxColumns);
}
```

### `TuiTable`: 表格 Builder

Command 輸出的標準化表格元件：

```java
package io.github.samzhu.grimo.shared.tui;

public class TuiTable {
    public enum Align { LEFT, RIGHT, CENTER }

    public static Builder builder() { ... }

    public static class Builder {
        Builder column(String name, int minWidth, Align align);
        Builder column(String name, int minWidth); // 預設 LEFT
        Builder row(String... values);
        String build();
    }
}
```

### View 重構對照

| View | 改用 | Phase |
|------|------|-------|
| `AgentCommands.list()` | `TuiTable` | Phase 1 |
| `GrimoStatusView.render()` | `DisplayWidth.truncate()` | Phase 2 |
| `GrimoSlashMenuView.render()` | `DisplayWidth.padRight()` + `DisplayWidth.truncate()` | Phase 2 |
| `BannerRenderer.render()` | `DisplayWidth.center()` | Phase 2 |
| `GrimoContentView` | 已正確（JLine API） | 不需改 |
| `GrimoInputView` | 已正確（WCWidth） | 不需改 |

## 實作分期

### Phase 1：DisplayWidth + TuiTable + AgentCommands
- 建立 `DisplayWidth` + 完整測試（ASCII、CJK、emoji、mixed）
- 建立 `TuiTable` + 測試
- `AgentCommands.list()` 改用 `TuiTable`
- 立即解決表格對齊

### Phase 2：StatusView + SlashMenuView + BannerRenderer
- `GrimoStatusView.render()` — `substring` → `DisplayWidth.truncate()`
- `GrimoSlashMenuView.render()` — `String.format` → `DisplayWidth.padRight()`
- `BannerRenderer.render()` — hardcoded 空白 → `DisplayWidth.center()`
- 全 TUI 寬度計算一致

## 影響範圍

| Phase | 動作 | 檔案 |
|-------|------|------|
| 1 | Create | `src/main/java/io/github/samzhu/grimo/shared/tui/DisplayWidth.java` |
| 1 | Create | `src/main/java/io/github/samzhu/grimo/shared/tui/TuiTable.java` |
| 1 | Create | `src/test/java/io/github/samzhu/grimo/shared/tui/DisplayWidthTest.java` |
| 1 | Create | `src/test/java/io/github/samzhu/grimo/shared/tui/TuiTableTest.java` |
| 1 | Modify | `src/main/java/io/github/samzhu/grimo/agent/AgentCommands.java` |
| 2 | Modify | `src/main/java/io/github/samzhu/grimo/GrimoStatusView.java` |
| 2 | Modify | `src/main/java/io/github/samzhu/grimo/GrimoSlashMenuView.java` |
| 2 | Modify | `src/main/java/io/github/samzhu/grimo/BannerRenderer.java` |
