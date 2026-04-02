package io.github.samzhu.grimo.command;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指令 registry + executor。Core 內部元件（不是 Port）。
 * 支援動態 register/unregister。
 */
@Component
public class CommandDispatcher {

    @FunctionalInterface
    public interface Handler {
        String execute(String rawArgs);
    }

    public record CommandEntry(String name, String description, String source) {}

    public record HandlerEntry(String name, String description, String source, Handler handler) {}

    private final Map<String, HandlerEntry> commands = new ConcurrentHashMap<>();

    public void register(String name, String description, String source, Handler handler) {
        commands.put(name, new HandlerEntry(name, description, source, handler));
    }

    public void unregister(String name) {
        commands.remove(name);
    }

    public String execute(String name, String rawArgs) {
        var entry = commands.get(name);
        if (entry == null) return null;
        return entry.handler().execute(rawArgs);
    }

    public boolean has(String name) {
        return commands.containsKey(name);
    }

    public HandlerEntry getEntry(String name) {
        return commands.get(name);
    }

    public List<CommandEntry> listAll() {
        return commands.values().stream()
            .map(e -> new CommandEntry(e.name(), e.description(), e.source()))
            .sorted((a, b) -> a.name().compareTo(b.name()))
            .toList();
    }
}
