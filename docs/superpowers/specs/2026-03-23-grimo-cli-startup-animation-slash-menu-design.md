# Grimo CLI 啟動動畫與斜線命令選單設計

> **Date**: 2026-03-23
> **Status**: Approved
> **Scope**: 啟動動畫、像素風 Banner、`/` 互動式命令選單

## 概述

為 Grimo CLI 增加三項 UX 改進：
1. **啟動動畫** — 魔法風格的載入動畫，與實際初始化流程同步
2. **像素風 Banner** — 動畫結束後留下的 logo + 環境狀態
3. **`/` 斜線命令選單** — 輸入 `/` 即時彈出互動式命令選單，↑↓ 選擇、Tab 填入、輸入文字即時過濾

品牌定位為「個性化助理風格」，呼應 Grimo 魔導書（Grimoire）+ 羽毛筆 + 星光魔法陣的品牌形象。

## 技術方案

採用純 JLine 方案（方案 A），不引入額外依賴：
- Spring Shell 4.0 已內含 JLine 3，用 ANSI escape codes + Unicode block characters 實現動畫與 logo
- `/` 選單利用 JLine 的 `Completer` + `AUTO_MENU` 機制
- 動畫在 `CommandLineRunner` 中執行，與現有啟動流程整合

## 1. 啟動動畫

### 元件：`StartupAnimationRenderer`

在 `CommandLineRunner` 中執行，利用 ANSI escape codes 操控終端逐幀渲染。整體動畫約 2-3 秒，與實際載入流程同步。

### 動畫流程（5 個階段）

```
Phase 1 (0.0s) — 星光散落
  零散的 ✦ ✧ · 從螢幕各處淡入

Phase 2 (0.5s) — 星光聚集
  ✦ ✧ 逐漸往中心移動聚集

Phase 3 (1.0s) — Logo 浮現
  像素風 logo 從中心逐行顯現
  同時開始載入 agents, skills...

Phase 4 (1.5s) — 載入進度
  Logo 下方顯示載入步驟，逐行出現：
    ✦ Detecting agents...    claude-cli ✓
    ✦ Loading skills...      3 loaded ✓
    ✦ Connecting MCP...      2 servers ✓
    ✦ Restoring tasks...     1 active ✓

Phase 5 (2.5s) — 定格
  清除動畫殘留，只保留最終 banner
  （像素風 logo + 環境狀態摘要）
```

### 技術細節

- 用 `\033[H` (cursor home) + `\033[2J` (clear screen) 控制畫面
- 用 `\033[?25l` / `\033[?25h` 隱藏/顯示游標避免閃爍
- 用 `\033[38;5;Xm` 設定 256 色（青色系 #30-37，對應品牌色 #2d7d9a / #5ba3b5）
- 每幀間 `Thread.sleep(50-100ms)` 控制節奏
- 動畫在 virtual thread 上執行，不阻塞其他初始化

### 非 ANSI 終端 Fallback

啟動時檢查 `System.console() == null` 或 `TERM=dumb`，若為非互動終端則跳過動畫，直接用純文字輸出 banner。

### 與載入流程同步

動畫渲染與實際載入流程透過 `CompletableFuture` 協調：

```
CommandLineRunner 中的執行流程：

1. 啟動動畫 thread（virtual thread），播放 Phase 1-3（星光 → logo）
2. 主線程開始實際載入：
   - agentFuture  = CompletableFuture.runAsync(() -> detectAgents())
   - skillFuture  = CompletableFuture.runAsync(() -> loadSkills())
   - mcpFuture    = CompletableFuture.runAsync(() -> connectMcp())
   - taskFuture   = CompletableFuture.runAsync(() -> restoreTasks())
3. 動畫 Phase 4 逐行顯示載入步驟：
   - 每一步 wait 對應的 future 完成後才顯示 ✓
   - 例：agentFuture.join() → 顯示 "Detecting agents... claude-cli ✓"
4. 所有 future 完成 + 動畫播完 → Phase 5 定格 banner
```

- 如果載入比動畫快，動畫照常播完最小動畫時長
- 如果載入比較慢，動畫等待對應 future 完成再推進

## 2. 像素風 Logo + 環境狀態 Banner

### 元件：`BannerRenderer`

動畫結束後留在螢幕上的靜態內容。

