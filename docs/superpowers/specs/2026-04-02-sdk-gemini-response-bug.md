# SDK Bug: Gemini Agent Response 包含 raw CLI output

> Spring AI Community Agent Client SDK (`agent-gemini` 0.11.0) 的 Gemini response 解析有兩個 bug。

## 現象

使用 Gemini agent 對話時，`AgentClientResponse.getResult()` 回傳的不是乾淨的 AI 回應，而是：

```
TextMessage[type=ASSISTANT, content=你好！我能為您做什麼？
YOLO mode is enabled. All tool calls will be automatically approved.
Keychain initialization encountered an error: Cannot find module '../build/Release/keytar.node'
...
Using FileKeychain fallback for secure storage.
Loaded cached credentials.
YOLO mode is enabled. All tool calls will be automatically approved.]
```

期望回傳：`你好！我能為您做什麼？`

## 根因分析（反編譯 SDK 原始碼確認）

### Bug 1: `GeminiAgentModel.convertResult()` 用 `Object::toString()`

**位置：** `agent-gemini-0.11.0.jar` → `GeminiAgentModel.java:226`

```java
// 現狀（錯誤）
String combinedText = result.messages().stream()
    .map(Object::toString)                    // ← 回傳 "TextMessage[type=ASSISTANT, content=...]"
    .collect(Collectors.joining("\n"));

// 應該是
String combinedText = result.messages().stream()
    .map(Message::getContent)                 // ← 回傳純文字 "你好！我能為您做什麼？"
    .collect(Collectors.joining("\n"));
```

`TextMessage` 是 Java record，`toString()` 回傳 `TextMessage[type=ASSISTANT, content=...]` 格式。應該呼叫 `getContent()` 或 `content()` 取得純文字。

### Bug 2: `CLITransport.parseResponse()` 不過濾 stderr

**位置：** `gemini-cli-sdk-0.11.0.jar` → `CLITransport.java:190-198`

```java
private List<Message> parseResponse(String output) {
    ArrayList<Message> messages = new ArrayList<>();
    if (output == null || output.trim().isEmpty()) {
        messages.add(TextMessage.error("Empty response from Gemini CLI"));
        return messages;
    }
    messages.add(TextMessage.assistant(output.trim()));  // ← 整個 stdout 包進去，含 stderr 雜訊
    return messages;
}
```

Gemini CLI（`gemini -p "hi" -y`）的 stdout 包含：
- CLI 啟動訊息（`YOLO mode is enabled`）
- Keychain 錯誤（`Cannot find module '../build/Release/keytar.node'`）
- 認證訊息（`Loaded cached credentials`）
- **實際 AI 回應**
- 429 retry 錯誤（`No capacity available for model`，多次）

`parseResponse()` 沒有解析和過濾，直接把整個 output 包成一個 `TextMessage`。

### 對比 Claude SDK 的做法

`agent-claude` SDK 的 `ClaudeCliTransport` 有完整的 output 解析：
- 逐行解析 JSON events
- 區分 `assistant`、`tool_use`、`result` 等 message type
- 只提取 AI 回應文字

Gemini SDK 缺少這層解析。

## 影響

| SDK 版本 | 模組 | 影響 |
|---------|------|------|
| `agent-gemini` 0.11.0 | `GeminiAgentModel.convertResult()` | `getResult()` 回傳 `TextMessage[...]` 格式 |
| `gemini-cli-sdk` 0.11.0 | `CLITransport.parseResponse()` | AI 回應混入 CLI stderr 雜訊 |

## 後續行動

### Phase 1: Grimo workaround（短期）

在 `ChatDispatcher` 或 `InputHandler` 層對 Gemini response 做 post-processing：

```java
// 擷取 TextMessage content
if (result.startsWith("TextMessage[type=ASSISTANT, content=")) {
    result = result.substring("TextMessage[type=ASSISTANT, content=".length());
    if (result.endsWith("]")) result = result.substring(0, result.length() - 1);
}
// 過濾 CLI noise（YOLO, keychain, retry）
result = filterCliNoise(result);
```

### Phase 2: SDK Issue + PR（中期）

1. 在 [spring-ai-community/agent-client](https://github.com/spring-ai-community/agent-client) 開 issue
2. 提交 PR 修正：
   - `GeminiAgentModel.convertResult()`: `Object::toString` → `Message::getContent`
   - `CLITransport.parseResponse()`: 解析 Gemini CLI output，過濾非 AI 回應內容

### Phase 3: SDK 升級（長期）

SDK 修正後升級 Grimo 的 dependency，移除 workaround。

## 發現方式

使用 `depx` skill 反編譯 `agent-gemini-0.11.0.jar` 和 `gemini-cli-sdk-0.11.0.jar` 原始碼分析。

## 相關檔案

- 反編譯原始碼：`.depx/source/agent-gemini/org/springaicommunity/agents/gemini/GeminiAgentModel.java`
- 反編譯原始碼：`.depx/source/gemini-cli-sdk/org/springaicommunity/agents/geminisdk/transport/CLITransport.java`
- SDK repo：https://github.com/spring-ai-community/agent-client
