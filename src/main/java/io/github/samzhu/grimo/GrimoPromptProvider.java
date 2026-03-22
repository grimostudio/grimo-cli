package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.springframework.shell.jline.PromptProvider;

/**
 * 自訂 Shell 提示符，顯示目前使用中的 Agent ID。
 * 當有可用的 Agent 時顯示 [agent-id] grimo:>，
 * 無可用 Agent 時顯示 [no agent] grimo:>。
 *
 * 設計說明：透過注入 AgentProviderRegistry 動態查詢可用的 agent，
 * 取第一個可用者的 id 作為顯示標籤，讓使用者在 shell 互動時能即時知道
 * 目前連線的 AI provider。
 *
 * @see org.springframework.shell.jline.PromptProvider
 */
public class GrimoPromptProvider implements PromptProvider {

    private final AgentProviderRegistry registry;

    public GrimoPromptProvider(AgentProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AttributedString getPrompt() {
        String agentLabel = registry.listAvailable().stream()
            .findFirst()
            .map(a -> a.id())
            .orElse("no agent");

        String prompt = "[" + agentLabel + "] grimo:> ";
        return new AttributedString(prompt,
            AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
    }
}
