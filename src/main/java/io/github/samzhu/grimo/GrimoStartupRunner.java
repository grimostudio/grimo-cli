package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.detect.AgentModelFactory;
import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.router.AgentRouter;
import io.github.samzhu.grimo.channel.ChannelEventListener;
import io.github.samzhu.grimo.channel.ChannelRegistry;
import io.github.samzhu.grimo.config.GrimoConfig;
import io.github.samzhu.grimo.mcp.McpCatalogBuilder;
import io.github.samzhu.grimo.shared.sandbox.SandboxDetector;
import io.github.samzhu.grimo.shared.session.SessionWriter;
import io.github.samzhu.grimo.home.GrimoHome;
import io.github.samzhu.grimo.project.ProjectContext;
import io.github.samzhu.grimo.skill.loader.SkillLoader;
import io.github.samzhu.grimo.shared.sandbox.GitHelper;
import io.github.samzhu.grimo.shared.sandbox.WorktreeProvisioner;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import io.github.samzhu.grimo.task.scheduler.TaskSchedulerService;
import io.github.samzhu.grimo.task.store.MarkdownTaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.shell.core.command.CommandExecutor;
import org.springframework.shell.core.command.CommandRegistry;

import java.nio.file.Path;

/**
 * Bean 定義配置類別：定義所有運行時管理物件的 @Bean，並執行啟動初始化邏輯。
 *
 * 設計說明：
 * - 所有 registry / manager 類別是普通 Java 物件，透過 @Bean 註冊為 Spring bean，
 *   使得 @Component 的 Commands 類別可以透過建構子注入取得依賴
 * - startupInitRunner bean：在 TUI 事件迴圈開始前（Order HIGHEST_PRECEDENCE + 1）執行靜默初始化：
 *   Agent 偵測、Skill 載入、MCP catalog 建構、Task 恢復、Sandbox 偵測
 * - TuiAdapter（Order HIGHEST_PRECEDENCE）在初始化完成後再建構 TUI 元件和啟動 event loop
 * - ChannelEventListener 需要是 Spring bean 才能讓 @EventListener 生效
 * - 舊版 AgentProviderRegistry / AgentDetector / McpClientManager / McpClientRegistry 已移除，
 *   改用 AgentModelRegistry（AgentModelFactory 偵測）和 McpCatalogBuilder（GrimoConfig 讀取）
 *
 * @see TuiAdapter
 */
@Configuration
@EnableScheduling
public class GrimoStartupRunner {

    private static final Logger log = LoggerFactory.getLogger(GrimoStartupRunner.class);

    // === Bean Definitions ===
    // 以下定義所有非 @Component 類別的 Spring bean，供各模組的 @Component Commands 類別注入使用

    @Bean
    GrimoHome grimoHome() {
        return new GrimoHome();
    }

    @Bean
    ProjectContext projectContext(GrimoHome grimoHome) {
        return new ProjectContext(grimoHome);
    }

    @Bean
    SessionWriter sessionWriter(ProjectContext projectContext) {
        return new SessionWriter(projectContext.dataDir());
    }

    @Bean
    GrimoConfig grimoConfig(GrimoHome grimoHome) {
        return new GrimoConfig(grimoHome.configFile());
    }

    // GrimoPromptProvider 已移除 — TerminalUI 不使用 LineReader/PromptProvider

    @Bean
    AgentModelRegistry agentModelRegistry() {
        return new AgentModelRegistry();
    }

    @Bean
    AgentRouter agentRouter(AgentModelRegistry agentModelRegistry, GrimoConfig grimoConfig) {
        return new AgentRouter(agentModelRegistry, grimoConfig);
    }

    @Bean
    SkillLoader skillLoader(GrimoHome grimoHome) {
        return new SkillLoader(grimoHome.skillsDir());
    }

    @Bean
    SkillRegistry skillRegistry() {
        return new SkillRegistry();
    }

    /**
     * SkillCommands 需要注入 Path skillsDir，此 bean 提供 GrimoHome 的 skills 目錄路徑。
     */
    @Bean
    Path skillsDir(GrimoHome grimoHome) {
        return grimoHome.skillsDir();
    }

    @Bean
    GitHelper gitHelper() {
        return new GitHelper();
    }

