package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.AgentCommands;
import io.github.samzhu.grimo.agent.DevCommands;
import io.github.samzhu.grimo.agent.tier.TierCommands;
import io.github.samzhu.grimo.channel.ChannelCommands;
import io.github.samzhu.grimo.command.CommandDispatcher;
import io.github.samzhu.grimo.mcp.McpCommands;
import io.github.samzhu.grimo.skill.SkillCommands;
import io.github.samzhu.grimo.task.TaskCommands;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 啟動時將所有 builtin 指令註冊到 CommandDispatcher。
 * 取代 Spring Shell CommandExecutor 的指令發現和執行。
 *
 * 設計說明：
 * - 每個 @Command 方法現在接受 String rawArgs（原始參數字串），
 *   不再依賴 Spring Shell 的 @Argument/@Option 解析機制。
 * - 無參數方法（list、status、version 等）以 lambda 包裝：args -> method()。
 * - 有參數方法（單一 rawArgs）使用方法引用：method::methodName。
 */
@Component
public class BuiltinCommandRegistrar {

    private final CommandDispatcher dispatcher;
    private final AgentCommands agentCommands;
    private final DevCommands devCommands;
    private final TierCommands tierCommands;
    private final SkillCommands skillCommands;
    private final McpCommands mcpCommands;
    private final ChannelCommands channelCommands;
    private final TaskCommands taskCommands;
    private final GrimoCommands grimoCommands;

    public BuiltinCommandRegistrar(
            CommandDispatcher dispatcher,
            AgentCommands agentCommands,
            DevCommands devCommands,
            TierCommands tierCommands,
            SkillCommands skillCommands,
            McpCommands mcpCommands,
            ChannelCommands channelCommands,
            TaskCommands taskCommands,
            GrimoCommands grimoCommands) {
        this.dispatcher = dispatcher;
        this.agentCommands = agentCommands;
        this.devCommands = devCommands;
        this.tierCommands = tierCommands;
        this.skillCommands = skillCommands;
        this.mcpCommands = mcpCommands;
        this.channelCommands = channelCommands;
        this.taskCommands = taskCommands;
        this.grimoCommands = grimoCommands;
    }

    @PostConstruct
    public void registerAll() {
        // Agent commands
        dispatcher.register("agent-list", "List all configured agents", "builtin",
                args -> agentCommands.list());
        dispatcher.register("agent-use", "Switch agent (auto-picks model)", "builtin",
                agentCommands::use);

        // Dev mode
        dispatcher.register("dev", "Enter Dev Mode (worktree + full access)", "builtin",
                devCommands::dev);

        // Tier commands
        dispatcher.register("tier", "View or set the session tier", "builtin",
                tierCommands::tier);
        dispatcher.register("skill-tier", "Override tier for a specific skill", "builtin",
                tierCommands::skillTier);

        // Skill commands
        dispatcher.register("skill-list", "List all loaded skills", "builtin",
                args -> skillCommands.list());
        dispatcher.register("skill-remove", "Remove a loaded skill", "builtin",
                skillCommands::remove);
        dispatcher.register("skill-install", "Install a skill from Git URL", "builtin",
                skillCommands::install);

        // MCP commands
        dispatcher.register("mcp", "List MCP servers", "builtin",
                args -> mcpCommands.list());
        dispatcher.register("mcp-add", "Add an MCP server", "builtin",
                mcpCommands::add);
        dispatcher.register("mcp-remove", "Remove an MCP server", "builtin",
                mcpCommands::remove);

        // Channel commands
        dispatcher.register("channel-list", "List configured channels", "builtin",
                args -> channelCommands.list());
        dispatcher.register("channel-remove", "Remove a channel", "builtin",
                channelCommands::remove);

        // Task commands
        dispatcher.register("task-create", "Create a new task", "builtin",
                taskCommands::create);
        dispatcher.register("task-list", "List all tasks", "builtin",
                args -> taskCommands.list());
        dispatcher.register("task-show", "Show task details", "builtin",
                taskCommands::show);
        dispatcher.register("task-cancel", "Cancel a task", "builtin",
                taskCommands::cancel);

        // General commands
        dispatcher.register("version", "Show version", "builtin",
                args -> grimoCommands.version());
        dispatcher.register("status", "Show system status", "builtin",
                args -> grimoCommands.status());
        dispatcher.register("chat", "Send a message to the agent", "builtin",
                grimoCommands::chat);
    }
}
