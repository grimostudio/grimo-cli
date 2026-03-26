package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.detect.AgentModelFactory;
import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import org.springaicommunity.agents.claude.ClaudeAgentModel;
import org.springaicommunity.agents.claude.ClaudeAgentOptions;
import org.springaicommunity.agents.codex.CodexAgentModel;
import org.springaicommunity.agents.codex.CodexAgentOptions;
import org.springaicommunity.agents.codexsdk.CodexClient;
import org.springaicommunity.agents.codexsdk.types.ApprovalPolicy;
import org.springaicommunity.agents.codexsdk.types.ExecuteOptions;
import org.springaicommunity.agents.gemini.GeminiAgentModel;
import org.springaicommunity.agents.gemini.GeminiAgentOptions;
import org.springaicommunity.agents.geminisdk.GeminiClient;
import org.springaicommunity.agents.geminisdk.transport.CLIOptions;
import org.springaicommunity.sandbox.LocalSandbox;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * CLI Agent 配置：定義所有支援的 AgentSpec 並建立 AgentModelFactory。
 *
 * 設計說明：
 * - 每個 AgentSpec 的 creator 接收 workingDirectory，回傳對應的 AgentModel
 * - options 從 GrimoConfig 的 agent-options.<id> 區段讀取；未設定時使用預設值
 * - 使用 Library 模式（不用 Starter），所有 AgentModel 手動建立
 * - Claude: 使用 ClaudeAgentModel.builder()，yolo=true 表示全自動、免確認
 * - Gemini: 需要 GeminiClient（CLIOptions + workingDir）和 LocalSandbox
 * - Codex:  需要 CodexClient（ExecuteOptions + workingDir）和 LocalSandbox，fullAuto=true
 * - timeout 預設 10 分鐘，符合長跑任務需求
 *
 * @see <a href="https://spring-ai-community.github.io/agent-client/api/claude-code-sdk.html">Claude Code SDK</a>
 * @see <a href="https://spring-ai-community.github.io/agent-client/api/gemini-cli-sdk.html">Gemini CLI SDK</a>
 * @see <a href="https://spring-ai-community.github.io/agent-client/api/codex-cli-sdk.html">Codex CLI SDK</a>
 */
@Configuration
public class AgentConfiguration {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    @Bean
    public AgentModelFactory agentModelFactory(AgentModelRegistry registry, GrimoConfig config) {
        var specs = List.of(
                // Claude Code CLI
                new AgentModelFactory.AgentSpec("claude", "cli", "Claude Code CLI", workingDirectory -> {
                    String model = config.getAgentOption("claude", "model");
                    if (model == null) model = "claude-sonnet-4-5";

                    ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                            .model(model)
                            .yolo(true)
                            .timeout(DEFAULT_TIMEOUT)
                            .workingDirectory(workingDirectory.toString())
                            .build();

                    return ClaudeAgentModel.builder()
                            .workingDirectory(workingDirectory)
                            .timeout(DEFAULT_TIMEOUT)
                            .defaultOptions(options)
                            .build();
                }),

                // Gemini CLI
                new AgentModelFactory.AgentSpec("gemini", "cli", "Gemini CLI", workingDirectory -> {
                    String model = config.getAgentOption("gemini", "model");
                    if (model == null) model = "gemini-2.5-flash";

                    CLIOptions cliOptions = CLIOptions.builder()
                            .model(model)
                            .yoloMode(true)
                            .timeout(DEFAULT_TIMEOUT)
                            .build();

                    GeminiClient client = GeminiClient.create(cliOptions, workingDirectory);

                    GeminiAgentOptions options = GeminiAgentOptions.builder()
                            .model(model)
                            .yolo(true)
                            .timeout(DEFAULT_TIMEOUT)
                            .workingDirectory(workingDirectory.toString())
                            .build();

                    LocalSandbox sandbox = new LocalSandbox(workingDirectory);

                    return new GeminiAgentModel(client, options, sandbox);
                }),

                // Codex CLI
                new AgentModelFactory.AgentSpec("codex", "cli", "Codex CLI", workingDirectory -> {
                    String model = config.getAgentOption("codex", "model");
                    if (model == null) model = "o4-mini";

                    ExecuteOptions executeOptions = ExecuteOptions.builder()
                            .model(model)
                            .fullAuto(true)
                            .approvalPolicy(ApprovalPolicy.NEVER)
                            .timeout(DEFAULT_TIMEOUT)
                            .workingDirectory(workingDirectory)
                            .build();

                    CodexClient client = CodexClient.create(executeOptions, workingDirectory);

                    CodexAgentOptions options = CodexAgentOptions.builder()
                            .model(model)
                            .fullAuto(true)
                            .approvalPolicy(ApprovalPolicy.NEVER)
                            .timeout(DEFAULT_TIMEOUT)
                            .build();

                    LocalSandbox sandbox = new LocalSandbox(workingDirectory);

                    return new CodexAgentModel(client, options, sandbox);
                })
        );
        return new AgentModelFactory(registry, specs);
    }
}
