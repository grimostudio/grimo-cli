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
- **Event-driven**: Inter-module communication uses Spring Modulith `ApplicationModuleListener` domain events (e.g., `IncomingMessageEvent`, `TaskExecutionEvent`).
- **Markdown persistence**: Tasks stored as `.md` files with YAML frontmatter in `~/grimo-workspace/tasks/`.
- **Spring Shell is the main entry point**, not a channel adapter. Channels (Telegram, LINE) are optional background threads.

### Testing

- Module tests: `@ApplicationModuleTest` for isolated Modulith module testing
- Integration tests: Spring Boot Test for cross-module event flows
- Testcontainers: Grafana OTEL LGTM container configured in `TestcontainersConfiguration`
