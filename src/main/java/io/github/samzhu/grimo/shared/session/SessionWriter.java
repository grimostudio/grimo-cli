package io.github.samzhu.grimo.shared.session;

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
 * - 每次 TUI session 的對話紀錄持久化到 ~/.grimo/projects/<encoded-cwd>/<uuid>.jsonl
 * - 對齊 Claude Code 的 projects/{encoded-cwd}/{sessionId}.jsonl 結構
 * - 每行一個 JSON 物件，包含 type/sessionId/timestamp/uuid/parentUuid/message 欄位
 * - 寫入時機：session 啟動（system）、使用者按 Enter（user）、AI 回覆完成（assistant）、命令執行完成（command）
 *
 * @see <a href="https://jsonlines.org/">JSON Lines specification</a>
 */
public class SessionWriter {

    private final ObjectMapper mapper = new ObjectMapper();
    private final String sessionId;
    private final Path dataDir;
    private final Path sessionFile;
    private String lastUuid;

    /**
     * 建構子：session 檔案直接放在 project data 根目錄。
     * 對齊 Claude Code 的 projects/{encoded-cwd}/{sessionId}.jsonl 結構。
     *
     * @param dataDir 專案資料目錄（如 ~/.grimo/projects/{encoded-cwd}/）
     */
    public SessionWriter(Path dataDir) {
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);
        this.dataDir = dataDir;
        this.sessionFile = dataDir.resolve(sessionId + ".jsonl");
    }

    /**
     * dispatch 紀錄存放目錄：{dataDir}/{sessionId}/dispatches/
     * 對齊 Claude Code 的 {sessionId}/subagents/ 結構。
     */
    public Path dispatchesDir() {
        return dataDir.resolve(sessionId).resolve("dispatches");
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

    /**
     * 寫入 dispatch-entered 摘要到主 session JSONL。
     * 記錄 skill dispatch 開始時的上下文資訊（agent、model、tier、branch、目標）。
     */
    public void writeDispatchEntered(String taskId, String agent, String model,
                                      String tier, String branchName, String goal) {
        var uuid = newUuid();
        var node = createBase("dispatch-entered", uuid, lastUuid);
        node.put("taskId", taskId);
        node.put("agent", agent);
        node.put("model", model);
        node.put("tier", tier);
        node.put("branchName", branchName);
        node.put("goal", goal);
        appendLine(node);
        lastUuid = uuid;
    }

    /**
     * 寫入 dispatch-completed 摘要到主 session JSONL，
     * 並建立 {sessionId}/dispatches/{taskId}.meta.json。
     * 完成時記錄執行結果摘要（changes、commits、duration），
     * 詳細 metadata 寫入獨立 meta.json 供後續查閱。
     */
    public void writeDispatchCompleted(String taskId, String agent, String model,
                                        String tier, String goal,
                                        String executionMode, String workDir,
                                        String branchName, String baseSha,
                                        String containerId,
                                        boolean hasChanges, int commitCount,
                                        String diffStat, long durationMs,
                                        String summary, String externalSessionPath) {
        // 1. 主 session JSONL 摘要
        var uuid = newUuid();
        var node = createBase("dispatch-completed", uuid, lastUuid);
        node.put("taskId", taskId);
        node.put("hasChanges", hasChanges);
        node.put("commitCount", commitCount);
        node.put("diffStat", diffStat);
        node.put("durationMs", durationMs);
        node.put("summary", summary);
        appendLine(node);
        lastUuid = uuid;

        // 2. meta.json
        writeDispatchMeta(taskId, agent, model, tier, goal,
                executionMode, workDir, branchName, baseSha, containerId,
                hasChanges, commitCount, diffStat, durationMs, summary,
                externalSessionPath);
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

    /**
     * Dispatch 完成後寫入詳細 metadata 到 {sessionId}/dispatches/{taskId}.meta.json。
     * 包含執行環境（mode/workDir/branch/sha）、結果摘要、外部 session 路徑。
     * 寫入失敗不中斷 TUI 運作（靜默處理 IOException）。
     */
    private void writeDispatchMeta(String taskId, String agent, String model,
                                    String tier, String goal,
                                    String executionMode, String workDir,
                                    String branchName, String baseSha,
                                    String containerId,
                                    boolean hasChanges, int commitCount,
                                    String diffStat, long durationMs,
                                    String summary, String externalSessionPath) {
        try {
            Path dir = dispatchesDir();
            Files.createDirectories(dir);
            var meta = mapper.createObjectNode();
            meta.put("taskId", taskId);
            meta.put("agent", agent);
            meta.put("model", model);
            meta.put("tier", tier);
            meta.put("goal", goal);

            var exec = mapper.createObjectNode();
            exec.put("mode", executionMode);
            exec.put("workDir", workDir);
            exec.put("branchName", branchName);
            exec.put("baseSha", baseSha);
            if (containerId != null) exec.put("containerId", containerId);
            meta.set("execution", exec);

            var result = mapper.createObjectNode();
            result.put("hasChanges", hasChanges);
            result.put("commitCount", commitCount);
            result.put("diffStat", diffStat);
            result.put("durationMs", durationMs);
            result.put("summary", summary);
            meta.set("result", result);

            if (externalSessionPath != null) {
                var ext = mapper.createObjectNode();
                ext.put(agent, externalSessionPath);
                meta.set("externalSessions", ext);
            }

            var writer = mapper.writerWithDefaultPrettyPrinter();
            Files.writeString(dir.resolve(taskId + ".meta.json"),
                    writer.writeValueAsString(meta));
        } catch (IOException e) {
            // Dispatch meta 寫入失敗不中斷 TUI 運作
        }
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
