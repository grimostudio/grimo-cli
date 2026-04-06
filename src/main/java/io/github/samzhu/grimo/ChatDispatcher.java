package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.router.AgentRouter;
import io.github.samzhu.grimo.agent.tier.Tier;
import io.github.samzhu.grimo.agent.tier.TierOptionsFactory;
import io.github.samzhu.grimo.agent.tier.TierRouter;
import io.github.samzhu.grimo.agent.tier.TierSelection;
import io.github.samzhu.grimo.command.InputPort;
import io.github.samzhu.grimo.config.GrimoConfig;
import io.github.samzhu.grimo.mcp.McpCatalogBuilder;
import io.github.samzhu.grimo.shared.session.SessionManager;
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
    private final TierOptionsFactory tierOptionsFactory;
    private final McpCatalogBuilder mcpCatalogBuilder;
    private final AtomicReference<Tier> sessionTier;
    private final SessionManager sessionManager;
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
                          TierOptionsFactory tierOptionsFactory,
                          McpCatalogBuilder mcpCatalogBuilder,
                          AtomicReference<Tier> sessionTier,
                          SessionManager sessionManager,
                          TuiEventBridge tuiEventBridge,
                          GrimoConfig grimoConfig) {
        this.agentModelRegistry = agentModelRegistry;
        this.agentRouter = agentRouter;
        this.tierRouter = tierRouter;
        this.tierOptionsFactory = tierOptionsFactory;
        this.mcpCatalogBuilder = mcpCatalogBuilder;
        this.sessionTier = sessionTier;
        this.sessionManager = sessionManager;
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
                    sessionManager.getWriter().writeAssistantMessage(result);
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
            String agentId = null;
            String model = null;
            long startTime = System.currentTimeMillis();
            try {
                agentId = grimoConfig.getDefaultAgent();
                if (agentId == null) {
                    agentId = agentModelRegistry.listAll().entrySet().stream()
                            .filter(e -> e.getValue().isAvailable())
                            .map(java.util.Map.Entry::getKey)
                            .findFirst().orElse(null);
                }
                if (agentId == null) {
                    handleError(callback, null, null, userInput, startTime, "No agents available");
                    return;
                }
                model = grimoConfig.getDefaultModel();
                var agentModel = agentModelRegistry.get(agentId);
                if (agentModel == null) {
                    handleError(callback, agentId, model, userInput, startTime, "Agent not found in registry");
                    return;
                }

                log.info("[DISPATCH] agent={}, model={}, goal={}", agentId, model,
                        userInput.length() > 100 ? userInput.substring(0, 100) + "..." : userInput);

                // 主對話 PLAN mode（同 doDispatch 說明）
                var options = tierOptionsFactory.build(agentId, model,
                        TierOptionsFactory.ExecutionMode.PLAN);
                var projectDir = java.nio.file.Path.of(System.getProperty("user.dir"));
                var client = AgentClient.builder(agentModel)
                        .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
                        .defaultMcpServers(mcpCatalogBuilder.getServerNames())
                        .defaultWorkingDirectory(projectDir)
                        .build();

                var future = java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> client.run(userInput, options));
                var response = future.get(120, java.util.concurrent.TimeUnit.SECONDS);

                handleResponse(callback, agentId, model, userInput, startTime, response);
            } catch (java.util.concurrent.TimeoutException e) {
                handleError(callback, agentId, model, userInput, startTime, "Agent 回應逾時（120 秒）");
            } catch (Exception e) {
                handleError(callback, agentId, model, userInput, startTime, e);
            }
        });
    }

    /**
     * 指定 agent 對話（/claude args、@claude args）。不走 tier routing。
     */
    public void dispatchTo(String agentId, String text, InputPort.ResponseCallback callback) {
        Thread.ofVirtual().name("grimo-chat-" + agentId).start(() -> {
            long startTime = System.currentTimeMillis();
            try {
                var agentModel = agentModelRegistry.get(agentId);
                if (agentModel == null) {
                    handleError(callback, agentId, null, text, startTime, "Agent not found in registry");
                    return;
                }
                var configModel = grimoConfig.getAgentOption(agentId, "model");
                // @mention 直接指定 agent 也走 PLAN mode
                var options = tierOptionsFactory.build(agentId, configModel,
                        TierOptionsFactory.ExecutionMode.PLAN);

                log.info("[DISPATCH-TO] agent={}, model={}, goal={}", agentId, configModel,
                        text.length() > 100 ? text.substring(0, 100) + "..." : text);

                var projectDir = java.nio.file.Path.of(System.getProperty("user.dir"));
                var client = AgentClient.builder(agentModel)
                        .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
                        .defaultMcpServers(mcpCatalogBuilder.getServerNames())
                        .defaultWorkingDirectory(projectDir)
                        .build();
                var response = client.run(text, options);

                sessionManager.getWriter().writeUserMessage(text);
                handleResponse(callback, agentId, configModel, text, startTime, response);
            } catch (Exception e) {
                handleError(callback, agentId, null, text, startTime, e);
            }
        });
    }

    // === 統一回應處理（不 log and throw — 只在這裡 log + callback）===

    /**
     * 統一處理 Agent 回應：驗證 result → log → session → callback。
     * 空/失敗回應走 onError，有內容走 onSuccess。
     */
    private void handleResponse(InputPort.ResponseCallback callback, String agentId, String model,
                                String goal, long startTime, org.springaicommunity.agents.client.AgentClientResponse response) {
        long duration = System.currentTimeMillis() - startTime;
        String result = response.getResult();
        boolean success = response.isSuccessful();

        log.info("[AGENT-RESPONSE] agent={}, model={}, success={}, duration={}ms, len={}",
                agentId, model, success, duration, result != null ? result.length() : 0);

        // 記錄 raw response 內容（截斷 500 字元，含不可見字元轉義）
        if (result != null) {
            String preview = result.length() > 500 ? result.substring(0, 500) + "..." : result;
            String escaped = preview.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
            log.info("[AGENT-RAW] agent={}, raw='{}'", agentId, escaped);
        }

        // 記錄 response metadata + 逐行拆解 activityLog
        try {
            var metadata = response.getMetadata();
            log.info("[AGENT-META] agent={}, metadata={}", agentId, metadata);

            // 用 reflection 取 providerFields（accessor 名稱可能不同）
            java.util.Map<String, Object> fields = null;
            for (var method : metadata.getClass().getMethods()) {
                if (method.getName().contains("rovider") && method.getParameterCount() == 0) {
                    var val = method.invoke(metadata);
                    if (val instanceof java.util.Map<?,?> m) {
                        @SuppressWarnings("unchecked")
                        var cast = (java.util.Map<String, Object>) m;
                        fields = cast;
                        break;
                    }
                }
            }

            if (fields != null) {
                log.info("[AGENT-META-FIELDS] agent={}, exitCode={}, successful={}, keys={}",
                        agentId, fields.get("exitCode"), fields.get("successful"), fields.keySet());

                // 逐行拆解 activityLog，標出 SDK parser 的 marker 位置
                var activityLog = fields.get("activityLog");
                if (activityLog != null) {
                    String[] logLines = activityLog.toString().split("\n");
                    log.info("[AGENT-ACTIVITY] agent={}, totalLines={}", agentId, logLines.length);
                    for (int i = 0; i < logLines.length; i++) {
                        String tag = "";
                        if (logLines[i].startsWith("codex")) tag = " ← MARKER(codex)";
                        if (logLines[i].startsWith("tokens used")) tag = " ← MARKER(tokens used)";
                        if (logLines[i].startsWith("user")) tag = " ← MARKER(user)";
                        log.info("[AGENT-ACTIVITY] L{}: '{}'{}", i, logLines[i], tag);
                    }
                }

                // 比對 success 判定差異
                var providerSuccess = fields.get("successful");
                if (providerSuccess != null && !providerSuccess.equals(success)) {
                    log.warn("[AGENT-SUCCESS-MISMATCH] response.isSuccessful()={}, provider.successful={}",
                            success, providerSuccess);
                }
            }
        } catch (Exception e) {
            log.debug("[AGENT-META] failed: {}", e.getMessage());
        }

        if (result == null || result.isBlank()) {
            String reason = success ? "empty response" : "failed with no output";
            log.warn("[AGENT-EMPTY] agent={}, model={}, success={}, duration={}ms, goal={}",
                    agentId, model, success, duration, goalPreview(goal));
            callback.onError(agentId + " 回應為空（" + reason + "）。請確認 agent 設定正確，或嘗試其他 agent。");
            return;
        }

        sessionManager.getWriter().writeAssistantMessage(result);
        callback.onSuccess(result);
    }

    /**
     * 統一處理錯誤（Exception）：log 完整 context + full stack trace → 使用者友善訊息。
     * SLF4J 最後的 Throwable 參數自動印出完整 stack trace。
     */
    private void handleError(InputPort.ResponseCallback callback, String agentId, String model,
                             String goal, long startTime, Exception e) {
        long duration = System.currentTimeMillis() - startTime;
        // 開發者 log：完整 context + full stack trace
        log.error("[AGENT-ERROR] agent={}, model={}, duration={}ms, errorClass={}, goal={}",
                agentId, model, duration, e.getClass().getName(), goalPreview(goal),
                e);  // ← SLF4J auto-prints full stack trace

        callback.onError(classifyAndFormat(agentId, e));
    }

    /**
     * 統一處理錯誤（已知原因字串）：log warn + 使用者訊息。
     */
    private void handleError(InputPort.ResponseCallback callback, String agentId, String model,
                             String goal, long startTime, String reason) {
        long duration = System.currentTimeMillis() - startTime;
        log.warn("[AGENT-ERROR] agent={}, model={}, duration={}ms, reason={}, goal={}",
                agentId, model, duration, reason, goalPreview(goal));

        String agent = agentId != null ? agentId : "agent";
        callback.onError(agent + "：" + reason);
    }

    /**
     * 根據 exception 類型格式化使用者友善訊息（不暴露 stack trace）。
     */
    private String classifyAndFormat(String agentId, Exception e) {
        String name = e.getClass().getSimpleName();
        String agent = agentId != null ? agentId : "agent";
        if (e instanceof java.util.concurrent.TimeoutException)
            return agent + " 回應逾時。請稍後再試或換其他 agent。";
        if (name.contains("Timeout")) return agent + " 回應逾時。請稍後再試。";
        if (name.contains("NotFound") || name.contains("ProcessException"))
            return agent + " CLI 未安裝或無法執行。請確認已安裝對應 CLI。";
        if (name.contains("Authentication") || name.contains("Auth"))
            return agent + " 認證失敗。請執行 agent 登入指令。";
        return agent + " 執行錯誤：" + e.getMessage();
    }

    private String goalPreview(String goal) {
        if (goal == null) return "(null)";
        return goal.length() > 200 ? goal.substring(0, 200) + "..." : goal;
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

        // 設計說明：主對話使用 PLAN mode — 限制 agent 修改檔案的能力
        // Claude: disallowedTools=["Edit","Write","MultiEdit"]
        // Gemini: yolo=false（需確認）
        // Codex: ApprovalPolicy.SMART + fullAuto=false
        // 注意：有 MCP 時 SDK bug 會導致 ClaudeAgentOptions.disallowedTools 丟失
        // （DefaultAgentClient.resolveMcpServers() 包成 DefaultAgentOptions），
        // 屆時需 workaround 或等 SDK 修復。目前無 MCP 不受影響。
        var tierOptions = tierOptionsFactory.build(
                tierSelection.agentId(), tierSelection.model(),
                TierOptionsFactory.ExecutionMode.PLAN);

        log.info("[DISPATCH-OPTIONS] agent={}, model={}, mode=PLAN, optionsClass={}, options={}",
                tierSelection.agentId(), tierSelection.model(),
                tierOptions.getClass().getSimpleName(), tierOptions);

        // 設計說明：直接用 CWD，Plan Mode 下 agent 的 disallowedTools 限制修改
        var agentModel = agentModelRegistry.get(tierSelection.agentId());
        log.debug("[DISPATCH-AGENT] agentModel={}, available={}, defaultOptions={}",
                agentModel.getClass().getSimpleName(), agentModel.isAvailable(),
                agentModel);

        var client = AgentClient.builder(agentModel)
                .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
                .defaultMcpServers(mcpCatalogBuilder.getServerNames())
                .defaultWorkingDirectory(projectDir)
                .build();

        log.info("[DISPATCH-RUN] calling client.run(), agent={}, model={}, goalLen={}",
                tierSelection.agentId(), tierSelection.model(), userInput.length());

        var response = client.run(userInput, tierOptions);

        long duration = System.currentTimeMillis() - startTime;
        log.info("[DISPATCH-RESULT] agent={}, model={}, success={}, duration={}ms, resultLen={}, " +
                        "responseClass={}, metadata={}",
                tierSelection.agentId(), tierSelection.model(),
                response.isSuccessful(), duration,
                response.getResult() != null ? response.getResult().length() : 0,
                response.getClass().getSimpleName(),
                response.getMetadata());

        if (!response.isSuccessful()) {
            log.warn("[DISPATCH-FAIL] agent={}, model={}, duration={}ms, result='{}'",
                    tierSelection.agentId(), tierSelection.model(), duration,
                    response.getResult() != null ? response.getResult().substring(0, Math.min(200, response.getResult().length())) : "(null)");
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
        var tierCtx = TierRouter.Context.builder()
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
