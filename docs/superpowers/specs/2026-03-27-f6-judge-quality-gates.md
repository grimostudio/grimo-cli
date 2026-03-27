# F6: Judge 品質閘門

> Date: 2026-03-27
> Status: Draft
> Phase: 4（增強）
> Parent: [PRD](2026-03-27-grimo-orchestration-platform-prd.md)

## 問題

Sub-agent 執行完 Skill 後，沒有自動驗證機制。使用者必須手動確認結果是否正確（build 有沒有過、test 有沒有跑）。

## 目標

Skill 可以定義品質閘門，sub-agent 完成後自動驗證結果。

## 設計

### Skill metadata 定義 Judge

```yaml
---
name: tdd-workflow
description: TDD 開發流程
metadata:
  grimo.tier: std
  grimo.judges: '[{"type":"build-success"},{"type":"test-pass"}]'
---
```

### Judge 類型

| 類型 | 說明 | 驗證方式 |
|------|------|---------|
| `build-success` | 建置成功 | 執行 `./gradlew build`，檢查 exit code |
| `test-pass` | 測試通過 | 執行 `./gradlew test`，檢查 exit code |
| `files-exist` | 預期檔案存在 | 檢查指定路徑 |
| `custom-command` | 自訂指令 | 執行使用者指定的 shell command |

### 執行流程

```
Sub-Agent 完成
  ↓
Grimo 讀 Skill 的 judges
  ↓
逐一執行 judge
  ├─ ✓ PASS → 顯示通過
  └─ ✗ FAIL → 顯示失敗原因
      ↓
      可選：自動重試（把錯誤訊息餵回 agent）
```

### 與 Spring AI Agent Client 的整合

> **⚠ API 待驗證**：以下 JudgeAdvisor / BuildSuccessJudge API 來自 Agent Client 文件
> （https://spring-ai-community.github.io/agent-client/judges/index.html），
> 實作前需確認 0.10.0-SNAPSHOT 是否包含 judge 模組。
> 如果不包含，需自行實作 judge 邏輯（用 shell command 執行 build/test 並檢查 exit code）。

**方案 A：如果 Agent Client Judge API 存在**

```java
AgentClient.builder(model)
    .defaultAdvisors(List.of(
        JudgeAdvisor.builder()
            .judge(new BuildSuccessJudge())
            .build()
    ))
    .build()
    .goal(text)
    .run();
```

**方案 B：如果 Judge API 不存在（自行實作）**

```java
// Sub-agent 完成後，Grimo 自己執行 judge
AgentClientResponse response = agentClient.goal(text).run();
for (JudgeSpec judge : skill.judges()) {
    JudgeResult result = judgeExecutor.execute(judge, workingDirectory);
    if (result.failed()) {
        // 顯示失敗，可選重試
    }
}
```

Grimo 根據 Skill metadata 的 `grimo.judges` 動態組合 judge list。

**Judge 不限於 sub-agent**：主 agent 執行 Skill 時也可以觸發 judge。

### 重試機制（可選）

```yaml
metadata:
  grimo.judges: '[{"type":"build-success","retry":2}]'
```

`retry: 2` = 最多重試 2 次。重試時把錯誤訊息加入 goal：

```
「上次執行失敗。錯誤：build failed at line 42, NullPointerException。
請修復後重試。」
```

## 新增元件

| 元件 | 職責 |
|------|------|
| `JudgeExecutor` | 執行 judge 驗證、處理重試邏輯 |
| `JudgeFactory` | 根據 type 建立對應的 Judge 實例 |
| `JudgeResult` | 封裝驗證結果（pass/fail、原因、耗時） |

## 影響範圍

| 檔案 | 變更 |
|------|------|
| `SubAgentDispatcher.java` | dispatch 完成後執行 judge |
| 新增 `orchestration/judge/` package | JudgeExecutor、JudgeFactory |
| `GrimoContentView.java` | 顯示 judge 結果 |

## 參考

- [Spring AI Agent Client — Judge API](https://spring-ai-community.github.io/agent-client/judges/index.html)
- [BuildSuccessJudge, FileExistsJudge, CommandJudge](https://spring-ai-community.github.io/agent-client/judges/index.html)
