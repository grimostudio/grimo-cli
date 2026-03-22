package io.github.samzhu.grimo.channel;

import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

/**
 * Spring Shell CLI commands for managing communication channel adapters.
 * Provides 'channel list' to display all registered channels with their status,
 * and 'channel remove' to unregister a channel adapter at runtime.
 *
 * Uses Spring Shell 4.0 @Command annotation model (replaces legacy @ShellComponent/@ShellMethod).
 * Reference: https://github.com/spring-projects/spring-shell/wiki/v4-migration-guide
 */
@Component
public class ChannelCommands {

    private final ChannelRegistry registry;

    public ChannelCommands(ChannelRegistry registry) {
        this.registry = registry;
    }

    /**
     * 裸指令別名：輸入 /channel 等同 /channel list，符合漸進式揭露原則。
     * Smart Default: bare 'channel' command delegates to 'channel list'.
     */
    @Command(name = "channel", description = "List configured communication channels (alias for 'channel list')")
    public String channelDefault() {
        return list();
    }

    @Command(name = {"channel", "list"}, description = "List configured communication channels")
    public String list() {
        var adapters = registry.listAll();
        if (adapters.isEmpty()) {
            return "No channels configured. Run 'channel add <type>' to add one.";
        }

        var sb = new StringBuilder();
        sb.append(String.format("  %-12s %-10s%n", "CHANNEL", "STATUS"));
        for (ChannelAdapter a : adapters) {
            String status = a.isEnabled() ? "enabled" : "disabled";
            sb.append(String.format("  %-12s %-10s%n", a.channelType(), status));
        }
        return sb.toString();
    }

    @Command(name = {"channel", "remove"}, description = "Remove a channel")
    public String remove(String channelType) {
        if (registry.get(channelType).isEmpty()) {
            return "Channel not found: " + channelType;
        }
        registry.remove(channelType);
        return "Channel removed: " + channelType;
    }
}
