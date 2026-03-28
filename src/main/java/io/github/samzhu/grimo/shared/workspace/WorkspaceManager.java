package io.github.samzhu.grimo.shared.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class WorkspaceManager {

    private final Path root;

    public WorkspaceManager(Path root) {
        this.root = root;
    }

    /**
     * 初始化 workspace：建立所有必要目錄，並在 config.yaml 不存在時建立帶範例的預設檔。
     *
     * 設計說明：
     * - 預設 config.yaml 包含註解範例，讓使用者知道可以設定什麼
     * - 已存在的 config.yaml 不會被覆蓋
     */
    public void initialize() {
        createDir(tasksDir());
        createDir(skillsDir());
        createDir(conversationsDir());
        createDir(logsDir());
        createDir(agentsDir());   // F4 Sub-Agent 定義檔目錄
        createDefaultConfig();
    }

    private void createDefaultConfig() {
        Path config = configFile();
        if (Files.exists(config)) {
            return;
        }
        try {
            Files.writeString(config, DEFAULT_CONFIG);
        } catch (IOException e) {
            // config 建立失敗不中斷啟動，使用者可以手動建立
        }
    }

    private static final String DEFAULT_CONFIG = """
            # Grimo CLI 設定檔
            # 文件位置：~/.grimo/config.yaml

            # Agent 設定
            #agents:
            #  default: claude          # 預設使用的 agent（claude / gemini / codex）
            #  model: claude-sonnet-4-5 # 預設模型

            # Per-agent 設定
            #agent-options:
            #  claude:
            #    model: claude-sonnet-4-5
            #  gemini:
            #    model: gemini-2.5-flash
            #  codex:
            #    model: o4-mini

            # MCP Server 定義（Portable MCP，自動轉成各 CLI 原生格式）
            # 支援 type: stdio / sse / http
            #mcp:
            #  filesystem:
            #    type: stdio
            #    command: npx
            #    args: [-y, "@modelcontextprotocol/server-filesystem", /tmp]
            #  deepwiki:
            #    type: sse
            #    url: https://mcp.deepwiki.com/sse
            """;

    public boolean isInitialized() {
        return Files.isDirectory(tasksDir())
            && Files.isDirectory(skillsDir());
    }

    public Path root()              { return root; }
    public Path tasksDir()          { return root.resolve("tasks"); }
    public Path skillsDir()         { return root.resolve("skills"); }
    public Path conversationsDir()  { return root.resolve("conversations"); }
    public Path logsDir()           { return root.resolve("logs"); }
    public Path configFile()        { return root.resolve("config.yaml"); }
    public Path agentsDir()         { return root.resolve("agents"); }

    private void createDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create directory: " + dir, e);
        }
    }
}
