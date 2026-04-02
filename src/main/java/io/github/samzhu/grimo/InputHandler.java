package io.github.samzhu.grimo;

import io.github.samzhu.grimo.command.*;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * InputPort 實作：Core 內部路由。
 *
 * 路由邏輯：
 *   / 開頭 → CommandDispatcher（本地指令 or skill or agent 快捷）
 *   @ 開頭 → CommandDispatcher（agent mention）
 *   其他   → ChatDispatcher（AI 對話）
 *
 * 設計說明（六角架構）：
 * - InputHandler 在 root package（不是 module），避免 Modulith 依賴爆炸
 * - Adapter（TuiAdapter, ChannelAdapter）呼叫 InputPort.handleInput()
 * - 結果透過 ResponseCallback 回到 Adapter — Core 不知道具體回覆機制
 */
@Component
public class InputHandler implements InputPort {

    private final CommandDispatcher commandDispatcher;
    private final ChatDispatcher chatDispatcher;

    public InputHandler(CommandDispatcher commandDispatcher, ChatDispatcher chatDispatcher) {
        this.commandDispatcher = commandDispatcher;
        this.chatDispatcher = chatDispatcher;
    }

    @Override
    public void handleInput(String text, InputMetadata metadata, ResponseCallback callback) {
        if (text.startsWith("/") || text.startsWith("@")) {
            String name = extractCommandName(text);
            String args = extractArgs(text);

            var entry = commandDispatcher.getEntry(name);
            if (entry != null) {
                if ("agent".equals(entry.source())) {
                    // Agent shortcut → async chat with specific agent (Plan B)
                    String agentId = name.startsWith("@") ? name.substring(1) : name;
                    chatDispatcher.dispatchTo(agentId, args, callback);
                } else {
                    // builtin / skill → sync execute
                    String result = commandDispatcher.execute(name, args);
                    if (result != null && !result.isEmpty()) {
                        callback.onSuccess(result);
                    }
                }
            } else {
                // Unknown command → treat as AI chat
                chatDispatcher.dispatch(text, callback);
            }
        } else {
            // Plain text → AI chat
            chatDispatcher.dispatch(text, callback);
        }
    }

    @Override
    public List<CommandDispatcher.CommandEntry> listAvailableCommands() {
        return commandDispatcher.listAll();
    }

    private String extractCommandName(String text) {
        String stripped = text.startsWith("/") ? text.substring(1) :
                          text.startsWith("@") ? text.substring(1) : text;
        int space = stripped.indexOf(' ');
        return space > 0 ? stripped.substring(0, space) : stripped;
    }

    private String extractArgs(String text) {
        String stripped = text.startsWith("/") ? text.substring(1) :
                          text.startsWith("@") ? text.substring(1) : text;
        int space = stripped.indexOf(' ');
        return space > 0 ? stripped.substring(space + 1).trim() : "";
    }
}
