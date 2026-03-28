# F2: SkillDefinition 對齊 Agent Skills 標準

> Date: 2026-03-27
> Status: Done
> Phase: 1（基礎設施）
> Parent: [PRD](2026-03-27-grimo-orchestration-platform-prd.md)

## 問題

Grimo 目前的 SkillDefinition 使用自訂欄位（`triggers`、`version`、`author`、`executor`），跟 Agent Skills 開放標準（agentskills.io）及 Claude Code 的 SKILL.md 格式不相容。安裝市面上為 Claude Code / Gemini CLI 寫的 Skill 會解析失敗或遺漏重要欄位。

## 目標

1. 相容 Agent Skills 開放標準的所有欄位
2. 相容 Claude Code 擴充欄位
3. Grimo 自有擴充放在 `metadata` map 裡（標準的擴充點）
4. 第三方 Skill 安裝到 Grimo 不會壞

## Agent Skills 開放標準（agentskills.io）

| 欄位 | 必填 | 說明 |
|------|------|------|
| `name` | ✓ | 小寫 `[a-z0-9-]`，需跟目錄名一致，max 64 chars |
| `description` | ✓ | max 1024 chars |
| `license` | | 授權（如 `Apache-2.0`） |
| `compatibility` | | 環境需求（如 `Requires Python 3.14+`） |
| `allowed-tools` | | 空格分隔的工具清單（實驗性） |
| `metadata` | | `map<string, string>` — 唯一官方擴充點 |

**標準以外的頂層欄位會導致驗證失敗。**

## Claude Code 額外支援的欄位

| 欄位 | 說明 |
|------|------|
| `model` | 執行時使用的模型 |
| `effort` | `low` / `medium` / `high` / `max` |
| `context` | `fork` = 隔離 subagent 執行 |
| `agent` | subagent 類型 |
| `user-invocable` | `false` = 只有 AI 能呼叫 |
| `disable-model-invocation` | `true` = 只有使用者能呼叫 |
| `paths` | Glob pattern 限制自動觸發範圍 |
| `argument-hint` | 自動補全提示 |
| `shell` | `bash` 或 `powershell` |

## Grimo 擴充欄位（放在 metadata 裡）

| metadata key | 說明 | 範例 |
|-------------|------|------|
| `grimo.tier` | Skill 建議的執行等級 | `lite` / `std` / `pro` |
| `grimo.subagents` | 要派遣的 sub-agent 列表 | JSON 字串 |
| `grimo.execution` | 調度方式 | `parallel` / `sequential` |
| `grimo.author` | 作者 | `grimo-team` |
| `grimo.version` | 版本 | `1.0.0` |

## 設計

### SkillDefinition 重構

```java
// 之前
public record SkillDefinition(
    String name,
    String description,
    String version,        // 移到 metadata
    String author,         // 移到 metadata
    String executor,       // 移除
    List<String> triggers, // 移除
    String body
) {}

// 之後
public record SkillDefinition(
    // Agent Skills 標準欄位
    String name,
    String description,
    String license,
    String compatibility,
    List<String> allowedTools,
    Map<String, String> metadata,
    // Claude Code 擴充欄位
    String model,
    String effort,
    String context,
    String agent,
    Boolean userInvocable,
    Boolean disableModelInvocation,
    String argumentHint,
    List<String> paths,
    // Body
    String body
) {
    // 便利方法：從 metadata 讀 Grimo 擴充
    public String grimoTier() {
        return metadata().getOrDefault("grimo.tier", "std");
    }

    public String grimoExecution() {
        // "parallel" = 平行多 sub-agent
        // "sequential" = 依序多 sub-agent
        // 無此欄位或其他值 = 單一 agent 執行（不派 sub-agent）
        return metadata().getOrDefault("grimo.execution", "");
    }

    public List<String> grimoSubagents() {
        // 解析 metadata 中的 grimo.subagents JSON
    }
}
```

### SkillLoader 變更

- 解析所有標準欄位 + Claude Code 欄位
- 未知欄位不報錯（forward compatibility）
- `metadata` 解析為 `Map<String, String>`
- 移除 `triggers`、`executor` 解析邏輯

### 向後相容

已存在的 Grimo Skill 如果用了舊欄位（`triggers`、`version`、`author`）：
- SkillLoader 發出 WARN log：「`triggers` is deprecated, use standard format」
- 自動映射：`version` → `metadata.grimo.version`，`author` → `metadata.grimo.author`
- `triggers` 和 `executor` 直接忽略

## 影響範圍

| 檔案 | 變更 |
|------|------|
| `SkillDefinition.java` | Record 重構（7 欄位 → 新結構） |
| `SkillLoader.java` | `parseSkillMd()` 完全重寫：移除 triggers/executor/version/author 解析，新增標準欄位 + metadata 解析 |
| `SkillCommands.java` | 顯示欄位調整（移除 version/author 直接存取，改從 metadata 讀取） |
| `GrimoCommandCompleter.java` | 適配新 SkillDefinition API |
| `SkillRegistryTest.java` | 更新 SkillDefinition 建構呼叫 |
| `SkillCommandsTest.java` | 更新 SkillDefinition 建構呼叫 |
| `SkillLoaderTest.java` | 重寫測試案例 |
| `GrimoCommandCompleterTest.java` | 更新 SkillDefinition 建構呼叫 |
| 既有 SKILL.md 檔案 | 遷移到新格式 |

## 範例：標準相容的 Grimo Skill

```yaml
---
name: multi-review
description: 多 agent 平行程式碼審查
license: MIT
metadata:
  grimo.tier: std
  grimo.author: grimo-team
  grimo.version: "1.0.0"
  grimo.subagents: '[{"name":"logic-review","goal":"Review for logic bugs","tier":"std"},{"name":"arch-review","goal":"Review for architecture","tier":"std"}]'
  grimo.execution: parallel
---

## 工作流程
1. 取得目標檔案或 git diff
2. 平行派遣所有 sub-agent
3. 收集結果，分區顯示
```

## 參考

- [Agent Skills Specification](https://agentskills.io/specification)
- [Claude Code Skills Documentation](https://code.claude.com/docs/en/skills)
