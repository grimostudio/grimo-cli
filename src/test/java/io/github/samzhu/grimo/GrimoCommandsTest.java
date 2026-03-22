package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.provider.AgentProvider;
import io.github.samzhu.grimo.agent.provider.AgentRequest;
import io.github.samzhu.grimo.agent.provider.AgentResult;
import io.github.samzhu.grimo.agent.provider.AgentType;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import io.github.samzhu.grimo.agent.router.AgentRouter;
import io.github.samzhu.grimo.channel.ChannelRegistry;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrimoCommandsTest {

    AgentProviderRegistry agentRegistry;
    AgentRouter router;
    ChannelRegistry channelRegistry;
    SkillRegistry skillRegistry;
    GrimoCommands commands;

    @BeforeEach
    void setUp() {
        agentRegistry = new AgentProviderRegistry();
        agentRegistry.register("stub", new AgentProvider() {
            @Override public String id() { return "stub"; }
            @Override public AgentType type() { return AgentType.API; }
            @Override public boolean isAvailable() { return true; }
            @Override public AgentResult execute(AgentRequest request) {
                return new AgentResult(true, "Echo: " + request.prompt());
            }
        });
        router = new AgentRouter(agentRegistry);
        channelRegistry = new ChannelRegistry();
        skillRegistry = new SkillRegistry();
        commands = new GrimoCommands(router, agentRegistry, channelRegistry, skillRegistry);
    }

    @Test
    void chatShouldReturnAgentResponse() {
        String result = commands.chat("Hello", null);
        assertThat(result).contains("Echo: Hello");
    }

    @Test
    void statusShouldShowSystemOverview() {
        String result = commands.status();
        assertThat(result).contains("Agents:");
        assertThat(result).contains("Channels:");
        assertThat(result).contains("Skills:");
    }
}
