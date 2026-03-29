# F2-d: Sandbox 環境準備與 Skill/MCP 自動配置

> Date: 2026-03-29
> Status: Draft
> Phase: 1（基礎設施）
> Parent: [PRD](2026-03-27-grimo-orchestration-platform-prd.md)
> Depends: F1 (MCP Catalog), F2 (Skill 標準)
> Supersedes: F2-c (啟動時 Skill 顯示 — 時機錯誤，skill 載入應在派遣時由 agent 原生 progressive disclosure 處理)

## 問題

目前 Grimo 派遣 agent 時直接使用 `System.getProperty("user.dir")` 作為工作目錄。Skill 和 MCP 的配置各自分散：

- MCP 透過 `AgentClient.Builder.mcpServerCatalog()` 傳遞（已實作 F1）
- Skill 放在 `~/.grimo/skills/` 但 CLI agent 看不到（agent 只認 `.claude/skills/`）
- 沒有統一的「執行環境準備」步驟

## 目標

1. 引入 Sandbox 層統一管理 agent 執行環境
2. 派遣 agent 前，自動將 Grimo 管理的 Skill 配置到 Sandbox 中
3. CLI agent 原生發現 Skill，Progressive Disclosure 自然運作
4. 支援 Local / Docker / E2B 三種 Sandbox 模式（先實作 Local）
5. 啟動時偵測可用的 Sandbox 後端（Docker daemon 等）
6. 派遣時在 TUI Content 區顯示 Skill 載入資訊（正確時機）

## 架構

```
┌───────────────────────────────────────────┐
│            Grimo 調度層                    │
│  SkillRegistry + McpCatalogBuilder        │
└──────────────┬────────────────────────────┘
               │ prepareSandbox()
┌──────────────▼────────────────────────────┐
│         Sandbox 層（環境準備）              │
│  .claude/skills/*/SKILL.md  ← symlink     │
│  MCP → AgentClient.mcpServerCatalog()     │
│  ┌────────┬──────────┬──────────┐         │
│  │ Local  │  Docker  │   E2B   │         │
│  └────────┴──────────┴──────────┘         │
└──────────────┬────────────────────────────┘
               │ sandbox.workDir()
┌──────────────▼────────────────────────────┐
│         AgentClient 層                     │
│  .goal(task).workingDirectory(workDir)    │
└──────────────┬────────────────────────────┘
               │ CLI subprocess
┌──────────────▼────────────────────────────┐
│   CLI Agent（Claude / Gemini / Codex）     │
│   原生發現 .claude/skills/*               │
│   Progressive Disclosure (Tier 1→2→3)     │
└───────────────────────────────────────────┘
```

## 設計

### 1. config.yaml 擴充

```yaml
# Sandbox 設定（agent 執行環境）
sandbox:
  mode: local                    # local | docker | e2b（預設 local）
  # docker:
  #   image: ghcr.io/spring-ai-community/agents-runtime:latest
  # e2b:
  #   api-key: ${E2B_API_KEY}
  #   template: base
```

### 2. Sandbox 偵測（啟動時 Phase 2）

啟動時偵測可用的 Sandbox 後端，結果顯示在 Banner / Status 區：

- **Local**: 永遠可用
- **Docker**: 檢查 Docker daemon 是否運行（`docker info`）
- **E2B**: 檢查 `E2B_API_KEY` 環境變數是否設定

偵測結果用於：
- 驗證 config.yaml 中設定的 mode 是否可用
- 在 Status 區顯示（如 `local sandbox`）
- 不可用時 fallback 到 local + WARN log

### 3. WorkspaceProvisioner（新元件）

> 注意：不叫 SandboxManager，避免與 SDK 的 `org.springaicommunity.sandbox.LocalSandbox` 混淆。
> SDK 的 `LocalSandbox` 是 agent 執行隔離層（Gemini/Codex AgentModel 建構時使用）。
> `WorkspaceProvisioner` 是 Grimo 的工作目錄準備層（配置 Skill 到工作目錄）。

