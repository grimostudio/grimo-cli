# Grimo CLI — Status Line + Prompt 重設計

> 日期：2026-03-23
> 狀態：設計中

## 背景

目前 Grimo CLI 的 UX 有三個問題：

1. **prompt `[no agent] grimo:>` 佔空間但資訊價值低** — agent/model 資訊在 banner 已顯示過
2. **`/` 斜線選單造成輸入區跳動** — 直接用 ANSI escape codes 在 prompt 下方繪製，與輸入行混在一起
3. **缺乏持久狀態列** — 使用者在操作過程中無法即時看到目前使用的 agent/model 等資訊

參考 Claude Code CLI 的 UX：固定輸入框 + 底部 status line + 乾淨的 `/` 選單。

## 設計方案

### 1. Status Line — 底部持久狀態列

使用 JLine 3 內建的 `org.jline.utils.Status` API，在終端底部固定顯示一行狀態資訊。

**顯示格式：**
```
 anthropic · sonnet4.5 │ ~/grimo-workspace │ 1 agent · 0 skill · 0 mcp · 0 task
```

**設計說明：**
- `Status.getStatus(terminal)` 取得 per-terminal 單例實例（存儲在 terminal attributes 中）
- **注意：** Spring Shell 的 `LineReaderImpl` 內部也使用同一個 Status 單例做補全顯示。
  `StatusLineRenderer` 必須與 LineReader 共享此實例，在每次 prompt redisplay 後重新 `update()`
  以覆蓋 LineReader 可能留下的補全狀態。
- `status.update(List<AttributedString>)` 更新內容，支援 `AttributedString` 256 色樣式
- 狀態列不會被使用者輸入或程式輸出干擾（JLine 自動管理 scroll region）
- dumb/null 終端跳過：`Status.getStatus(terminal, false)` 在不支援的終端回傳 `null`，
  `StatusLineRenderer` 初始化時做 null check，後續 update 操作為 no-op
- **執行緒安全：** `update()` 方法加 `synchronized`，避免多來源（背景 MCP 連線、使用者指令）
  同時呼叫造成終端輸出錯亂

**樣式規劃：**
- provider/model：cyan (`38;5;37`)
- 分隔符 `│`：gray (`38;5;245`)
- workspace path：gray
- resource counts 數字：white bold，標籤：gray

**新增類別 `StatusLineRenderer`：**
```java
public class StatusLineRenderer {
    private final Terminal terminal;
    private Status status;

    // 純函數：組裝狀態列 AttributedString（方便單元測試）
    public AttributedString buildStatusLine(String provider, String model,
                                             String workspacePath,
                                             int agentCount, int skillCount,
                                             int mcpCount, int taskCount);

    // 更新終端底部狀態列
    public void update(String provider, String model, String workspacePath,
                       int agentCount, int skillCount, int mcpCount, int taskCount);

    // 暫停/恢復（供斜線選單使用）
    public void suspend();
    public void restore();
}
```

**更新時機：**
- 啟動完成後初始化
- `agent use` / `agent model` 等指令執行後更新
- skill/mcp/task 增減時更新

### 2. Prompt 簡化

將 `GrimoPromptProvider` 從 `[no agent] grimo:>` 簡化為：

```
❯
```

- 移除 `AgentProviderRegistry` 和 `GrimoConfig` 依賴
- 使用 cyan (`38;5;37`) 上色
- 極簡風格，所有狀態資訊由 status line 負責

### 3. 斜線選單 — 維持 ANSI 繪製，改用 `LineReader.printAbove()` 風格

**設計決策：** 不使用 `Status` API 渲染選單。原因：
- Status API 設計用途為低頻更新的 1-2 行持久狀態列，非高頻互動選單
- 每次按鍵觸發 `Status.update()` 會反覆調整 scroll region，造成可見閃爍
- 注意：`Status.suspend()` 後呼叫 `update()` 仍會儲存 lines（但不渲染），`restore()` 時會顯示最後一次 update 的內容

**改進方案：** 保留 ANSI 手動繪製，但修正游標管理：
- `SlashMenuRenderer` 建構子接受 `Terminal` + `StatusLineRenderer`（nullable，dumb 終端為 null）
- 選單開啟前：`StatusLineRenderer.suspend()` 暫停狀態列
- 選單繪製：**不使用 `\033[s`/`\033[u`**，改用純相對行移動 (`\033[nA` cursor up / `\033[nB` cursor down)
  搭配 `drawnLines` 計數器追蹤已繪製行數，避免 save/restore 單一 slot 衝突
