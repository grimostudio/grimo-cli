package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.router.AgentRouter;
import io.github.samzhu.grimo.agent.tier.Tier;
import io.github.samzhu.grimo.agent.tier.TierKeywordDetector;
import io.github.samzhu.grimo.agent.tier.TierOptionsFactory;
import io.github.samzhu.grimo.agent.tier.TierRouter;
import io.github.samzhu.grimo.agent.tier.TierSelection;
import io.github.samzhu.grimo.mcp.McpCatalogBuilder;
import io.github.samzhu.grimo.shared.session.SessionWriter;
import io.github.samzhu.grimo.tui.TuiEventBridge;
import io.github.samzhu.grimo.tui.TuiKeyHandler;
import io.github.samzhu.grimo.tui.screen.EventLoop;
import io.github.samzhu.grimo.tui.view.ContentView;
import io.github.samzhu.grimo.tui.view.StatusView;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agents.client.AgentClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * AI チャットのディスパッチャー：ユーザー入力を受け取り、Tier ルーティングを経て AgentClient を呼び出す。
 *
 * 設計說明：
 * - TuiAdapter.processInput() の else ブランチ（AI 対話ロジック）を抽出（SP4 Task 4）
 * - Tier 路由、Agent 可用性確認、MCP catalog セットアップ、AgentClient 呼び出し、エラーハンドリングを担当
 * - スレッド管理（Virtual Thread 起動）と ContentView/StatusView の描画更新は TuiAdapter に残す
 * - TUI 元件（ContentView、StatusView、EventLoop）は bind() で後から注入（Spring bean でないため）
 *
 * @see TuiAdapter
 */
