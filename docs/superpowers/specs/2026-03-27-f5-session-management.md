# F5: Session 管理

> Date: 2026-03-27
> Status: Draft
> Phase: 4（增強）
> Parent: [PRD](2026-03-27-grimo-orchestration-platform-prd.md)

## 問題

目前 SessionWriter 已記錄對話到 JSONL 檔案，但沒有任何指令可以查看、恢復或匯出歷史 session。每次啟動 Grimo 都是全新對話，之前的 context 完全消失。

## 目標

讓使用者能查看歷史 session、恢復之前的對話 context、匯出對話紀錄。

## 設計

### Session 生命週期

```
啟動 Grimo → 自動建立新 Session（UUID）
  ↓
對話中 → SessionWriter 持續寫入 JSONL
  ↓
結束 Grimo → Session 自動關閉
  ↓
下次啟動 → 新 Session，可選擇 resume 舊的
```

### 指令

| 指令 | 說明 |
|------|------|
| `/session list` | 列出歷史 session（時間、摘要、agent） |
| `/session resume <id>` | 載入舊 session 的摘要作為新對話的 context |
| `/session export <id>` | 匯出 session 為 markdown 檔案 |
| `/session info` | 顯示當前 session 資訊（ID、開始時間、訊息數） |

### Resume 機制

Resume 不是「恢復完整對話」（那會造成 context 爆炸），而是：

1. 讀取 JSONL 檔案
2. 用 lite agent 產出摘要（約 500-1000 token）
3. 把摘要作為新 goal 的 prefix 傳給 agent

```
❯ /session resume abc-123

  ⚙ 載入 session abc-123...
  ⚙ 產出摘要...

  已載入 session 摘要：
  「上次你在重構 auth module，完成了 TokenService interface 抽取，
   還沒做 AuthController 的注入修改。」

❯ 繼續改 AuthController
  → agent 收到的 goal：
    「[Session context: 上次在重構 auth module，完成了 TokenService
     interface 抽取，還沒做 AuthController 注入修改。]
     繼續改 AuthController」
```

### 儲存位置

沿用既有的 per-project session 結構（與現有 SessionWriter 一致）：

```
~/.grimo/projects/<encoded-cwd>/sessions/
  ├── <uuid>.jsonl                # 對話紀錄（既有）
  ├── <uuid>.meta.yaml            # 中繼資料（新增：開始時間、agent、摘要）
  └── ...
```

## 新增元件

| 元件 | 職責 |
|------|------|
| `SessionManager` | 管理 session 生命週期、列出/載入/匯出 |
| `SessionSummarizer` | 用 lite agent 產出 session 摘要 |
| `SessionCommands` | `/session` 系列指令 |

## 影響範圍

| 檔案 | 變更 |
|------|------|
| 新增 `shared/session/SessionManager.java` | Session CRUD |
| 新增 `shared/session/SessionSummarizer.java` | 摘要產生 |
| 新增 `shared/session/SessionCommands.java` | 指令 |
| `SessionWriter.java` | 移入 `shared/session/` package（目前在 root package） |
| `GrimoTuiRunner.java` | 啟動時建立 session、結束時關閉 |
