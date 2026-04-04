package io.github.samzhu.grimo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 內建設定：從 application.yaml grimo.* 綁定。
 *
 * 設計說明：
 * - 與 GrimoConfig（讀 ~/.grimo/config.yaml 使用者設定）職責分離
 * - GrimoProperties = 開發者維護的 built-in defaults（模型清單、tier 對應、預設模型）
 * - GrimoConfig = 使用者的客製覆寫（tier-models、skill-overrides、agent-options）
 * - @ConfigurationProperties 由 Spring Boot 自動綁定，type-safe
 *
 * @see <a href="https://platform.claude.com/docs/en/docs/about-claude/models/overview">Claude Models</a>
 * @see <a href="https://ai.google.dev/gemini-api/docs/models">Gemini Models</a>
 * @see <a href="https://developers.openai.com/codex/models">Codex Models</a>
 */
@Component
@ConfigurationProperties(prefix = "grimo")
public class GrimoProperties {

    /** CLI 模型清單：agent → List<ModelEntry>。各 CLI 能用的所有模型，不過濾。 */
    private Map<String, List<ModelEntry>> models = Map.of();

    /** Agent 預設模型：agent → modelId。/agent-use 無指定 model 時使用。 */
    private Map<String, String> defaults = Map.of();

    /**
     * Tier → Model 推薦對應：tier → List<TierEntry>。
     * TierRouter 在 user config 沒設定時的 fallback。
     * 命名 tier-models（非 skill-tiers）：tier 機制通用於 skill、龍蝦模式等 pipeline。
     */
    private Map<String, List<TierEntry>> tierModels = Map.of();

    /**
     * CLI 可用模型資訊（benchmark、pricing 為開發者參考）。
     * 模型 ID 必須是各 CLI 實際接受的值。
     * 注意：Gemini SDK 驗證 model 必須以 "gemini-" 開頭。
     */
    public record ModelEntry(String id, String sweBench, String gpqa,
                             String pricing, String context) {}

    /** Tier fallback list 的單一條目。 */
    public record TierEntry(String agent, String model) {}

    public Map<String, List<ModelEntry>> getModels() { return models; }
    public void setModels(Map<String, List<ModelEntry>> models) { this.models = models; }

    public Map<String, String> getDefaults() { return defaults; }
    public void setDefaults(Map<String, String> defaults) { this.defaults = defaults; }

    public Map<String, List<TierEntry>> getTierModels() { return tierModels; }
    public void setTierModels(Map<String, List<TierEntry>> tierModels) { this.tierModels = tierModels; }
}
