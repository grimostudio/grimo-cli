package io.github.samzhu.grimo.agent.tier;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.config.GrimoConfig;
import io.github.samzhu.grimo.config.GrimoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

/**
 * Tier 路由器：根據 sessionTier 或 user-default 解析 agent+model，再 walk fallback list。
 *
 * 設計說明（簡化後優先順序）：
 * 1. 使用者 /tier 指令（sessionTier）     → session 級，持續到下次切換
 * 2. 使用者 /agent-use 設定的 default agent → config bean 欄位
 * 3. 預設 std → walk tier-models fallback list（from grimoProperties）
 *
 * Tier 確定後，walk tier-models.<tier> fallback list：
 * - 每個 entry 查 registry.get(agentId) → isAvailable()
 * - 第一個可用的就選定
 * - 全都不可用 → fallback 到 first available agent
 *
 * @see <a href="https://www.mindstudio.ai/blog/set-up-ai-model-router-llm-stack-c2610">MindStudio 3-Tier</a>
 */
public class TierRouter {

    private static final Logger log = LoggerFactory.getLogger(TierRouter.class);

    private final AgentModelRegistry registry;
    private final GrimoConfig config;
    private final GrimoProperties grimoProperties;

    public TierRouter(AgentModelRegistry registry, GrimoConfig config, GrimoProperties grimoProperties) {
        this.registry = registry;
        this.config = config;
        this.grimoProperties = grimoProperties;
    }

    public TierSelection resolve(Context ctx) {
        Tier tier;
        String source;

        if (ctx.sessionTier != null) {
            tier = ctx.sessionTier;
            source = "session";
        } else {
            String explicitDefault = config.getDefaultAgent();
            if (explicitDefault != null) {
                var agent = registry.get(explicitDefault);
                if (agent != null && agent.isAvailable()) {
                    String model = config.getDefaultModel();
                    if (model == null) model = grimoProperties.getDefaults()
                            .getOrDefault(explicitDefault, "unknown");
                    return new TierSelection(explicitDefault, model, Tier.STD, "user-default");
                }
                throw new IllegalStateException(
                    "%s is not available. Run '/agent-use' to switch agent.".formatted(explicitDefault));
            }
            tier = Tier.STD;
            source = "default";
        }

        log.debug("Tier resolved: {} (source: {})", tier, source);
        return walkFallbackList(tier, source);
    }

    /**
     * 解析預設 agent+model：供 status bar 和啟動時使用。
     *
     * 設計說明：
     * - 使用者有明確設定（/agent-use）→ 檢查 isAvailable()，不可用則 throw
     * - 無明確設定 → 走 tier-models.std fallback list，選第一個可用
     * - 確保 status bar 顯示與實際 dispatch 一致
     */
    public TierSelection resolveDefault() {
        String explicitDefault = config.getDefaultAgent();
        if (explicitDefault != null) {
            var agent = registry.get(explicitDefault);
            if (agent != null && agent.isAvailable()) {
                String model = config.getDefaultModel();
                if (model == null) model = grimoProperties.getDefaults()
                        .getOrDefault(explicitDefault, "unknown");
                return new TierSelection(explicitDefault, model, Tier.STD, "user-default");
            }
            throw new IllegalStateException(
                "%s is not available. Run '/agent-use' to switch agent.".formatted(explicitDefault));
        }
        return resolve(Context.builder().build());
    }

    private TierSelection walkFallbackList(Tier tier, String source) {
        var builtIn = grimoProperties.getTierModels();
        var entries = builtIn.get(tier.value());

        if (entries != null && !entries.isEmpty()) {
            for (var entry : entries) {
                String agentId = entry.agent();
                String model = entry.model();
                var agent = registry.get(agentId);
                if (agent != null && agent.isAvailable()) {
                    log.info("Tier routing: {} → {} / {} (source: {})", tier, agentId, model, source);
                    return new TierSelection(agentId, model, tier, source);
                }
                log.debug("Tier fallback: {} / {} not available, trying next", agentId, model);
            }
        }

        // No agent available for this tier
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
        @Nullable final Tier sessionTier;

        private Context(Builder builder) {
            this.sessionTier = builder.sessionTier;
        }

        public static Builder builder() { return new Builder(); }

        public static class Builder {
            private Tier sessionTier;
            public Builder sessionTier(Tier tier) { this.sessionTier = tier; return this; }
            public Context build() { return new Context(this); }
        }
    }
}
