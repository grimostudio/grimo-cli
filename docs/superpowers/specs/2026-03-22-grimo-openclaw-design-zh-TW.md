# Grimo — 自我演化 AI 助手平台設計規格

> 日期：2026-03-22
> 狀態：草稿

## 1. 概述

### 1.1 什麼是 Grimo

Grimo 是一個本機常駐的 AI 助手平台。以 Spring Shell CLI 作為主程序啟動，對外提供 Telegram、LINE 可插拔的通訊能力。結合 SDLC 開發能力與通用任務自動化。

### 1.2 目標

- 以本機 daemon 程序提供 24/7 全天候服務
- 以 CLI 作為主程序入口，對外提供可插拔的通訊頻道（Telegram、LINE）
- 可插拔的執行後端：LLM API 與 CLI 工具平等可用
- 基於 Markdown 的任務管理與 cron 排程
- 可擴展的 Skill 系統，透過 `SKILL.md` 檔案即放即用
- MCP client 支援：連接外部 tool server 擴展能力
- 隱私優先：完全運行於使用者的硬體上

### 1.3 非目標（MVP）

- 雲端部署或託管 SaaS
- macOS Keychain / libsecret 整合
- GraalVM native image
- Web 管理介面
- 多用戶 / 多租戶支援

## 2. 架構

### 2.1 高階架構

Spring Boot 單一 JAR 應用程式，採用 Spring Modulith 模組邊界，Spring Shell 作為互動式 CLI。

```
grimo（主程序 — Spring Shell CLI）
├── channel 模組       ← 可插拔通訊適配器（Telegram、LINE）
├── task 模組           ← Cron 排程、Markdown 持久化
├── agent 模組          ← 統一 AgentProvider（API + CLI）
├── skill 模組          ← 掃描 workspace/skills/*.md，熱載入
├── mcp 模組            ← MCP client（連接外部 tool server）
└── shared 模組         ← 領域事件、設定、workspace 管理
```

### 2.2 模組結構（Spring Modulith）

```
com.grimo
├── channel/
│   ├── telegram/        # TelegramBots Long Polling 適配器
│   └── line/            # LINE SDK Webhook 適配器
│
├── task/
│   ├── model/           # Task, Schedule, CronExpression
│   ├── store/           # Markdown 檔案持久化
│   └── scheduler/       # Spring TaskScheduler + CronTrigger
│
├── agent/
│   ├── provider/        # AgentProvider 介面 + 實作
│   ├── router/          # 路由至最佳可用 provider
│   └── detect/          # 啟動時自動偵測可用 agent
│
├── skill/
│   ├── loader/          # 掃描 workspace/skills/*.md
│   ├── registry/        # 運行時 skill 註冊表
│   └── builtin/         # healthcheck, remind, cron-report
│
├── mcp/
│   └── client/          # 連接外部 MCP tool server
│
└── shared/
    ├── event/           # 領域事件定義
    ├── config/          # YAML 設定載入
    └── workspace/       # Workspace 路徑管理
```

### 2.3 模組間通訊

所有模組透過 Spring Modulith `ApplicationModuleListener` 事件驅動通訊：

```
使用者訊息（Telegram/LINE 透過頻道，或 CLI 透過 Spring Shell）
  → channel 模組發布 IncomingMessageEvent（或 Shell 直接呼叫 agent）
    → agent 模組監聽，解析意圖
      → 任務指令 → 發布 TaskCreateRequestEvent → task 模組處理
      → 直接對話 → 回應 → 發布 OutgoingMessageEvent → channel 送出
      → 排程請求 → 發布 ScheduleTaskEvent → task 模組建立 cron job

排程觸發
  → task 模組發布 TaskExecutionEvent
    → agent 模組監聽，執行任務
      → 發布 TaskCompletedEvent + OutgoingMessageEvent（通知使用者）
```

## 3. 頻道模組（Channel Module）

### 3.1 統一適配器介面

```java
public interface ChannelAdapter {
    String channelType();           // "telegram", "line"
    void send(OutgoingMessage msg);
    boolean isEnabled();
}
```

每個適配器將平台特定格式轉換為統一的領域事件。頻道為可插拔設計 — 系統可在零頻道（純 CLI）或任意頻道組合下運作。

### 3.2 訊息模型

