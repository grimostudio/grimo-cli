# Grimo CLI Agent Orchestration Platform — PRD

> Date: 2026-03-27
> Status: Draft
> Author: Sam Zhu + Claude

## 產品定位

Grimo 是一個本地運行的 **CLI Agent 調度平台**。它不是 AI agent，而是坐在 Claude Code、Gemini CLI、Codex CLI 等工具之上的調度層。使用者透過 Grimo 統一管理多個 CLI agent，實現成本最佳化和多 agent 協作。

**一句話：** 單一 CLI agent 做不到的事 — 跨 agent 調度、成本最佳化、多 agent 協作。

## 架構總覽

```
┌───────────────────────────────────────────────┐
│              Grimo 調度層                      │
│                                               │
│  ┌─────────────────────────────────────────┐  │
│  │  Tier Router                            │  │
│  │  決定用什麼等級（lite/std/pro）          │  │
│  └─────────────────────────────────────────┘  │
│  ┌─────────────────────────────────────────┐  │
│  │  Sub-Agent Dispatcher                   │  │
│  │  派遣多個 agent、收集結果               │  │
│  └─────────────────────────────────────────┘  │
│  ┌─────────────────────────────────────────┐  │
│  │  Grimo Skill Engine                     │  │
│  │  讀取調度指令（不是執行指令）            │  │
│  └─────────────────────────────────────────┘  │
│  ┌─────────────────────────────────────────┐  │
│  │  MCP Catalog                            │  │
│  │  統一定義、自動分發給各 agent            │  │
│  └─────────────────────────────────────────┘  │
└─────┬──────────────┬──────────────┬───────────┘
      │              │              │
   Claude Code    Gemini CLI    Codex CLI    ...
      │              │              │
   各自獨立運行    各自獨立運行   各自獨立運行
   讀自己的 skill  讀自己的 skill  讀自己的 skill
```

## 架構原則

1. **Grimo 是調度者，不是中間人** — 啟動 agent、收結果，不攔截 agent 內部過程
2. **Agent 獨立運行** — 各 agent 讀自己的 skill、MCP、config，Grimo 不控制 agent 內部行為
3. **Grimo Skill = 調度指令** — 定義派誰、怎麼分工，不是給 agent 的執行指令
4. **Sub-agent context 隔離** — 每個 sub-agent 獨立 context，只透過 goal 傳遞必要資訊，不共享對話歷史
5. **相容業界標準** — SKILL.md 格式對齊 Agent Skills 開放標準（agentskills.io）
6. **Library over Starter** — CLI agent 作為 plain Java objects 管理，不依賴 Spring auto-config

## 目標使用者

- 已安裝 1 個以上 CLI agent 的開發者
- 想省錢：用便宜模型做簡單事，貴模型做複雜事
- 想要多觀點：多個 agent 同時 review / 研究
- 不想手動管理多個 CLI 視窗

## 核心差異化

| 能力 | 單獨使用 CLI Agent | 透過 Grimo |
|------|-------------------|------------|
| 多觀點審查 | 只有一個模型的看法 | N 個 agent 平行審查 |
| 成本最佳化 | 固定用一個模型 | Tier 制：便宜的做簡單事，貴的做複雜事 |
| 容錯 | 一個掛了就沒了 | 自動 fallback 到其他 agent |
| MCP 管理 | 每個 CLI 各自設定 | 定義一次，Portable MCP 自動分發 |
| Skill 跨 agent | Skill 綁定特定 CLI | Grimo Skill 定義調度，任何 agent 可執行 |
| 分工交付 | 做不到 | pro 模型規劃 → lite 模型執行 |

## Feature 清單

| ID | Feature | Spec 文件 | Phase |
|----|---------|-----------|-------|
| F0 | 資料目錄遷移 ~/grimo-workspace → ~/.grimo | [F0 Spec](2026-03-27-grimo-home-directory.md) | Phase 1 |
| F1 | MCP Catalog 接通 AgentClient | [F1 Spec](2026-03-27-f1-mcp-catalog-wiring.md) | Phase 1 |
| F2 | SkillDefinition 對齊 Agent Skills 標準 | [F2 Spec](2026-03-27-f2-skill-standard-compatibility.md) | Phase 1 |
| F3 | Tier 三級制（lite/std/pro） | [F3 Spec](2026-03-27-f3-tier-system.md) | Phase 2 |
| F4 | Sub-Agent 調度系統 | [F4 Spec](2026-03-27-f4-subagent-dispatch.md) | Phase 3 |
| F5 | Session 管理 | [F5 Spec](2026-03-27-f5-session-management.md) | Phase 4 |
| F6 | Judge 品質閘門 | [F6 Spec](2026-03-27-f6-judge-quality-gates.md) | Phase 4 |

## Phase 規劃

```
Phase 1 — 基礎設施修正
  F0: 資料目錄遷移到 ~/.grimo
  F1: MCP Catalog → AgentClient
  F2: SkillDefinition 標準相容
  目標：現有功能穩定、標準相容、目錄結構對齊業界

Phase 2 — 成本最佳化
  F3: Tier 三級制
  目標：Skill 執行自動選擇最適合的 agent/model

Phase 3 — 核心差異化
  F4: Sub-Agent 調度
  目標：多 agent 平行/分工，Grimo 的獨特價值

Phase 4 — 增強
  F5: Session 管理
  F6: Judge 品質閘門
  目標：完善使用體驗和品質保證
```

## 技術棧

- Java 25（Virtual Threads）
- Spring Boot 4.0.x + Spring Shell 4.0.x + Spring Modulith 2.0.x
- Spring AI Community Agent Client 0.10.0-SNAPSHOT（Library mode）
- JLine 3（TUI rendering）
- AgentClient API：目前使用 `AgentClient.create(model).goal(text).workingDirectory(path).run()`（F1 實作時需驗證是否有 builder pattern）

## 參考資料

- [Spring AI Community Agent Client](https://spring-ai-community.github.io/agent-client/)
- [Agent Skills Open Standard](https://agentskills.io/specification)
- [Claude Code Sub-Agents](https://code.claude.com/docs/en/sub-agents)
- [Anthropic: Building Effective Agents](https://www.anthropic.com/research/building-effective-agents)
- [RouteLLM: Cost-efficient Model Routing](https://github.com/lm-sys/RouteLLM)
- [MindStudio: 3-Tier Model Router](https://www.mindstudio.ai/blog/set-up-ai-model-router-llm-stack-c2610)
- [everything-claude-code](https://github.com/affaan-m/everything-claude-code)
- [gstack](https://github.com/garrytan/gstack)
