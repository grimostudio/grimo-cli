package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.detect.AgentDetector;
import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.registry.AgentProviderRegistry;
import io.github.samzhu.grimo.agent.router.AgentRouter;
import io.github.samzhu.grimo.channel.ChannelEventListener;
import io.github.samzhu.grimo.channel.ChannelRegistry;
import io.github.samzhu.grimo.mcp.client.McpClientManager;
import io.github.samzhu.grimo.mcp.client.McpClientRegistry;
import io.github.samzhu.grimo.shared.config.GrimoConfig;
import io.github.samzhu.grimo.shared.config.GrimoProperties;
import io.github.samzhu.grimo.shared.workspace.WorkspaceManager;
import io.github.samzhu.grimo.skill.loader.SkillLoader;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import io.github.samzhu.grimo.task.scheduler.TaskSchedulerService;
import io.github.samzhu.grimo.task.store.MarkdownTaskStore;
import org.jline.terminal.Terminal;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.shell.core.command.CommandExecutor;
import org.springframework.shell.core.command.CommandRegistry;

import java.nio.file.Path;

/**
 * Bean 定義配置類別：定義所有運行時管理物件的 @Bean。
 *
 * 設計說明：
 * - 所有 registry / manager 類別是普通 Java 物件，透過 @Bean 註冊為 Spring bean，
 *   使得 @Component 的 Commands 類別可以透過建構子注入取得依賴
 * - 啟動流程（動畫、偵測、banner、REPL loop）由 {@link GrimoTuiRunner} 負責
 * - ChannelEventListener 需要是 Spring bean 才能讓 @ApplicationModuleListener 生效
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(GrimoProperties.class)
public class GrimoStartupRunner {

    // === Bean Definitions ===
    // 以下定義所有非 @Component 類別的 Spring bean，供各模組的 @Component Commands 類別注入使用

    @Bean
    WorkspaceManager workspaceManager(GrimoProperties properties) {
        return new WorkspaceManager(Path.of(properties.workspace()));
    }

    @Bean
    GrimoConfig grimoConfig(WorkspaceManager workspaceManager) {
        return new GrimoConfig(workspaceManager.configFile());
    }

    @Bean
    AgentProviderRegistry agentProviderRegistry() {
        return new AgentProviderRegistry();
    }

    // GrimoPromptProvider 已移除 — TerminalUI 不使用 LineReader/PromptProvider

    @Bean
    AgentDetector agentDetector(AgentProviderRegistry registry) {
        return new AgentDetector(registry);
    }

    @Bean
    AgentModelRegistry agentModelRegistry() {
        return new AgentModelRegistry();
    }

    @Bean
    AgentRouter agentRouter(AgentModelRegistry agentModelRegistry, GrimoConfig grimoConfig) {
        return new AgentRouter(agentModelRegistry, grimoConfig);
    }

    @Bean
    SkillLoader skillLoader(WorkspaceManager workspaceManager) {
        return new SkillLoader(workspaceManager.skillsDir());
    }

    @Bean
    SkillRegistry skillRegistry() {
        return new SkillRegistry();
    }

    /**
     * SkillCommands 需要注入 Path skillsDir，此 bean 提供 workspace 下的 skills 目錄路徑。
     */
    @Bean
    Path skillsDir(WorkspaceManager workspaceManager) {
        return workspaceManager.skillsDir();
    }

    @Bean
    MarkdownTaskStore markdownTaskStore(WorkspaceManager workspaceManager) {
        return new MarkdownTaskStore(workspaceManager.tasksDir());
    }

    @Bean
    TaskSchedulerService taskSchedulerService(MarkdownTaskStore store,
                                               TaskScheduler taskScheduler,
                                               ApplicationEventPublisher publisher) {
        return new TaskSchedulerService(store, taskScheduler, publisher);
    }

    @Bean
    ChannelRegistry channelRegistry() {
        return new ChannelRegistry();
    }

    /**
     * ChannelEventListener 使用 @ApplicationModuleListener 監聽 OutgoingMessageEvent，
     * 必須是 Spring bean 才能被 Spring Modulith 事件機制發現。
     */
    @Bean
    ChannelEventListener channelEventListener(ChannelRegistry channelRegistry) {
        return new ChannelEventListener(channelRegistry);
    }

    @Bean
    McpClientRegistry mcpClientRegistry() {
        return new McpClientRegistry();
    }

    @Bean
    McpClientManager mcpClientManager(McpClientRegistry mcpRegistry) {
        return new McpClientManager(mcpRegistry);
    }

    @Bean
    BannerRenderer bannerRenderer() {
        return new BannerRenderer();
    }

    /**
     * CommandExecutor：執行解析後的命令。
     * TerminalUI 模式下由 GrimoTuiRunner 手動呼叫（取代 ShellRunner 的自動執行）。
     */
    @Bean
    CommandExecutor commandExecutor(CommandRegistry commandRegistry) {
        return new CommandExecutor(commandRegistry);
    }

    /**
     * 覆蓋 Spring Shell 自動配置的 CommandCompleter bean。
     * JLineShellAutoConfiguration 的 CommandCompleter 標註 @ConditionalOnMissingBean，
     * 當此 bean 存在時自動配置不會建立預設的 CommandCompleter。
     * GrimoCommandCompleter 繼承 CommandCompleter，增加 / 斜線選單 + SkillRegistry 補全。
     *
     * @see <a href="https://github.com/spring-projects/spring-shell">Spring Shell JLineShellAutoConfiguration</a>
     */
    @Bean
    GrimoCommandCompleter commandCompleter(
            CommandRegistry commandRegistry,
            SkillRegistry skillRegistry) {
        return new GrimoCommandCompleter(commandRegistry, skillRegistry);
    }

    /**
     * 覆蓋 Spring Shell 自動配置的 CommandParser bean。
     * 去除使用者輸入的 / 前綴後再交給預設 parser 解析，
     * 使 /agent list → agent list，讓 Spring Shell 正確找到命令。
     * 搭配 GrimoCommandCompleter 的 / 前綴候選值使用。
     *
     * @see SlashStrippingCommandParser
     * @see GrimoCommandCompleter
     */
    @Bean
    org.springframework.shell.core.command.CommandParser commandParser(CommandRegistry commandRegistry) {
        return new SlashStrippingCommandParser(
                new org.springframework.shell.core.command.DefaultCommandParser(commandRegistry));
    }
}
