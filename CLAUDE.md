# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

遵循 First Principles Thinking 思考根本問題, 不是只解決表面狀況

你上網查證一下比在那邊試要快

了解功能需求或是目的後, 或是為什麼考慮這樣處理, 要記得寫設計說明註解

需要的開發資訊 SDK 都上網再確認過一次

參考資料要附上來源

plan 中 畫出流程或循序圖 來確認正確性

log 不足以確認根本問題時, 請嘗試多加 log, 重新測試後, 再來釐清問題, 才做修改計劃提高正確性

套件都要使用到最新的

開發過程中整理 docs/glossary.md, 這樣比較好跟用戶對焦 跟釐清

採用 Event-driven 設計, 透過 Spring Modulith 利用 event 解耦

## Project Overview

Grimo is a locally-hosted AI assistant platform built as a Spring Shell CLI. It provides pluggable communication channels (Telegram, LINE), task scheduling with Markdown persistence, and a unified agent provider model that treats LLM APIs and CLI tools equally. Design spec: `docs/superpowers/specs/2026-03-22-grimo-openclaw-design-zh-TW.md`.

## Build & Run Commands

```bash
./gradlew build          # Full build (compile + test)
./gradlew test           # Run all tests (JUnit 5)
./gradlew bootRun        # Run the application
./gradlew nativeCompile  # Build GraalVM native image (requires GraalVM 25+)
./gradlew nativeTest     # Run tests as native image
```

Run a single test class:
```bash
./gradlew test --tests "io.github.samzhu.grimo.SomeTest"
```

## Tech Stack

- **Java 25** (Virtual Threads), **Spring Boot 4.0.x**, **Spring Modulith 2.0.x**, **Spring Shell 4.0.x**
- **Spring AI 2.0.0-M3** — used as library (not starter) for runtime dynamic agent creation
- **MCP Java SDK** — used directly (not Spring AI MCP Starter) for runtime dynamic management
- Gradle Kotlin DSL, single-module build

## Architecture

Single Gradle module. Module boundaries enforced by **Spring Modulith** package structure under `io.github.samzhu.grimo`:

| Package | Purpose |
|---------|---------|
| `channel/` | Pluggable adapters (Telegram, LINE) implementing `ChannelAdapter` interface |
| `task/` | Cron scheduling via Spring `TaskScheduler` + Markdown file persistence |
| `agent/` | Unified `AgentProvider` interface, provider registry (`ConcurrentHashMap`, not Spring DI), auto-detection, routing |
| `skill/` | `SKILL.md` hot-loading from workspace, skill registry, built-in skills |
| `mcp/` | MCP client connections to external tool servers |
| `home/` | GrimoHome — 全域 app 資料目錄 (~/.grimo) 管理 |
| `project/` | ProjectContext — CWD 專案身份、per-project 資料目錄 |
| `config/` | GrimoConfig — YAML 設定讀寫 |
| `shared/` | Domain events, session persistence, sandbox |
| `tui/` | Terminal UI — `TuiAdapter`（TUI 入口，建構元件、啟動 event loop）、`TuiKeyHandler`（鍵盤/滑鼠路由）、`TuiEventBridge`（domain event → TUI 更新）、core (Renderable, Layout, DisplayWidth), views, overlays, widgets, selection, screen |

### 指令命名慣例

指令名稱用 **hyphen 連接**（`/noun-verb`），不用空格子指令。唯一例外是 `/mcp`（歷史因素，對齊 Claude Code `claude mcp add` 語法）。

```
✅ /agent-use, /agent-list, /skill-list, /task-create, /session-resume
❌ /agent use, /session resume（空格子指令）
```

無參數時若需互動選擇，開 overlay（如 `/agent-use`、`/session-resume`）。

### Key Design Decisions

- **Library over Starter**: LLM providers, MCP servers, and channels are managed as plain Java objects at runtime (not Spring beans), enabling dynamic add/remove via CLI commands.
- **Event-driven**: Inter-module communication用 Spring `@EventListener`（**不是** `@ApplicationModuleListener`，見下方說明）。`IncomingMessageEvent` / `OutgoingMessageEvent` 為 Channel adapter 訊息 port；TUI 走六角架構管線（`InputPort` → `InputHandler` → `CommandDispatcher`）。
- **Markdown persistence**: Tasks stored as `.md` files with YAML frontmatter in `~/.grimo/tasks/`.
- **Spring Shell is the main entry point**, not a channel adapter. Channels (Telegram, LINE) are optional background threads.

### 架構須知（開發時必讀）

#### Event 機制：用 `@EventListener`，不用 `@ApplicationModuleListener`

Grimo 是 CLI app，沒有 DB、沒有 transaction。`@ApplicationModuleListener` 底層是 `@TransactionalEventListener`，沒有 active transaction 時 **listener 不會 fire**（靜默失敗）。

