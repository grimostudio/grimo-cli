package io.github.samzhu.grimo.agent.advisor;

import io.github.samzhu.grimo.SessionWriter;
import org.springaicommunity.agents.client.AgentClientRequest;
import org.springaicommunity.agents.client.AgentClientResponse;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisor;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisorChain;
import org.springframework.core.Ordered;

/**
 * 記錄 agent goal 和 result 到 session JSONL 檔案。
 *
 * 設計說明：
 * - 實作 AgentCallAdvisor（Spring AI Community 的 around-advice 模式）
 * - 在 chain.nextCall() 前後分別記錄 goal 和 result
 * - Order 設為 HIGHEST_PRECEDENCE + 500，在 validation 之後、其他 advisor 之前
 */
public class GrimoSessionAdvisor implements AgentCallAdvisor {

    private final SessionWriter sessionWriter;

    public GrimoSessionAdvisor(SessionWriter sessionWriter) {
        this.sessionWriter = sessionWriter;
    }

    @Override
    public AgentClientResponse adviseCall(AgentClientRequest request, AgentCallAdvisorChain chain) {
        sessionWriter.writeUserMessage(request.goal().getContent());
        AgentClientResponse response = chain.nextCall(request);
        sessionWriter.writeAssistantMessage(response.getResult());
        return response;
    }

    @Override
    public String getName() {
        return "GrimoSession";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 500;
    }
}
