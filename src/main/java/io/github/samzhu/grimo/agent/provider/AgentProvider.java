package io.github.samzhu.grimo.agent.provider;

public interface AgentProvider {
    String id();
    AgentType type();
    boolean isAvailable();
    AgentResult execute(AgentRequest request);
}
