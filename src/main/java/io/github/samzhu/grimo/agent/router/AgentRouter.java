package io.github.samzhu.grimo.agent.router;

import io.github.samzhu.grimo.agent.provider.AgentProvider;
import io.github.samzhu.grimo.agent.provider.AgentType;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;

import java.util.Comparator;

/**
 * 路由器負責根據指定的 agent ID 或自動選擇策略來決定使用哪個 AgentProvider。
 *
 * <p>自動選擇策略：優先選擇 CLI 類型的 provider（因為 CLI 通常具有更完整的上下文處理能力），
 * 若無可用的 CLI provider 則回退到 API 類型。</p>
 */
public class AgentRouter {

    private final AgentProviderRegistry registry;

    public AgentRouter(AgentProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * 路由到指定或自動選擇的 AgentProvider。
     *
     * @param explicitAgentId 明確指定的 agent ID，傳入 null 時啟用自動選擇
     * @return 可用的 AgentProvider
     * @throws IllegalStateException 當指定的 agent 不可用或無任何可用 provider 時
     */
    public AgentProvider route(String explicitAgentId) {
        if (explicitAgentId != null) {
            return registry.get(explicitAgentId)
                .filter(AgentProvider::isAvailable)
                .orElseThrow(() -> new IllegalStateException(
                    "Agent not available: " + explicitAgentId));
        }
        return autoSelect();
    }

    /**
     * 自動選擇最佳可用的 AgentProvider。
     * 排序邏輯：CLI 類型排在 API 類型前面，取第一個。
     */
    private AgentProvider autoSelect() {
        return registry.listAvailable().stream()
            .sorted(Comparator.comparingInt(p -> p.type() == AgentType.CLI ? 0 : 1))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No agent providers available. Run 'agent add' to configure one."));
    }
}
