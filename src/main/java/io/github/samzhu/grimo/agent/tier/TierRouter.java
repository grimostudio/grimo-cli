package io.github.samzhu.grimo.agent.tier;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.config.GrimoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Tier 路由器：根據 6 級優先順序解析 Tier，再 walk fallback list 選定 agent+model。
 *
 * 設計說明 — 6 級優先順序（高→低）：
 * 1. 使用者本輪關鍵字（keywordTier）     → per-turn，不持久
 * 2. 使用者 /tier 指令（sessionTier）     → session 級，持續到下次切換
 * 3. skill-overrides（config）            → 兩種形式（tier 或直接 agent+model）
 * 4. Skill metadata（grimo.tier）         → Skill 作者定義
 * 5. 安裝時自動分析結果（已寫入 metadata） → 等同 #4
 * 6. 預設 std
 *
 * Tier 確定後，walk skill-tiers.<tier> fallback list：
 * - 每個 entry 查 registry.get(agentId) → isAvailable()
 * - 第一個可用的就選定
 * - 全都不可用 → throw IllegalStateException
 *
 * @see <a href="https://www.mindstudio.ai/blog/set-up-ai-model-router-llm-stack-c2610">MindStudio 3-Tier</a>
 */
public class TierRouter {

    private static final Logger log = LoggerFactory.getLogger(TierRouter.class);

    private final AgentModelRegistry registry;
    private final GrimoConfig config;

    public TierRouter(AgentModelRegistry registry, GrimoConfig config) {
        this.registry = registry;
        this.config = config;
    }

    public TierSelection resolve(Context ctx) {
        // --- Priority 3: skill-overrides（形式 B：直接指定 agent+model）---
        if (ctx.skillName != null) {
            var overrides = config.getSkillOverrides();
            var override = overrides.get(ctx.skillName);
            if (override != null && override.containsKey("agent") && override.containsKey("model")) {
                String agentId = override.get("agent");
                String model = override.get("model");
                var agent = registry.get(agentId);
                if (agent != null && agent.isAvailable()) {
                    log.info("Tier resolved: skill-override-direct → {} / {}", agentId, model);
                    return new TierSelection(agentId, model, Tier.STD, "skill-override-direct");
                }
            }
        }

        // --- Resolve tier level (priorities 1-6) ---
        Tier tier;
        String source;

        if (ctx.keywordTier != null) {
            tier = ctx.keywordTier;
            source = "keyword";
        } else if (ctx.sessionTier != null) {
            tier = ctx.sessionTier;
            source = "session";
        } else if (ctx.skillName != null) {
            var overrides = config.getSkillOverrides();
            var override = overrides.get(ctx.skillName);
            if (override != null && override.containsKey("tier")) {
                tier = Tier.fromString(override.get("tier"));
                source = "skill-override";
            } else if (ctx.skillTier != null) {
                tier = Tier.fromString(ctx.skillTier);
                source = "skill-metadata";
            } else {
                tier = Tier.STD;
                source = "default";
            }
        } else if (ctx.skillTier != null) {
            tier = Tier.fromString(ctx.skillTier);
            source = "skill-metadata";
        } else {
            tier = Tier.STD;
            source = "default";
        }

        log.debug("Tier resolved: {} (source: {})", tier, source);
        return walkFallbackList(tier, source);
    }

    private TierSelection walkFallbackList(Tier tier, String source) {
        var tiers = config.getSkillTiers();
        List<Map<String, String>> fallbackList = tiers.get(tier.value());

        if (fallbackList != null && !fallbackList.isEmpty()) {
            for (var entry : fallbackList) {
                String agentId = entry.get("agent");
                String model = entry.get("model");
                var agent = registry.get(agentId);
                if (agent != null && agent.isAvailable()) {
                    log.info("Tier routing: {} → {} / {} (source: {})", tier, agentId, model, source);
                    return new TierSelection(agentId, model, tier, source);
                }
                log.debug("Tier fallback: {} / {} not available, trying next", agentId, model);
            }
            throw new IllegalStateException(
                    "沒有可用的 agent 符合 %s 等級。請確認至少一個 CLI agent 已安裝。".formatted(tier.value()));
        }

        // skill-tiers 未設定 → fallback to conversation default
        return fallbackToConversationDefault(tier, source);
    }

    private TierSelection fallbackToConversationDefault(Tier tier, String source) {
        String defaultAgent = config.getDefaultAgent();
        if (defaultAgent != null) {
            var agent = registry.get(defaultAgent);
            if (agent != null && agent.isAvailable()) {
                String model = config.getAgentOption(defaultAgent, "model");
                if (model == null) model = config.getDefaultModel();
                if (model == null) model = "unknown";
                log.info("Tier routing: no tier config, using conversation default: {} / {}", defaultAgent, model);
                return new TierSelection(defaultAgent, model, tier, source);
            }
        }

        var available = registry.listAvailable();
        if (available.isEmpty()) {
            throw new IllegalStateException("No agents available. Install a CLI agent (claude, gemini, or codex).");
        }
        var first = available.entrySet().iterator().next();
        return new TierSelection(first.getKey(), "unknown", tier, "fallback-first-available");
    }

    /**
     * TierRouter 的輸入 context。使用 builder pattern 建構。
     */
    public static class Context {
        @Nullable final Tier keywordTier;
        @Nullable final Tier sessionTier;
        @Nullable final String skillName;
        @Nullable final String skillTier;

        private Context(Builder builder) {
            this.keywordTier = builder.keywordTier;
            this.sessionTier = builder.sessionTier;
            this.skillName = builder.skillName;
            this.skillTier = builder.skillTier;
        }

        public static Builder builder() { return new Builder(); }

        public static class Builder {
            private Tier keywordTier;
            private Tier sessionTier;
            private String skillName;
            private String skillTier;

            public Builder keywordTier(Tier tier) { this.keywordTier = tier; return this; }
            public Builder sessionTier(Tier tier) { this.sessionTier = tier; return this; }
            public Builder skillName(String name) { this.skillName = name; return this; }
            public Builder skillTier(String tier) { this.skillTier = tier; return this; }
            public Context build() { return new Context(this); }
        }
    }
}
