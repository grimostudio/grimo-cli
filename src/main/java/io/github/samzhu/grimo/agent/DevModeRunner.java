package io.github.samzhu.grimo.agent;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.tier.TierOptionsFactory;
import io.github.samzhu.grimo.agent.tier.TierRouter;
import io.github.samzhu.grimo.agent.tier.Tier;
import io.github.samzhu.grimo.mcp.McpCatalogBuilder;
import io.github.samzhu.grimo.shared.event.DevModeEnteredEvent;
import io.github.samzhu.grimo.shared.event.DevModeCompletedEvent;
import io.github.samzhu.grimo.shared.sandbox.GitHelper;
import io.github.samzhu.grimo.shared.sandbox.WorktreeProvisioner;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agents.client.AgentClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dev Mode 生命週期管理。
 *
 * 設計說明：
 * - 建立 worktree → provision skills → dispatch agent（DEV mode, 全開）→ diff → 發布完成事件
 * - 由 /dev 指令或 skill metadata.grimo.execution=isolated 觸發
 * - 完成後由 TUI event listener 顯示 diff summary + merge options
 * - 遵循 CLAUDE.md：Command → Event → TUI 解耦
 *
 * @see TierOptionsFactory.ExecutionMode#DEV
 */
@Component
public class DevModeRunner {

    private static final Logger log = LoggerFactory.getLogger(DevModeRunner.class);

    private final WorktreeProvisioner worktreeProvisioner;
    private final GitHelper gitHelper;
    private final AgentModelRegistry agentModelRegistry;
    private final TierRouter tierRouter;
    private final TierOptionsFactory tierOptionsFactory;
    private final McpCatalogBuilder mcpCatalogBuilder;
    private final SkillRegistry skillRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final AtomicReference<Tier> sessionTier;

    public DevModeRunner(WorktreeProvisioner worktreeProvisioner,
                         GitHelper gitHelper,
                         AgentModelRegistry agentModelRegistry,
                         TierRouter tierRouter,
                         TierOptionsFactory tierOptionsFactory,
                         McpCatalogBuilder mcpCatalogBuilder,
                         SkillRegistry skillRegistry,
                         ApplicationEventPublisher eventPublisher,
                         AtomicReference<Tier> sessionTier) {
        this.worktreeProvisioner = worktreeProvisioner;
        this.gitHelper = gitHelper;
        this.agentModelRegistry = agentModelRegistry;
        this.tierRouter = tierRouter;
        this.tierOptionsFactory = tierOptionsFactory;
        this.mcpCatalogBuilder = mcpCatalogBuilder;
        this.skillRegistry = skillRegistry;
        this.eventPublisher = eventPublisher;
        this.sessionTier = sessionTier;
    }

    /**
     * 執行 Dev Mode：worktree → agent dispatch → diff → event。
     */
    public void run(String goal, Path projectDir) {
        long startTime = System.currentTimeMillis();
        String taskId = UUID.randomUUID().toString().substring(0, 8);

        // Tier routing
        var tierCtx = TierRouter.Context.builder()
                .sessionTier(sessionTier.get())
                .build();
        var tierSelection = tierRouter.resolve(tierCtx);

        var agentModel = agentModelRegistry.get(tierSelection.agentId());
        if (agentModel == null) {
            log.error("Agent not found for dev mode: {}", tierSelection.agentId());
            eventPublisher.publishEvent(new DevModeCompletedEvent(
                    taskId, tierSelection.agentId(), tierSelection.model(),
                    tierSelection.tier().name().toLowerCase(), goal,
                    "local", projectDir.toString(), null, null,
                    0, "", 0, false,
                    "Agent not found: " + tierSelection.agentId(), null));
            return;
        }

        // Dev Mode options（全開）
        var devOptions = tierOptionsFactory.build(
                tierSelection.agentId(), tierSelection.model(),
                TierOptionsFactory.ExecutionMode.DEV);

        // 建 worktree
        var skillNames = skillRegistry.listAll().stream()
                .map(io.github.samzhu.grimo.skill.loader.SkillDefinition::name).toList();
        var worktree = worktreeProvisioner.provision(projectDir, taskId, skillNames);

        // 發布進入事件
        eventPublisher.publishEvent(new DevModeEnteredEvent(
                taskId,
                tierSelection.agentId(),
                tierSelection.model(),
                tierSelection.tier().name().toLowerCase(),
                goal,
                worktree.branchName() != null ? worktree.branchName() : "dev-" + taskId,
                worktree.workDir().toString()));

        log.info("Dev Mode entered: branch={}, agent={}, model={}",
                worktree.branchName(), tierSelection.agentId(), tierSelection.model());

        try {
            // Agent dispatch（全開權限）
            var client = AgentClient.builder(agentModel)
                    .mcpServerCatalog(mcpCatalogBuilder.getCatalog())
                    .defaultMcpServers(mcpCatalogBuilder.getServerNames())
                    .defaultWorkingDirectory(worktree.workDir())
                    .build();
            var response = client.run(goal, devOptions);

            long duration = System.currentTimeMillis() - startTime;
            String result = response.getResult();

            // Cleanup（auto-commits uncommitted changes, preserves branch if changes exist）
            worktreeProvisioner.cleanup(worktree, projectDir);

            // Diff summary
            int commitCount = 0;
            String diffStat = "";
            boolean hasChanges = false;

            if (worktree.isWorktree() && worktree.baseSha() != null) {
                try {
                    commitCount = gitHelper.getCommitCount(
                            projectDir, worktree.baseSha(), worktree.branchName());
                    if (commitCount > 0) {
                        diffStat = gitHelper.getDiffStat(
                                projectDir, worktree.baseSha(), worktree.branchName());
                        hasChanges = true;
                    }
                } catch (Exception e) {
                    // Branch cleaned up (no changes) — expected
                    log.debug("No changes on branch: {}", e.getMessage());
                }
            }

            String extPath = resolveExternalSessionPath(tierSelection.agentId(), worktree);
            eventPublisher.publishEvent(new DevModeCompletedEvent(
                    taskId, tierSelection.agentId(), tierSelection.model(),
                    tierSelection.tier().name().toLowerCase(), goal,
                    worktree.isWorktree() ? "worktree" : "local",
                    worktree.workDir().toString(), worktree.branchName(),
                    worktree.baseSha(),
                    commitCount, diffStat, duration, hasChanges, result, extPath));

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Dev Mode failed: {}ms, error={}", duration, e.getMessage(), e);

            try {
                worktreeProvisioner.cleanup(worktree, projectDir);
            } catch (Exception ce) {
                log.warn("Dev Mode cleanup failed: {}", ce.getMessage());
            }

            eventPublisher.publishEvent(new DevModeCompletedEvent(
                    taskId, tierSelection.agentId(), tierSelection.model(),
                    tierSelection.tier().name().toLowerCase(), goal,
                    worktree.isWorktree() ? "worktree" : "local",
                    worktree.workDir().toString(), worktree.branchName(),
                    worktree.baseSha(),
                    0, "", duration, false,
                    "Dev Mode error: " + e.getMessage(), null));
        }
    }

    /**
     * 解析外部 agent session 路徑。
     * Claude Code 的 session 存放在 ~/.claude/projects/{encoded-cwd}/。
     */
    private String resolveExternalSessionPath(String agentId,
            io.github.samzhu.grimo.shared.sandbox.WorktreeInfo worktree) {
        if (!"claude".equals(agentId)) return null;
        String encoded = worktree.workDir().toString().replaceAll("[^a-zA-Z0-9]", "-");
        return "~/.claude/projects/" + encoded + "/";
    }
}
