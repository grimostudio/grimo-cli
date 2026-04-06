package io.github.samzhu.grimo.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Config bean：啟動時讀檔填入欄位，getter 讀欄位，setter write-through。
 *
 * 設計說明：
 * - 建構子負責建立預設 config.yaml（若不存在）並載入到 bean 欄位
 * - GrimoHome.initialize() 只建目錄結構，不處理 config 內容
 * - getter 直接讀 bean 欄位（不讀檔）
 * - setter 更新 bean 欄位 + 寫檔（write-through）
 * - 所有 getter/setter 保持 synchronized（Virtual Thread 併發保護）
 * - event publish 由呼叫端負責（AgentCommands、McpCommands）
 */
public class GrimoConfig {

    private static final String DEFAULT_CONFIG = """
            agents:
              default: claude
              model: claude-sonnet-4-6
            sandbox:
              mode: local
            """;

    private final Path configFile;

    // --- bean fields ---
    private String defaultAgent;
    private String defaultModel;
    private Map<String, Map<String, Object>> mcpServers;
    private String sandboxMode;

    public GrimoConfig(Path configFile) {
        this.configFile = configFile;
        if (!Files.exists(configFile)) {
            createDefaultConfig();
        }
        loadFromFile();
    }

    // --- getters (read fields) ---

    public synchronized String getDefaultAgent() { return defaultAgent; }
    public synchronized String getDefaultModel() { return defaultModel; }
    public synchronized Map<String, Map<String, Object>> getMcpServers() {
        return mcpServers != null ? mcpServers : Map.of();
    }
    public synchronized String getSandboxMode() {
        return sandboxMode != null ? sandboxMode : "local";
    }

    // --- setters (write-through) ---

    public synchronized void setDefaultAgent(String agentId) {
        this.defaultAgent = agentId;
        save();
    }

    public synchronized void setDefaultModel(String model) {
        this.defaultModel = model;
        save();
    }

    @SuppressWarnings("unchecked")
    public synchronized void setMcpServer(String name, Map<String, Object> serverDef) {
        if (this.mcpServers == null) {
            this.mcpServers = new LinkedHashMap<>();
        }
        this.mcpServers.put(name, new LinkedHashMap<>(serverDef));
        save();
    }

    public synchronized boolean removeMcpServer(String name) {
        if (this.mcpServers == null || !this.mcpServers.containsKey(name)) {
            return false;
        }
        this.mcpServers.remove(name);
        save();
        return true;
    }

    // --- persistence ---

    private void createDefaultConfig() {
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, DEFAULT_CONFIG);
            Files.setPosixFilePermissions(configFile,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException e) {
            // Config creation failure should not block startup
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized void loadFromFile() {
        try (var reader = Files.newBufferedReader(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(reader);
            if (data == null) data = new LinkedHashMap<>();

            var agents = (Map<String, Object>) data.get("agents");
            if (agents != null) {
                this.defaultAgent = agents.get("default") != null ? agents.get("default").toString() : null;
                this.defaultModel = agents.get("model") != null ? agents.get("model").toString() : null;
            }

            var mcp = (Map<String, Map<String, Object>>) data.get("mcp");
            this.mcpServers = mcp != null ? new LinkedHashMap<>(mcp) : null;

            var sandbox = (Map<String, Object>) data.get("sandbox");
            if (sandbox != null) {
                this.sandboxMode = sandbox.get("mode") != null ? sandbox.get("mode").toString() : "local";
            } else {
                this.sandboxMode = "local";
            }
        } catch (IOException e) {
            // Fallback to defaults
            this.defaultAgent = "claude";
            this.defaultModel = "claude-sonnet-4-6";
            this.sandboxMode = "local";
        }
    }

    private synchronized void save() {
        var data = new LinkedHashMap<String, Object>();

        // agents
        var agents = new LinkedHashMap<String, Object>();
        if (defaultAgent != null) agents.put("default", defaultAgent);
        if (defaultModel != null) agents.put("model", defaultModel);
        if (!agents.isEmpty()) data.put("agents", agents);

        // sandbox
        var sandbox = new LinkedHashMap<String, Object>();
        sandbox.put("mode", sandboxMode != null ? sandboxMode : "local");
        data.put("sandbox", sandbox);

        // mcp (only if non-empty)
        if (mcpServers != null && !mcpServers.isEmpty()) {
            data.put("mcp", mcpServers);
        }

        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);

        try {
            Files.writeString(configFile, yaml.dump(data));
            Files.setPosixFilePermissions(configFile,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write config: " + configFile, e);
        }
    }
}
