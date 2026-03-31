package io.github.samzhu.grimo.shared.tui;

import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 系統剪貼簿寫入：native 指令（pbcopy/xclip/xsel）為主，OSC 52 為 SSH 備援。
 *
 * 設計說明：
 * - 主要用 pbcopy（macOS）或 xclip/xsel（Linux），這是 tmux 和 neovim 的做法
 * - OSC 52 只在 SSH 遠端（無 pbcopy）時作為備援
 *   Terminal.app 完全不支援 OSC 52，iTerm2 預設關閉
 * - pbcopy 的 OutputStream 必須用 try-with-resources 確保 close（觸發 EOF）
 *   否則 pbcopy 會永遠等 EOF 導致 timeout
 *
 * @see <a href="https://github.com/tmux/tmux/wiki/Clipboard">tmux Clipboard Wiki</a>
 * @see <a href="https://neovim.io/doc/user/provider/">Neovim clipboard provider</a>
 */
public class ClipboardWriter {

    private static final Logger log = LoggerFactory.getLogger(ClipboardWriter.class);
    private static final int OSC52_MAX_BYTES = 100_000;

    /**
     * 複製文字到系統剪貼簿。同步執行 native 指令（不在背景 thread）。
     *
     * 設計說明：改為同步是因為 copy 通常在 Ctrl+C handler 中呼叫，
     * 非同步可能導致 process 還沒完成就被 GC 或 app 退出。
     */
    public void copy(Terminal terminal, String text) {
        if (text == null || text.isEmpty()) {
            log.debug("[CLIPBOARD] copy called with null/empty text, skipping");
            return;
        }

        log.info("[CLIPBOARD] copy: len={} text='{}'",
                text.length(), text.substring(0, Math.min(80, text.length())));

        // 1. 主要：native 指令（pbcopy/xclip）— tmux/neovim 的做法
        boolean nativeOk = nativeCopy(text);
        log.info("[CLIPBOARD] nativeCopy result: {}", nativeOk);

        // 2. 備援：OSC 52（SSH 遠端沒有 pbcopy 時）
        if (!nativeOk && isWithinOsc52Limit(text)) {
            String seq = buildOsc52Sequence(text, isTmux(), isScreen());
            terminal.writer().write(seq);
            terminal.writer().flush();
            log.info("[CLIPBOARD] OSC52 fallback sent, seq len={}", seq.length());
        }
    }

    /**
     * 用 native 指令複製到系統剪貼簿。
     * 使用 try-with-resources 確保 OutputStream close（觸發 pbcopy 的 EOF）。
     *
     * @return true if copied successfully
     */
    private boolean nativeCopy(String text) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[][] cmds;
        if (os.contains("mac")) {
            cmds = new String[][]{{"pbcopy"}};
        } else {
            cmds = new String[][]{
                {"xclip", "-selection", "clipboard"},
                {"xsel", "--clipboard", "--input"}
            };
        }
        for (String[] cmd : cmds) {
            try {
                log.info("[CLIPBOARD] trying: {}", String.join(" ", cmd));
                Process p = new ProcessBuilder(cmd).start();
                // try-with-resources 確保 close（觸發 EOF），pbcopy 才會完成
                try (OutputStream out = p.getOutputStream()) {
                    out.write(text.getBytes(UTF_8));
                    out.flush();
                }
                boolean finished = p.waitFor(3, TimeUnit.SECONDS);
                int exitCode = finished ? p.exitValue() : -1;
                log.info("[CLIPBOARD] result: finished={} exitCode={}", finished, exitCode);
                if (finished && exitCode == 0) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("[CLIPBOARD] {} failed: {}", cmd[0], e.getMessage());
            }
        }
        return false;
    }

    // --- OSC 52 備援（SSH 用）---

    static String buildOsc52Sequence(String text, boolean tmux, boolean screen) {
        String base64 = Base64.getEncoder().encodeToString(text.getBytes(UTF_8));
        String osc52 = "\033]52;c;" + base64 + "\007";
        if (tmux) {
            return "\033Ptmux;\033" + osc52 + "\033\\";
        } else if (screen) {
            return "\033P" + osc52 + "\033\\";
        }
        return osc52;
    }

    static boolean isWithinOsc52Limit(String text) {
        return text.getBytes(UTF_8).length <= OSC52_MAX_BYTES;
    }

    private static boolean isTmux() {
        return System.getenv("TMUX") != null;
    }

    private static boolean isScreen() {
        String term = System.getenv("TERM");
        return term != null && term.startsWith("screen");
    }
}
