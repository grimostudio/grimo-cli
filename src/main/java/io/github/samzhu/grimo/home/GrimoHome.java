package io.github.samzhu.grimo.home;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 全域目錄管理：管理 ~/.grimo 下的固定目錄結構。
 *
 * 設計說明：
 * - 取代 WorkspaceManager 中全域目錄管理的職責
 * - 不包含 conversations 目錄（已棄用）
 * - 新增 projects 目錄，供 ProjectContext 儲存 per-project 資料
 * - 無參建構子預設指向 ~/.grimo，有參建構子供測試注入
 */
public class GrimoHome {

    private final Path root;

    /**
     * 正式環境用：預設 ~/.grimo
     */
    public GrimoHome() {
        this(Path.of(System.getProperty("user.home"), ".grimo"));
    }

    /**
     * 測試用：允許注入任意根目錄
     */
    public GrimoHome(Path root) {
        this.root = root;
    }

    /**
     * 初始化全域目錄結構：建立所有必要子目錄，並在 config.yaml 不存在時寫入預設範例。
     *
     * 設計說明：
     * - conversations 目錄已棄用，不再建立
     * - projects 目錄供 ProjectContext 使用，儲存 per-project 的對話與元資料
     * - 已存在的 config.yaml 不會被覆蓋，保留使用者自訂內容
     */
    public void initialize() {
        createDir(tasksDir());
        createDir(skillsDir());
        createDir(agentsDir());
        createDir(logsDir());
        createDir(projectsDir());
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
            #  model: claude-sonnet-4-6 # 預設模型

            # Per-agent 設定
            #agent-options:
            #  claude:
            #    model: claude-sonnet-4-6
            #  gemini:
            #    model: gemini-2.5-pro
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

            # Sandbox 設定（agent 執行環境）
            # skill 和 MCP 會自動配置到 sandbox 中讓 CLI agent 使用
            #sandbox:
            #  mode: local              # local | docker | e2b（預設 local）
            #  docker:
            #    image: ghcr.io/spring-ai-community/agents-runtime:latest
            #  e2b:
            #    api-key: ${E2B_API_KEY}
            #    template: base

            # Skill 三級對應表（每級多選項，按優先順序 fallback）
            skill-tiers:
              lite:
                - agent: gemini
                  model: gemini-2.5-flash
                - agent: claude
                  model: claude-haiku-4-5
                - agent: codex
                  model: o4-mini
              std:
                - agent: claude
                  model: claude-sonnet-4-6
                - agent: gemini
                  model: gemini-2.5-pro
                - agent: codex
                  model: o4-mini
              pro:
                - agent: claude
                  model: claude-opus-4-6
                - agent: gemini
                  model: gemini-2.5-pro
                - agent: codex
                  model: o3

            # Tier 關鍵字觸發（per-turn）
            tier-keywords:
              pro:
                - 仔細想
                - 深入分析
                - 好好想
                - think hard
                - think deeply
              lite:
                - 快速
                - 簡單說
                - 大概看一下
                - quickly
                - briefly
            """;

    /**
     * 檢查全域目錄是否已初始化（至少 tasks 和 skills 目錄存在）。
     */
    public boolean isInitialized() {
        return Files.isDirectory(tasksDir())
            && Files.isDirectory(skillsDir());
    }

    public Path root()        { return root; }
    public Path configFile()  { return root.resolve("config.yaml"); }
    public Path tasksDir()    { return root.resolve("tasks"); }
    public Path skillsDir()   { return root.resolve("skills"); }
    public Path agentsDir()   { return root.resolve("agents"); }
    public Path logsDir()     { return root.resolve("logs"); }
    public Path projectsDir() { return root.resolve("projects"); }

    private void createDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create directory: " + dir, e);
        }
    }
}