```java
public record IncomingMessageEvent(
    String channelType,       // "telegram" | "line"
    String channelUserId,     // 平台使用者 ID
    String conversationId,    // 對話 ID（群組或私聊）
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

### 3.3 Telegram 適配器

- TelegramBots Spring Boot Starter，Long Polling 模式
- 不需 webhook、不需公網 IP
- 只需一個 BotFather token

### 3.4 LINE 適配器

- LINE Messaging API SDK for Java
- 需要 webhook（公網 URL）：使用 Cloudflare Tunnel 處理本機開發/生產環境
- 需要 Channel Access Token + Channel Secret

### 3.5 Spring Shell（主程序）

Spring Shell 是主程序入口，不是頻道適配器：

- Spring Shell 4.x 提供互動式 CLI 及所有 `grimo>` 指令
- 使用者在 shell 中直接打字對話或管理系統
- 頻道適配器（Telegram、LINE）在啟用時作為背景線程啟動
- 系統僅靠 CLI 即可完整運作 — 頻道為可選的附加功能

## 4. 任務模組（Task Module）

### 4.1 任務生命週期

```
PENDING → RUNNING → COMPLETED
                  → FAILED
         CANCELLED
```

### 4.2 任務類型

| 類型 | 說明 | 範例 |
|------|------|------|
| immediate | 立即執行 | 「幫我查今天天氣」 |
| delayed | 延遲執行 | 「30 分鐘後提醒我開會」 |
| cron | 週期執行 | 「每天早上 9 點檢查 API 健康」 |

### 4.3 Markdown 持久化

每個任務一個 `.md` 檔案，frontmatter 存 metadata：

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

# 檢查 API 健康狀態

檢查 https://api.example.com/actuator/health 並回報狀態。

## 執行紀錄

### 2026-03-22 09:00
API 回應 200 OK，延遲 45ms
```

### 4.4 排程引擎

- 使用 Spring 內建 `TaskScheduler` + `CronTrigger`
- 啟動時掃描 `tasks/` 目錄，恢復所有 `cron` 和未完成的 `delayed` 任務
- 執行結果 append 到該 Markdown 的「執行紀錄」section

### 4.5 健康檢查整合

- 內建 `healthcheck` skill 呼叫目標 Actuator endpoint
- 解析標準 Actuator 回應格式（`UP`/`DOWN`/components）
- 非 Actuator URL 退化為 HTTP status code 檢查
- Grimo 自身暴露 Actuator，每個模組提供自訂 `HealthIndicator`：
  - `telegram` — bot 連線狀態
  - `line` — bot 連線狀態
  - `llm` — LLM provider 連線狀態
  - `scheduler` — 排程器運行狀態

### 4.6 CLI 指令

```
grimo> task create "每天早上9點檢查 API 健康" --cron "0 9 * * *"
grimo> task list
grimo> task show task-20260322-001
grimo> task cancel task-20260322-001
grimo> task history task-20260322-001
```

也可透過任何頻道以自然語言建立任務。

## 5. Agent 執行引擎模組

### 5.1 統一 Agent Provider 模型

LLM API 與 CLI 工具平等對待，統一為 Agent Provider：

```java
public interface AgentProvider {
    String id();
    AgentType type();          // API 或 CLI
    boolean isAvailable();     // 偵測是否可用
    AgentResult execute(AgentRequest request);
}
```

### 5.2 應用層 Registry

Agent provider 以應用層 registry 管理（非 Spring DI），支援運行時動態新增/移除：

```java
// 動態建立 — 不需 Spring Starter
var api = new AnthropicApi(apiKey);
ChatModel model = AnthropicChatModel.builder()
    .anthropicApi(api)
    .defaultOptions(AnthropicChatOptions.builder()
        .model("claude-sonnet-4")
        .build())
    .build();
agentProviderRegistry.register("anthropic", model);
```

ChatModel、McpSyncClient、ChannelAdapter 都是普通 Java 物件，以 `ConcurrentHashMap` registry 管理，不是 Spring bean。

### 5.3 啟動時自動偵測

```
偵測可用 Agent...
  claude CLI 發現於 /usr/local/bin/claude
  ANTHROPIC_API_KEY 已偵測
  codex CLI 未找到
  OPENAI_API_KEY 未設定
  ollama 運行中 (localhost:11434)

可用 Agent：claude-cli, anthropic-api, ollama
預設 Agent：claude-cli
```

### 5.4 路由策略

| 策略 | 說明 |
|------|------|
| 使用者指定 | `grimo> ask --agent ollama "解釋這段程式"` |
| Skill 指定 | Skill frontmatter 中 `executor: claude-cli` |
| 自動選擇 | 優先 CLI（能力較強），退化至 API |
| 降級 | 首選不可用時自動降級到下一個可用 agent |

### 5.5 CLI 管理

