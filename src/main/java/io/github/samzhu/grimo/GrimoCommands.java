package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.router.AgentRouter;
import io.github.samzhu.grimo.channel.ChannelRegistry;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.springaicommunity.agents.model.AgentTaskRequest;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Top-level Spring Shell CLI commands for Grimo.
 *
 * 設計說明：
 * - chat 命令：將使用者訊息透過 AgentRouter 路由到適當的 AgentModel 執行，
 *   支援可選的 --agent 參數指定特定 agent，未指定時自動選擇最佳 agent。
 * - status 命令：顯示系統概覽，包含已註冊的 Agents、Channels、Skills 數量與狀態。
 *
 * 位於根套件 (io.github.samzhu.grimo)，作為跨模組的頂層入口點，
 * 依賴 agent、channel、skill 三個模組的 registry。
 *
 * Uses Spring Shell 4.0 @Command annotation model.
 * Reference: https://docs.spring.io/spring-shell/reference/api/org/springframework/shell/core/command/annotation/Command.html
 */
@Component
public class GrimoCommands {

    private final AgentRouter router;
    private final AgentModelRegistry agentRegistry;
    private final ChannelRegistry channelRegistry;
    private final SkillRegistry skillRegistry;

    public GrimoCommands(AgentRouter router,
                          AgentModelRegistry agentRegistry,
                          ChannelRegistry channelRegistry,
                          SkillRegistry skillRegistry) {
        this.router = router;
        this.agentRegistry = agentRegistry;
        this.channelRegistry = channelRegistry;
        this.skillRegistry = skillRegistry;
    }

    @Command(name = "chat", description = "Send a message to the agent")
    public String chat(String message, @Option(defaultValue = "") String agent) {
        String agentId = agent != null && !agent.isBlank() ? agent : null;
        var model = router.route(agentId);
        var request = AgentTaskRequest.builder(message, Path.of(".")).build();
        var response = model.call(request);
        return response.isSuccessful() ? response.getText() : "Error: agent call failed";
    }

    @Command(name = "status", description = "Show system status")
    public String status() {
        var agents = agentRegistry.listAll();
        var channels = channelRegistry.listAll();
        var skills = skillRegistry.listAll();

        return String.format("""
            Agents: %d configured (%d available)
            Channels: %d configured (%d enabled)
            Skills: %d loaded""",
            agents.size(),
            agentRegistry.listAvailable().size(),
            channels.size(),
            channelRegistry.listEnabled().size(),
            skills.size());
    }
}