    @Bean
    WorktreeProvisioner worktreeProvisioner(GrimoHome grimoHome, GitHelper gitHelper) {
        return new WorktreeProvisioner(grimoHome.skillsDir(), gitHelper);
    }

    @Bean
    SandboxDetector sandboxDetector() {
        return new SandboxDetector();
    }

    @Bean
    MarkdownTaskStore markdownTaskStore(GrimoHome grimoHome) {
        return new MarkdownTaskStore(grimoHome.tasksDir());
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
     * ChannelEventListener 使用 @EventListener 監聽 OutgoingMessageEvent，
     * 必須是 Spring bean 才能被 Spring 事件機制發現。
     */
    @Bean
    ChannelEventListener channelEventListener(ChannelRegistry channelRegistry) {
        return new ChannelEventListener(channelRegistry);
    }

    @Bean
    io.github.samzhu.grimo.tui.widget.Banner banner() {
        return new io.github.samzhu.grimo.tui.widget.Banner();
    }

    /**
     * CommandExecutor：執行解析後的命令。
     * TerminalUI 模式下由 TuiAdapter 手動呼叫（取代 ShellRunner 的自動執行）。
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

    /**
     * 啟動靜默初始化 runner：在 TuiAdapter（HIGHEST_PRECEDENCE + 2）之前執行。
     *
     * 設計說明（為何用 ApplicationRunner 而非 @PostConstruct）：
     * - @PostConstruct 在 bean 建立時執行，彼時 Spring context 未完全就緒（CommandRegistry 等可能尚未初始化）
     * - ApplicationRunner 在 context 完全就緒後執行，確保所有 bean 都可用
     * - Order(HIGHEST_PRECEDENCE + 1) 數值小於 TuiAdapter(HIGHEST_PRECEDENCE + 2)，
     *   Spring 先執行本 runner，再執行 TuiAdapter（TuiAdapter.run() 會阻塞在 event loop）
     *
     * 初始化步驟：
     * 1. GrimoHome 與 ProjectContext 初始化（確保目錄存在）
     * 2. Agent 偵測並註冊到 AgentModelRegistry（Virtual Thread 並行偵測）
     * 3. Skill 載入並註冊到 SkillRegistry
     * 4. MCP catalog 建構（快取在 McpCatalogBuilder 內）
     * 5. Task 從磁碟恢復排程
     * 6. Sandbox 後端偵測（日誌記錄）
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    ApplicationRunner startupInitRunner(GrimoHome grimoHome,
                                        ProjectContext projectContext,
                                        AgentModelFactory agentModelFactory,
                                        SkillLoader skillLoader,
                                        SkillRegistry skillRegistry,
                                        McpCatalogBuilder mcpCatalogBuilder,
                                        TaskSchedulerService taskSchedulerService,
                                        SandboxDetector sandboxDetector,
                                        GrimoConfig grimoConfig) {
        return args -> {
            // Step 1: Home & Project 初始化
            if (!grimoHome.isInitialized()) {
                grimoHome.initialize();
            }
            projectContext.initialize();

            // Step 2: Agent 偵測並註冊（Virtual Thread 並行偵測）
            agentModelFactory.detectAndRegister(
                    Path.of(System.getProperty("user.dir")));

            // Step 3: Skill 載入
            try {
                var skills = skillLoader.loadAll();
                skills.forEach(skill -> {
                    skillRegistry.register(skill);
                    log.info("Loaded skill: {}", skill.name());
                });
            } catch (Exception e) {
                log.warn("Skill loading failed: {}", e.getMessage());
            }

            // Step 4: MCP catalog 初始建構（快取在 McpCatalogBuilder 內，/mcp-add 時自動 rebuild）
            mcpCatalogBuilder.rebuild();
            log.debug("MCP catalog built: {} servers [{}]",
                    mcpCatalogBuilder.getServerNames().size(),
                    String.join(", ", mcpCatalogBuilder.getServerNames()));

            // Step 5: Task 從磁碟恢復排程
            try {
                taskSchedulerService.restoreAll();
            } catch (Exception e) {
                log.warn("Task restoration failed: {}", e.getMessage());
            }

            // Step 6: Sandbox 後端偵測
            var sandboxResult = sandboxDetector.detect();
            String sandboxMode = sandboxDetector.resolveMode(sandboxResult, grimoConfig.getSandboxMode());
            log.info("Using sandbox mode: {}", sandboxMode);
        };
    }
}