```
grimo> agent list
  ID          TYPE   MODEL/TOOL       STATUS
  anthropic   api    claude-sonnet-4  ready
  ollama      api    llama3           ready
  claude-cli  cli    claude-code      ready
  codex-cli   cli    codex            not found

grimo> agent add anthropic
請輸入 API Key：sk-ant-****
anthropic (claude-sonnet-4) 已就緒

grimo> agent add claude-cli
偵測到 claude 位於 /usr/local/bin/claude
```

## 6. Skill 模組

### 6.1 Skill 定義格式

每個 skill 是一個目錄，包含 `SKILL.md` 檔案：

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
description: 定期檢查目標服務的健康狀態
version: 1.0.0
author: grimo-builtin
executor: api
triggers:
  - cron
  - command
---

# 健康檢查

檢查指定 URL 的 HTTP 狀態或 Spring Boot Actuator /health endpoint。

## 使用方式

- 直接指令：`檢查 https://api.example.com/actuator/health`
- 排程：`每小時檢查 https://api.example.com/actuator/health`

## 行為

1. 發送 GET 請求到目標 URL
2. 解析回應：
   - Actuator 格式：解析 status 和 components
   - 一般 URL：檢查 HTTP status code
3. 異常時透過來源頻道通知使用者

## 參數

- `url`（必填）：目標服務 URL
- `timeout`（選填）：超時秒數，預設 10
- `expect_status`（選填）：預期 HTTP 狀態碼，預設 200
```

### 6.2 載入機制

- 啟動時掃描 `skills/` 目錄
- 監控目錄變更，運行時熱載入新 skill
- Skill 內容作為 system prompt 注入 LLM context
- 只注入當前請求相關的 skill（依 description 語意匹配），節省 context window

### 6.3 內建 Skill（MVP）

| Skill | 說明 |
|-------|------|
| `healthcheck` | HTTP / Actuator 健康檢查 |
| `remind` | 延遲提醒（「30 分鐘後提醒我...」）|
| `cron-report` | 排程任務執行報告彙整 |

### 6.4 社群 Skill 安裝

```
grimo> skill install https://github.com/someone/grimo-skill-weather
weather skill 已安裝到 skills/weather/

grimo> skill list
  NAME          VERSION  AUTHOR         STATUS
  healthcheck   1.0.0    grimo-builtin  loaded
  remind        1.0.0    grimo-builtin  loaded
  cron-report   1.0.0    grimo-builtin  loaded
  weather       0.2.1    someone        loaded
```

本質上就是 `git clone` 到 `skills/` 目錄，沒有複雜的 registry。

## 7. MCP 模組（僅 Client）

Grimo 連接外部 MCP tool server 擴展自身能力。MCP 連線透過 CLI 指令在運行時動態管理。

### 7.1 動態 MCP Client 管理

使用 MCP Java SDK（`io.modelcontextprotocol.sdk:mcp`），不使用 Spring AI MCP Starter，以支援運行時動態新增/移除：

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

支援 STDIO 和 HTTP SSE 兩種 transport。

### 7.2 工具發現

- 連線後 Grimo 呼叫 `listTools()` 發現可用工具
- 工具自動註冊到 agent 的可用工具列表
- 支援 `toolsChangeConsumer` callback，處理 server 動態新增/移除工具

### 7.3 CLI 管理

```
grimo> mcp add github --stdio "npx @modelcontextprotocol/server-github"
grimo> mcp add postgres --sse http://localhost:3001/sse
grimo> mcp list
  NAME      TRANSPORT  STATUS      TOOLS
  github    stdio      connected   12
  postgres  sse        connected   5
grimo> mcp remove github
```

### 7.4 持久化

MCP 連線設定持久化到 `config.yaml`。啟動時 Grimo 自動重新連接所有先前設定的 MCP server。

## 8. Workspace 結構

```
~/grimo-workspace/
├── config.yaml              # 設定檔（agent、channel、mcp）
├── skills/                  # Skill 目錄
│   ├── healthcheck/
│   │   └── SKILL.md
│   ├── remind/
│   │   └── SKILL.md
│   └── cron-report/
│       └── SKILL.md
├── tasks/                   # 任務 Markdown 檔案
│   ├── 2026-03-22-check-api.md
│   └── ...
├── conversations/           # 對話紀錄（依頻道和使用者分檔）
│   ├── telegram-12345.md
│   └── line-U1a2b3c.md
└── logs/                    # 執行日誌
    └── grimo.log
```

設定檔權限設為 `600` 確保安全。MVP 階段敏感資訊直接存於 `config.yaml`。

## 9. 啟動流程

```
$ grimo
     或
