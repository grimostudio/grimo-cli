package io.github.samzhu.grimo.agent.advisor;

import io.github.samzhu.grimo.SessionWriter;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.client.AgentClientRequest;
import org.springaicommunity.agents.client.AgentClientResponse;
import org.springaicommunity.agents.client.Goal;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisorChain;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GrimoSessionAdvisorTest {

    @Test
    void adviseCallLogsGoalAndResult() {
        var sessionWriter = mock(SessionWriter.class);
        var advisor = new GrimoSessionAdvisor(sessionWriter);

        var goal = new Goal("test goal");
        var request = mock(AgentClientRequest.class);
        when(request.goal()).thenReturn(goal);

        var response = mock(AgentClientResponse.class);
        when(response.getResult()).thenReturn("test result");

        var chain = mock(AgentCallAdvisorChain.class);
        when(chain.nextCall(request)).thenReturn(response);

        var result = advisor.adviseCall(request, chain);

        assertThat(result).isSameAs(response);
        verify(chain).nextCall(request);
        verify(sessionWriter).writeUserMessage("test goal");
        verify(sessionWriter).writeAssistantMessage("test result");
    }

    @Test
    void getNameReturnsGrimoSession() {
        var advisor = new GrimoSessionAdvisor(mock(SessionWriter.class));
        assertThat(advisor.getName()).isEqualTo("GrimoSession");
    }

    @Test
    void getOrderIsAfterValidation() {
        var advisor = new GrimoSessionAdvisor(mock(SessionWriter.class));
        assertThat(advisor.getOrder()).isGreaterThan(Integer.MIN_VALUE);
    }
}
