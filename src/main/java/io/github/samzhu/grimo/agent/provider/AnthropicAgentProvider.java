package io.github.samzhu.grimo.agent.provider;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Agent provider for Anthropic Claude models via Spring AI library integration.
 *
 * Uses Spring AI 2.0.0-M3 which wraps the official Anthropic Java SDK
 * (com.anthropic:anthropic-java). The AnthropicChatModel is constructed
 * as a plain Java object (not a Spring bean), enabling runtime dynamic
 * creation and removal via CLI commands.
 *
 * The API key and model name are passed via AnthropicChatOptions, and
 * AnthropicChatModel auto-creates the underlying SDK client from those options.
 *
 * @see <a href="https://docs.spring.io/spring-ai/reference/api/chat/anthropic-chat.html">Spring AI Anthropic Chat</a>
 * @see <a href="https://github.com/anthropics/anthropic-sdk-java">Anthropic Java SDK</a>
 */
public class AnthropicAgentProvider implements AgentProvider {

    private final String apiKey;
    private final String modelName;
    private final AnthropicChatModel chatModel;

    public AnthropicAgentProvider(String apiKey, String modelName) {
        this.apiKey = apiKey;
        this.modelName = modelName;

        if (apiKey != null && !apiKey.isBlank()) {
            // AnthropicChatModel auto-creates the underlying AnthropicClient
            // from the options when no explicit client is provided to the builder.
            this.chatModel = AnthropicChatModel.builder()
                .options(AnthropicChatOptions.builder()
                    .apiKey(apiKey)
                    .model(modelName)
                    .build())
                .build();
        } else {
            this.chatModel = null;
        }
    }

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public AgentType type() {
        return AgentType.API;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && chatModel != null;
    }

    @Override
    public AgentResult execute(AgentRequest request) {
        if (!isAvailable()) {
            return new AgentResult(false, "Anthropic provider not available — no API key configured");
        }
        try {
            var prompt = request.systemInstruction() != null
                ? new Prompt(request.systemInstruction() + "\n\n" + request.prompt())
                : new Prompt(request.prompt());
            var response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getText();
            return new AgentResult(true, content);
        } catch (Exception e) {
            return new AgentResult(false, "Anthropic API error: " + e.getMessage());
        }
    }
}