### 佈局結構（左圖右文，類似 Claude Code）

```
  ██  ✦          Grimo v0.1.0
  ██▄▄██          claude-cli · sonnet
  ██✦✧██          ~/workspace/grimo-cli
  ▀▀▀▀▀▀          3 skills · 2 mcp · 1 task
```

**左側 — 像素風 Logo（4 行高）：**
- 用 `█▀▄▐▌▗▖` 等 Unicode block characters 拼出簡化的魔導書意象
- 顏色用 ANSI 256 色的青色系（與品牌色一致）
- 具體圖案在實作階段微調，目標是「一眼能認出是書 + 魔法元素」

**右側 — 環境狀態（4 行）：**

| 行 | 內容 | 樣式 |
|----|------|------|
| 1 | `Grimo v{version}` | 白色粗體 + 灰色版本號 |
| 2 | `{agent} · {model}` | 灰色 |
| 3 | `{工作目錄}` | 灰色，`~` 取代 `$HOME` |
| 4 | `{N} skills · {N} mcp · {N} tasks` | 灰色 |

### 資料來源

- 版本號：從 `MANIFEST.MF` 的 `Implementation-Version` 讀取，dev 環境 fallback 到 `"dev"`
- Agent/Model：從 `GrimoConfig` 取得 `defaultAgent` 和 `defaultModel`
- 工作目錄：從 `WorkspaceManager.root()` 取得
- 資源計數：從各 Registry 的 `listAll().size()` 取得

## 3. `/` 斜線命令互動式選單

### 元件：`SlashCommandCompleter`

實作 JLine 的 `Completer` 介面，在用戶輸入 `/` 時提供命令候選項。

### 行為流程

```
用戶輸入 /        → 下方立即彈出選單（所有命令 + skills）
用戶按 ↑↓         → 高亮移動到不同選項
用戶按 Tab         → 將選中命令填入輸入框
用戶繼續輸入文字    → 選單即時過濾（JLine 內建模糊匹配）
```

### 選單內容

兩個來源，平鋪顯示（不分組）：

1. **Commands** — 從 Spring Shell 的 `CommandRegistration` registry 動態取得
2. **Skills** — 從 `SkillRegistry` 動態取得

### 顯示格式

左側命令名（青色）+ 右側簡短說明（灰色），利用 JLine `Candidate` 的 `displ` + `descr` 欄位：

```
/chat              Send a message to the agent
/status            Show system status
/agent list        List available agents
/agent use         Set default agent
/skill list        List all loaded skills
/summarize         Summarize text content
```

### 技術實現

```java
/**
 * 自訂 JLine Completer，當輸入以 / 開頭時提供命令與 skill 的候選項。
 * 結合 AUTO_MENU 設定，實現輸入 / 立即彈出互動式選單的 UX。
 */
public class SlashCommandCompleter implements Completer {

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line();
        if (!buffer.startsWith("/")) return;  // 只在 / 開頭時觸發

        String prefix = buffer.substring(1);  // 去掉 / 用於過濾

        // 1. 從 Spring Shell CommandRegistration 取得所有註冊命令
        // 2. 從 SkillRegistry 取得所有 skills
        // 3. 全部包裝成 Candidate 加入 candidates list
        //    JLine 根據 prefix 自動過濾匹配
    }
}
```

### `/` 鍵自訂 Widget

建立自訂 JLine Widget，綁定到 `/` 鍵，使輸入 `/` 的同時自動觸發 completion menu：

```java
/**
 * 自訂 widget：插入 / 字元後自動觸發 completion。
 * 只在輸入框為空（游標位置 0）時觸發，避免干擾一般輸入（如檔案路徑 /foo/bar）。
 */
Widget slashAndComplete = () -> {
    // 只在行首觸發自動完成，其他位置正常插入 /
    if (reader.getBuffer().cursor() == 0) {
        reader.getBuffer().write('/');
        reader.callWidget(LineReader.COMPLETE);
    } else {
        reader.getBuffer().write('/');
    }
    return true;
};

reader.getWidgets().put("slash-complete", slashAndComplete);
reader.getKeyMaps().get(LineReader.MAIN).bind(slashAndComplete, "/");
```

