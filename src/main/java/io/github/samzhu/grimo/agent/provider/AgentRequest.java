package io.github.samzhu.grimo.agent.provider;

import java.util.List;
import java.util.Map;

public record AgentRequest(
    String prompt,
    String systemInstruction,
    List<Map<String, String>> tools,
    Map<String, Object> options
) {
    public AgentRequest(String prompt) {
        this(prompt, null, List.of(), Map.of());
    }
}
