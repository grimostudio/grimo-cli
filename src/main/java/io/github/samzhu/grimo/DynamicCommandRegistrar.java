package io.github.samzhu.grimo;

import io.github.samzhu.grimo.command.CommandDispatcher;
import io.github.samzhu.grimo.shared.event.AgentDetectedEvent;
import io.github.samzhu.grimo.shared.event.SkillInstalledEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 動態指令註冊：監聽 domain events，自動在 CommandDispatcher 註冊/解除指令。
 */
@Component
public class DynamicCommandRegistrar {

    private final CommandDispatcher dispatcher;
    private final SkillExecutor skillExecutor;

    public DynamicCommandRegistrar(CommandDispatcher dispatcher, SkillExecutor skillExecutor) {
        this.dispatcher = dispatcher;
        this.skillExecutor = skillExecutor;
    }

    @EventListener
    public void on(AgentDetectedEvent event) {
        if (!event.available()) return;
        String agentId = event.agentId();

        // /claude → direct chat with agent
        dispatcher.register(agentId,
            "Chat with " + agentId, "agent",
            args -> null);  // agent commands are async — InputHandler handles via chatDispatcher.dispatchTo()

        // @claude → mention syntax
        dispatcher.register("@" + agentId,
            "Mention " + agentId, "agent",
            args -> null);
    }

    @EventListener
    public void on(SkillInstalledEvent event) {
        dispatcher.register(event.skillName(),
            event.description(), "skill",
            args -> skillExecutor.execute(event.skillName(), args));
    }
}
