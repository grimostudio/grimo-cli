package io.github.samzhu.grimo;

import io.github.samzhu.grimo.command.CommandDispatcher;
import io.github.samzhu.grimo.shared.event.AgentDetectedEvent;
import io.github.samzhu.grimo.shared.event.SkillInstalledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DynamicCommandRegistrarTest {

    private CommandDispatcher dispatcher;
    private DynamicCommandRegistrar registrar;

    @BeforeEach
    void setUp() {
        dispatcher = new CommandDispatcher();
        registrar = new DynamicCommandRegistrar(dispatcher, mock(SkillExecutor.class));
    }

    @Test void agentDetectedRegistersSlashCommand() {
        registrar.on(new AgentDetectedEvent("claude", true));
        assertThat(dispatcher.has("claude")).isTrue();
    }

    @Test void agentDetectedRegistersAtMention() {
        registrar.on(new AgentDetectedEvent("claude", true));
        assertThat(dispatcher.has("@claude")).isTrue();
    }

    @Test void unavailableAgentNotRegistered() {
        registrar.on(new AgentDetectedEvent("codex", false));
        assertThat(dispatcher.has("codex")).isFalse();
    }

    @Test void agentCommandDescriptionCorrect() {
        registrar.on(new AgentDetectedEvent("gemini", true));
        var entry = dispatcher.getEntry("gemini");
        assertThat(entry).isNotNull();
        assertThat(entry.description()).contains("gemini");
        assertThat(entry.source()).isEqualTo("agent");
    }

    @Test void skillInstalledRegistersCommand() {
        registrar.on(new SkillInstalledEvent("brainstorming", "Generate ideas"));
        assertThat(dispatcher.has("brainstorming")).isTrue();
    }

    @Test void skillCommandHasCorrectSourceAndDescription() {
        registrar.on(new SkillInstalledEvent("brainstorming", "Generate ideas"));
        var entry = dispatcher.getEntry("brainstorming");
        assertThat(entry).isNotNull();
        assertThat(entry.source()).isEqualTo("skill");
        assertThat(entry.description()).isEqualTo("Generate ideas");
    }
}
