package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.router.AgentRouter;
import io.github.samzhu.grimo.channel.ChannelRegistry;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.model.AgentModel;
import org.springaicommunity.agents.model.AgentResponse;
import org.springaicommunity.agents.model.AgentTaskRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GrimoCommandsTest {

    AgentModelRegistry agentRegistry;
    AgentRouter router;
    ChannelRegistry channelRegistry;
    SkillRegistry skillRegistry;
    GrimoCommands commands;

    @BeforeEach
    void setUp() {
        agentRegistry = new AgentModelRegistry();

        AgentModel stubModel = mock(AgentModel.class);
        when(stubModel.isAvailable()).thenReturn(true);
        AgentResponse stubResponse = mock(AgentResponse.class);
        when(stubResponse.isSuccessful()).thenReturn(true);
        when(stubResponse.getText()).thenReturn("Echo: Hello");
        when(stubModel.call(any(AgentTaskRequest.class))).thenReturn(stubResponse);

        agentRegistry.register("stub", stubModel);
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
