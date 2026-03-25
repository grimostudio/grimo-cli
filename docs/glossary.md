# Grimo CLI 術語表（Glossary）

> 統一專案中使用的名詞定義，搭配佈局示意圖標註。

## 佈局總覽

```
┌──────────────────────────────────────────────────┐
│                                                  │
│   ✦     Grimo v0.0.1                            │
│ ▄████▄  claude-cli · unknown                    │
│ █●██●█  ~/grimo-workspace                       │  ← Content 區
│ ██████  1 agent · 0 skill                       │
│ ▀▄▀▀▄▀                                          │
│                                                  │
│ ❯ 你好                                           │  ← 使用者輸入紀錄
│                                                  │
│ ⏺ 你好！有什麼我可以幫你的嗎                      │  ← AI 回覆紀錄
│                                                  │
│ ┌──────────────────────────────────────────────┐ │
│ │  /agent        List all agents               │ │  ← 斜線指令選單
│ │  /agent list   List all agents               │ │    （Modal Overlay）
│ │  /agent model  Switch default model          │ │
│ └──────────────────────────────────────────────┘ │
├──────────────────────────────────────────────────┤  ← 分隔線
│ ❯ /ag█                                          │  ← Input 區
├──────────────────────────────────────────────────┤  ← 分隔線
│ claude-cli · unknown │ ~/grimo │ 1 agent · 0 mcp│  ← Status 區
└──────────────────────────────────────────────────┘
```

## 佈局區域

| 名詞 | 英文 | 說明 |
|------|------|------|
| **Content 區** | Content Area | 佔據畫面上方大部分空間的可滾動區域。啟動時顯示 Banner，之後累積使用者輸入紀錄、AI 回覆、命令輸出。內容往上滾動，舊內容（包含 Banner）最終被推出畫面。 |
| **Input 區** | Input Area | 固定在底部的單行輸入框，顯示 `❯` 提示符號。使用者在此輸入文字或斜線指令。**永遠不移動**。 |
| **Status 區** | Status Bar | 固定在最底行的狀態列，顯示 agent、model、workspace、資源計數。**永遠不移動**。 |
| **斜線指令選單** | Slash Command Menu | 輸入 `/` 時以 Modal Overlay 浮動出現在 Input 區正上方（覆蓋 Content 區底部）。最多 5 項，即時過濾，↑↓ 選擇。選取或取消後消失。**不影響 Input 區和 Status 區位置**。 |
| **分隔線** | Separator | Input 區上下的水平線（`─`），視覺上區隔 Content、Input、Status 三個區域。 |

## 互動元素

| 名詞 | 英文 | 說明 |
|------|------|------|
| **斜線指令** | Slash Command | 所有以 `/` 開頭的指令的統稱。包含 Skills（`/brainstorming`）、應用命令（`/agent list`）、系統命令（`/exit`）。觸發條件：行首 `/` 或 `空格 + /`。 |
| **使用者輸入紀錄** | User Input Record | Content 區中的使用者輸入歷史，以 `❯` 前綴 + 深色背景行顯示。 |
| **AI 回覆紀錄** | AI Reply Record | Content 區中的 AI 回應，以 `⏺` 前綴 + 一般文字顯示。 |
| **命令輸出** | Command Output | 斜線指令執行後的結果，直接顯示在 Content 區（無前綴）。 |
| **Banner** | Banner | 啟動時顯示的吉祥物 + 版本 + 環境資訊（5 行），位於 Content 區底部。隨對話累積逐漸被推出畫面。 |

## 樣式

| 名詞 | 英文 | 說明 |
|------|------|------|
| **系統標誌色** | Brand Accent Color | Steel blue，ANSI 256 color 67（`#5F87AF`）。用於：吉祥物 Logo、選中的斜線指令文字、Input 區中已填入的斜線指令名稱。 |
| **預設文字色** | Default Text Color | Terminal 預設前景色。用於：未選中的斜線指令、一般文字。 |

## Session 與歷史

| 名詞 | 英文 | 說明 |
|------|------|------|
| **Session** | Session | 一次 TUI 啟動到結束的對話歷程。以 UUID 識別，存為 JSONL 檔案。可透過 `--resume` 恢復。 |
| **Session 檔案** | Session File | `~/grimo-workspace/projects/<encoded-cwd>/sessions/<session-uuid>.jsonl`。對齊 Claude Code 結構。每行一個 JSON 物件（含 uuid、parentUuid 支援對話樹），append-only。 |
| **滾動** | Scroll | Content 區支援滑鼠滾輪 / Mac 觸控板兩指滾動，瀏覽已消失的歷史對話。自動跟隨模式：在底部時新內容自動顯示，滾動中途不自動跳轉。 |

## Domain Events（Spring Modulith）

| 名詞 | 英文 | 說明 |
|------|------|------|
| **AgentChangedEvent** | Agent Changed Event | agent/model 切換時由命令層發布。TUI 監聽後更新 Status 區。 |
| **ResourceCountChangedEvent** | Resource Count Changed Event | skill/mcp/task 數量變更時發布。TUI 監聽後更新 Status 區資源計數。 |

已有的 events：`IncomingMessageEvent`、`OutgoingMessageEvent`、`TaskExecutionEvent`

## 技術元件對應

| 佈局區域 | 實作元件 | Spring Shell / JLine 類別 |
|----------|----------|--------------------------|
| Content 區 | `GrimoContentView` | 自建，extends `BoxView` |
| Input 區 | `GrimoInputView` | 自建，extends `BoxView`（InputView 無 setText） |
| Status 區 | `StatusBarView` | Spring Shell 內建 |
| 斜線指令選單 | `GrimoSlashCommandListView` | 自建，透過 `TerminalUI.setModal()` 顯示 |
| 分隔線 | `BoxView.setShowBorder(true)` | Spring Shell 內建 |
| 整體佈局 | `GridView` | Spring Shell 內建（3 row：flex + 1 + 1） |
| TUI 引擎 | `TerminalUI` | Spring Shell 內建 |
