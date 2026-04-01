package io.github.samzhu.grimo;

import io.github.samzhu.grimo.shared.event.IncomingMessageEvent;
import io.github.samzhu.grimo.shared.event.OutgoingMessageEvent;
import io.github.samzhu.grimo.shared.session.SessionWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.CommandExecutor;
import org.springframework.shell.core.command.CommandParser;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * 統一訊息路由器：接收 {@link IncomingMessageEvent}，判斷是斜線指令或 AI 對話，
 * 執行後透過 {@link OutgoingMessageEvent} 發布結果。
 *
 * 設計說明（Event-driven pipeline）：
 * <pre>
 * TUI input
 *   → IncomingMessageEvent (sourceAdapter="tui")
 *   → MessageRouter.on(IncomingMessageEvent)
 *       ├─ /command → CommandExecutor.execute() → OutgoingMessageEvent(targetAdapter=sourceAdapter)
 *       └─ AI text  → ChatDispatcher.dispatch() （ChatDispatcher 自管 Virtual Thread 與 TUI 更新）
 * </pre>
 *
 * 執行緒說明：
 * - 指令執行（/command）同步完成，結果透過 OutgoingMessageEvent 回傳。
 * - AI 對話委派給 {@link ChatDispatcher#dispatch(String)}；
 *   ChatDispatcher 內部啟動 Virtual Thread，並直接更新 TUI 元件（不走 OutgoingMessageEvent）。
 *   這樣保留了 ChatDispatcher 的 streaming / progress 更新能力，未來可逐步遷移到事件驅動。
 *
 * TUI 專屬攔截（/exit、/mcp 無參數、/agent-use 無參數）仍在 TuiAdapter.processInput() 處理，
 * 不會進入 MessageRouter。
 *
 * @see TuiAdapter
 * @see ChatDispatcher
 */
@Component
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    private final CommandParser commandParser;
    private final CommandExecutor commandExecutor;
    private final CommandRegistry commandRegistry;
    private final ChatDispatcher chatDispatcher;
    private final SessionWriter sessionWriter;
    private final ApplicationEventPublisher eventPublisher;

    public MessageRouter(CommandParser commandParser,
                         CommandExecutor commandExecutor,
                         CommandRegistry commandRegistry,
                         ChatDispatcher chatDispatcher,
                         SessionWriter sessionWriter,
                         ApplicationEventPublisher eventPublisher) {
        this.commandParser = commandParser;
        this.commandExecutor = commandExecutor;
        this.commandRegistry = commandRegistry;
        this.chatDispatcher = chatDispatcher;
        this.sessionWriter = sessionWriter;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 接收入站訊息，路由到指令執行或 AI 對話。
     *
     * 設計說明：
     * - 斜線指令（text 以 "/" 開頭）：同步執行，捕獲輸出，發布 OutgoingMessageEvent
     * - AI 對話：委派 ChatDispatcher（含 Virtual Thread），結果由 ChatDispatcher 直接更新 TUI
     */
    @EventListener
    public void on(IncomingMessageEvent event) {
        String text = event.text();
        if (text == null || text.isBlank()) {
            return;
        }

        if (text.startsWith("/")) {
            executeCommand(text, event);
        } else {
            // AI 對話：ChatDispatcher 負責 Virtual Thread 管理與 TUI 直接更新
            chatDispatcher.dispatch(text);
        }
    }

    /**
     * 執行斜線指令並將輸出發布為 {@link OutgoingMessageEvent}。
     *
     * 設計說明：
     * - 使用 CommandContext（StringWriter）捕獲指令輸出，與 TuiAdapter.processInput() 相同模式
     * - 同時寫入 SessionWriter（command entry），保持 JSONL 對話記錄完整性
     * - targetAdapter 對應 event.sourceAdapter()，確保回應回到同一個 adapter
     */
    private void executeCommand(String text, IncomingMessageEvent event) {
        try {
            var parsed = commandParser.parse(text);
            var stringWriter = new StringWriter();
            var printWriter = new PrintWriter(stringWriter);
            var ctx = new CommandContext(parsed, commandRegistry, printWriter, null);
            commandExecutor.execute(ctx);
            printWriter.flush();
            String output = stringWriter.toString().trim();
            sessionWriter.writeCommandMessage(text, output);
            if (!output.isEmpty()) {
                publishOutgoing(output, event);
            }
        } catch (Exception e) {
            log.warn("Command execution failed: text={}, error={}", text, e.getMessage());
            String errorMsg = "Error: " + e.getMessage();
            sessionWriter.writeCommandMessage(text, errorMsg);
            publishOutgoing(errorMsg, event);
        }
    }

    /**
     * 發布 OutgoingMessageEvent，targetAdapter 對應原始 sourceAdapter。
     */
    private void publishOutgoing(String result, IncomingMessageEvent source) {
        eventPublisher.publishEvent(new OutgoingMessageEvent(
                source.channelType(),
                source.conversationId(),
                result,
                List.of(),
                source.sourceAdapter()   // targetAdapter = sourceAdapter（回到來源）
        ));
    }
}