```java
/**
 * 派遣 agent 前準備工作目錄：配置 Skill 檔案讓 CLI agent 原生發現。
 *
 * 設計說明：
 * - 每次 agent 派遣前呼叫 provision()，將 SkillRegistry 中的 skill
 *   symlink 到工作目錄的 .claude/skills/
 * - CLI agent 原生發現 .claude/skills/* 中的 SKILL.md
 * - Progressive Disclosure：agent 啟動時只讀 name+description（Tier 1），
 *   對話中 AI 判斷需要時才載入完整 body（Tier 2）
 * - 併發假設：GrimoTuiRunner 已有 agentRunning 守衛，同時只有一個 agent 執行
 *
 * @see <a href="https://agentskills.io/client-implementation/adding-skills-support">
 *      Agent Skills Progressive Disclosure</a>
 */
public class WorkspaceProvisioner {

    // 將 skills symlink 到 projectDir/.claude/skills/，回傳已配置的 skill 名稱
    public List<String> provision(Path projectDir, List<SkillDefinition> skills);

    // 清理 Grimo 建立的 symlink（不刪除使用者自己的 skill）
    public void cleanup(Path projectDir, List<String> provisionedSkillNames);
}
```

### 4. Skill 配置機制

**Local 模式：** 在工作目錄下建立 `.claude/skills/` 並 symlink Grimo 管理的 skill：

```
projectDir/
└── .claude/
    └── skills/
        ├── code-review -> ~/.grimo/skills/code-review     (symlink)
        └── explain-code -> ~/.grimo/skills/explain-code   (symlink)
```

- 使用 symlink 而非複製，避免重複檔案
- 如果 `.claude/skills/` 已存在（使用者自己的 skill），不覆蓋，只新增
- Grimo skill 名稱衝突時以使用者的為優先（WARN log）
- 派遣結束後 cleanup 移除 Grimo 建立的 symlink（不刪除使用者的）
- 併發安全：`GrimoTuiRunner` 已有 `agentRunning` 守衛，同時只有一個 agent 執行，不需要額外的 mutex

**Docker / E2B 模式（未來）：** 使用 `SandboxFiles.create()` 寫入 SKILL.md 檔案內容。

### 5. 派遣流程變更

現在：
```java
AgentClient.builder(model)
    .mcpServerCatalog(catalog)
    .defaultMcpServers(serverNames)
    .build()
    .goal(text)
    .workingDirectory(Path.of(System.getProperty("user.dir")))
    .run();
```

之後：
```java
Path projectDir = Path.of(System.getProperty("user.dir"));

// 1. 配置 Skills 到工作目錄
var provisionedSkills = workspaceProvisioner.provision(
    projectDir, skillRegistry.listAll());

try {
    // 2. 在 Content 區顯示已配置的 Skills
    displaySkillsLoaded(provisionedSkills);

    // 3. 派遣 Agent（workingDirectory 不變，仍是 projectDir）
    AgentClient.builder(model)
        .mcpServerCatalog(catalog)
        .defaultMcpServers(serverNames)
        .build()
        .goal(text)
        .workingDirectory(projectDir)
        .run();
} finally {
    // 4. 清理 Grimo 建立的 symlink
    workspaceProvisioner.cleanup(projectDir, provisionedSkills);
}
```

### 6. TUI 顯示（派遣時，非啟動時）

在 agent 開始執行前，Content 區顯示已配置的 skill（對齊 Claude Code 風格）：

```
● Skill(code-review)
  └ Successfully loaded skill
● Skill(explain-code)
  └ Successfully loaded skill
```

- `●` 綠色（ANSI green）
- Skill 名稱預設色
- `└ Successfully loaded skill` 灰色（ANSI 245）
- 這是 Tier 1 catalog 階段（name + description 已進入 agent context）
- 沒有 skill 時不顯示

### 7. Log

