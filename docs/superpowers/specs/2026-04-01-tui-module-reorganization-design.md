# TUI 模組重組：命名正名 + 目錄結構

> Sub-project 2 of 4: TUI 重構系列。將 TUI 相關程式碼從 `shared/tui/` 和 root package 集中到獨立 `tui/` top-level 模組，建立清晰的子 package 結構和命名體系。

## 目標

1. 建立 `tui/` top-level Spring Modulith 模組，取代 `shared/tui/` named interface
2. 將 root package 的 View/Screen/EventLoop/Banner 搬入 `tui/`
3. 去掉 `Grimo`/`Tui` 前綴 — package 即上下文
4. 用子 package 分群：core、view、overlay、widget、selection、screen
5. 命名體系涵蓋 SP3 未來組件擴充

## 背景

SP1 已將 `home/`、`project/`、`config/` 提升為 top-level 模組。`shared/` 剩下 event、session、sandbox、**tui** — 其中 tui 只被 root package 使用，不是真正共用，應獨立為模組。

目前 TUI 相關程式碼散落兩處：
- `shared/tui/` — 12 個工具類（DisplayWidth、Layout、Selector 等）
- root package — 8 個應用元件（Views、Screen、EventLoop、Banner）

唯一例外：`agent/AgentCommands.java` import `TuiTable` 用於格式化命令輸出。

## 設計

### 1. 模組結構

```
io.github.samzhu.grimo.tui/                  ← @ApplicationModule
├── core/                                     ← @NamedInterface("core")
│   ├── Renderable.java                       (原 TuiComponent)
│   ├── Layout.java
│   └── DisplayWidth.java
│
├── view/                                     ← @NamedInterface("view")
│   ├── ContentView.java                      (原 GrimoContentView)
│   ├── InputView.java                        (原 GrimoInputView)
│   └── StatusView.java                       (原 GrimoStatusView)
│
├── overlay/                                  ← @NamedInterface("overlay")
│   ├── SlashMenu.java                        (原 GrimoSlashMenuView)
│   └── McpPanel.java                         (原 GrimoMcpManagerView)
│
├── widget/                                   ← @NamedInterface("widget")
│   ├── Selector.java                         (原 TuiSelector)
│   ├── StatusBar.java                        (原 TuiStatusBar)
│   ├── Table.java                            (原 TuiTable)
│   ├── Message.java                          (原 TuiMessage)
│   └── Banner.java                           (原 BannerRenderer)
│
├── selection/                                ← @NamedInterface("selection")
│   ├── TextSelection.java
│   ├── SelectionRange.java
│   ├── Clipboard.java                        (原 ClipboardWriter)
│   └── AutoScroller.java
│
└── screen/                                   ← @NamedInterface("screen")
    ├── Screen.java                           (原 GrimoScreen)
    ├── EventLoop.java                        (原 GrimoEventLoop)
    └── BufferLine.java
```

### 2. 完整命名對照表

| Before | After | Package | 變更類型 |
|--------|-------|---------|---------|
| `shared.tui.TuiComponent` | `tui.core.Renderable` | core | rename + move |
| `shared.tui.Layout` | `tui.core.Layout` | core | move only |
| `shared.tui.DisplayWidth` | `tui.core.DisplayWidth` | core | move only |
| `GrimoContentView` | `tui.view.ContentView` | view | rename + move |
| `GrimoInputView` | `tui.view.InputView` | view | rename + move |
| `GrimoStatusView` | `tui.view.StatusView` | view | rename + move |
| `GrimoSlashMenuView` | `tui.overlay.SlashMenu` | overlay | rename + move |
| `GrimoMcpManagerView` | `tui.overlay.McpPanel` | overlay | rename + move |
| `shared.tui.TuiSelector` | `tui.widget.Selector` | widget | rename + move |
| `shared.tui.TuiStatusBar` | `tui.widget.StatusBar` | widget | rename + move |
| `shared.tui.TuiTable` | `tui.widget.Table` | widget | rename + move |
| `shared.tui.TuiMessage` | `tui.widget.Message` | widget | rename + move |
| `BannerRenderer` | `tui.widget.Banner` | widget | rename + move |
| `shared.tui.TextSelection` | `tui.selection.TextSelection` | selection | move only |
| `shared.tui.SelectionRange` | `tui.selection.SelectionRange` | selection | move only |
| `shared.tui.ClipboardWriter` | `tui.selection.Clipboard` | selection | rename + move |
| `shared.tui.AutoScroller` | `tui.selection.AutoScroller` | selection | move only |
| `GrimoScreen` | `tui.screen.Screen` | screen | rename + move |
| `GrimoEventLoop` | `tui.screen.EventLoop` | screen | rename + move |
| `shared.tui.BufferLine` | `tui.screen.BufferLine` | screen | move only |

