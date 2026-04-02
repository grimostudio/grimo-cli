package io.github.samzhu.grimo.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandDispatcherTest {

    private CommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new CommandDispatcher();
    }

    @Test
    void registerAndExecute() {
        dispatcher.register("hello", "Say hello", "test", args -> "Hello, " + args + "!");
        String result = dispatcher.execute("hello", "World");
        assertThat(result).isEqualTo("Hello, World!");
    }

    @Test
    void executeUnknownReturnsNull() {
        String result = dispatcher.execute("nonexistent", "args");
        assertThat(result).isNull();
    }

    @Test
    void hasReturnsTrueForRegistered() {
        dispatcher.register("ping", "Ping command", "test", args -> "pong");
        assertThat(dispatcher.has("ping")).isTrue();
    }

    @Test
    void hasReturnsFalseForUnknown() {
        assertThat(dispatcher.has("unknown")).isFalse();
    }

    @Test
    void unregisterRemovesCommand() {
        dispatcher.register("temp", "Temporary command", "test", args -> "result");
        dispatcher.unregister("temp");
        assertThat(dispatcher.execute("temp", "")).isNull();
        assertThat(dispatcher.has("temp")).isFalse();
    }

    @Test
    void listAllReturnsAllEntries() {
        dispatcher.register("cmd1", "Command one", "source1", args -> "1");
        dispatcher.register("cmd2", "Command two", "source2", args -> "2");

        List<CommandDispatcher.CommandEntry> entries = dispatcher.listAll();

        assertThat(entries).hasSize(2);
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.name()).isEqualTo("cmd1");
            assertThat(e.description()).isEqualTo("Command one");
            assertThat(e.source()).isEqualTo("source1");
        });
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.name()).isEqualTo("cmd2");
            assertThat(e.description()).isEqualTo("Command two");
            assertThat(e.source()).isEqualTo("source2");
        });
    }

    @Test
    void registerOverwritesExisting() {
        dispatcher.register("cmd", "Original", "src", args -> "original");
        dispatcher.register("cmd", "Updated", "src", args -> "updated");

        String result = dispatcher.execute("cmd", "");
        assertThat(result).isEqualTo("updated");

        var entry = dispatcher.getEntry("cmd");
        assertThat(entry.description()).isEqualTo("Updated");
    }

    @Test
    void listAllSortedByName() {
        dispatcher.register("zebra", "Z command", "src", args -> "z");
        dispatcher.register("alpha", "A command", "src", args -> "a");
        dispatcher.register("mango", "M command", "src", args -> "m");

        List<CommandDispatcher.CommandEntry> entries = dispatcher.listAll();

        assertThat(entries).extracting(CommandDispatcher.CommandEntry::name)
            .containsExactly("alpha", "mango", "zebra");
    }
}
