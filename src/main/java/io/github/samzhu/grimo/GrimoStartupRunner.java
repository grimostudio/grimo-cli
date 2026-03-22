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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;

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

    @Bean
    AgentDetector agentDetector(AgentProviderRegistry registry) {
        return new AgentDetector(registry);
    }

    @Bean
    AgentRouter agentRouter(AgentProviderRegistry registry) {
        return new AgentRouter(registry);
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

    /**
     * 有序啟動流程，以 @Bean 方法定義 ApplicationRunner：
     * 1. 初始化 workspace 目錄結構
     * 2. 自動偵測可用的 agent provider（CLI 工具、API key、本地服務）
     * 3. 從 workspace/skills/ 載入所有 SKILL.md 並註冊
     * 4. 從 config.yaml 讀取 MCP 伺服器設定並重新建立連線
     * 5. 恢復先前已排定的 cron 任務
     */
    @Bean
    ApplicationRunner grimoStartup(WorkspaceManager workspaceManager,
                                    GrimoConfig grimoConfig,
                                    AgentDetector agentDetector,
                                    SkillLoader skillLoader,
                                    SkillRegistry skillRegistry,
                                    TaskSchedulerService taskSchedulerService,
                                    McpClientManager mcpClientManager) {
        return args -> {
            // 1. Initialize workspace
            log.info("Loading workspace: {}", workspaceManager.root());
            if (!workspaceManager.isInitialized()) {
                workspaceManager.initialize();
                log.info("Workspace initialized at {}", workspaceManager.root());
            }

            // 2. Detect available agents
            log.info("Detecting available agents...");
            var results = agentDetector.detect();
            results.forEach(r -> log.info("  {} — {}", r.id(), r.detail()));

            // 3. Load skills
            log.info("Loading skills...");
            var skills = skillLoader.loadAll();
            skills.forEach(skillRegistry::register);
            log.info("Loaded {} skills", skills.size());

            // 4. Reconnect MCP servers from config.yaml
            log.info("Connecting MCP servers...");
            var config = grimoConfig.load();
            if (config.containsKey("mcp")) {
                @SuppressWarnings("unchecked")
                var mcpServers = (java.util.Map<String, java.util.Map<String, String>>) config.get("mcp");
                mcpServers.forEach((name, settings) -> {
                    try {
                        String transport = settings.get("transport");
                        String command = settings.get("command");
                        if ("stdio".equals(transport)) {
                            mcpClientManager.addStdio(name, command);
                            log.info("  Connected MCP server: {}", name);
                        }
                    } catch (Exception e) {
                        log.warn("  Failed to connect MCP server {}: {}", name, e.getMessage());
                    }
                });
            }

            // 5. Restore scheduled tasks
            log.info("Restoring scheduled tasks...");
            taskSchedulerService.restoreAll();
            log.info("Scheduled tasks: {}", taskSchedulerService.getScheduledTaskIds().size());

            log.info("Grimo is ready.");
        };
    }
}
