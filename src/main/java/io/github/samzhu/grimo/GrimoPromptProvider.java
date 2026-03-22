package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.springframework.shell.jline.PromptProvider;

/**
 * 自訂 Shell 提示符，顯示目前使用中的 Agent ID。
 * 當有可用的 Agent 時顯示 [agent-id] grimo:>，
 * 無可用 Agent 時顯示 [no agent] grimo:>。
 *
 * 設計說明：優先採用 config.yaml 設定的預設 agent（若可用），
 * 其次 fallback 到 registry 中第一個可用的 agent，
 * 讓使用者在 shell 互動時能即時知道目前連線的 AI provider。
 * GrimoConfig 允許為 null，以確保向後相容性。
 *
 * @see org.springframework.shell.jline.PromptProvider
 */
public class GrimoPromptProvider implements PromptProvider {

    private final AgentProviderRegistry registry;
    private final GrimoConfig config;

    /** 向後相容的單參數建構子，config 為 null 時 fallback 到 registry 第一個可用 agent。 */
    public GrimoPromptProvider(AgentProviderRegistry registry) {
        this(registry, null);
    }

    public GrimoPromptProvider(AgentProviderRegistry registry, GrimoConfig config) {
        this.registry = registry;
        this.config = config;
    }

    @Override
    public AttributedString getPrompt() {
        String agentLabel = resolveAgentLabel();
        String prompt = "[" + agentLabel + "] grimo:> ";
        return new AttributedString(prompt,
            AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
    }

    /**
     * 解析要顯示的 agent 標籤。
     * 1. 優先用 config 預設（若 config 非 null 且 defaultAgent 可用）
     * 2. Fallback: 第一個可用 agent
     * 3. 無可用 agent 時回傳 "no agent"
     */
    private String resolveAgentLabel() {
        // 1. 優先用 config 預設
        if (config != null) {
            String defaultAgent = config.getDefaultAgent();
            if (defaultAgent != null && registry.get(defaultAgent)
                    .filter(a -> a.isAvailable()).isPresent()) {
                return defaultAgent;
            }
        }
        // 2. Fallback: 第一個可用 agent
        return registry.listAvailable().stream()
            .findFirst()
            .map(a -> a.id())
            .orElse("no agent");
    }
}
