# Grimo — Self-Evolving AI Assistant Platform Design Spec

> Date: 2026-03-22
> Status: Draft

## 1. Overview

### 1.1 What is Grimo

Grimo is a locally-hosted, always-on AI assistant platform. It launches via Spring Shell CLI as the main program, with pluggable communication channels (Telegram, LINE) for external access. It combines SDLC development capabilities with general-purpose task automation.

### 1.2 Goals

- 24/7 availability as a local daemon process
- CLI as main program entry point, with pluggable communication channels (Telegram, LINE)
- Pluggable execution backends: LLM API providers and CLI tools are equally usable
- Markdown-based task management with cron scheduling
- Extensible skill system via drop-in `SKILL.md` files
- MCP client support for connecting external tool servers
- Privacy-first: runs entirely on user's hardware

### 1.3 Non-Goals (MVP)

- Cloud deployment or hosted SaaS
- macOS Keychain / libsecret integration for secrets
- GraalVM native image
- Web dashboard UI
- Multi-user / multi-tenant support

## 2. Architecture

### 2.1 High-Level Architecture

Spring Boot single-JAR application with Spring Modulith module boundaries and Spring Shell as the interactive CLI.

```
grimo (Main program — Spring Shell CLI)
├── channel module      ← Pluggable communication adapters (Telegram, LINE)
├── task module          ← Cron scheduling, Markdown persistence
├── agent module         ← Unified AgentProvider (API + CLI)
├── skill module         ← Scan workspace/skills/*.md, hot reload
├── mcp module           ← MCP client (connect external tool servers)
└── shared module        ← Domain events, config, workspace management
```

### 2.2 Module Structure (Spring Modulith)

```
com.grimo
├── channel/
│   ├── telegram/        # TelegramBots Long Polling adapter
│   └── line/            # LINE SDK Webhook adapter
│
├── task/
│   ├── model/           # Task, Schedule, CronExpression
│   ├── store/           # Markdown file persistence
│   └── scheduler/       # Spring TaskScheduler + CronTrigger
│
├── agent/
│   ├── provider/        # AgentProvider interface + implementations
│   ├── router/          # Route to best available provider
│   └── detect/          # Auto-detect available agents on startup
│
├── skill/
│   ├── loader/          # Scan workspace/skills/*.md
│   ├── registry/        # Runtime skill registry
│   └── builtin/         # healthcheck, remind, cron-report
│
├── mcp/
│   └── client/          # Connect to external MCP tool servers
│
└── shared/
    ├── event/           # Domain event definitions
    ├── config/          # YAML config loading
    └── workspace/       # Workspace path management
```

### 2.3 Inter-Module Communication

All modules communicate via Spring Modulith `ApplicationModuleListener` events:

```
User message (Telegram/LINE via channel, or CLI via Spring Shell)
  → channel module publishes IncomingMessageEvent (or Shell directly invokes agent)
    → agent module listens, parses intent
      → task command → publishes TaskCreateRequestEvent → task module handles
      → direct conversation → responds → publishes OutgoingMessageEvent → channel sends
      → schedule request → publishes ScheduleTaskEvent → task module creates cron job

Scheduled trigger
  → task module publishes TaskExecutionEvent
    → agent module listens, executes task
      → publishes TaskCompletedEvent + OutgoingMessageEvent (notify user)
```

## 3. Channel Module

### 3.1 Unified Adapter Interface

```java
public interface ChannelAdapter {
    String channelType();           // "telegram", "line"
    void send(OutgoingMessage msg);
    boolean isEnabled();
}
```

Each adapter converts platform-specific formats to/from unified domain events. Channels are pluggable — the system works with zero channels (CLI-only) or any combination.

### 3.2 Message Model

```java
public record IncomingMessageEvent(
    String channelType,       // "telegram" | "line"
    String channelUserId,     // platform user ID
    String conversationId,    // conversation ID (group or DM)
    String text,
    List<Attachment> attachments,
    Instant timestamp
) {}

public record OutgoingMessageEvent(
    String channelType,
    String conversationId,
    String text,
    List<Attachment> attachments
) {}
```

### 3.3 Telegram Adapter

- TelegramBots Spring Boot Starter, Long Polling mode
- No webhook required, no public IP needed
- Only needs a BotFather token

### 3.4 LINE Adapter

- LINE Messaging API SDK for Java
- Requires webhook (public URL): Cloudflare Tunnel for local dev/prod
- Needs Channel Access Token + Channel Secret

