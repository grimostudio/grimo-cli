package io.github.samzhu.grimo.agent.advisor;

import org.springaicommunity.agents.client.AgentClientRequest;
import org.springaicommunity.agents.client.AgentClientResponse;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisor;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisorChain;
import org.springaicommunity.agents.model.AgentGeneration;
import org.springaicommunity.agents.model.AgentGenerationMetadata;
import org.springaicommunity.agents.model.AgentResponse;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.Map;

/**
 * 阻擋危險操作的 advisor。
 *
 * 設計說明：
 * - 在 agent 執行前檢查 goal 內容是否包含危險操作
 * - 如果命中，直接回傳 blocked response，不呼叫 chain.nextCall()
 * - Order 設為 HIGHEST_PRECEDENCE，第一個執行
 */
public class GoalValidationAdvisor implements AgentCallAdvisor {

    private static final List<String> BANNED_OPERATIONS = List.of(
            "rm -rf /", "DROP DATABASE", "DELETE FROM", "format disk",
            "mkfs", "> /dev/sda", "dd if=/dev/zero"
    );

    @Override
    public AgentClientResponse adviseCall(AgentClientRequest request, AgentCallAdvisorChain chain) {
        String goal = request.goal().getContent().toLowerCase();
        for (String banned : BANNED_OPERATIONS) {
            if (goal.contains(banned.toLowerCase())) {
                var generation = new AgentGeneration(
                        "Goal blocked: contains dangerous operation '" + banned + "'",
                        new AgentGenerationMetadata("BLOCKED", Map.of()));
                var agentResponse = new AgentResponse(List.of(generation));
                return new AgentClientResponse(agentResponse);
            }
        }
        return chain.nextCall(request);
    }

    @Override
    public String getName() {
        return "GoalValidation";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
