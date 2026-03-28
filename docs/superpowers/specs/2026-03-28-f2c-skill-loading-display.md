# F2-c: Skill 載入 TUI 顯示 + Log

> Date: 2026-03-28
> Status: Done
> Phase: 1（基礎設施）
> Parent: [F2 Spec](2026-03-27-f2-skill-standard-compatibility.md)

## 問題

Skill 載入過程完全靜默 — 沒有 log、沒有 TUI 回饋。使用者不知道哪些 skill 已載入。

## 目標

1. 啟動時在 Content 區逐一顯示已載入的 skill（對齊 Claude Code 風格）
2. 加入 `log.info()` 方便 debug

## 設計

### TUI 顯示

Content 區在 Banner 之後、使用者第一次輸入之前，顯示每個載入的 skill：

```
● Skill(brainstorming)
  └ Successfully loaded skill
● Skill(writing-plans)
  └ Successfully loaded skill
```

- `●` 用綠色（ANSI green, color index 2）
- Skill 名稱用預設色
- `└ Successfully loaded skill` 用灰色（ANSI 245）
- 沒有 skill 時不顯示任何東西

### Log

- 每個 skill 載入成功：`log.info("Loaded skill: {}", skill.name())`
- 載入失敗（既有行為保留）：`log.warn("Skill loading failed: {}", e.getMessage())`

### 實作方式

`loadSkills()` 在 Phase 2 執行（TUI 尚未啟動），log 在此加入。
Phase 4 建立 contentView 後，遍歷 `skillRegistry.listAll()` 把格式化訊息 append 到 Content 區。

## 影響範圍

| 動作 | 檔案 | 變更 |
|------|------|------|
| Modify | `GrimoTuiRunner.java` | `loadSkills()` 加 log.info；Phase 4 加 skill 載入顯示到 Content 區 |

不需修改其他檔案。

## 驗證方式

1. 啟動 Grimo，Content 區在 Banner 下方顯示所有已載入 skill
2. 日誌中出現 `Loaded skill: brainstorming` 等 info 訊息
3. 沒有 skill 時不顯示任何東西
