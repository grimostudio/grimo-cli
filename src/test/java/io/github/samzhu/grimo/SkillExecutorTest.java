package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.DevModeRunner;
import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SkillExecutorTest {

    private SkillRegistry skillRegistry;
    private ChatDispatcher chatDispatcher;
    private DevModeRunner devModeRunner;
    private SkillExecutor skillExecutor;

    @BeforeEach
    void setUp() throws Exception {
        skillRegistry = mock(SkillRegistry.class);
        chatDispatcher = mock(ChatDispatcher.class);
        devModeRunner = mock(DevModeRunner.class);
        skillExecutor = new SkillExecutor(skillRegistry, chatDispatcher, devModeRunner);
    }

    /** SkillDefinition 工廠方法：產生帶特定 grimo.execution metadata 的 skill */
    private SkillDefinition skillWithExecution(String skillName, String executionMode) {
        Map<String, String> metadata = executionMode != null
            ? Map.of("grimo.execution", executionMode)
            : Map.of();
        return new SkillDefinition(
            skillName, "Test skill " + skillName,
            null, null, List.of(), metadata,
            null, null, null, null,
            null, null, null, List.of(), null, "# body"
        );
    }

    @Test
    void inlineSkillDispatchesToChat() throws Exception {
        var skill = skillWithExecution("brainstorming", "inline");
        when(skillRegistry.get("brainstorming")).thenReturn(Optional.of(skill));
        when(chatDispatcher.doDispatch(anyString())).thenReturn("Here are some ideas");

        String result = skillExecutor.execute("brainstorming", "new product");

        assertThat(result).isEqualTo("Here are some ideas");
        verify(chatDispatcher).doDispatch(anyString());
        verify(devModeRunner, never()).run(anyString(), any());
    }

    @Test
    void isolatedSkillDispatchesToDevMode() throws Exception {
        var skill = skillWithExecution("refactor", "isolated");
        when(skillRegistry.get("refactor")).thenReturn(Optional.of(skill));

        String result = skillExecutor.execute("refactor", "clean up service layer");

        assertThat(result).isNull();  // async — null indicates DevModeCompletedEvent will notify
        verify(devModeRunner).run(anyString(), any());
        verify(chatDispatcher, never()).doDispatch(anyString());
    }

    @Test
    void defaultExecutionModeIsInline() throws Exception {
        // No grimo.execution metadata → grimoExecution() returns "" → inline
        var skill = skillWithExecution("summarize", null);
        when(skillRegistry.get("summarize")).thenReturn(Optional.of(skill));
        when(chatDispatcher.doDispatch(anyString())).thenReturn("Summary done");

        String result = skillExecutor.execute("summarize", "long doc");

        assertThat(result).isEqualTo("Summary done");
        verify(chatDispatcher).doDispatch(anyString());
        verify(devModeRunner, never()).run(anyString(), any());
    }

    @Test
    void unknownSkillReturnsError() {
        when(skillRegistry.get("missing")).thenReturn(Optional.empty());

        String result = skillExecutor.execute("missing", "");

        assertThat(result).isEqualTo("Skill not found: missing");
        verifyNoInteractions(chatDispatcher, devModeRunner);
    }

    @Test
    void fullGoalIncludesSlashAndArgs() throws Exception {
        var skill = skillWithExecution("brainstorming", "inline");
        when(skillRegistry.get("brainstorming")).thenReturn(Optional.of(skill));
        when(chatDispatcher.doDispatch(anyString())).thenReturn("ok");

        skillExecutor.execute("brainstorming", "fintech startup ideas");

        verify(chatDispatcher).doDispatch("/brainstorming fintech startup ideas");
    }
}