### 3.5 Spring Shell (Main Program)

Spring Shell is the main program entry point, not a channel adapter:

- Spring Shell 4.x provides the interactive CLI and all `grimo>` commands
- User types directly in the shell to chat or manage the system
- Channel adapters (Telegram, LINE) launch as background threads when enabled
- The system is fully functional with CLI alone — channels are optional add-ons

## 4. Task Module

### 4.1 Task Lifecycle

```
PENDING → RUNNING → COMPLETED
                  → FAILED
         CANCELLED
```

### 4.2 Task Types

| Type | Description | Example |
|------|-------------|---------|
| immediate | Execute now | "Check the weather" |
| delayed | Execute after delay | "Remind me in 30 minutes" |
| cron | Recurring schedule | "Check API health every day at 9am" |

### 4.3 Markdown Persistence

Each task is a `.md` file with YAML frontmatter:

```
~/grimo-workspace/tasks/
├── 2026-03-22-check-api-health.md
└── 2026-03-22-remind-meeting.md
```

```markdown
---
id: task-20260322-001
type: cron
cron: "0 9 * * *"
status: completed
channel: telegram
created: 2026-03-22T10:30:00+08:00
last_run: 2026-03-22T09:00:00+08:00
next_run: 2026-03-23T09:00:00+08:00
---

# Check API Health

Check https://api.example.com/actuator/health and report status.

## Execution Log

### 2026-03-22 09:00
API responded 200 OK, latency 45ms
```

### 4.4 Scheduling Engine

- Spring built-in `TaskScheduler` + `CronTrigger`
- On startup: scan `tasks/` directory, restore all `cron` and pending `delayed` tasks
- Execution results appended to the Markdown file's "Execution Log" section

### 4.5 Health Check Integration

- Built-in `healthcheck` skill calls target Actuator endpoints
- Parses standard Actuator response format (`UP`/`DOWN`/components)
- Non-Actuator URLs fall back to HTTP status code check
- Grimo itself exposes Actuator with custom `HealthIndicator` per module:
  - `telegram` — bot connection status
  - `line` — bot connection status
  - `llm` — LLM provider connectivity
  - `scheduler` — scheduler running status

### 4.6 CLI Commands

```
grimo> task create "Check API health every day at 9am" --cron "0 9 * * *"
grimo> task list
grimo> task show task-20260322-001
grimo> task cancel task-20260322-001
grimo> task history task-20260322-001
```

Tasks can also be created via natural language through any channel.

## 5. Agent Execution Module

### 5.1 Unified Agent Provider Model

LLM API providers and CLI tools are treated equally as Agent Providers:

```java
public interface AgentProvider {
    String id();
    AgentType type();          // API or CLI
    boolean isAvailable();     // detect availability
    AgentResult execute(AgentRequest request);
}
```

### 5.2 Application-Level Registry

Agent providers are managed in an application-level registry (not Spring DI), supporting dynamic add/remove at runtime:

```java
// Dynamic creation — no Spring Starter needed
var api = new AnthropicApi(apiKey);
ChatModel model = AnthropicChatModel.builder()
    .anthropicApi(api)
    .defaultOptions(AnthropicChatOptions.builder()
        .model("claude-sonnet-4")
        .build())
    .build();
agentProviderRegistry.register("anthropic", model);
```

ChatModel, McpSyncClient, and ChannelAdapter are all plain Java objects managed in `ConcurrentHashMap` registries, not Spring beans.

### 5.3 Auto-Detection on Startup

```
Detecting available Agents...
  claude CLI found (/usr/local/bin/claude)
  ANTHROPIC_API_KEY detected
  codex CLI not found
  OPENAI_API_KEY not set
  ollama running (localhost:11434)

Available Agents: claude-cli, anthropic-api, ollama
Default Agent: claude-cli
```

### 5.4 Router Strategy

| Strategy | Description |
|----------|-------------|
| User-specified | `grimo> ask --agent ollama "Explain this code"` |
| Skill-specified | Skill frontmatter `executor: claude-cli` |
| Auto-select | Prefer CLI (richer capabilities), fall back to API |
| Fallback | If primary unavailable, degrade to next available agent |

### 5.5 CLI Management

```
grimo> agent list
  ID          TYPE   MODEL/TOOL       STATUS
  anthropic   api    claude-sonnet-4  ready
  ollama      api    llama3           ready
  claude-cli  cli    claude-code      ready
  codex-cli   cli    codex            not found

grimo> agent add anthropic
Enter API Key: sk-ant-****
anthropic (claude-sonnet-4) ready

grimo> agent add claude-cli
Detected claude at /usr/local/bin/claude
```

