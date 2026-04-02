package io.github.samzhu.grimo;

import io.github.samzhu.grimo.agent.DevModeRunner;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Skill 指令執行：讀 SKILL.md metadata → execution mode → 委派。
 *
 * 設計說明（Grimo = orchestrator）：
 * - execution=isolated → DevModeRunner（worktree + 全開權限）
 * - execution=inline（預設）→ ChatDispatcher.doDispatch()（主對話 + Plan Mode）
 * - fullGoal 包含 /skillName + args，agent 看到跟使用者輸入一樣的格式
 *
 * SkillDefinition.grimoExecution() 回傳 metadata["grimo.execution"]，預設 ""（empty = inline）。
 * "isolated" 值觸發 DevModeRunner（worktree 隔離，非同步，完成後發布 DevModeCompletedEvent）。
 */
@Component
public class SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(SkillExecutor.class);

    private final SkillRegistry skillRegistry;
    private final ChatDispatcher chatDispatcher;
    private final DevModeRunner devModeRunner;

    public SkillExecutor(SkillRegistry skillRegistry,
                         ChatDispatcher chatDispatcher,
                         DevModeRunner devModeRunner) {
        this.skillRegistry = skillRegistry;
        this.chatDispatcher = chatDispatcher;
        this.devModeRunner = devModeRunner;
    }

    /**
     * 執行指定 skill。
     *
     * @param skillName skill 名稱（不含 / 前綴）
     * @param args      使用者傳入的參數字串（可為空字串）
     * @return 執行結果（inline 模式下為 agent 回覆；isolated 模式下為 null，結果由 DevModeCompletedEvent 非同步通知）
     */
    public String execute(String skillName, String args) {
        var skillOpt = skillRegistry.get(skillName);
        if (skillOpt.isEmpty()) {
            return "Skill not found: " + skillName;
        }

        var skill = skillOpt.get();

        // 設計說明：使用 SkillDefinition.grimoExecution() 讀取 metadata["grimo.execution"]
        // 預設回傳 "" (empty string)，視為 inline 執行
        String executionMode = skill.grimoExecution();

        String fullGoal = "/" + skillName + (args.isEmpty() ? "" : " " + args);

        if ("isolated".equals(executionMode)) {
            // Worktree + full permissions → DevModeRunner (async, notifies via DevModeCompletedEvent)
            var projectDir = Path.of(System.getProperty("user.dir"));
            log.info("Skill execution: mode=isolated, skill={}, goal={}", skillName, fullGoal);
            devModeRunner.run(fullGoal, projectDir);
            return null;  // async — DevModeCompletedEvent will notify
        } else {
            // Inline in main context → ChatDispatcher (sync)
            log.info("Skill execution: mode=inline, skill={}, goal={}", skillName, fullGoal);
            try {
                return chatDispatcher.doDispatch(fullGoal);
            } catch (Exception e) {
                log.error("Skill execution failed: skill={}, error={}", skillName, e.getMessage(), e);
                return "Skill execution error: " + e.getMessage();
            }
        }
    }
}
