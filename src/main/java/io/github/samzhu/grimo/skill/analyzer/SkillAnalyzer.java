package io.github.samzhu.grimo.skill.analyzer;

import io.github.samzhu.grimo.agent.registry.AgentModelRegistry;
import io.github.samzhu.grimo.agent.tier.Tier;
import io.github.samzhu.grimo.agent.tier.TierOptionsFactory;
import io.github.samzhu.grimo.agent.tier.TierRouter;
import io.github.samzhu.grimo.agent.tier.TierSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agents.client.AgentClient;
import org.springaicommunity.agents.model.AgentModel;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安裝 Skill 時用 lite 等級的 agent 自動分析 Skill 複雜度。
 *
 * 設計說明：
 * - 使用 lite tier 的 agent 分析（省成本）
 * - 送出標準 prompt，要求回傳 JSON { "tier": "...", "reason": "..." }
 * - 解析失敗時回傳 STD（安全預設）
 * - 如果 Skill 已標 grimo.tier → 跳過分析，尊重作者判斷
 */
public class SkillAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SkillAnalyzer.class);

    private static final String ANALYSIS_PROMPT = """
        分析以下 SKILL.md 的執行複雜度，判定為 lite / std / pro 三級之一：

        lite: 簡單查詢、格式轉換、單步操作
        std:  功能開發、bug 修復、單檔重構、一般 review
        pro:  多步驟工作流、跨檔案重構、深度分析、需要規劃能力

        SKILL.md 內容：
        %s

        回傳 JSON: { "tier": "pro", "reason": "多步驟研究流程，需要交叉比對多來源" }
        只回傳 JSON，不要其他文字。
        """;

    private static final Pattern TIER_PATTERN = Pattern.compile("\"tier\"\\s*:\\s*\"(lite|std|pro)\"");
    private static final Pattern REASON_PATTERN = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]+)\"");

    private final TierRouter tierRouter;
    private final TierOptionsFactory optionsFactory;
    private final AgentModelRegistry registry;
    private final Path workingDirectory;

    public SkillAnalyzer(TierRouter tierRouter, TierOptionsFactory optionsFactory,
                         AgentModelRegistry registry, Path workingDirectory) {
        this.tierRouter = tierRouter;
        this.optionsFactory = optionsFactory;
        this.registry = registry;
        this.workingDirectory = workingDirectory;
    }

    /**
     * 分析 Skill body，回傳建議的 Tier 和原因。
     */
    public AnalysisResult analyze(String skillBody) {
        try {
            var ctx = TierRouter.Context.builder().skillTier("lite").build();
            TierSelection selection = tierRouter.resolve(ctx);

            AgentModel agentModel = registry.get(selection.agentId());
            if (agentModel == null) {
                log.warn("No agent available for skill analysis, defaulting to std");
                return new AnalysisResult(Tier.STD, "No agent available for analysis");
            }

            var client = AgentClient.builder(agentModel)
                    .defaultWorkingDirectory(workingDirectory)
                    .build();
            var options = optionsFactory.build(selection.agentId(), selection.model());
            var response = client.run(ANALYSIS_PROMPT.formatted(skillBody), options);

            return parseResponse(response.getResult());
        } catch (Exception e) {
            log.warn("Skill analysis failed, defaulting to std: {}", e.getMessage());
            return new AnalysisResult(Tier.STD, "Analysis failed: " + e.getMessage());
        }
    }

    // package-private for testability
    static AnalysisResult parseResponse(String response) {
        if (response == null) {
            return new AnalysisResult(Tier.STD, "Empty response");
        }
        Matcher tierMatcher = TIER_PATTERN.matcher(response);
        if (tierMatcher.find()) {
            Tier tier = Tier.fromString(tierMatcher.group(1));
            String reason = "auto-analyzed";
            Matcher reasonMatcher = REASON_PATTERN.matcher(response);
            if (reasonMatcher.find()) {
                reason = reasonMatcher.group(1);
            }
            return new AnalysisResult(tier, reason);
        }
        return new AnalysisResult(Tier.STD, "Could not parse tier from response");
    }

    public record AnalysisResult(Tier tier, String reason) {}
}