## 6. Skill Module

### 6.1 Skill Definition Format

Each skill is a directory with a `SKILL.md` file:

```
~/grimo-workspace/skills/
├── healthcheck/
│   └── SKILL.md
├── remind/
│   └── SKILL.md
└── cron-report/
    └── SKILL.md
```

```markdown
---
name: healthcheck
description: Periodically check target service health status
version: 1.0.0
author: grimo-builtin
executor: api
triggers:
  - cron
  - command
---

# Health Check

Check HTTP status or Spring Boot Actuator /health endpoint of specified URLs.

## Usage

- Direct: `Check https://api.example.com/actuator/health`
- Scheduled: `Check https://api.example.com/actuator/health every hour`

## Behavior

1. Send GET request to target URL
2. Parse response:
   - Actuator format: parse status and components
   - General URL: check HTTP status code
3. Notify user via source channel on anomalies

## Parameters

- `url` (required): Target service URL
- `timeout` (optional): Timeout in seconds, default 10
- `expect_status` (optional): Expected HTTP status code, default 200
```

### 6.2 Loading Mechanism

- Scan `skills/` directory on startup
- Watch directory for changes, hot reload new skills at runtime
- Skill content injected as system prompt context to LLM
- Only inject relevant skills per request (semantic matching on description) to conserve context window

### 6.3 Built-in Skills (MVP)

| Skill | Description |
|-------|-------------|
| `healthcheck` | HTTP / Actuator health check |
| `remind` | Delayed reminders ("remind me in 30 minutes...") |
| `cron-report` | Scheduled task execution report summary |

### 6.4 Community Skill Installation

```
grimo> skill install https://github.com/someone/grimo-skill-weather
weather skill installed to skills/weather/

grimo> skill list
  NAME          VERSION  AUTHOR         STATUS
  healthcheck   1.0.0    grimo-builtin  loaded
  remind        1.0.0    grimo-builtin  loaded
  cron-report   1.0.0    grimo-builtin  loaded
  weather       0.2.1    someone        loaded
```

Essentially `git clone` into `skills/` directory. No complex registry.

## 7. MCP Module (Client Only)

Grimo connects to external MCP tool servers to extend its capabilities. MCP connections are managed dynamically at runtime via CLI commands.

### 7.1 Dynamic MCP Client Management

Uses MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`) directly, not Spring AI MCP Starter, to support runtime add/remove:

```java
// grimo> mcp add github --stdio "npx @modelcontextprotocol/server-github"
var params = ServerParameters.builder("npx")
    .args("-y", "@modelcontextprotocol/server-github")
    .build();
var transport = new StdioClientTransport(params);
var mcpClient = McpClient.sync(transport).build();
mcpClient.initialize();
mcpClientRegistry.register("github", mcpClient);
```

Supports STDIO and HTTP SSE transports.

### 7.2 Tool Discovery

- On connect, Grimo calls `listTools()` to discover available tools
- Tools auto-registered to agent's available tool list
- Supports `toolsChangeConsumer` callback for servers that dynamically add/remove tools

### 7.3 CLI Management

```
grimo> mcp add github --stdio "npx @modelcontextprotocol/server-github"
grimo> mcp add postgres --sse http://localhost:3001/sse
grimo> mcp list
  NAME      TRANSPORT  STATUS   TOOLS
  github    stdio      connected  12
  postgres  sse        connected  5
grimo> mcp remove github
```

### 7.4 Persistence

MCP connections persisted to `config.yaml`. On startup, Grimo reconnects all previously configured MCP servers.

## 8. Workspace Structure

```
~/grimo-workspace/
├── config.yaml              # Configuration (agents, channels, mcp)
├── skills/                  # Skill directories
│   ├── healthcheck/
│   │   └── SKILL.md
│   ├── remind/
│   │   └── SKILL.md
│   └── cron-report/
│       └── SKILL.md
├── tasks/                   # Task Markdown files
│   ├── 2026-03-22-check-api.md
│   └── ...
├── conversations/           # Conversation history (per channel per user)
│   ├── telegram-12345.md
│   └── line-U1a2b3c.md
└── logs/                    # Execution logs
    └── grimo.log
```

Config file permissions set to `600` for security. Secrets stored directly in `config.yaml` for MVP simplicity.

## 9. Startup Flow