@Component
public class ChatDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ChatDispatcher.class);

    private final AgentModelRegistry agentModelRegistry;
    private final AgentRouter agentRouter;
    private final TierRouter tierRouter;
    private final TierKeywordDetector tierKeywordDetector;
    private final TierOptionsFactory tierOptionsFactory;
    private final McpCatalogBuilder mcpCatalogBuilder;
    private final AtomicReference<Tier> sessionTier;
    private final SessionWriter sessionWriter;
    private final TuiEventBridge tuiEventBridge;

    /** TUI 元件（run 時由 TuiAdapter.bindTui() 設定） */
    private volatile ContentView contentView;
    private volatile StatusView statusView;
    private volatile EventLoop eventLoop;
    private volatile TuiKeyHandler.AgentStateRef agentState;

    /** 本輪 tier（顯示用）：每次 dispatch 重設 */
    private volatile TierSelection currentTierSelection;

    public ChatDispatcher(AgentModelRegistry agentModelRegistry,
                          AgentRouter agentRouter,
                          TierRouter tierRouter,
                          TierKeywordDetector tierKeywordDetector,
                          TierOptionsFactory tierOptionsFactory,
                          McpCatalogBuilder mcpCatalogBuilder,
                          AtomicReference<Tier> sessionTier,
                          SessionWriter sessionWriter,
                          TuiEventBridge tuiEventBridge) {
        this.agentModelRegistry = agentModelRegistry;
        this.agentRouter = agentRouter;
        this.tierRouter = tierRouter;
        this.tierKeywordDetector = tierKeywordDetector;
        this.tierOptionsFactory = tierOptionsFactory;
        this.mcpCatalogBuilder = mcpCatalogBuilder;
        this.sessionTier = sessionTier;
        this.sessionWriter = sessionWriter;
        this.tuiEventBridge = tuiEventBridge;
    }

    /**
     * TuiAdapter が run() で TUI 元件を構築した後に呼び出してバインドする。
     * TUI 元件は Spring bean ではないため、run() 後に取得可能になる。
     *
     * @param contentView  主内容区元件
     * @param statusView   status bar 元件
     * @param eventLoop    イベントループ（setDirty 呼び出しに使用）
     * @param agentState   AI 対話の並行制御状態
     */
    public void bindTui(ContentView contentView,
                        StatusView statusView,
                        EventLoop eventLoop,
                        TuiKeyHandler.AgentStateRef agentState) {
        this.contentView = contentView;
        this.statusView = statusView;
        this.eventLoop = eventLoop;
        this.agentState = agentState;
    }

    /**
     * AI チャットを Agent にディスパッチする。Virtual Thread の起動は TuiAdapter が担当。
     *
     * 設計說明：
     * - agentRunning チェック → Tier ルーティング → AgentClient ビルド → run() までを担当
     * - "thinking..." 行の追加と Virtual Thread の起動は呼び出し元（TuiAdapter）で行う
     *
     * @param userInput ユーザーが入力したテキスト
     */
    public void dispatch(String userInput) {
        if (agentState == null || contentView == null) {
            log.warn("ChatDispatcher not bound to TUI yet, ignoring dispatch");
            return;
        }

        if (agentState.agentRunning) {
            contentView.appendError("Agent is still running. Wait or press Ctrl+C to cancel.");
            return;
        }

        try {
            // --- Tier 路由：決定用哪個 agent + model ---
            var keywordTier = tierKeywordDetector.detect(userInput).orElse(null);
            var tierCtx = TierRouter.Context.builder()
                    .keywordTier(keywordTier)
                    .sessionTier(sessionTier.get())
                    .build();
            var tierSelection = tierRouter.resolve(tierCtx);
            currentTierSelection = tierSelection;

            var model = agentModelRegistry.get(tierSelection.agentId());
            if (model == null) {
                throw new IllegalStateException("Agent not found: " + tierSelection.agentId());
            }

            // 設計說明：主對話使用 DEV mode — 跟 Claude Code 預設行為一致
            // SDK bug: 有 MCP 時 ClaudeAgentOptions 被 DefaultAgentOptions.from() 覆蓋
            // 導致 disallowedTools 丟失（instanceof ClaudeAgentOptions → false）
            // 隔離由 /dev 指令的 worktree 提供，不依賴工具限制
            var tierOptions = tierOptionsFactory.build(
                    tierSelection.agentId(), tierSelection.model());

            var mcpServers = mcpCatalogBuilder.getServerNames();
            log.info("Tier routing: {} → {} / {} (source: {}), goal: {}, mcpServers: {}",
                    tierSelection.tier(), tierSelection.agentId(), tierSelection.model(),
                    tierSelection.source(),
                    userInput.length() > 100 ? userInput.substring(0, 100) + "..." : userInput,
                    mcpServers.isEmpty() ? "none" : String.join(", ", mcpServers));

            agentState.agentRunning = true;
            contentView.appendLine(new AttributedString("\u23f3 thinking...",
                    AttributedStyle.DEFAULT.foreground(245)));
            eventLoop.setDirty();

            // 設計說明：主對話不顯示 tier（tier 是給 skill dispatch 用的）。
            // 使用者選的 agent+model 已經顯示在正常 status bar，不需要額外的 tier 標示。

            agentState.agentThread = Thread.startVirtualThread(() -> {
                long startTime = System.currentTimeMillis();
                // 設計說明：主對話 Plan Mode — 直接在 CWD 工作，不建 worktree
                // Worktree 隔離由 Dev Mode（Phase B）處理：skill metadata.grimo.execution=isolated 或 /dev 指令
                // 參考：Claude Code 預設行為 — 直接在 CWD，worktree 是可選的
                var projectDir = java.nio.file.Path.of(System.getProperty("user.dir"));
                try {
                    // 移除 "thinking..." 暫時狀態行
                    contentView.removeLastLine();

                    // 設計說明：直接用 CWD，Plan Mode 下 agent 的 disallowedTools 限制修改
                    var client = AgentClient.builder(model)
                            .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
                            .defaultMcpServers(mcpCatalogBuilder.getServerNames())
                            .defaultWorkingDirectory(projectDir)
                            .build();
                    var response = client.run(userInput, tierOptions);

                    long duration = System.currentTimeMillis() - startTime;
                    if (response.isSuccessful()) {
                        log.info("Agent response received: success=true, duration={}ms, resultLength={}",
                                duration, response.getResult() != null ? response.getResult().length() : 0);

                        if (response.getResult() != null && !response.getResult().isBlank()) {
                            contentView.appendAiReply(response.getResult());
                        }
                    } else {
                        log.warn("Agent response received: success=false, duration={}ms, result={}",
                                duration, response.getResult());
                        contentView.appendError(response.getResult());
                    }
                    sessionWriter.writeAssistantMessage(response.getResult());
                } catch (Exception e) {
                    long duration = System.currentTimeMillis() - startTime;
                    log.error("Agent call failed: duration={}ms, error={}", duration, e.getMessage(), e);
                    String errorMsg = formatAgentError(e);
                    contentView.appendError(errorMsg);
                } finally {
                    agentState.agentRunning = false;
                    agentState.agentThread = null;
                    currentTierSelection = null;
                    statusView.setStatusText(tuiEventBridge.getOriginalStatusText());
                    eventLoop.setDirty();
                }
            });
        } catch (IllegalStateException e) {
            log.warn("Agent routing failed: {}", e.getMessage());
            contentView.appendError(e.getMessage());
        }
    }

    /**
     * 格式化 Agent 執行錯誤訊息，依例外類型給出使用者友善的提示。
     */
    private String formatAgentError(Exception e) {
        String name = e.getClass().getSimpleName();
        if (name.contains("NotFoundException")) {
            return "\u26a0 CLI not found. Install the agent CLI and try again.";
        } else if (name.contains("AuthenticationException")) {
            return "\u26a0 Authentication failed. Run the agent's login command.";
        } else if (name.contains("TimeoutException")) {
            return "\u26a0 Agent timed out. Try a simpler goal.";
        }
        return "\u26a0 Agent error: " + e.getMessage();
    }
}
