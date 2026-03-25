package io.github.samzhu.grimo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/**
 * Session 對話存檔：JSONL（JSON Lines）格式，append-only。
 *
 * 設計說明：
 * - 每次 TUI session 的對話紀錄持久化到 ~/grimo-workspace/projects/<encoded-cwd>/sessions/<uuid>.jsonl
 * - 對齊 Claude Code 的 session 檔案設計
 * - 每行一個 JSON 物件，包含 type/sessionId/timestamp/uuid/parentUuid/message 欄位
 * - 寫入時機：session 啟動（system）、使用者按 Enter（user）、AI 回覆完成（assistant）、命令執行完成（command）
 *
 * @see <a href="https://jsonlines.org/">JSON Lines specification</a>
 */
public class SessionWriter {

    private final ObjectMapper mapper = new ObjectMapper();
    private final String sessionId;
    private final Path sessionFile;
    private String lastUuid;

    /**
     * 建構子：建立 session 檔案路徑。
     *
     * @param sessionsBaseDir 基礎目錄（如 ~/grimo-workspace/projects/<encoded-cwd>/sessions/）
     */
    public SessionWriter(Path sessionsBaseDir) {
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);
        this.sessionFile = sessionsBaseDir.resolve(sessionId + ".jsonl");
    }

    /**
     * 初始化：建立目錄並寫入 system 訊息。
     */
    public void writeSystemMessage(String cwd, String version, String systemPrompt) {
        try {
            Files.createDirectories(sessionFile.getParent());
        } catch (IOException e) {
            // 目錄已存在或無法建立，忽略
        }
        var uuid = newUuid();
        var node = createBase("system", uuid, null);
        node.put("cwd", cwd);
        node.put("version", version);
        var message = mapper.createObjectNode();
        message.put("role", "system");
        message.put("content", systemPrompt);
        node.set("message", message);
        appendLine(node);
        lastUuid = uuid;
    }

    /**
     * 寫入使用者輸入。
     */
    public void writeUserMessage(String content) {
        var uuid = newUuid();
        var node = createBase("user", uuid, lastUuid);
        var message = mapper.createObjectNode();
        message.put("role", "user");
        message.put("content", content);
        node.set("message", message);
        appendLine(node);
        lastUuid = uuid;
    }

    /**
     * 寫入 AI 回覆。
     */
    public void writeAssistantMessage(String content) {
        var uuid = newUuid();
        var node = createBase("assistant", uuid, lastUuid);
        var message = mapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content);
        node.set("message", message);
        appendLine(node);
        lastUuid = uuid;
    }

    /**
     * 寫入命令執行結果。
     */
    public void writeCommandMessage(String commandName, String output) {
        var uuid = newUuid();
        var node = createBase("command", uuid, lastUuid);
        node.put("command", commandName);
        var message = mapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", output);
        node.set("message", message);
        appendLine(node);
        lastUuid = uuid;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Path getSessionFile() {
        return sessionFile;
    }

    // === 內部方法 ===

    private ObjectNode createBase(String type, String uuid, String parentUuid) {
        var node = mapper.createObjectNode();
        node.put("type", type);
        node.put("sessionId", sessionId);
        node.put("timestamp", Instant.now().toString());
        node.put("uuid", uuid);
        if (parentUuid != null) {
            node.put("parentUuid", parentUuid);
        }
        return node;
    }

    private String newUuid() {
        return "msg-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void appendLine(ObjectNode node) {
        try {
            String json = mapper.writeValueAsString(node);
            Files.writeString(sessionFile, json + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Session 寫入失敗不中斷 TUI 運作
        }
    }
}
