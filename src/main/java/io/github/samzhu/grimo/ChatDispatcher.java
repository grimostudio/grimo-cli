package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.router.AgentRouter;
import io.github.samzhu.grimo.agent.tier.Tier;
import io.github.samzhu.grimo.agent.tier.TierKeywordDetector;
import io.github.samzhu.grimo.agent.tier.TierOptionsFactory;
import io.github.samzhu.grimo.agent.tier.TierRouter;
import io.github.samzhu.grimo.agent.tier.TierSelection;
import io.github.samzhu.grimo.command.InputPort;
import io.github.samzhu.grimo.config.GrimoConfig;
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
    private final GrimoConfig grimoConfig;

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
                          TuiEventBridge tuiEventBridge,
                          GrimoConfig grimoConfig) {
        this.agentModelRegistry = agentModelRegistry;
        this.agentRouter = agentRouter;
        this.tierRouter = tierRouter;
        this.tierKeywordDetector = tierKeywordDetector;
        this.tierOptionsFactory = tierOptionsFactory;
        this.mcpCatalogBuilder = mcpCatalogBuilder;
        this.sessionTier = sessionTier;
        this.sessionWriter = sessionWriter;
        this.tuiEventBridge = tuiEventBridge;
        this.grimoConfig = grimoConfig;
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
     * AI チャットを Agent にディスパッチする（TUI バインド版）。
     *
     * 設計說明：
     * - TuiAdapter から呼ばれる既存エントリポイント。bindTui() 済みであること。
     * - doDispatch() で純粋ロジックを実行し、結果を contentView/statusView に反映する。
     * - Virtual Thread で非同期実行（agentThread に保持）。
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
            var tierSelection = resolveTier(userInput);
            currentTierSelection = tierSelection;

            agentState.agentRunning = true;
            contentView.appendLine(new AttributedString("\u23f3 thinking...",
                    AttributedStyle.DEFAULT.foreground(245)));
            eventLoop.setDirty();

            // 設計說明：主對話不顯示 tier（tier 是給 skill dispatch 用的）。
            // 使用者選的 agent+model 已經顯示在正常 status bar，不需要額外的 tier 標示。

            agentState.agentThread = Thread.startVirtualThread(() -> {
                try {
                    // 移除 "thinking..." 暫時狀態行
                    contentView.removeLastLine();

                    String result = doDispatch(userInput, tierSelection);

                    if (result != null && !result.isBlank()) {
                        contentView.appendAiReply(result);
                    }
                    sessionWriter.writeAssistantMessage(result);
                } catch (Exception e) {
                    log.error("Agent call failed: error={}", e.getMessage(), e);
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
     * AI チャットを Agent にディスパッチする（コールバック版）。
     *
     * 設計說明（六角架構 Port パターン）：
     * - TUI 以外の Adapter（LINE、Discord、SkillExecutor 等）から呼ばれる。
     * - TUI 元件（contentView/statusView）には一切触れず、結果を callback.onResponse() で返す。
     * - Adapter 側の ResponseCallback に channel 固有の回覆ロジックを閉じ込める。
     *   例: TUI → contentView.append()、LINE → lineApi.reply(replyToken)
     * - Virtual Thread で非同期実行（TUI 版と同じスレッドモデル）。
     * - エラー時は "Error: <message>" 形式で callback に通知する。
     *
     * @param userInput ユーザーが入力したテキスト
     * @param callback  結果受取り closure（channel 固有の回覆処理を封装）
     */
    public void dispatch(String userInput, InputPort.ResponseCallback callback) {
        Thread.startVirtualThread(() -> {
            try {
                // 設計說明：主對話使用使用者選的 defaultAgent（/agent-use 設定），
                // 不走 TierRouter 的 skill-tiers fallback list。
                // TierRouter 是給 Skill dispatch 用的（有 tier metadata）。
                String agentId = grimoConfig.getDefaultAgent();
                if (agentId == null) {
                    // Fallback：找第一個可用的 agent
                    agentId = agentModelRegistry.listAll().entrySet().stream()
                            .filter(e -> e.getValue().isAvailable())
                            .map(java.util.Map.Entry::getKey)
                            .findFirst().orElse(null);
                }
                if (agentId == null) {
                    callback.onResponse("No agents available.");
                    return;
                }
                String model = grimoConfig.getDefaultModel();
                var agentModel = agentModelRegistry.get(agentId);
                if (agentModel == null) {
                    callback.onResponse("Agent not available: " + agentId);
                    return;
                }

                log.info("Chat dispatch: agent={}, model={}, goal={}",
                        agentId, model,
                        userInput.length() > 100 ? userInput.substring(0, 100) + "..." : userInput);

                var options = tierOptionsFactory.build(agentId, model);
                var projectDir = java.nio.file.Path.of(System.getProperty("user.dir"));
                var client = AgentClient.builder(agentModel)
                        .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
                        .defaultMcpServers(mcpCatalogBuilder.getServerNames())
                        .defaultWorkingDirectory(projectDir)
                        .build();
                var response = client.run(userInput, options);

                log.info("Chat response: success={}, duration=N/A, resultLength={}",
                        response.isSuccessful(),
                        response.getResult() != null ? response.getResult().length() : 0);

                sessionWriter.writeAssistantMessage(response.getResult());
                callback.onResponse(response.getResult());
            } catch (Exception e) {
                log.error("Agent call failed (callback dispatch): error={}", e.getMessage(), e);
                callback.onResponse("Error: " + e.getMessage());
            }
        });
    }

    /**
     * 指定 agent 對話。不走 tier routing — 直接用指定的 agent。
     * 用於 /claude args、@claude args 等 agent 快捷指令。
     *
     * 設計說明：
     * - agentId 直接決定使用哪個 agent，跳過 TierRouter 的 keyword/session/fallback 邏輯。
     * - model 從 per-agent config（agent-options.<agentId>.model）讀取；未設定時為 null，
     *   TierOptionsFactory.build() 接受 null model，各 SDK 會用自身的預設值。
     * - 其餘邏輯（MCP catalog、worktree、session 寫入）與 dispatch(callback) 一致。
     *
     * @param agentId 指定的 agent（如 "claude", "gemini"）
     * @param text 使用者輸入文字
     * @param callback 結果回傳
     */
    public void dispatchTo(String agentId, String text, InputPort.ResponseCallback callback) {
        Thread.ofVirtual().name("grimo-chat-" + agentId).start(() -> {
            try {
                var model = agentModelRegistry.get(agentId);
                if (model == null) {
                    callback.onResponse("Agent not available: " + agentId);
                    return;
                }
                // 從 per-agent config 讀取記憶的 model；未設定時 TierOptionsFactory 各 SDK 用預設值
                var configModel = grimoConfig.getAgentOption(agentId, "model");
                var options = tierOptionsFactory.build(agentId, configModel);

                var projectDir = java.nio.file.Path.of(System.getProperty("user.dir"));
                var client = AgentClient.builder(model)
                        .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
                        .defaultMcpServers(mcpCatalogBuilder.getServerNames())
                        .defaultWorkingDirectory(projectDir)
                        .build();
                var response = client.run(text, options);

                sessionWriter.writeUserMessage(text);
                sessionWriter.writeAssistantMessage(response.getResult());
                callback.onResponse(response.getResult());
            } catch (Exception e) {
                log.error("Agent call failed (dispatchTo): agentId={}, error={}", agentId, e.getMessage(), e);
                callback.onResponse("Error: " + e.getMessage());
            }
        });
    }

    /**
     * SkillExecutor（同一 package）用の同期 AI 呼び出しエントリポイント。
     *
     * 設計說明：
     * - Tier 解決を内包し、SkillExecutor から単純に呼び出せるシグネチャを提供。
     * - TUI 操作・Virtual Thread 管理・セッション書き込みを含まない純粋関数。
     * - 例外は呼び出し元に伝播させる（SkillExecutor が適切にハンドリング）。
     *
     * @param userInput ユーザーが入力したテキスト
     * @return Agent の応答テキスト
     * @throws Exception AgentClient 実行中の例外
     */
    String doDispatch(String userInput) throws Exception {
        var tierSelection = resolveTier(userInput);
        return doDispatch(userInput, tierSelection);
    }

    /**
     * 純粋 AI 呼び出しロジック：Tier 選択済みの状態で AgentClient を実行し結果文字列を返す。
     *
     * 設計說明：
     * - TUI 操作・Virtual Thread 管理・セッション書き込みを含まない純粋関数。
     * - dispatch(String) と dispatch(String, ResponseCallback) の共通実装として抽出。
     * - SkillExecutor（Plan B）からも直接呼び出せるよう package-private にする。
     * - 失敗時は AgentResponse.isSuccessful() == false の結果テキストをそのまま返す
     *   （呼び出し元が成功/失敗を区別する必要がある場合は AgentResponse を返すよう拡張可）。
     *
     * @param userInput     ユーザーが入力したテキスト
     * @param tierSelection 解決済みの Tier 選択（resolveTier() で取得）
     * @return Agent の応答テキスト（成功・失敗を問わず getResult() の値）
     * @throws Exception AgentClient 実行中の例外
     */
    String doDispatch(String userInput, TierSelection tierSelection) throws Exception {
        long startTime = System.currentTimeMillis();
        // 設計說明：主對話 Plan Mode — 直接在 CWD 工作，不建 worktree
        // Worktree 隔離由 Dev Mode（Phase B）處理：skill metadata.grimo.execution=isolated 或 /dev 指令
        // 參考：Claude Code 預設行為 — 直接在 CWD，worktree 是可選的
        var projectDir = java.nio.file.Path.of(System.getProperty("user.dir"));

        // 設計說明：主對話使用 DEV mode — 跟 Claude Code 預設行為一致
        // SDK bug: 有 MCP 時 ClaudeAgentOptions 被 DefaultAgentOptions.from() 覆蓋
        // 導致 disallowedTools 丟失（instanceof ClaudeAgentOptions → false）
        // 隔離由 /dev 指令的 worktree 提供，不依賴工具限制
        var tierOptions = tierOptionsFactory.build(
                tierSelection.agentId(), tierSelection.model());

        // 設計說明：直接用 CWD，Plan Mode 下 agent 的 disallowedTools 限制修改
        var client = AgentClient.builder(agentModelRegistry.get(tierSelection.agentId()))
                .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
                .defaultMcpServers(mcpCatalogBuilder.getServerNames())
                .defaultWorkingDirectory(projectDir)
                .build();
        var response = client.run(userInput, tierOptions);

        long duration = System.currentTimeMillis() - startTime;
        if (response.isSuccessful()) {
            log.info("Agent response received: success=true, duration={}ms, resultLength={}",
                    duration, response.getResult() != null ? response.getResult().length() : 0);
        } else {
            log.warn("Agent response received: success=false, duration={}ms, result={}",
                    duration, response.getResult());
        }
        return response.getResult();
    }

    /**
     * ユーザー入力から Tier を解決する。
     *
     * 設計說明：
     * - dispatch() と dispatch(callback) の共通前処理として抽出。
     * - キーワード検出 → セッション Tier → デフォルト の優先順で解決。
     *
     * @param userInput ユーザーが入力したテキスト
     * @return 解決済みの TierSelection
     * @throws IllegalStateException Agent が見つからない場合
     */
    private TierSelection resolveTier(String userInput) {
        var keywordTier = tierKeywordDetector.detect(userInput).orElse(null);
        var tierCtx = TierRouter.Context.builder()
                .keywordTier(keywordTier)
                .sessionTier(sessionTier.get())
                .build();
        var tierSelection = tierRouter.resolve(tierCtx);

        var model = agentModelRegistry.get(tierSelection.agentId());
        if (model == null) {
            throw new IllegalStateException("Agent not found: " + tierSelection.agentId());
        }

        var mcpServers = mcpCatalogBuilder.getServerNames();
        log.info("Tier routing: {} → {} / {} (source: {}), goal: {}, mcpServers: {}",
                tierSelection.tier(), tierSelection.agentId(), tierSelection.model(),
                tierSelection.source(),
                userInput.length() > 100 ? userInput.substring(0, 100) + "..." : userInput,
                mcpServers.isEmpty() ? "none" : String.join(", ", mcpServers));

        return tierSelection;
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
