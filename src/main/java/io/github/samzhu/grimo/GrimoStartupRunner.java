package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.detect.AgentDetector;
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
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.shell.core.command.CommandRegistry;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.nio.file.Path;

/**
 * 核心啟動配置類別，負責兩件事：
 * 1. 定義所有運行時管理物件的 @Bean（這些類別刻意不使用 @Component，以便動態管理）
 * 2. 提供 ApplicationRunner bean 執行有序的啟動流程：
 *    workspace 初始化 → agent 偵測 → skill 載入 → MCP 連線 → 排程任務恢復
 *
 * 設計考量：
 * - 所有 registry / manager 類別是普通 Java 物件，透過此處的 @Bean 註冊為 Spring bean，
 *   使得 @Component 的 Commands 類別可以透過建構子注入取得依賴
 * - ApplicationRunner 以 @Bean 方法定義（而非讓 Configuration 類別實作介面），
 *   避免 Configuration 類別自身建構子注入其所定義的 bean 造成循環依賴
 * - ChannelEventListener 需要是 Spring bean 才能讓 @ApplicationModuleListener 生效，
 *   因此也在此處定義為 @Bean
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(GrimoProperties.class)
public class GrimoStartupRunner {

    private static final Logger log = LoggerFactory.getLogger(GrimoStartupRunner.class);

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

    /**
     * 自訂 Shell 提示符 bean，顯示目前可用的 Agent ID，
     * 優先採用 config.yaml 設定的預設 agent，
     * 讓使用者在互動時能即時知道目前連線的 AI provider。
     */
    @Bean
    GrimoPromptProvider grimoPromptProvider(AgentProviderRegistry registry, GrimoConfig grimoConfig) {
        return new GrimoPromptProvider(registry, grimoConfig);
    }

    @Bean
    AgentDetector agentDetector(AgentProviderRegistry registry) {
        return new AgentDetector(registry);
    }

    @Bean
    AgentRouter agentRouter(AgentProviderRegistry registry, GrimoConfig grimoConfig) {
        return new AgentRouter(registry, grimoConfig);
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
     * 有序啟動流程，以 @Bean 方法定義 CommandLineRunner：
     * 使用 CommandLineRunner 而非 ApplicationRunner，避免與 Spring Shell 的
     * springShellApplicationRunner 衝突（它有 @ConditionalOnMissingBean(ApplicationRunner.class)）。
     *
     * 設計說明：
     * - @Order(HIGHEST_PRECEDENCE) 確保此 runner 在 Spring Shell 的 ApplicationRunner 之前執行
     *   （Spring Shell 的 REPL 是阻塞式 while(true) 迴圈，一旦啟動就不會讓出執行緒）
     * - 全程同步執行（不使用 CompletableFuture），避免 log 與動畫 ANSI 輸出交錯
     * - 動畫順序：Phase 1-3 開場 → 同步載入 + Phase 4 逐行顯示 → Phase 5 定格 banner
     * - 非 ANSI 終端（dumb/null）跳過動畫，直接輸出 banner
     * - / 鍵 widget 在游標位置 0 時觸發補全選單，否則正常輸入
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    CommandLineRunner grimoStartup(WorkspaceManager workspaceManager,
                                    GrimoConfig grimoConfig,
                                    AgentDetector agentDetector,
                                    SkillLoader skillLoader,
                                    SkillRegistry skillRegistry,
                                    TaskSchedulerService taskSchedulerService,
                                    McpClientManager mcpClientManager,
                                    McpClientRegistry mcpClientRegistry,
                                    BannerRenderer bannerRenderer,
                                    Terminal terminal,
                                    LineReader lineReader) {
        return args -> {
            // 1. Initialize workspace（同步，後續步驟依賴）
            if (!workspaceManager.isInitialized()) {
                workspaceManager.initialize();
            }

            // 2. 判斷終端是否支援 ANSI 動畫
            boolean animated = StartupAnimationRenderer.isAnimationSupported(terminal.getType());
            StartupAnimationRenderer animator = animated ? new StartupAnimationRenderer(terminal) : null;

            // 3. Phase 1-3: 播放開場動畫（同步，避免與 log 輸出交錯）
            if (animated) {
                animator.playIntroAnimation();
            }

            // 4. 同步執行載入任務 + Phase 4 逐行顯示結果
            // Step 1: Detect agents（保留偵測結果供 banner 使用）
            String agentDetail;
            boolean agentSuccess;
            java.util.List<io.github.samzhu.grimo.agent.detect.AgentDetector.DetectionResult> agentResults = java.util.List.of();
            try {
                agentResults = agentDetector.detect();
                long available = agentResults.stream().filter(r -> r.available()).count();
                agentDetail = available + " available";
                agentSuccess = true;
            } catch (Exception e) {
                agentDetail = e.getMessage();
                agentSuccess = false;
            }
            showStep(animated, animator, terminal, 0, "Detecting agents", agentDetail, agentSuccess);

            // Step 2: Load skills
            String skillDetail;
            boolean skillSuccess;
            try {
                var skills = skillLoader.loadAll();
                skills.forEach(skillRegistry::register);
                skillDetail = skills.size() + " loaded";
                skillSuccess = true;
            } catch (Exception e) {
                skillDetail = e.getMessage();
                skillSuccess = false;
            }
            showStep(animated, animator, terminal, 1, "Loading skills", skillDetail, skillSuccess);

            // Step 3: Connect MCP servers
            String mcpDetail;
            boolean mcpSuccess;
            try {
                var config = grimoConfig.load();
                int count = 0;
                if (config.containsKey("mcp")) {
                    @SuppressWarnings("unchecked")
                    var mcpServers = (java.util.Map<String, java.util.Map<String, String>>) config.get("mcp");
                    for (var entry : mcpServers.entrySet()) {
                        try {
                            String transport = entry.getValue().get("transport");
                            String command = entry.getValue().get("command");
                            if ("stdio".equals(transport)) {
                                mcpClientManager.addStdio(entry.getKey(), command);
                                count++;
                            }
                        } catch (Exception e) {
                            // 個別 MCP server 連線失敗不中斷流程
                        }
                    }
                }
                mcpDetail = count + " servers";
                mcpSuccess = true;
            } catch (Exception e) {
                mcpDetail = e.getMessage();
                mcpSuccess = false;
            }
            showStep(animated, animator, terminal, 2, "Connecting MCP", mcpDetail, mcpSuccess);

            // Step 4: Restore tasks
            String taskDetail;
            boolean taskSuccess;
            try {
                taskSchedulerService.restoreAll();
                int count = taskSchedulerService.getScheduledTaskIds().size();
                taskDetail = count + " active";
                taskSuccess = true;
            } catch (Exception e) {
                taskDetail = e.getMessage();
                taskSuccess = false;
            }
            showStep(animated, animator, terminal, 3, "Restoring tasks", taskDetail, taskSuccess);

            // 5. Phase 4 → 5 過渡：等一下讓使用者看到載入結果
            if (animated) {
                Thread.sleep(800);
                animator.clearAnimation();
            }

            // 6. 輸出靜態 banner
            String version = GrimoStartupRunner.class.getPackage().getImplementationVersion();
            if (version == null) version = "dev";

            // Agent/Model：優先用 config 設定，fallback 到偵測到的第一個可用 agent
            String agentId = grimoConfig.getDefaultAgent();
            if (agentId == null) {
                agentId = agentResults.stream()
                    .filter(r -> r.available())
                    .map(r -> r.id())
                    .findFirst().orElse("no agent");
            }
            String model = grimoConfig.getDefaultModel();
            if (model == null) model = "unknown";
            String workspacePath = workspaceManager.root().toString()
                    .replace(System.getProperty("user.home"), "~");

            long agentCount = agentResults.stream().filter(r -> r.available()).count();
            terminal.writer().print(bannerRenderer.render(
                    version, agentId, model, workspacePath,
                    (int) agentCount,
                    skillRegistry.listAll().size(),
                    mcpClientRegistry.listAll().size(),
                    taskSchedulerService.getScheduledTaskIds().size()));
            terminal.writer().println();
            terminal.writer().flush();

            // 7. JLine 設定：AUTO_MENU、AUTO_LIST、LIST_MAX
            // LIST_MAX 設高避免 "do you wish to see all N possibilities?" 提示吃掉使用者輸入
            lineReader.setOpt(LineReader.Option.AUTO_MENU);
            lineReader.setOpt(LineReader.Option.AUTO_LIST);
            lineReader.setOpt(LineReader.Option.AUTO_MENU_LIST);
            lineReader.setVariable(LineReader.LIST_MAX, 100);

            // 8. 註冊 / 鍵 widget：游標在行首時插入 / 並觸發補全
            // Candidate value 含 / 前綴（如 "/agent list"），JLine 會依輸入 /ag
            // 進行前綴匹配過濾。LIST_MAX=100 避免 "do you wish to see" 提示。
            Widget slashAndComplete = () -> {
                if (lineReader.getBuffer().cursor() == 0) {
                    lineReader.getBuffer().write('/');
                    lineReader.callWidget(LineReader.EXPAND_OR_COMPLETE);
                } else {
                    lineReader.getBuffer().write('/');
                }
                return true;
            };
            lineReader.getWidgets().put("slash-complete", slashAndComplete);
            lineReader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new Reference("slash-complete"), "/");

            log.debug("Grimo is ready.");
        };
    }

    /**
     * 顯示單一載入步驟結果：動畫模式用 ANSI 定位，非動畫模式用 println。
     */
    private static void showStep(boolean animated, StartupAnimationRenderer animator,
                                  Terminal terminal, int index,
                                  String label, String detail, boolean success) {
        String text = StartupAnimationRenderer.formatLoadingStep(label, detail, success);
        if (animated) {
            animator.showLoadingStep(index, text);
        } else {
            terminal.writer().println(text);
            terminal.writer().flush();
        }
    }
}
