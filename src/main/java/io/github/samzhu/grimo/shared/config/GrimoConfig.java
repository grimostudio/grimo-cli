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
    public Map<String, Object> load() {
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

    public String getDefaultAgent() {
        return getNestedString("agents", "default");
    }

    public String getDefaultModel() {
        return getNestedString("agents", "model");
    }

    public void setDefaultAgent(String agentId) {
        setNestedValue("agents", "default", agentId);
    }

    public void setDefaultModel(String model) {
        setNestedValue("agents", "model", model);
    }

    @SuppressWarnings("unchecked")
    private String getNestedString(String section, String key) {
        var data = load();
        var sectionMap = (Map<String, Object>) data.get(section);
        if (sectionMap == null) return null;
        var value = sectionMap.get(key);
        return value != null ? value.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private void setNestedValue(String section, String key, String value) {
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
    public String getAgentOption(String agentId, String key) {
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
    public Map<String, Map<String, Object>> getMcpServers() {
        var data = load();
        var mcp = (Map<String, Map<String, Object>>) data.get("mcp");
        return mcp != null ? mcp : Map.of();
    }

    public void save(Map<String, Object> data) {
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