```
$ grimo
     or
$ grimo --workspace ~/my-project

1. Load workspace
   ├── Read config.yaml
   └── If not found → enter onboarding wizard

2. Detect available Agents
   ├── Scan CLI tools (claude, codex...)
   ├── Check API Key environment variables
   └── Probe Ollama

3. Load Skills
   └── Scan skills/ directory

4. Connect MCP Servers
   └── Connect per config.yaml settings

5. Start Channels
   ├── Telegram Long Polling
   ├── LINE Webhook + Tunnel
   └── Spring Shell interactive prompt

6. Restore Scheduled Tasks
   └── Scan tasks/ for cron jobs

Grimo ready.
```

### Onboarding Wizard (First Launch)

```
$ grimo

Welcome to Grimo! First-time setup...

1/3 Choose LLM Provider
  > 1. Anthropic (Claude)
    2. OpenAI (GPT)
    3. Ollama (local)
    4. Set up later

Enter API Key: sk-ant-****
Connected to Claude Sonnet 4

2/3 Enable channels (multi-select, Enter to skip)
  > 1. Telegram
    2. LINE

Choice: 1
Enter Telegram Bot Token (from @BotFather): 123456:ABC***
Telegram Bot @my_grimo_bot connected

3/3 Workspace directory
Default: ~/grimo-workspace
> (Enter for default)

Setup complete! Type /help for commands
grimo>
```

## 10. Technology Stack

| Layer | Technology | Version | Note |
|-------|-----------|---------|------|
| Language | Java | 25 (Virtual Threads) | |
| Framework | Spring Boot | 4.0.x | |
| Modularity | Spring Modulith | 2.0.x | |
| CLI | Spring Shell | 4.0.x | |
| AI Core | Spring AI | 2.0.0-M3 | Library, not Starter |
| LLM Providers | spring-ai-anthropic, spring-ai-openai, spring-ai-ollama | (follows Spring AI) | Library, not Starter — dynamic runtime creation |
| Agent Tools | spring-ai-agent-utils | 0.5.x | |
| CLI Agent | spring-ai-agent-client | 0.1.x | |
| MCP Client | io.modelcontextprotocol.sdk:mcp | 1.1.x | MCP Java SDK, not Spring AI MCP Starter — dynamic runtime management |
| Telegram | TelegramBots Spring Boot | 8.x | |
| LINE | line-bot-sdk-java | 9.x | |
| Scheduling | Spring TaskScheduler | built-in | |
| Health Check | Spring Boot Actuator | built-in | |
| Build | Gradle (Kotlin DSL) | latest | |

**Why libraries instead of starters:** Users add/remove LLM providers, MCP servers, and channels at runtime via CLI commands. Spring Boot Starters use static auto-configuration (read YAML at startup), which doesn't support dynamic lifecycle management. The underlying libraries (ChatModel, McpSyncClient) are plain Java objects that can be created/destroyed programmatically.

### Project Gradle Structure

```
grimo/
├── build.gradle.kts          # Single module: Spring Boot + Spring Shell + Modulith
├── settings.gradle.kts
└── src/
    ├── main/java/io/github/samzhu/grimo/
    │   ├── GrimoApplication.java
    │   ├── channel/
    │   ├── task/
    │   ├── agent/
    │   ├── skill/
    │   ├── mcp/
    │   └── shared/
    └── main/resources/
        └── application.yaml
```

Single Gradle module. Module boundaries enforced entirely by Spring Modulith package structure. No sub-projects — keeps build simple.

### Testing Strategy

| Level | Tool | Scope |
|-------|------|-------|
| Module test | `@ApplicationModuleTest` | Single Modulith module |
| Integration test | Spring Boot Test | Cross-module event flows |
| Skill test | Markdown parse verification | Skill loading correctness |

## 11. CLI Commands Summary

```
grimo> help

  chat        Type text directly to chat with the agent
  agent       list | add | switch         Manage execution engines
  channel     list | add | remove         Manage pluggable communication channels
  task        create | list | show | cancel  Manage tasks
  skill       list | install | remove     Manage skills
  mcp         list | add | remove         Manage MCP connections
  config      show | edit                 View/edit configuration
  status      Show system status (agents, channels, schedules)
```

## 12. Isolation Strategy

- **Local-first**: Lightweight tasks (chat, reminders, web search) run directly on host
- **Docker optional**: Development tasks (code generation, refactoring) can optionally run in Docker sandbox
- Agent providers declare their sandbox support; router respects sandbox preference when available
