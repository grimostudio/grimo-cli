# Grimo CLI

Grimo is a locally-hosted AI assistant platform built as a Spring Shell CLI. It provides pluggable communication channels (Telegram, LINE), task scheduling with Markdown persistence, and a unified agent provider model that treats LLM APIs and CLI tools equally.

## Build & Run

```bash
./gradlew build          # Full build (compile + test)
./gradlew bootRun        # Run the application
./gradlew nativeCompile  # Build GraalVM native image (requires GraalVM 25+)
```

## Tech Stack

- **Java 25** (Virtual Threads), **Spring Boot 4.0.x**, **Spring Modulith 2.0.x**, **Spring Shell 4.0.x**
- **Spring AI 2.0.0-M3** — used as library (not starter) for runtime dynamic agent creation
- **MCP Java SDK** — used directly (not Spring AI MCP Starter) for runtime dynamic management
- **JLine 3** — raw Terminal + Display + BindingReader for custom TUI (replacing Spring Shell TerminalUI)
- Gradle Kotlin DSL, single-module build

## TUI Architecture

Custom TUI built on raw JLine 3, replacing Spring Shell TerminalUI due to its [broken SGR mouse event parsing](https://github.com/spring-projects/spring-shell/issues/1085).

| Component | Description |
|-----------|-------------|
| `GrimoEventLoop` | Dual-thread event loop (JLine Tmux pattern): input thread + render thread |
| `GrimoScreen` | Screen compositor using JLine `Display` for diff-based rendering (no flicker) |
| `GrimoContentView` | Scrollable content area (banner + conversation history) |
| `GrimoInputView` | Input area with slash command brand-color rendering |
| `GrimoSlashMenuView` | Slash command menu (modal overlay, max 5 items, instant filter) |
| `GrimoStatusView` | Status bar (agent, model, workspace, resource counts) |

Key features:
- Mouse wheel / Mac trackpad two-finger scroll support via JLine `MouseEvent`
- Diff-based rendering via JLine `Display` (only redraws changed characters)
- Spring Shell `CommandParser`/`CommandExecutor`/`CommandRegistry` retained for command processing

## References

- [Spring AI Community](https://springaicommunity.mintlify.app/)
  - [Agent Client (incubating)](https://springaicommunity.mintlify.app/projects/incubating/agent-client) — used to integrate Claude Code, Gemini CLI as agent providers
- [Spring Shell TUI docs](https://docs.spring.io/spring-shell/reference/tui/index.html)
- [JLine Mouse Support](https://jline.org/docs/advanced/mouse-support/)
- [JLine Display (diff-based rendering)](https://github.com/jline/jline3/blob/master/terminal/src/main/java/org/jline/utils/Display.java)
- [Design spec](docs/superpowers/specs/2026-03-22-grimo-openclaw-design-zh-TW.md)
- [Raw JLine TUI design spec](docs/superpowers/specs/2026-03-25-raw-jline-tui-design.md)