| 時機 | Level | 訊息 |
|------|-------|------|
| 啟動偵測 | INFO | `Sandbox backends: local ✓, docker ✗, e2b ✗` |
| 啟動偵測 | INFO | `Using sandbox mode: local` |
| 派遣準備 | INFO | `Provisioning sandbox: 2 skills [code-review, explain-code]` |
| Skill 配置 | DEBUG | `Symlinked skill: code-review -> ~/.grimo/skills/code-review` |
| 名稱衝突 | WARN | `Skill 'X' exists in project, skipping Grimo version` |
| 清理 | DEBUG | `Cleaned up sandbox: removed 2 symlinks` |

## 依賴

`LocalSandbox` 已透過現有的 `spring-ai-gemini` / `spring-ai-codex-agent` 依賴引入（`org.springaicommunity.sandbox` 套件）。Phase A 不需要新增依賴。

```kotlin
// build.gradle.kts — 現有依賴已包含 LocalSandbox
implementation("org.springaicommunity.agents:spring-ai-gemini:0.10.0-SNAPSHOT")
// ↑ 包含 org.springaicommunity.sandbox.LocalSandbox

// Phase B: Docker 支援時加入
// implementation("org.springaicommunity.sandbox:agent-sandbox-docker:0.9.1-SNAPSHOT")
```

> 注意：Phase A 的 `WorkspaceProvisioner` 不直接使用 SDK 的 `LocalSandbox` class，
> 而是用 Java NIO（`Files.createSymbolicLink`）操作工作目錄。
> 未來 Phase B 升級 Docker 模式時才需要 `SandboxFiles` API。

## 影響範圍

| 動作 | 檔案 | 變更 |
|------|------|------|
| Create | `WorkspaceProvisioner.java` | Skill symlink 配置 + 清理 |
| Create | `SandboxDetector.java` | 啟動時偵測可用 Sandbox 後端 |
| Modify | `GrimoTuiRunner.java` | 注入 WorkspaceProvisioner，派遣前/後呼叫 |
| Modify | `GrimoConfig.java` | 讀取 `sandbox` 設定 |
| Modify | `WorkspaceManager.java` | DEFAULT_CONFIG 增加 sandbox 區段 |
| Modify | `GrimoStartupRunner.java` | 註冊 WorkspaceProvisioner / SandboxDetector bean |
| Modify | `BannerRenderer.java` 或 Status | 顯示 sandbox 模式 |
| Modify | `AgentConfiguration.java` | 確認與現有 LocalSandbox 使用不衝突 |
| Modify | `docs/glossary.md` | 新增 Sandbox / WorkspaceProvisioner 術語 |

## 分階段實作

**Phase A（此次）：** Local Sandbox + Skill 配置 + TUI 顯示
- `SandboxManager` 只支援 Local 模式
- Symlink skill 到 `.claude/skills/`
- 派遣時顯示 `● Skill(name)`
- 啟動偵測（Docker daemon check）

**Phase B（未來）：** Docker Sandbox
- 加入 `agent-sandbox-docker` 依賴
- `SandboxManager` 支援 Docker 模式
- 使用 `SandboxFiles.create()` 寫入 skill

**Phase C（未來）：** E2B Sandbox
- 加入 `agent-sandbox-e2b` 依賴
- E2B API 整合

## 驗證方式

1. `~/.grimo/skills/` 放入測試 skill
2. 啟動 Grimo，日誌顯示 `Sandbox backends: local ✓, docker ✗`
3. 輸入任意對話，派遣 agent 前 Content 區顯示 `● Skill(name)`
4. 檢查工作目錄 `.claude/skills/` 下有 symlink
5. Agent 能原生使用 Grimo 管理的 skill
6. 派遣結束後 symlink 被清理

## 參考

- [Agent Sandbox](https://springaicommunity.mintlify.app/projects/incubating/agent-sandbox) — Spring AI Community
- [Agent Skills Progressive Disclosure](https://agentskills.io/client-implementation/adding-skills-support) — agentskills.io
- [Claude Code Skills](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview) — Anthropic
- [Agent Client](https://spring-ai-community.github.io/agent-client/) — Spring AI Community
