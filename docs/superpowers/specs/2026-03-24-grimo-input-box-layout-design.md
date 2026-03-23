# Grimo CLI — 固定輸入框 Layout 設計

> 日期：2026-03-24
> 狀態：設計完成

## 背景

目前 Grimo CLI 啟動後 prompt `❯` 緊接在 banner 下方（螢幕頂部），而 status line 在最底部，中間有大片空白。參考 Claude Code 的 layout：分隔線 + prompt 固定在底部、status line 緊貼其下，輸出在上方滾動。

## 設計方案

### 1. Status 改為 2 行（分隔線 + 狀態資訊）

`StatusLineRenderer.update()` 改為輸出 2 行 `AttributedString`：

```
────────────────────────────────────────────────  (cyan 分隔線，寬度 = 終端寬度)
 claude-cli · unknown │ ~/grimo-workspace │ 1 agent · 0 skill · 0 mcp · 0 task
```

**技術說明：**
- JLine `Status.update(List<AttributedString>)` 原生支援多行
- scroll region 自動保留 2 行在底部
- LineReader 的 prompt `❯` 自然定位在分隔線正上方
- 分隔線使用 `─`（U+2500 BOX DRAWINGS LIGHT HORIZONTAL），顏色 cyan (`38;5;37`)
- 寬度 = `terminal.getWidth()`

### 2. 初始填充空行

banner 輸出後、status line 初始化後，計算並印出空行把 prompt 推到底部：

```java
int bannerLines = 6; // banner 佔用行數（吉祥物 5 行 + 資訊 4 行，加空行）
int statusLines = 2; // 分隔線 + 狀態列
int fillLines = terminal.getHeight() - bannerLines - statusLines;
for (int i = 0; i < fillLines; i++) {
    terminal.writer().println();
}
```

首次啟動時 prompt 在分隔線正上方，與 Claude Code 的 layout 一致。

### 3. 後續指令行為

Spring Shell 的 REPL 正常流程：
- 指令輸出印出後，prompt 在下一行
- 因為螢幕已被填充，輸出自然往上滾動
- prompt 基本都在接近底部的位置

## 檔案變更

| 檔案 | 動作 | 說明 |
|------|------|------|
| `StatusLineRenderer.java` | **修改** | `update()` / `restore()` 改為 2 行輸出，新增 `buildSeparatorLine()` |
| `StatusLineRendererTest.java` | **修改** | 更新測試驗證 2 行輸出 |
| `GrimoStartupRunner.java` | **修改** | banner 後加填充空行邏輯 |

## 技術參考

- [JLine Status.java — 支援多行 update()](https://github.com/jline/jline3/blob/master/terminal/src/main/java/org/jline/utils/Status.java)
- [Spring Boot Logging](https://docs.spring.io/spring-boot/reference/features/logging.html)