```java
// ✅ 正確：CLI app 用這個
@EventListener
void on(AgentSwitchedEvent event) { ... }

// ❌ 錯誤：沒有 DB 和 transaction，listener 永遠不會觸發
@ApplicationModuleListener
void on(AgentSwitchedEvent event) { ... }
```

如需 async：`@Async @EventListener` + Virtual Thread executor。

#### UI 與功能解耦：Command → Event → TUI

功能層（Commands、Services）不直接操作 TUI 元件。改為 publish event，TUI listener 自動刷新。

```
/agent-use claude opus
  → AgentCommands.use() → publishEvent(AgentSwitchedEvent)
  → TuiEventBridge.on(AgentSwitchedEvent) → statusView.refresh()

不要：
  → AgentCommands.use() → 直接呼叫 refreshStatusBar()
```

#### TUI 框架：`tui/` 包

所有 TUI 寬度計算用 `DisplayWidth`（封裝 JLine WCWidth）。禁止直接用 `String.format("%-Ns")` 或 `text.substring(0, cols)` 做對齊/截斷。

- `Renderable.render(int width)` — 元件契約，每行保證 columnLength == width
- `Layout.horizontal/vertical(total, gap, Fixed/Fill...)` — 區域切分
- `DisplayWidth.padRight/truncate/center/wrap` — 寬度感知操作

#### `shared/` 包不依賴功能模組

`shared/` 是基礎設施層，不應 import `agent/`、`skill/`、`task/` 等功能包。如需跨模組傳遞資料，用 interface 或 event。

#### 文字選取：auto-copy on release

TUI 在 alternate screen + SGR mouse tracking 下，終端原生選取被攔截。App 層實作文字選取：

- **座標系統**：buffer-absolute（不受 viewport 滾動影響），`Screen.screenToBuffer()` 轉換時需扣除底部對齊的 padding
- **複製觸發**：滑鼠放開自動複製（auto-copy on release），不用按鍵。macOS 上 Cmd+C 被終端攔截、Ctrl+C 是中斷信號，都不可靠
- **剪貼簿**：`pbcopy`（macOS）/ `xclip`（Linux）為主，OSC 52 為 SSH 備援。參考 tmux/Claude Code fullscreen mode
- **反白渲染**：`Screen.applySelectionHighlight()` 在 `Display.update()` 前疊加 `AttributedStyle.INVERSE`
- **主對話不顯示 tier**：tier 是給 skill dispatch 用的，主對話 status bar 保持使用者選的 agent+model

#### TuiAdapter 架構（六角架構：Adapter → Port → Core）

`TuiAdapter` 是精簡後的 TUI 入口，搭配以下元件：
- `TuiKeyHandler`（tui/）— 鍵盤/滑鼠事件路由、overlay 管理
- `TuiEventBridge`（tui/，@Component）— @EventListener 橋接 domain event → TUI 更新
- `ChatDispatcher`（root，@Component）— AI 對話分派（tier routing → AgentClient）
- `InputPort` / `InputHandler`（command/）— 統一輸入管線（命令路由 → CommandDispatcher）

#### 統一管線規則（Adapter → Port，不可繞過 InputPort）

TUI adapter（TuiKeyHandler、TuiAdapter）**不可直接呼叫 CommandDispatcher 或 ChatDispatcher**。所有使用者輸入必須走六角架構管線：

```
inputCallback.onTextSubmit(text)
  → TuiAdapter.processInput()
    → InputPort.handleInput()
      → InputHandler
          ├─ /command → CommandDispatcher → ResponseCallback → contentView
          └─ AI text  → ChatDispatcher（Virtual Thread，直接更新 TUI）
```

唯一例外：TUI 專屬攔截（`/exit`、`/mcp` 無參數開 overlay、`/agent-use` 無參數開 overlay）— 不產生指令輸出，直接在 adapter 層處理。

#### Spring AI Community Agent Client

- 主站文件：https://springaicommunity.mintlify.app/
- Agent Client（incubating）：https://springaicommunity.mintlify.app/projects/incubating/agent-client
- 用途：透過 Agent Client 使用 Claude Code、Gemini CLI 作為 agent provider

**已知 SDK Bug（0.10.0-SNAPSHOT）：** `DefaultAgentClient.resolveMcpServers()` 將 `ClaudeAgentOptions` 包成 `DefaultAgentOptions`，導致有 MCP server 配置時 per-request options（disallowedTools、maxThinkingTokens 等）丟失。Workaround：重要設定放在 `defaultOptions` 而非 per-request options。

### Testing

- Module tests: `@ApplicationModuleTest` for isolated Modulith module testing
- Integration tests: Spring Boot Test for cross-module event flows
- Testcontainers: Grafana OTEL LGTM container configured in `TestcontainersConfiguration`
- **排除慢速測試**：開發迭代時用 `--tests "specific.TestClass"` 跑目標測試，不跑 Testcontainers/OTEL。具體排除：`GrimoApplicationTests`（OTEL 啟動慢）、`SandboxDockerIntegrationTest`（需 Docker）