### 3. 測試對照表

| Before | After |
|--------|-------|
| `test/.../shared/tui/DisplayWidthTest.java` | `test/.../tui/core/DisplayWidthTest.java` |
| `test/.../shared/tui/LayoutTest.java` | `test/.../tui/core/LayoutTest.java` |
| `test/.../shared/tui/TuiSelectorTest.java` | `test/.../tui/widget/SelectorTest.java` |
| `test/.../shared/tui/TuiTableTest.java` | `test/.../tui/widget/TableTest.java` |
| `test/.../shared/tui/TuiMessageTest.java` | `test/.../tui/widget/MessageTest.java` |
| `test/.../shared/tui/TextSelectionTest.java` | `test/.../tui/selection/TextSelectionTest.java` |
| `test/.../shared/tui/SelectionRangeTest.java` | `test/.../tui/selection/SelectionRangeTest.java` |
| `test/.../shared/tui/ClipboardWriterTest.java` | `test/.../tui/selection/ClipboardTest.java` |
| `test/.../shared/tui/AutoScrollerTest.java` | `test/.../tui/selection/AutoScrollerTest.java` |
| `test/.../BannerRendererTest.java` | `test/.../tui/widget/BannerTest.java` |

### 4. Modulith 設定

**新模組 `tui/package-info.java`：**
```java
@org.springframework.modulith.ApplicationModule
package io.github.samzhu.grimo.tui;
```

**子 package NamedInterfaces：**

每個子 package 建立 `package-info.java`，讓外部模組可精確宣告依賴：
- `tui.core` → `@NamedInterface("core")`
- `tui.view` → `@NamedInterface("view")`
- `tui.overlay` → `@NamedInterface("overlay")`
- `tui.widget` → `@NamedInterface("widget")`
- `tui.selection` → `@NamedInterface("selection")`
- `tui.screen` → `@NamedInterface("screen")`

**allowedDependencies 更新：**

| 模組 | Before | After |
|------|--------|-------|
| `agent` | `shared::tui` | `tui::widget` |
| `shared` | (無 tui 相關) | 移除 `@NamedInterface("tui")` 的 package-info |

> 設計說明：`agent/AgentCommands.java` 只 import `TuiTable`（→ `Table`），所以 agent 只需 `tui::widget`。

### 5. 外部消費者 import 更新

**GrimoTuiRunner.java（root package，最大消費者）：**

| Before | After |
|--------|-------|
| `import ...shared.tui.AutoScroller` | `import ...tui.selection.AutoScroller` |
| `import ...shared.tui.ClipboardWriter` | `import ...tui.selection.Clipboard` |
| `import ...shared.tui.TextSelection` | `import ...tui.selection.TextSelection` |
| `import ...GrimoContentView` | `import ...tui.view.ContentView` |
| `import ...GrimoInputView` | `import ...tui.view.InputView` |
| `import ...GrimoStatusView` | `import ...tui.view.StatusView` |
| `import ...GrimoSlashMenuView` | `import ...tui.overlay.SlashMenu` |
| `import ...GrimoMcpManagerView` | `import ...tui.overlay.McpPanel` |
| `import ...GrimoScreen` | `import ...tui.screen.Screen` |
| `import ...GrimoEventLoop` | `import ...tui.screen.EventLoop` |
| `import ...BannerRenderer` | `import ...tui.widget.Banner` |

