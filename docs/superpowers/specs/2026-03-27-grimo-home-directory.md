# 應用程式資料目錄遷移：~/grimo-workspace → ~/.grimo

> Date: 2026-03-27
> Status: Draft
> Parent: [PRD](2026-03-27-grimo-orchestration-platform-prd.md)

## 問題

目前 Grimo 的所有資料存在 `~/grimo-workspace/`。這個路徑：
- 不符合 Unix 慣例（dotfile 用於應用程式資料）
- 在 home 目錄下可見，佔用使用者視覺空間
- 跟業界工具不一致（Claude Code 用 `~/.claude/`、Gemini CLI 用 `~/.gemini/`）

## 目標

將所有應用程式資料統一到 `~/.grimo/`，對齊業界慣例。只做使用者級，專案級 `.grimo/` 不在此次範圍。

## 設計

### 新目錄結構

```
~/.grimo/
├── config.yaml              # 使用者設定
├── skills/                  # 使用者安裝的 Grimo Skill（SKILL.md）
│   └── <skill-name>/
│       └── SKILL.md
├── agents/                  # Sub-agent 定義檔（F4 新增）
│   └── <agent-name>.md
├── projects/                # Per-project 運行時資料
│   └── <encoded-cwd>/
│       └── sessions/
│           └── <uuid>.jsonl
├── tasks/                   # 排程任務（Markdown 持久化）
├── logs/                    # 應用程式 log
│   └── grimo.log
└── conversations/           # 對話紀錄（既有）
```

### 對比業界工具

| 工具 | 資料目錄 |
|------|---------|
| Claude Code | `~/.claude/` |
| Gemini CLI | `~/.gemini/` |
| Codex CLI | `~/.codex/` |
| OpenCode | `~/.config/opencode/` |
| **Grimo** | **`~/.grimo/`** |

### 變更範圍

| 檔案 | 變更 |
|------|------|
| `application.yaml` | `grimo.workspace` 從 `${user.home}/grimo-workspace` 改為 `${user.home}/.grimo` |
| `logback-spring.xml` | log 路徑從 `grimo-workspace/logs` 改為 `.grimo/logs` |
| `WorkspaceManager.java` | 新增 `agentsDir()` 方法（給 F4 Sub-agent 定義檔用） |

**不需要改的：** 所有透過 `WorkspaceManager` 讀路徑的程式碼（GrimoConfig、SkillLoader、SessionWriter、TaskStore 等），因為路徑來自 `WorkspaceManager.root()`，只改 root 就全部生效。

### 既有資料遷移

不做自動遷移。使用者手動操作：
```bash
mv ~/grimo-workspace ~/.grimo
```

在 release note 說明遷移方式。

### 環境變數覆蓋

使用者可以透過環境變數自訂路徑（既有機制，不需改動）：
```bash
GRIMO_WORKSPACE=~/my-custom-path grimo
```

## 驗證方式

1. 刪除 `~/grimo-workspace/`，啟動 Grimo
2. 確認 `~/.grimo/` 自動建立，含 skills/、tasks/、logs/ 子目錄
3. 確認 config.yaml 寫入 `~/.grimo/config.yaml`
4. 確認 log 寫入 `~/.grimo/logs/grimo.log`
5. 確認 session 寫入 `~/.grimo/projects/<encoded-cwd>/sessions/`
