package io.github.samzhu.grimo.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
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
     * 設定指定 agent 的選項值（寫入 agent-options.<agentId>.<key>）。
     * 用於 per-agent 模型記憶：使用者切換模型後，下次切回同一 agent 自動恢復。
     */
    @SuppressWarnings("unchecked")
    public synchronized void setAgentOption(String agentId, String key, String value) {
        var data = load();
        var agentOptions = (Map<String, Object>) data.computeIfAbsent("agent-options", k -> new LinkedHashMap<>());
        var agentSection = (Map<String, Object>) agentOptions.computeIfAbsent(agentId, k -> new LinkedHashMap<>());
        agentSection.put(key, value);
        save(data);
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

    /**
     * 取得 sandbox 模式設定（local / docker / e2b）。
     * 預設 "local"。
     */
    public synchronized String getSandboxMode() {
        String mode = getNestedString("sandbox", "mode");
        return mode != null ? mode : "local";
    }

    /**
     * 取得 tier-models 設定：每個 tier 對應一個 agent+model fallback list。
     * 回傳格式：Map<tierName, List<Map<"agent"|"model", value>>>
     *
     * 設計說明：
     * - 更名自 getSkillTiers — tier 機制通用，不只 skill 用
     * - 每級可配多組 agent+model，按順序 fallback
     * - 回傳原始結構，TierRouter 負責解析與 isAvailable() 檢查
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, List<Map<String, String>>> getTierModels() {
        var data = load();
        var tiers = (Map<String, List<Map<String, String>>>) data.get("tier-models");
        return tiers != null ? tiers : Map.of();
    }

    /**
     * 取得 skill-overrides 設定：per-skill 的 tier 或 agent+model 覆寫。
     * 回傳格式：Map<skillName, Map<"tier"|"agent"|"model", value>>
     *
     * 兩種形式：
     * - 形式 A：{ tier: "pro" } → 走 tier fallback
     * - 形式 B：{ agent: "claude", model: "claude-opus-4" } → 直接使用
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Map<String, String>> getSkillOverrides() {
        var data = load();
        var overrides = (Map<String, Map<String, String>>) data.get("skill-overrides");
        return overrides != null ? overrides : Map.of();
    }

    /**
     * 設定特定 skill 的 tier/agent/model 覆寫（寫入 skill-overrides 區段）。
     */
    @SuppressWarnings("unchecked")
    public synchronized void setSkillOverride(String skillName, Map<String, String> override) {
        var data = load();
        var overrides = (Map<String, Map<String, String>>)
                data.computeIfAbsent("skill-overrides", k -> new LinkedHashMap<>());
        overrides.put(skillName, new LinkedHashMap<>(override));
        save(data);
    }

    /**
     * 取得 tier-keywords 設定：每個 tier 對應的觸發關鍵字列表。
     * 回傳格式：Map<tierName, List<keyword>>
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, List<String>> getTierKeywords() {
        var data = load();
        var keywords = (Map<String, List<String>>) data.get("tier-keywords");
        return keywords != null ? keywords : Map.of();
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
