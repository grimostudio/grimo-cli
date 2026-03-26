package io.github.samzhu.grimo.agent.router;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.springframework.lang.Nullable;
import org.springaicommunity.agents.model.AgentModel;

/**
 * 路由邏輯：選擇要使用的 AgentModel。
 *
 * 設計說明：
 * - 取代舊版使用 AgentProviderRegistry 的路由
 * - 移除 CLI/API 優先邏輯（全部都是 CLI）
 * - 路由順序：明確指定 > config default > 第一個可用的
 */
public class AgentRouter {

    private final AgentModelRegistry registry;
    private final GrimoConfig config;

    public AgentRouter(AgentModelRegistry registry) {
        this(registry, null);
    }

    public AgentRouter(AgentModelRegistry registry, GrimoConfig config) {
        this.registry = registry;
        this.config = config;
    }

    public AgentModel route(@Nullable String agentId) {
        if (agentId != null) {
            AgentModel model = registry.get(agentId);
            if (model == null) {
                throw new IllegalStateException("Agent not found: " + agentId);
            }
            return model;
        }

        // config default
        String defaultAgent = config != null ? config.getDefaultAgent() : null;
        if (defaultAgent != null) {
            AgentModel model = registry.get(defaultAgent);
            if (model != null) {
                return model;
            }
        }

        // first available
        var available = registry.listAvailable();
        if (available.isEmpty()) {
            throw new IllegalStateException("No agents available. Install a CLI agent (claude, gemini, or codex).");
        }
        return available.values().iterator().next();
    }
}
