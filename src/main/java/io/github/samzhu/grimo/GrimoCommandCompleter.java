package io.github.samzhu.grimo;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.jline.CommandCompleter;

import java.util.List;
import java.util.Set;

/**
 * 擴充 Spring Shell 的 CommandCompleter，增加兩項功能：
 * 1. 當輸入以 / 開頭時，提供所有管理命令 + Skills 的候選項（排除 chat）
 * 2. 非 / 開頭時，委派給原始 CommandCompleter 處理正常命令補全
 *
 * 設計說明：
 * - 繼承 CommandCompleter 而非獨立 Completer，因為 JLineShellAutoConfiguration
 *   的 lineReader bean 明確接收 CommandCompleter 類型參數
 * - 利用 @ConditionalOnMissingBean 機制覆蓋自動配置的 CommandCompleter bean
 * - Candidate 的 value 不含 / 前綴，讓 JLine 替換時自動去除 /
 *
 * @see <a href="https://github.com/spring-projects/spring-shell/blob/main/spring-shell-core-autoconfigure/src/main/java/org/springframework/shell/core/autoconfigure/JLineShellAutoConfiguration.java">JLineShellAutoConfiguration</a>
 * @see CommandCompleter
 */
public class GrimoCommandCompleter extends CommandCompleter {

    private final CommandRegistry commandRegistry;
    private final SkillRegistry skillRegistry;

    /** 排除的命令 — chat 不需要出現在選單（直接輸入文字即為對話） */
    private static final Set<String> EXCLUDED_COMMANDS = Set.of("chat");

    public GrimoCommandCompleter(CommandRegistry commandRegistry, SkillRegistry skillRegistry) {
        super(commandRegistry);
        this.commandRegistry = commandRegistry;
        this.skillRegistry = skillRegistry;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line();
        if (buffer.startsWith("/")) {
            completeSlashCommands(candidates);
        } else {
            super.complete(reader, line, candidates);
        }
    }

    /**
     * 產生斜線命令選單的候選項：
     * 1. 從 CommandRegistry 取得所有已註冊命令（排除 chat）
     * 2. 從 SkillRegistry 取得所有已載入的 Skills
     */
    private void completeSlashCommands(List<Candidate> candidates) {
        // 1. 從 CommandRegistry 取得所有命令（prefix="" 表示全部）
        commandRegistry.getCommandsByPrefix("").stream()
            .filter(cmd -> !EXCLUDED_COMMANDS.contains(cmd.getName()))
            .forEach(cmd -> candidates.add(new Candidate(
                cmd.getName(),
                "/" + cmd.getName(),
                null,
                cmd.getDescription(),
                null, null, true
            )));

        // 2. 從 SkillRegistry 取得所有 Skills
        for (SkillDefinition skill : skillRegistry.listAll()) {
            candidates.add(new Candidate(
                "skill " + skill.name(),
                "/skill " + skill.name(),
                null,
                skill.description(),
                null, null, true
            ));
        }
    }
}