所有 field 名、局部變數名也隨類名更新（如 `contentView` → `contentView` 不改，`slashMenuView` → `slashMenu`，`mcpManagerView` → `mcpPanel`，`bannerRenderer` → `banner`，`clipboardWriter` → `clipboard`）。

**GrimoStartupRunner.java：**

| Before | After |
|--------|-------|
| `import ...BannerRenderer` | `import ...tui.widget.Banner` |
| bean: `BannerRenderer bannerRenderer()` | `Banner banner()` |

**agent/AgentCommands.java：**

| Before | After |
|--------|-------|
| `import ...shared.tui.TuiTable` | `import ...tui.widget.Table` |

### 6. 內部 cross-reference 更新

搬入 `tui/` 後，原本在同一個 root package 或 shared/tui/ 的類別現在分散在不同子 package，需要新增跨子 package 的 import。例如：

- `ContentView` implements `Renderable` → 需 `import ...tui.core.Renderable`
- `Screen` uses `BufferLine` → 同 package（screen/），不需 import
- `Screen` uses `Layout`, `TextSelection`, `SelectionRange` → 需 cross-package import
- `Banner` uses `DisplayWidth`, `Layout`, `Renderable` → 需 `import ...tui.core.*`

這些都是 tui 模組內部的 import，不涉及 Modulith 跨模組邊界。

### 7. SP3 擴充預留

SP3 新組件的歸屬已明確：

| SP3 組件 | 歸屬 package | 說明 |
|----------|-------------|------|
| 通用選擇器 | `tui/widget/` | 單選/多選 list |
| 兩層選擇器 | `tui/widget/` | category → item 的聯動選擇 |
| FuzzyPicker | `tui/widget/` | 模糊搜尋選擇器 |
| 新畫面區域 | `tui/view/` | 如果需要新的固定區域 |
| 新浮動面板 | `tui/overlay/` | 如 settings panel |

### 8. Glossary + CLAUDE.md 更新

**CLAUDE.md Architecture 表格：**
- 移除 `shared/` 行中的 "TUI framework"
- 新增或更新 `tui/` 行的描述

**Glossary 技術元件對應表：** 所有 `GrimoXxxView` → 新名對照更新。

**Glossary TUI 框架術語表：** 更新所有類名（TuiComponent → Renderable 等）。

## 不在範圍內

- GrimoTuiRunner 拆解（SP4）
- 新 UI 組件設計實作（SP3）
- Event 歸屬出版者（SP4）
- 類別內部邏輯不改，只搬 package + 改名

## 風險與緩解

| 風險 | 緩解 |
|------|------|
| 20+ 個類改名搬遷，遺漏 import | Compiler 報錯 + `grep` 驗證 |
| `Renderable` 取代 `TuiComponent` 影響所有實作類 | 機械替換，implements 聲明更新 |
| GrimoTuiRunner field 名更新遺漏 | `grep` 驗證所有 field/local var |
| tui 子 package 的 NamedInterface 宣告錯誤 | ModulithStructureTest 驗證 |

## 驗收標準

1. `./gradlew build` 全部通過
2. `ModulithStructureTest` 通過
3. `grep -r "shared\.tui\|shared::tui" src/` 回傳空
4. `grep -r "GrimoContentView\|GrimoInputView\|GrimoStatusView\|GrimoSlashMenuView\|GrimoMcpManagerView\|GrimoScreen\|GrimoEventLoop\|BannerRenderer\|TuiComponent\|TuiSelector\|TuiStatusBar\|TuiTable\|TuiMessage\|ClipboardWriter" src/` 回傳空
5. `src/main/java/.../shared/tui/` 目錄不存在
6. `src/test/java/.../shared/tui/` 目錄不存在
7. Glossary 和 CLAUDE.md 反映新結構