- `clearDrawnLines()` 也改用 `\033[nA` 向上移動 + `\033[K` 逐行清除，不再使用 save/restore
- 選單關閉後：`cleanup()` 清除選單行 → `StatusLineRenderer.restore()` 恢復狀態列

**運作流程：**
```
1. 使用者按 `/`
2. StatusLineRenderer.suspend() 暫停狀態列（釋放底部空間）
3. SlashMenuRenderer 用 ANSI escape codes 在 prompt 下方繪製選單
4. 鍵盤事件處理：字元=過濾、↑↓=導航、Enter/Tab=確認、Esc=取消
5. cleanup() 清除選單繪製的所有行
6. StatusLineRenderer.restore() 恢復狀態列
```

### 4. Banner 保持不變

啟動動畫 + banner（含右側資訊）照常顯示。banner 是一次性的啟動畫面，status line 負責持續更新。

## 檔案變更

| 檔案 | 動作 | 說明 |
|------|------|------|
| `StatusLineRenderer.java` | **新增** | 封裝 `Status` API，管理狀態列內容與樣式 |
| `GrimoPromptProvider.java` | **修改** | 簡化為 `❯ `，移除 registry/config 依賴 |
| `SlashMenuRenderer.java` | **修改** | 加入 StatusLineRenderer suspend/restore 協調 |
| `GrimoStartupRunner.java` | **修改** | 初始化 StatusLineRenderer，注入到選單 |
| `GrimoPromptProviderTest.java` | **修改** | 更新測試（簡化後的 prompt） |
| `StatusLineRendererTest.java` | **新增** | 測試 `buildStatusLine()` 格式化邏輯 |

## 序列圖

```
啟動流程：
┌──────────┐  ┌─────────────┐  ┌──────────────────┐  ┌────────┐
│ Runner   │  │ Animator    │  │ StatusLineRenderer│  │Terminal│
└────┬─────┘  └──────┬──────┘  └────────┬─────────┘  └───┬────┘
     │ playIntro()   │                   │                │
     │──────────────>│                   │                │
     │               │  ANSI animation  │                │
     │               │──────────────────────────────────>│
     │ clearAnimation│                   │                │
     │──────────────>│                   │                │
     │ print(banner) │                   │                │
     │───────────────────────────────────────────────────>│
     │               │  update(status)  │                │
     │──────────────────────────────────>│                │
     │               │                   │ Status.update()│
     │               │                   │───────────────>│
     │               │                   │                │

斜線選單流程：
┌──────────┐  ┌──────────────────┐  ┌──────────────────┐  ┌────────┐
│ Widget   │  │ SlashMenuRenderer│  │ StatusLineRenderer│  │Terminal│
└────┬─────┘  └────────┬─────────┘  └────────┬─────────┘  └───┬────┘
     │ show(items,     │                      │                │
     │  statusLine)    │                      │                │
     │────────────────>│                      │                │
     │                 │ suspend()            │                │
     │                 │─────────────────────>│                │
     │                 │                      │ Status.suspend │
     │                 │                      │───────────────>│
     │                 │ ANSI render(menu)    │                │
     │                 │─────────────────────────────────────>│
     │                 │ [使用者操作: 過濾/導航]                │
     │                 │ ANSI render(更新)    │                │
     │                 │─────────────────────────────────────>│
     │                 │ cleanup()            │                │
     │                 │─────────────────────────────────────>│
     │                 │ restore()            │                │
     │                 │─────────────────────>│                │
     │                 │                      │ Status.restore │
     │                 │                      │───────────────>│
     │<────────────────│ return selected      │                │
```

## 技術參考

- [JLine 3 Status.java 原始碼](https://github.com/jline/jline3/blob/master/terminal/src/main/java/org/jline/utils/Status.java)
- [JLine Interactive Features 文件](https://jline.org/docs/advanced/interactive-features/)
- [JLine Example.java — Status 使用範例](https://github.com/jline/jline3/blob/master/builtins/src/test/java/org/jline/example/Example.java)
- [Spring Shell 4.0 Customization](https://docs.spring.io/spring-shell/reference/customization/index.html)
