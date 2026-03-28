package io.github.samzhu.grimo.shared.config;

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

public class GrimoConfig {

    private final Path configFile;

    public GrimoConfig(Path configFile) {
        this.configFile = configFile;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> load() {
        if (!Files.exists(configFile)) {
            return new LinkedHashMap<>();
        }
        try (var reader = Files.newBufferedReader(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(reader);
            return data != null ? data : new LinkedHashMap<>();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read config: " + configFile, e);
        }
    }

    public synchronized String getDefaultAgent() {
        return getNestedString("agents", "default");
    }

    public synchronized String getDefaultModel() {
        return getNestedString("agents", "model");
    }

    public synchronized void setDefaultAgent(String agentId) {
        setNestedValue("agents", "default", agentId);
    }

    public synchronized void setDefaultModel(String model) {
        setNestedValue("agents", "model", model);
    }

    @SuppressWarnings("unchecked")
    private synchronized String getNestedString(String section, String key) {
        var data = load();
        var sectionMap = (Map<String, Object>) data.get(section);
        if (sectionMap == null) return null;
        var value = sectionMap.get(key);
        return value != null ? value.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private synchronized void setNestedValue(String section, String key, String value) {
        var data = load();
        var sectionMap = (Map<String, Object>) data.computeIfAbsent(section, k -> new LinkedHashMap<>());
        sectionMap.put(key, value);
        save(data);
    }

    /**
     * 取得指定 agent 的選項值（從 agent-options.<agentId>.<key>）。
     *
     * 設計說明：
     * - 支援 per-agent 設定（每個 CLI agent 有自己的 model、timeout 等）
     * - 取代原本全域的 agents.model 設定
     */
    @SuppressWarnings("unchecked")
    public synchronized String getAgentOption(String agentId, String key) {
        var data = load();
        var agentOptions = (Map<String, Object>) data.get("agent-options");
        if (agentOptions == null) return null;
        var agentSection = (Map<String, Object>) agentOptions.get(agentId);
        if (agentSection == null) return null;
        var value = agentSection.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 取得所有 MCP server 定義（從 mcp 區段）。
     * 回傳格式：Map<serverName, Map<configKey, configValue>>
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Map<String, Object>> getMcpServers() {
        var data = load();
        var mcp = (Map<String, Map<String, Object>>) data.get("mcp");
        return mcp != null ? mcp : Map.of();
    }

    /**
     * 新增或更新指定名稱的 MCP server 定義到 config 的 mcp 區段。
     * 若 mcp 區段不存在則自動建立。
     */
    @SuppressWarnings("unchecked")
    public synchronized void setMcpServer(String name, Map<String, Object> serverDef) {
        var data = load();
        var mcp = (Map<String, Map<String, Object>>) data.computeIfAbsent("mcp", k -> new LinkedHashMap<>());
        mcp.put(name, new LinkedHashMap<>(serverDef));
        save(data);
    }

    /**
     * 移除指定名稱的 MCP server 定義。
     * 回傳 true 表示成功移除，false 表示該 server 不存在。
     */
    @SuppressWarnings("unchecked")
    public synchronized boolean removeMcpServer(String name) {
        var data = load();
        var mcp = (Map<String, Map<String, Object>>) data.get("mcp");
        if (mcp == null || !mcp.containsKey(name)) {
            return false;
        }
        mcp.remove(name);
        save(data);
        return true;
    }

    public synchronized void save(Map<String, Object> data) {
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
