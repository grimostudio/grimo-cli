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

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

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
     * 有序啟動流程，以 @Bean 方法定義 CommandLineRunner：
     * 使用 CommandLineRunner 而非 ApplicationRunner，避免與 Spring Shell 的
     * springShellApplicationRunner 衝突（它有 @ConditionalOnMissingBean(ApplicationRunner.class)）。
     *
     * 啟動分為 5 個階段：
     * Phase 1-3: 動畫開場（虛擬執行緒）＋ 後端並行載入（CompletableFuture）
     * Phase 4:   動畫下方逐行顯示載入結果
     * Phase 5:   清除動畫、輸出靜態 banner
     * 最後設定 JLine（AUTO_MENU、/ 鍵 widget）
     *
     * 設計說明：
     * - 動畫與載入並行，利用虛擬執行緒與 CompletableFuture 達成非同步
     * - 非 ANSI 終端（dumb/null）跳過動畫，直接輸出 banner
     * - / 鍵 widget 在游標位置 0 時觸發補全選單，否則正常輸入
     */
    @Bean
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
            log.info("Loading workspace: {}", workspaceManager.root());
            if (!workspaceManager.isInitialized()) {
                workspaceManager.initialize();
                log.info("Workspace initialized at {}", workspaceManager.root());
            }

            // 2. 判斷終端是否支援 ANSI 動畫
            boolean animated = StartupAnimationRenderer.isAnimationSupported(terminal.getType());
            StartupAnimationRenderer animator = animated ? new StartupAnimationRenderer(terminal) : null;

            // 3. Phase 1-3: 在虛擬執行緒播放開場動畫
            Thread animationThread = null;
            if (animated) {
                animationThread = Thread.ofVirtual().name("startup-animation").start(() -> {
                    animator.playIntroAnimation();
                });
            }

            // 4. 並行執行 4 項載入任務（與動畫同時進行）
            CompletableFuture<String> agentFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    var results = agentDetector.detect();
                    results.forEach(r -> log.info("  {} — {}", r.id(), r.detail()));
                    return results.size() + " agents found";
                } catch (Exception e) {
                    log.error("Agent detection failed", e);
                    throw new RuntimeException(e);
                }
            });

            CompletableFuture<String> skillFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    var skills = skillLoader.loadAll();
                    skills.forEach(skillRegistry::register);
                    return skills.size() + " skills loaded";
                } catch (Exception e) {
                    log.error("Skill loading failed", e);
                    throw new RuntimeException(e);
                }
            });

            CompletableFuture<String> mcpFuture = CompletableFuture.supplyAsync(() -> {
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
                                    log.info("  Connected MCP server: {}", entry.getKey());
                                    count++;
                                }
                            } catch (Exception e) {
                                log.warn("  Failed to connect MCP server {}: {}", entry.getKey(), e.getMessage());
                            }
                        }
                    }
                    return count + " mcp connected";
                } catch (Exception e) {
                    log.error("MCP connection failed", e);
                    throw new RuntimeException(e);
                }
            });

            CompletableFuture<String> taskFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    taskSchedulerService.restoreAll();
                    int size = taskSchedulerService.getScheduledTaskIds().size();
                    return size + " tasks restored";
                } catch (Exception e) {
                    log.error("Task restoration failed", e);
                    throw new RuntimeException(e);
                }
            });

            // 5. 等待動畫執行緒完成（Phase 1-3 結束）
            if (animationThread != null) {
                animationThread.join();
            }

            // 6. Phase 4: 逐行顯示載入結果
            record LoadingStep(String label, CompletableFuture<String> future) {}
            var steps = new LoadingStep[] {
                new LoadingStep("Detecting agents", agentFuture),
                new LoadingStep("Loading skills", skillFuture),
                new LoadingStep("Connecting MCP", mcpFuture),
                new LoadingStep("Restoring tasks", taskFuture)
            };

            for (int i = 0; i < steps.length; i++) {
                String detail;
                boolean success;
                try {
                    detail = steps[i].future().join();
                    success = true;
                } catch (Exception e) {
                    detail = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    success = false;
                }
                String text = StartupAnimationRenderer.formatLoadingStep(steps[i].label(), detail, success);
                if (animated) {
                    animator.showLoadingStep(i, text);
                } else {
                    terminal.writer().println(text);
                    terminal.writer().flush();
                }
            }

            // 7. 等待 800ms 讓使用者看到 Phase 4 載入結果
            Thread.sleep(800);

            // 8. Phase 5: 清除動畫，輸出靜態 banner
            if (animated) {
                animator.clearAnimation();
            }

            // 取得版本號（注意：lambda 中不可用 getClass()，必須用類別名稱）
            String version = GrimoStartupRunner.class.getPackage().getImplementationVersion();
            if (version == null) {
                version = "dev";
            }

            // 從 GrimoConfig 取得 agent/model 設定
            var configMap = grimoConfig.load();
            @SuppressWarnings("unchecked")
            var agentConfig = (java.util.Map<String, String>) configMap.getOrDefault("agent", java.util.Map.of());
            String agentId = agentConfig.getOrDefault("default", "none");
            String model = agentConfig.getOrDefault("model", "-");

            // workspace 路徑以 ~ 取代 home 目錄
            String workspacePath = workspaceManager.root().toString()
                    .replace(System.getProperty("user.home"), "~");

            String banner = bannerRenderer.render(
                    version, agentId, model, workspacePath,
                    skillRegistry.listAll().size(),
                    mcpClientRegistry.listAll().size(),
                    taskSchedulerService.getScheduledTaskIds().size()
            );
            terminal.writer().print(banner);
            terminal.writer().flush();

            // 9. JLine 設定：AUTO_MENU、AUTO_LIST、LIST_MAX
            lineReader.setOpt(LineReader.Option.AUTO_MENU);
            lineReader.setOpt(LineReader.Option.AUTO_LIST);
            lineReader.setVariable(LineReader.LIST_MAX, 20);

            // 10. 註冊 / 鍵 widget：游標在行首時插入 / 並觸發補全選單
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

            log.info("Grimo is ready.");
        };
    }
}
