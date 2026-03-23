package io.github.samzhu.grimo;

import org.springframework.shell.core.command.CommandParser;
import org.springframework.shell.core.command.ParsedInput;

/**
 * 裝飾器模式的 CommandParser：去除使用者輸入的 / 前綴後再委派給原始 parser。
 *
 * 設計說明：
 * - 使用者在 shell 輸入 /agent list 時，JLine 補全會保留 / 前綴
 *   （因為 GrimoCommandCompleter 的 Candidate value 包含 / 以利 JLine 過濾匹配）
 * - Spring Shell 的 CommandRegistry 註冊的命令名稱不含 /（如 "agent list"）
 * - 此 parser 在命令解析前去除 / 前綴，讓 /agent list → agent list
 * - 利用 @ConditionalOnMissingBean 機制覆蓋 JLineShellAutoConfiguration 的預設 CommandParser
 *
 * @see GrimoCommandCompleter
 * @see <a href="https://github.com/spring-projects/spring-shell">Spring Shell CommandParser</a>
 */
public class SlashStrippingCommandParser implements CommandParser {

    private final CommandParser delegate;

    public SlashStrippingCommandParser(CommandParser delegate) {
        this.delegate = delegate;
    }

    @Override
    public ParsedInput parse(String input) {
        if (input != null && input.startsWith("/")) {
            input = input.substring(1);
        }
        return delegate.parse(input);
    }
}
