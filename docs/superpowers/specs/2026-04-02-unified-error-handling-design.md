# 統一錯誤處理：ResponseCallback + ChatDispatcher

> Rich callback（onSuccess/onError）+ 統一 handleResponse/handleError + 詳細 log。

## 目標

1. `ResponseCallback` 區分 `onSuccess` / `onError` — Adapter 能正確渲染（AI 回應 vs 錯誤訊息）
2. ChatDispatcher 統一 `handleResponse()` + `handleError()` — 不散落各處
3. Log 完整 context + full stack trace（開發者用）
4. User message 短、友善、可行動（使用者用）
5. 不 log and throw — 只在最終處理處 log 一次

## 設計原則

> **Java 錯誤處理最佳實踐：**
> - 不 log and throw（選一個：處理 or 傳播）
> - SLF4J 最後 Throwable 參數自動印 full stack trace
> - User message ≠ Developer log — 完全分離
> - 用具體類型分類，不 catch generic Throwable

## 變更

### 1. InputPort.ResponseCallback（2 方法取代 1 方法）

```java
public interface InputPort {

    interface ResponseCallback {
        /** Agent 成功回應 — Adapter 用 appendAiReply 渲染 */
        void onSuccess(String result);
        /** 錯誤 — Adapter 用 appendError 渲染 */
        void onError(String userMessage);
    }
}
```

### 2. ChatDispatcher 統一方法

**`handleResponse()`** — 驗證 response、log、session、callback：
- result 有值 → `callback.onSuccess(result)` + session write
- result 空/null → `callback.onError(...)` + log warn

**`handleError()`** — 分類錯誤、log（含 stack trace）、callback：
- `log.error("[AGENT-ERROR] agent={}, model={}, duration={}ms, goal={}", ..., exception)` — SLF4J 自動印 stack trace
- `callback.onError(classifyAndFormat(agentId, exception))` — 使用者友善訊息

**`classifyAndFormat()`** — 根據 exception 類名格式化：
- TimeoutException → "⚠ {agent} 回應逾時。"
- *NotFound*/*Process* → "⚠ {agent} CLI 未安裝。"
- *Auth* → "⚠ {agent} 認證失敗。"
- 其他 → "⚠ {agent} 執行錯誤：{message}"

### 3. 每個 dispatch 方法只有 try-catch

```java
public void dispatch(String text, ResponseCallback callback) {
    Thread.startVirtualThread(() -> {
        String agentId = null; String model = null; long start = currentTimeMillis();
        try {
            // resolve agent, build client, execute
            handleResponse(callback, agentId, model, text, start, response);
        } catch (Exception e) {
            handleError(callback, agentId, model, text, start, e);
        }
    });
}

public void dispatchTo(String agentId, String text, ResponseCallback callback) {
    Thread.ofVirtual().start(() -> {
        long start = currentTimeMillis();
        try {
            // build client, execute
            handleResponse(callback, agentId, model, text, start, response);
        } catch (Exception e) {
            handleError(callback, agentId, model, text, start, e);
        }
    });
}
```

### 4. TuiAdapter callback 更新

```java
inputPort.handleInput(text, metadata, new InputPort.ResponseCallback() {
    @Override public void onSuccess(String result) {
        contentView.removeLastLine();  // 移除 ⏳ thinking
        contentView.appendAiReply(result);
        eventLoop.setDirty();
    }
    @Override public void onError(String message) {
        contentView.removeLastLine();  // 移除 ⏳ thinking
        contentView.appendError(message);
        eventLoop.setDirty();
    }
});
```

Command 結果走 `onSuccess`（InputHandler 中同步指令回傳）。

### 5. InputHandler 適配

```java
// 同步指令結果
String result = commandDispatcher.execute(name, args);
if (result != null && !result.isEmpty()) {
    callback.onSuccess(result);  // was: callback.onResponse(result)
}

// AI 對話
chatDispatcher.dispatch(text, callback);  // ChatDispatcher 內部呼叫 onSuccess/onError
```

### 6. Log 格式

```
成功：
[AGENT-RESPONSE] agent=claude, model=claude-sonnet-4-6, success=true, duration=4308ms, len=56

空回應：
[AGENT-EMPTY] agent=codex, model=o4-mini, success=false, duration=2883ms, goal=hi 你是誰?

錯誤（含 full stack trace）：
[AGENT-ERROR] agent=gemini, model=gemini-2.5-pro, duration=120000ms, goal=hi
org.springaicommunity.agents.geminisdk.exceptions.GeminiSDKException: Command execution failed...
    at org.springaicommunity.agents.gemini.GeminiAgentModel.executeViaSandbox(...)
    at ...（完整 stack trace）
```

## 影響範圍

| 檔案 | 變更 |
|------|------|
| `command/InputPort.java` | ResponseCallback: `onResponse` → `onSuccess` + `onError` |
| `ChatDispatcher.java` | 統一 handleResponse/handleError，移除散落的 error 處理 |
| `InputHandler.java` | `callback.onResponse` → `callback.onSuccess` |
| `TuiAdapter.java` | callback 改用 onSuccess/onError |
| `InputHandlerTest.java` | 更新 callback mock |

## 測試

| 案例 | 驗證 |
|------|------|
| 成功回應 | `callback.onSuccess(result)` 被呼叫 |
| 空回應 | `callback.onError(...)` 被呼叫，訊息含 agent name |
| Timeout | `callback.onError(...)` 被呼叫，訊息含「逾時」 |
| Exception | `callback.onError(...)` 被呼叫，log 含 full stack trace |
| 指令結果 | `callback.onSuccess(result)` 被呼叫 |

## 驗收標準

1. `./gradlew build` 通過
2. 空回應顯示 `⚠ codex 回應為空` 而非空白 ⏺
3. 逾時顯示 `⚠ gemini 回應逾時` 而非 hang
4. Log 含 `[AGENT-ERROR]` + full stack trace
5. 不再有 `callback.onResponse("Error: " + e.getMessage())` 這種 ad-hoc 錯誤
