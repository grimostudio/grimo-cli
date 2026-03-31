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
| `shared/` | Domain events, config loading, workspace path management |

### Key Design Decisions

- **Library over Starter**: LLM providers, MCP servers, and channels are managed as plain Java objects at runtime (not Spring beans), enabling dynamic add/remove via CLI commands.
- **Event-driven**: Inter-module communication用 Spring `@EventListener`（**不是** `@ApplicationModuleListener`，見下方說明）。
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
  → TuiEventListener.on(AgentSwitchedEvent) → statusView.refresh()

不要：
  → AgentCommands.use() → 直接呼叫 refreshStatusBar()
```

#### TUI 框架：`shared.tui` 包

所有 TUI 寬度計算用 `DisplayWidth`（封裝 JLine WCWidth）。禁止直接用 `String.format("%-Ns")` 或 `text.substring(0, cols)` 做對齊/截斷。

- `TuiComponent.render(int width)` — 元件契約，每行保證 columnLength == width
- `Layout.horizontal/vertical(total, gap, Fixed/Fill...)` — 區域切分
- `DisplayWidth.padRight/truncate/center/wrap` — 寬度感知操作

#### `shared/` 包不依賴功能模組

`shared/` 是基礎設施層，不應 import `agent/`、`skill/`、`task/` 等功能包。如需跨模組傳遞資料，用 interface 或 event。

#### 文字選取：auto-copy on release

TUI 在 alternate screen + SGR mouse tracking 下，終端原生選取被攔截。App 層實作文字選取：

- **座標系統**：buffer-absolute（不受 viewport 滾動影響），`GrimoScreen.screenToBuffer()` 轉換時需扣除底部對齊的 padding
- **複製觸發**：滑鼠放開自動複製（auto-copy on release），不用按鍵。macOS 上 Cmd+C 被終端攔截、Ctrl+C 是中斷信號，都不可靠
- **剪貼簿**：`pbcopy`（macOS）/ `xclip`（Linux）為主，OSC 52 為 SSH 備援。參考 tmux/Claude Code fullscreen mode
- **反白渲染**：`GrimoScreen.applySelectionHighlight()` 在 `Display.update()` 前疊加 `AttributedStyle.INVERSE`
- **主對話不顯示 tier**：tier 是給 skill dispatch 用的，主對話 status bar 保持使用者選的 agent+model

#### GrimoTuiRunner 重構方向

目前是 God Object（21 個建構子參數、13+ 職責）。重構目標：拆分為獨立的 lifecycle 和 event listener，每個只管一件事。詳見 `docs/superpowers/specs/2026-03-31-event-driven-tui-refactor.md`。

### Testing

- Module tests: `@ApplicationModuleTest` for isolated Modulith module testing
- Integration tests: Spring Boot Test for cross-module event flows
- Testcontainers: Grafana OTEL LGTM container configured in `TestcontainersConfiguration`
- **排除慢速測試**：開發迭代時用 `--tests "specific.TestClass"` 跑目標測試，不跑 Testcontainers/OTEL
