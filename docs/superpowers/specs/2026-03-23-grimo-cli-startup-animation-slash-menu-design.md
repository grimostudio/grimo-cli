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

### 與載入流程同步

- 動畫渲染與實際載入流程同步 — agent 偵測完成時動畫剛好顯示到對應步驟
- 如果載入比動畫快，動畫照常播完
- 如果載入比較慢，動畫等待載入完成再推進到下一步

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

### JLine 設定

透過自訂 `LineReader` 配置（`@Bean` 或 `BeanPostProcessor`）：

| 選項 | 值 | 說明 |
|------|----|------|
| `AUTO_MENU` | `true` | 自動顯示候選選單 |
| `AUTO_LIST` | `true` | 自動列出所有候選 |
| `LIST_MAX` | `20` | 最多顯示 20 項 |

另外需綁定自訂 widget 到 `/` 鍵：輸入 `/` 字元的同時自動觸發 completion。

### 與 Spring Shell 整合

- Spring Shell 4.0 的 `CompletionProvider` 是 command-level 的，無法做到全域 `/` 觸發
- 需要在 JLine 層直接操作：透過 `JLineShellAutoConfiguration` 暴露的 `LineReader` 掛載自訂 `Completer` 和 key binding
- `SlashCommandCompleter` 作為 `@Bean` 註冊

## 新增元件總覽

| 元件 | 套件 | 職責 |
|------|------|------|
| `StartupAnimationRenderer` | `io.github.samzhu.grimo.shared.ui` | 啟動動畫渲染 |
| `BannerRenderer` | `io.github.samzhu.grimo.shared.ui` | 靜態 banner 渲染 |
| `SlashCommandCompleter` | `io.github.samzhu.grimo.shared.ui` | `/` 命令選單補全 |

## 修改既有檔案

| 檔案 | 變更 |
|------|------|
| `GrimoStartupRunner` | 整合 `StartupAnimationRenderer` + `BannerRenderer` 到 `CommandLineRunner` |
| JLine 配置 | 新增 `@Bean` 掛載 `SlashCommandCompleter` + `AUTO_MENU` + `/` key binding |

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