關鍵 API：
- `reader.getBuffer().write(char)` — 在游標位置插入字元
- `reader.callWidget(LineReader.COMPLETE)` — 程式化觸發 completion popup
- `reader.getWidgets().put(name, widget)` — 註冊自訂 widget
- `reader.getKeyMaps().get(LineReader.MAIN).bind(widget, key)` — 綁定按鍵

### JLine 設定與 Spring Shell 整合

Spring Shell 4.0 的 `DefaultJLineShellConfiguration` 將 `LineReader` 和 `Terminal` 暴露為 Spring bean
（[原始碼](https://github.com/spring-projects/spring-shell/blob/main/spring-shell-jline/src/main/java/org/springframework/shell/jline/DefaultJLineShellConfiguration.java)），
因此可直接在 `CommandLineRunner` 中注入 `LineReader` bean 來設定自訂 widget 和 key binding。

具體整合方式：在 `GrimoStartupRunner` 的 `CommandLineRunner` bean 中，於載入完成後執行：

```
1. 注入 LineReader bean
2. 設定 LineReader options：
   - AUTO_MENU = true   — 自動顯示候選選單
   - AUTO_LIST = true   — 自動列出所有候選
   - LIST_MAX  = 20     — 最多顯示 20 項
3. 註冊 SlashCommandCompleter 到 LineReader 的 completer（透過 AggregateCompleter 與既有 completer 組合）
4. 註冊 slash-complete widget 並綁定到 / 鍵
```

注意：Spring Shell 4.0 的 `CompletionProvider` 是 command-level 的，無法做到全域 `/` 觸發，
因此必須在 JLine 層操作。`LineReader` 的 widgets 和 key maps 支援建立後修改。

## 新增元件總覽

元件放在根套件 `io.github.samzhu.grimo`（與 `GrimoStartupRunner`、`GrimoPromptProvider` 同層），
因為 `SlashCommandCompleter` 需要存取 `skill` 模組的 `SkillRegistry`。
根據 Spring Modulith 的依賴規則，`shared` 套件不應反向依賴 `skill`，
而根套件作為 application 主套件可以存取所有模組。

| 元件 | 套件 | 職責 |
|------|------|------|
| `StartupAnimationRenderer` | `io.github.samzhu.grimo` | 啟動動畫渲染 |
| `BannerRenderer` | `io.github.samzhu.grimo` | 靜態 banner 渲染 |
| `SlashCommandCompleter` | `io.github.samzhu.grimo` | `/` 命令選單補全 |

## 修改既有檔案

| 檔案 | 變更 |
|------|------|
| `GrimoStartupRunner` | 整合 `StartupAnimationRenderer` + `BannerRenderer` 到 `CommandLineRunner` |
| JLine 配置 | 新增 `@Bean` 掛載 `SlashCommandCompleter` + `AUTO_MENU` + `/` key binding |

## 實作注意事項

- **Completer 組合**：`LineReader` 可能已有既有 completer，實作時需用 `reader.getCompleter()` 確認，再透過 `AggregateCompleter` 組合
- **載入失敗顯示**：Phase 4 中若某步驟失敗（如 MCP 連線失敗），顯示紅色 `✗` + 錯誤摘要，不中斷動畫流程
- **終端偵測**：除了 `System.console() == null`，也檢查 JLine `Terminal` bean 的 `terminal.getType()` 是否為 `dumb`，避免 IDE / DevTools 環境誤判

## 不做的事項（YAGNI）

- ❌ 最近使用命令記錄 — 用戶輸入關鍵字過濾已足夠
- ❌ 分組標題顯示 — 平鋪命令列表更簡潔
- ❌ ASCII art logo — 改用 Unicode block characters 像素風
- ❌ 額外動畫依賴 — 純 ANSI escape codes 足夠

## 參考資料

- [JLine 3 Completion Wiki](https://github.com/jline/jline3/wiki/Completion)
- [JLine 3 Autosuggestions](https://github.com/jline/jline3/wiki/Autosuggestions)
- [Spring Shell 4.0 Completion Reference](https://docs.spring.io/spring-shell/reference/completion.html)
- [Spring Shell v4 Migration Guide](https://github.com/spring-projects/spring-shell/wiki/v4-migration-guide)
- [ANSI Escape Codes Reference](https://en.wikipedia.org/wiki/ANSI_escape_code)
