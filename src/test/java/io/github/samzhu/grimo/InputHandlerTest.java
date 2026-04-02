package io.github.samzhu.grimo;

import io.github.samzhu.grimo.command.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InputHandlerTest {

    private CommandDispatcher dispatcher;
    private ChatDispatcher chatDispatcher;
    private InputHandler handler;
    private InputPort.ResponseCallback callback;
    private String capturedResult;
    private String capturedError;

    @BeforeEach
    void setUp() {
        dispatcher = new CommandDispatcher();
        chatDispatcher = mock(ChatDispatcher.class);
        handler = new InputHandler(dispatcher, chatDispatcher);
        capturedResult = null;
        capturedError = null;
        callback = new InputPort.ResponseCallback() {
            @Override public void onSuccess(String result) { capturedResult = result; }
            @Override public void onError(String message) { capturedError = message; }
        };
    }

    @Test void slashCommandRoutesToDispatcher() {
        dispatcher.register("agent-list", "List agents", "builtin", args -> "claude, gemini");
        handler.handleInput("/agent-list", InputMetadata.tui("s1"), callback);
        assertThat(capturedResult).isEqualTo("claude, gemini");
    }

    @Test void slashCommandWithArgsRoutesCorrectly() {
        dispatcher.register("agent-use", "Switch agent", "builtin", args -> "Switched to " + args);
        handler.handleInput("/agent-use claude opus", InputMetadata.tui("s1"), callback);
        assertThat(capturedResult).isEqualTo("Switched to claude opus");
    }

    @Test void unknownSlashRoutesToChat() {
        handler.handleInput("/unknown-cmd", InputMetadata.tui("s1"), callback);
        verify(chatDispatcher).dispatch(eq("/unknown-cmd"), any());
    }

    @Test void plainTextRoutesToChat() {
        handler.handleInput("hello world", InputMetadata.tui("s1"), callback);
        verify(chatDispatcher).dispatch(eq("hello world"), any());
    }

    @Test void callbackReceivesCommandResult() {
        dispatcher.register("version", "Show version", "builtin", args -> "1.0.0");
        handler.handleInput("/version", InputMetadata.tui("s1"), callback);
        assertThat(capturedResult).isEqualTo("1.0.0");
    }

    @Test void emptyResultNotSentToCallback() {
        dispatcher.register("noop", "No output", "builtin", args -> "");
        handler.handleInput("/noop", InputMetadata.tui("s1"), callback);
        assertThat(capturedResult).isNull();  // empty result not sent
    }

    @Test void listAvailableCommandsDelegatesToDispatcher() {
        dispatcher.register("a-cmd", "desc a", "builtin", args -> "");
        dispatcher.register("b-cmd", "desc b", "builtin", args -> "");
        var cmds = handler.listAvailableCommands();
        assertThat(cmds).hasSize(2);
        assertThat(cmds.get(0).name()).isEqualTo("a-cmd");  // sorted
    }

    @Test void agentSourceRoutesToDispatchTo() {
        // /claude write tests → dispatchTo("claude", "write tests", callback)
        dispatcher.register("claude", "Claude Code CLI shortcut", "agent", args -> "");
        handler.handleInput("/claude write tests", InputMetadata.tui("s1"), callback);
        verify(chatDispatcher).dispatchTo(eq("claude"), eq("write tests"), any());
    }

    @Test void atMentionRoutesToDispatchTo() {
        // @gemini explain this → dispatchTo("gemini", "explain this", callback)
        dispatcher.register("gemini", "Gemini CLI shortcut", "agent", args -> "");
        handler.handleInput("@gemini explain this", InputMetadata.tui("s1"), callback);
        verify(chatDispatcher).dispatchTo(eq("gemini"), eq("explain this"), any());
    }
}