$ grimo --workspace ~/my-project

1. 載入 workspace
   ├── 讀取 config.yaml
   └── 若不存在 → 進入 onboarding 引導

2. 偵測可用 Agent
   ├── 掃描 CLI 工具（claude, codex...）
   ├── 檢查 API Key 環境變數
   └── 探測 Ollama

3. 載入 Skill
   └── 掃描 skills/ 目錄

4. 連接 MCP Server
   └── 依 config.yaml 設定連線

5. 啟動頻道
   ├── Telegram Long Polling
   ├── LINE Webhook + Tunnel
   └── Spring Shell 互動提示

6. 恢復排程任務
   └── 掃描 tasks/ 中的 cron 任務

Grimo 就緒。
```

### Onboarding 引導（首次啟動）

```
$ grimo

歡迎使用 Grimo！首次設定開始...

1/3 選擇 LLM Provider
  > 1. Anthropic (Claude)
    2. OpenAI (GPT)
    3. Ollama (本機)
    4. 稍後設定

請輸入 API Key：sk-ant-****
已連線 Claude Sonnet 4

2/3 啟用通訊頻道（可多選，Enter 跳過）
  > 1. Telegram
    2. LINE

選擇：1
請輸入 Telegram Bot Token（從 @BotFather 取得）：123456:ABC***
Telegram Bot @my_grimo_bot 已連線

3/3 工作目錄
預設：~/grimo-workspace
>（Enter 使用預設）

設定完成！輸入 /help 查看指令
grimo>
```

## 10. 技術棧

| 層面 | 技術 | 版本 | 備註 |
|------|------|------|------|
| 語言 | Java | 25 (Virtual Threads) | |
| 框架 | Spring Boot | 4.0.x | |
| 模組化 | Spring Modulith | 2.0.x | |
| CLI | Spring Shell | 4.0.x | |
| AI 核心 | Spring AI | 2.0.0-M3 | Library，非 Starter |
| LLM Provider | spring-ai-anthropic, spring-ai-openai, spring-ai-ollama |（隨 Spring AI）| Library，非 Starter — 運行時動態建立 |
| Agent 工具 | spring-ai-agent-utils | 0.5.x | |
| CLI Agent | spring-ai-agent-client | 0.1.x | |
| MCP Client | io.modelcontextprotocol.sdk:mcp | 1.1.x | MCP Java SDK，非 Spring AI MCP Starter — 運行時動態管理 |
| Telegram | TelegramBots Spring Boot | 8.x | |
| LINE | line-bot-sdk-java | 9.x | |
| 排程 | Spring TaskScheduler | 內建 | |
| 健康檢查 | Spring Boot Actuator | 內建 | |
| 建置 | Gradle (Kotlin DSL) | latest | |

**為何使用 Library 而非 Starter：** 使用者在運行時透過 CLI 指令動態新增/移除 LLM provider、MCP server、通訊頻道。Spring Boot Starter 使用靜態 auto-configuration（啟動時讀取 YAML），不支援動態生命週期管理。底層 library（ChatModel、McpSyncClient）是普通 Java 物件，可程式化建立/銷毀。

### 專案 Gradle 結構

```
grimo/
├── build.gradle.kts          # 單一模組，Spring Boot + Spring Shell + Modulith
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

單一 Gradle 模組，模組邊界完全由 Spring Modulith 的 package 結構保證。不拆子專案，保持建置簡單。

### 測試策略

| 層級 | 工具 | 範圍 |
|------|------|------|
| 模組測試 | `@ApplicationModuleTest` | 單一 Modulith 模組 |
| 整合測試 | Spring Boot Test | 跨模組事件流 |
| Skill 測試 | Markdown 解析驗證 | Skill 載入正確性 |

## 11. CLI 指令總覽

```
grimo> help

  chat        直接輸入文字與 agent 對話
  agent       list | add | switch        管理執行引擎
  channel     list | add | remove        管理可插拔通訊頻道
  task        create | list | show | cancel  管理任務
  skill       list | install | remove    管理 Skill
  mcp         list | add | remove        管理 MCP 連線
  config      show | edit                查看/編輯設定
  status      顯示系統狀態（agent、channel、排程）
```

## 12. 隔離策略

- **本機優先**：輕量任務（對話、提醒、查資料）直接在主機上執行
- **Docker 可選**：開發任務（程式碼生成、重構）可選擇在 Docker sandbox 中執行
- Agent provider 宣告其 sandbox 支援能力；router 在可用時尊重 sandbox 偏好設定
