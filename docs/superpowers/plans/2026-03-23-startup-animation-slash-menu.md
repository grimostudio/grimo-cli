# Startup Animation & Slash Command Menu Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a magical startup animation with pixel ghost mascot banner and `/` slash command interactive menu to Grimo CLI.

**Architecture:** Three new classes in root package `io.github.samzhu.grimo`: `BannerRenderer` (static banner with ghost mascot + env info), `StartupAnimationRenderer` (5-phase ANSI animation synced with loading via CompletableFuture), and `GrimoCommandCompleter` (extends Spring Shell's `CommandCompleter` to handle `/` prefix + skill candidates). The existing `GrimoStartupRunner` is modified to orchestrate animation → loading → banner → JLine widget configuration.

**Tech Stack:** JLine 3 (Completer, Candidate, Widget, LineReader.Option), ANSI escape codes, Unicode block characters, Spring Shell 4.0.1 auto-configuration (`@ConditionalOnMissingBean` on `CommandCompleter`), CompletableFuture, Virtual Threads (Java 25).

**Spec:** `docs/superpowers/specs/2026-03-23-grimo-cli-startup-animation-slash-menu-design.md`

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/io/github/samzhu/grimo/BannerRenderer.java` | Pure function: renders static ghost mascot banner + environment status |
| Create | `src/test/java/io/github/samzhu/grimo/BannerRendererTest.java` | Unit tests for banner rendering |
| Create | `src/main/java/io/github/samzhu/grimo/GrimoCommandCompleter.java` | Extends `CommandCompleter`, adds `/` prefix handling + SkillRegistry candidates |
| Create | `src/test/java/io/github/samzhu/grimo/GrimoCommandCompleterTest.java` | Unit tests for slash command completion |
| Create | `src/main/java/io/github/samzhu/grimo/StartupAnimationRenderer.java` | 5-phase ANSI terminal animation, synced with CompletableFuture loading steps |
| Create | `src/test/java/io/github/samzhu/grimo/StartupAnimationRendererTest.java` | Tests for dumb terminal fallback and loading step result formatting |
| Modify | `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java` | Integrate animation + banner + GrimoCommandCompleter bean + `/` widget + AUTO_MENU |

---

### Task 1: BannerRenderer — Static Ghost Mascot Banner

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/BannerRenderer.java`
- Create: `src/test/java/io/github/samzhu/grimo/BannerRendererTest.java`

The banner renders a left-side ghost mascot (5 lines, Unicode block characters, ANSI 256 cyan/gold) + right-side environment status (4 lines). This is a pure function — takes data, returns a formatted string. No Terminal dependency.

**Banner layout (from spec):**
```
    ✦                Grimo v0.1.0
  ▄████▄             claude-cli · sonnet
  █●██●█             ~/workspace/grimo-cli
  ██████             3 skills · 2 mcp · 1 task
  ▀▄▀▀▄▀
```

- [ ] **Step 1: Write failing test — banner contains mascot and env info**

```java
// src/test/java/io/github/samzhu/grimo/BannerRendererTest.java
package io.github.samzhu.grimo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BannerRendererTest {

    @Test
    void renderShouldContainVersionAndAgentInfo() {
        var renderer = new BannerRenderer();

        String banner = renderer.render("0.1.0", "claude-cli", "sonnet",
            "~/workspace/grimo-cli", 3, 2, 1);

        assertThat(banner).contains("Grimo");
        assertThat(banner).contains("0.1.0");
        assertThat(banner).contains("claude-cli");
        assertThat(banner).contains("sonnet");
        assertThat(banner).contains("~/workspace/grimo-cli");
        assertThat(banner).contains("3 skills");
        assertThat(banner).contains("2 mcp");
        assertThat(banner).contains("1 task");
    }

    @Test
    void renderShouldContainMascotCharacters() {
        var renderer = new BannerRenderer();

        String banner = renderer.render("dev", "none", "none", "~/test", 0, 0, 0);

        // Ghost mascot uses Unicode block characters
        assertThat(banner).contains("▄");
        assertThat(banner).contains("█");
        assertThat(banner).contains("▀");
        // Gold star above head
        assertThat(banner).contains("✦");
    }

    @Test
    void renderShouldHandleDevVersion() {
        var renderer = new BannerRenderer();

        String banner = renderer.render("dev", "no agent", "unknown",
            "~/test", 0, 0, 0);

        assertThat(banner).contains("dev");
        assertThat(banner).contains("no agent");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.samzhu.grimo.BannerRendererTest" --info`
Expected: FAIL — `BannerRenderer` class does not exist

- [ ] **Step 3: Implement BannerRenderer**

```java
// src/main/java/io/github/samzhu/grimo/BannerRenderer.java
package io.github.samzhu.grimo;

/**
 * 渲染靜態 Banner：左側可愛方塊幽靈吉祥物 + 右側環境狀態。
 *
 * 設計說明：
 * - 左側用 Unicode block characters (█▀▄▐▌) 拼出 5 行高的幽靈造型
 * - 顏色用 ANSI 256 色：青色系 (38;5;30-37) 對應品牌色 #5ba3b5，金色 (38;5;178) 用於星光
 * - 右側 4 行環境狀態：版本、Agent/Model、工作目錄、資源計數
 * - 純函數設計，無 Terminal 依賴，方便單元測試
 *
 * @see <a href="https://en.wikipedia.org/wiki/ANSI_escape_code#8-bit">ANSI 256 Color Reference</a>
 */
public class BannerRenderer {

    // ANSI 256 色碼
    private static final String CYAN = "\033[38;5;37m";     // 青色（品牌色 #5ba3b5）
    private static final String GOLD = "\033[38;5;178m";    // 金色（星光 #e8c44a）
    private static final String WHITE = "\033[1;37m";       // 白色粗體
    private static final String GRAY = "\033[38;5;245m";    // 灰色
    private static final String RESET = "\033[0m";

    /**
     * 渲染完整 banner 字串（含 ANSI 色碼）。
     *
     * @param version       應用程式版本號（如 "0.1.0" 或 "dev"）
     * @param agentId       當前預設 Agent ID（如 "claude-cli"）
     * @param model         當前預設模型（如 "sonnet"）
     * @param workspacePath 工作目錄路徑（已用 ~ 取代 $HOME）
     * @param skillCount    已載入的 skill 數量
     * @param mcpCount      已連線的 MCP server 數量
     * @param taskCount     進行中的 task 數量
     * @return 含 ANSI escape codes 的多行 banner 字串
     */
    public String render(String version, String agentId, String model,
                         String workspacePath, int skillCount, int mcpCount, int taskCount) {
        // 左側吉祥物 5 行 + 右側資訊 4 行（右側對齊到吉祥物第 2-5 行）
        String gap = "        ";  // 8 spaces between mascot and info
        var sb = new StringBuilder();
        sb.append(GOLD).append("    ✦").append(RESET)
          .append(gap).append("       ")
          .append(WHITE).append("Grimo").append(RESET)
          .append(" ").append(GRAY).append("v").append(version).append(RESET)
          .append("\n");
        sb.append(CYAN).append("  ▄████▄").append(RESET)
          .append(gap)
          .append(GRAY).append(agentId).append(" · ").append(model).append(RESET)
          .append("\n");
        sb.append(CYAN).append("  █").append(WHITE).append("●").append(CYAN).append("██")
          .append(WHITE).append("●").append(CYAN).append("█").append(RESET)
          .append(gap)
          .append(GRAY).append(workspacePath).append(RESET)
          .append("\n");
        sb.append(CYAN).append("  ██████").append(RESET)
          .append(gap)
          .append(GRAY).append(skillCount).append(" skills · ")
          .append(mcpCount).append(" mcp · ")
          .append(taskCount).append(" task").append(RESET)
          .append("\n");
        sb.append(CYAN).append("  ▀▄▀▀▄▀").append(RESET).append("\n");
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.samzhu.grimo.BannerRendererTest" --info`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/BannerRenderer.java \
        src/test/java/io/github/samzhu/grimo/BannerRendererTest.java
git commit -m "feat: add BannerRenderer for ghost mascot banner"
```

---

### Task 2: GrimoCommandCompleter — Slash Command Completion

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/GrimoCommandCompleter.java`
- Create: `src/test/java/io/github/samzhu/grimo/GrimoCommandCompleterTest.java`

Extends Spring Shell's `CommandCompleter` to:
1. Handle `/` prefix — when input starts with `/`, provide all commands (except `chat`) + skills as candidates
2. Normal completion — delegate to `super.complete()` for non-`/` input

**Key insight:** `JLineShellAutoConfiguration` declares `CommandCompleter` bean with `@ConditionalOnMissingBean` ([source: spring-shell-core-autoconfigure-4.0.1](https://github.com/spring-projects/spring-shell)). If we provide our own `CommandCompleter` typed bean, the auto-config backs off.

- [ ] **Step 1: Write failing test — slash prefix provides command + skill candidates**

```java
// src/test/java/io/github/samzhu/grimo/GrimoCommandCompleterTest.java
package io.github.samzhu.grimo;

import io.github.samzhu.grimo.skill.loader.SkillDefinition;
import io.github.samzhu.grimo.skill.registry.SkillRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrimoCommandCompleterTest {

    SkillRegistry skillRegistry;
    // CommandRegistry is an interface from Spring Shell — we mock it
    org.springframework.shell.core.command.CommandRegistry commandRegistry;
    GrimoCommandCompleter completer;

    @BeforeEach
    void setUp() {
        skillRegistry = new SkillRegistry();
        commandRegistry = mock(org.springframework.shell.core.command.CommandRegistry.class);
        // Return empty list for any prefix query (simplifies test setup)
        when(commandRegistry.getCommandsByPrefix(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(List.of());
        completer = new GrimoCommandCompleter(commandRegistry, skillRegistry);
    }

    @Test
    void slashPrefixShouldProvideSkillCandidates() {
        skillRegistry.register(new SkillDefinition("healthcheck", "Check health", "1.0.0",
            "grimo-builtin", "api", List.of("cron"), "# HC"));

        var candidates = new ArrayList<Candidate>();
        var line = mockParsedLine("/", List.of("/"));
        completer.complete(mock(LineReader.class), line, candidates);

        assertThat(candidates).anyMatch(c -> c.value().contains("healthcheck"));
    }

    @Test
    void slashPrefixShouldFilterByTypedText() {
        skillRegistry.register(new SkillDefinition("healthcheck", "Check health", "1.0.0",
            "grimo-builtin", "api", List.of(), "# HC"));

        var candidates = new ArrayList<Candidate>();
        var line = mockParsedLine("/hea", List.of("/hea"));
        completer.complete(mock(LineReader.class), line, candidates);

        // Skill candidates should still be returned — JLine handles filtering
        assertThat(candidates).anyMatch(c -> c.value().contains("healthcheck"));
    }

    @Test
    void nonSlashInputShouldDelegateToParent() {
        var candidates = new ArrayList<Candidate>();
        var line = mockParsedLine("stat", List.of("stat"));
        completer.complete(mock(LineReader.class), line, candidates);

        // Should delegate to parent — no custom skill candidates added
        // (parent returns empty since we mocked empty registry)
        // Just verify no exception thrown
    }

    private ParsedLine mockParsedLine(String line, List<String> words) {
        var parsed = mock(ParsedLine.class);
        when(parsed.line()).thenReturn(line);
        when(parsed.words()).thenReturn(words);
        when(parsed.word()).thenReturn(words.isEmpty() ? "" : words.getLast());
        when(parsed.wordIndex()).thenReturn(0);
        when(parsed.wordCursor()).thenReturn(line.length());
        return parsed;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.samzhu.grimo.GrimoCommandCompleterTest" --info`
Expected: FAIL — `GrimoCommandCompleter` class does not exist

- [ ] **Step 3: Implement GrimoCommandCompleter**

```java
// src/main/java/io/github/samzhu/grimo/GrimoCommandCompleter.java
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
 * @see <a href="https://github.com/jline/jline3/wiki/Completion">JLine 3 Completion Wiki</a>
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
     * 提供 / 選單的候選項：所有已註冊命令（排除 chat）+ 所有已載入 Skills。
     * Candidate value 不含 / 前綴，讓 JLine 替換 /xxx → command name。
     */
    private void completeSlashCommands(List<Candidate> candidates) {
        // 1. 從 CommandRegistry 取得所有命令
        commandRegistry.getCommandsByPrefix("").stream()
            .filter(cmd -> !EXCLUDED_COMMANDS.contains(cmd.getName()))
            .forEach(cmd -> candidates.add(new Candidate(
                cmd.getName(),                    // value — 填入輸入框的文字
                "/" + cmd.getName(),               // display — 選單中顯示的文字
                null,                              // group
                cmd.getDescription(),              // description — 右側說明
                null,                              // suffix
                null,                              // key
                true                               // complete — 選中即完成
            )));

        // 2. 從 SkillRegistry 取得所有 Skills
        for (SkillDefinition skill : skillRegistry.listAll()) {
            candidates.add(new Candidate(
                "skill " + skill.name(),           // value — 如 "skill healthcheck"
                "/skill " + skill.name(),           // display
                null,
                skill.description(),
                null,
                null,
                true
            ));
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.samzhu.grimo.GrimoCommandCompleterTest" --info`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoCommandCompleter.java \
        src/test/java/io/github/samzhu/grimo/GrimoCommandCompleterTest.java
git commit -m "feat: add GrimoCommandCompleter for slash command menu"
```

---

### Task 3: StartupAnimationRenderer — 5-Phase Startup Animation

**Files:**
- Create: `src/main/java/io/github/samzhu/grimo/StartupAnimationRenderer.java`
- Create: `src/test/java/io/github/samzhu/grimo/StartupAnimationRendererTest.java`

Renders a 5-phase ANSI animation on the terminal, synced with actual loading steps via `CompletableFuture`. Falls back to plain text on dumb terminals.

**Animation Phases:**
1. (0.0s) Star particles ✦ ✧ · scatter on screen
2. (0.5s) Stars gather toward center
3. (1.0s) Ghost mascot logo appears line by line
4. (1.5s) Loading progress — each step waits for its CompletableFuture
5. (2.5s) Clear and show final static banner

- [ ] **Step 1: Write failing test — dumb terminal fallback and loading step formatting**

```java
// src/test/java/io/github/samzhu/grimo/StartupAnimationRendererTest.java
package io.github.samzhu.grimo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StartupAnimationRendererTest {

    @Test
    void isAnimationSupportedShouldReturnFalseForDumbTerminal() {
        assertThat(StartupAnimationRenderer.isAnimationSupported("dumb")).isFalse();
    }

    @Test
    void isAnimationSupportedShouldReturnTrueForXterm() {
        assertThat(StartupAnimationRenderer.isAnimationSupported("xterm-256color")).isTrue();
    }

    @Test
    void isAnimationSupportedShouldReturnFalseForNull() {
        assertThat(StartupAnimationRenderer.isAnimationSupported(null)).isFalse();
    }

    @Test
    void formatLoadingStepSuccessShouldContainCheckMark() {
        String result = StartupAnimationRenderer.formatLoadingStep(
            "Detecting agents", "claude-cli", true);

        assertThat(result).contains("Detecting agents");
        assertThat(result).contains("claude-cli");
        assertThat(result).contains("✓");
    }

    @Test
    void formatLoadingStepFailureShouldContainCrossMark() {
        String result = StartupAnimationRenderer.formatLoadingStep(
            "Connecting MCP", "connection refused", false);

        assertThat(result).contains("Connecting MCP");
        assertThat(result).contains("✗");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.samzhu.grimo.StartupAnimationRendererTest" --info`
Expected: FAIL — `StartupAnimationRenderer` class does not exist

- [ ] **Step 3: Implement StartupAnimationRenderer**

```java
// src/main/java/io/github/samzhu/grimo/StartupAnimationRenderer.java
package io.github.samzhu.grimo;

import org.jline.terminal.Terminal;

import java.io.PrintWriter;
import java.util.Random;

/**
 * 啟動動畫渲染器：5 階段 ANSI 動畫，與實際載入流程透過 CompletableFuture 同步。
 *
 * 設計說明：
 * - Phase 1: 星光 ✦ ✧ · 隨機散落螢幕
 * - Phase 2: 星光往中心聚集
 * - Phase 3: 幽靈吉祥物 logo 從中心逐行浮現
 * - Phase 4: 載入進度逐行顯示（由外部 CompletableFuture 驅動）
 * - Phase 5: 清除動畫殘留，只留最終 banner
 * - 非 ANSI 終端（dumb/null）跳過動畫，直接輸出 banner
 *
 * 動畫使用 ANSI escape codes 控制：
 * - \033[H (cursor home) + \033[2J (clear screen) 清除畫面
 * - \033[?25l / \033[?25h 隱藏/顯示游標
 * - \033[row;colH 定位游標
 * - \033[38;5;Xm 設定 256 色
 *
 * @see <a href="https://en.wikipedia.org/wiki/ANSI_escape_code">ANSI Escape Codes Reference</a>
 */
public class StartupAnimationRenderer {

    private static final String CYAN = "\033[38;5;37m";
    private static final String GOLD = "\033[38;5;178m";
    private static final String WHITE = "\033[1;37m";
    private static final String GREEN = "\033[38;5;34m";
    private static final String RED = "\033[38;5;196m";
    private static final String GRAY = "\033[38;5;245m";
    private static final String RESET = "\033[0m";

    private static final String HIDE_CURSOR = "\033[?25l";
    private static final String SHOW_CURSOR = "\033[?25h";
    private static final String CLEAR_SCREEN = "\033[H\033[2J";

    private static final String[] STAR_CHARS = {"✦", "✧", "·", "✦", "✧"};
    private static final String[] MASCOT_LINES = {
        "    ✦",
        "  ▄████▄",
        "  █●██●█",
        "  ██████",
        "  ▀▄▀▀▄▀"
    };

    private static final long FRAME_DELAY_MS = 80;

    private final Terminal terminal;
    private final PrintWriter writer;
    private final int width;
    private final int height;
    private final Random random = new Random();

    public StartupAnimationRenderer(Terminal terminal) {
        this.terminal = terminal;
        this.writer = terminal.writer();
        this.width = Math.max(terminal.getWidth(), 40);
        this.height = Math.max(terminal.getHeight(), 20);
    }

    /**
     * 判斷終端是否支援 ANSI 動畫。
     * dumb/null 終端不支援 ANSI escape codes，應使用 fallback 純文字輸出。
     */
    public static boolean isAnimationSupported(String terminalType) {
        if (terminalType == null) return false;
        return !terminalType.equals("dumb") && !terminalType.equals("null");
    }

    /**
     * 格式化單一載入步驟的顯示文字。
     *
     * @param label   步驟名稱（如 "Detecting agents"）
     * @param detail  結果摘要（如 "claude-cli" 或錯誤訊息）
     * @param success 是否成功
     * @return 含 ANSI 色碼的格式化字串
     */
    public static String formatLoadingStep(String label, String detail, boolean success) {
        String icon = success ? GREEN + "✓" + RESET : RED + "✗" + RESET;
        return GOLD + "  ✦ " + RESET + GRAY + String.format("%-22s", label + "...")
            + RESET + " " + GRAY + detail + " " + icon + RESET;
    }

    /**
     * 播放 Phase 1-3（星光散落 → 聚集 → Logo 浮現）。
     * 在 virtual thread 上呼叫，不阻塞主線程的載入工作。
     */
    public void playIntroAnimation() {
        writer.print(HIDE_CURSOR);
        writer.print(CLEAR_SCREEN);
        writer.flush();

        // Phase 1: 星光散落（~500ms，約 6 幀）
        int[][] starPositions = new int[15][2]; // [row, col]
        for (int i = 0; i < starPositions.length; i++) {
            starPositions[i][0] = random.nextInt(height - 2) + 1;
            starPositions[i][1] = random.nextInt(width - 2) + 1;
        }
        for (int frame = 0; frame < 6; frame++) {
            int starsToShow = (frame + 1) * 3; // 3, 6, 9, 12, 15
            for (int i = 0; i < Math.min(starsToShow, starPositions.length); i++) {
                moveCursor(starPositions[i][0], starPositions[i][1]);
                writer.print(GOLD + STAR_CHARS[i % STAR_CHARS.length] + RESET);
            }
            writer.flush();
            sleep(FRAME_DELAY_MS);
        }

        // Phase 2: 星光往中心聚集（~500ms，約 6 幀）
        int centerRow = height / 2 - 2;
        int centerCol = width / 2 - 4;
        for (int frame = 0; frame < 6; frame++) {
            writer.print(CLEAR_SCREEN);
            float progress = (frame + 1) / 6.0f;
            for (int i = 0; i < starPositions.length; i++) {
                int targetRow = centerRow + (i % 5) - 2;
                int targetCol = centerCol + (i % 8) - 4;
                int row = starPositions[i][0] + (int)((targetRow - starPositions[i][0]) * progress);
                int col = starPositions[i][1] + (int)((targetCol - starPositions[i][1]) * progress);
                moveCursor(Math.max(1, row), Math.max(1, col));
                writer.print(GOLD + STAR_CHARS[i % STAR_CHARS.length] + RESET);
            }
            writer.flush();
            sleep(FRAME_DELAY_MS);
        }

        // Phase 3: Logo 逐行浮現（~400ms，5 行 × 80ms）
        writer.print(CLEAR_SCREEN);
        writer.flush();
        for (int i = 0; i < MASCOT_LINES.length; i++) {
            int row = centerRow + i;
            moveCursor(row, centerCol);
            String color = (i == 0) ? GOLD : CYAN;
            // 眼睛用白色
            String line = MASCOT_LINES[i];
            if (line.contains("●")) {
                writer.print(CYAN + line.replace("●", WHITE + "●" + CYAN) + RESET);
            } else {
                writer.print(color + line + RESET);
            }
            writer.flush();
            sleep(FRAME_DELAY_MS);
        }
    }

    /**
     * 在動畫區域顯示單一載入步驟結果。
     * Phase 4 中由 GrimoStartupRunner 呼叫，每完成一個 CompletableFuture 就顯示一行。
     *
     * @param stepIndex 步驟序號（0-3），決定顯示在 logo 下方第幾行
     * @param text      已格式化的步驟文字（由 formatLoadingStep 產生）
     */
    public void showLoadingStep(int stepIndex, String text) {
        int centerRow = height / 2 - 2;
        int row = centerRow + MASCOT_LINES.length + 1 + stepIndex;
        moveCursor(row, width / 2 - 20);
        writer.print(text);
        writer.flush();
    }

    /**
     * Phase 5：清除動畫殘留，準備顯示最終 banner。
     */
    public void clearAnimation() {
        writer.print(CLEAR_SCREEN);
        writer.print(SHOW_CURSOR);
        writer.flush();
    }

    private void moveCursor(int row, int col) {
        writer.printf("\033[%d;%dH", row, col);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.samzhu.grimo.StartupAnimationRendererTest" --info`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/StartupAnimationRenderer.java \
        src/test/java/io/github/samzhu/grimo/StartupAnimationRendererTest.java
git commit -m "feat: add StartupAnimationRenderer for 5-phase startup animation"
```

---

### Task 4: Integrate into GrimoStartupRunner — Animation + Banner + Slash Menu

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java`

This is the main integration task. Modifies the `CommandLineRunner` to orchestrate:
1. Animation (Phase 1-3) on virtual thread, loading on main thread via CompletableFuture
2. Animation (Phase 4) synced with loading results
3. Banner display (Phase 5)
4. JLine configuration (`/` widget + AUTO_MENU)

Also adds `GrimoCommandCompleter` and `BannerRenderer` as @Bean definitions.

- [ ] **Step 1: Add new bean definitions to GrimoStartupRunner**

Add these beans to `GrimoStartupRunner.java` (after existing bean definitions, before `grimoStartup`):

```java
// New imports needed:
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;

// New beans:

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
        org.springframework.shell.core.command.CommandRegistry commandRegistry,
        SkillRegistry skillRegistry) {
    return new GrimoCommandCompleter(commandRegistry, skillRegistry);
}
```

- [ ] **Step 2: Rewrite grimoStartup CommandLineRunner to add animation + banner + JLine config**

Replace the existing `grimoStartup` method with the following:

```java
/**
 * 有序啟動流程，整合動畫、載入、Banner 與 JLine 設定：
 *
 * 1. 啟動動畫 Phase 1-3（virtual thread）與實際載入（CompletableFuture）並行
 * 2. Phase 4：每個載入步驟完成後顯示結果
 * 3. Phase 5：清除動畫，顯示靜態 Banner
 * 4. 設定 JLine：AUTO_MENU + / 鍵 widget
 *
 * 非 ANSI 終端跳過動畫，直接顯示純文字 Banner。
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
        // 1. Initialize workspace
        log.info("Loading workspace: {}", workspaceManager.root());
        if (!workspaceManager.isInitialized()) {
            workspaceManager.initialize();
            log.info("Workspace initialized at {}", workspaceManager.root());
        }

        // 判斷是否支援動畫
        boolean animated = StartupAnimationRenderer.isAnimationSupported(terminal.getType());
        StartupAnimationRenderer animator = animated ? new StartupAnimationRenderer(terminal) : null;

        // 如果支援動畫，啟動 Phase 1-3（virtual thread）
        Thread animationThread = null;
        if (animated) {
            animationThread = Thread.ofVirtual().name("startup-animation").start(() -> {
                animator.playIntroAnimation();
            });
        }

        // 2. 平行載入（CompletableFuture）
        var agentFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            log.info("Detecting available agents...");
            var results = agentDetector.detect();
            results.forEach(r -> log.info("  {} — {}", r.id(), r.detail()));
            return results;
        });

        var skillFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            log.info("Loading skills...");
            var skills = skillLoader.loadAll();
            skills.forEach(skillRegistry::register);
            log.info("Loaded {} skills", skills.size());
            return skills;
        });

        var mcpFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            log.info("Connecting MCP servers...");
            int count = 0;
            var config = grimoConfig.load();
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
            return count;
        });

        var taskFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            log.info("Restoring scheduled tasks...");
            taskSchedulerService.restoreAll();
            int count = taskSchedulerService.getScheduledTaskIds().size();
            log.info("Scheduled tasks: {}", count);
            return count;
        });

        // 等待動畫 Phase 1-3 完成
        if (animationThread != null) {
            animationThread.join();
        }

        // Phase 4: 逐行顯示載入結果
        // Agent detection
        try {
            var results = agentFuture.join();
            String firstAvailable = results.stream()
                .filter(r -> r.available())
                .map(r -> r.id())
                .findFirst().orElse("none");
            if (animated) {
                animator.showLoadingStep(0,
                    StartupAnimationRenderer.formatLoadingStep("Detecting agents", firstAvailable, true));
            }
        } catch (Exception e) {
            if (animated) {
                animator.showLoadingStep(0,
                    StartupAnimationRenderer.formatLoadingStep("Detecting agents", e.getMessage(), false));
            }
        }

        // Skill loading
        try {
            var skills = skillFuture.join();
            String detail = skills.size() + " loaded";
            if (animated) {
                animator.showLoadingStep(1,
                    StartupAnimationRenderer.formatLoadingStep("Loading skills", detail, true));
            }
        } catch (Exception e) {
            if (animated) {
                animator.showLoadingStep(1,
                    StartupAnimationRenderer.formatLoadingStep("Loading skills", e.getMessage(), false));
            }
        }

        // MCP connection
        try {
            int count = mcpFuture.join();
            String detail = count + " servers";
            if (animated) {
                animator.showLoadingStep(2,
                    StartupAnimationRenderer.formatLoadingStep("Connecting MCP", detail, true));
            }
        } catch (Exception e) {
            if (animated) {
                animator.showLoadingStep(2,
                    StartupAnimationRenderer.formatLoadingStep("Connecting MCP", e.getMessage(), false));
            }
        }

        // Task restoration
        try {
            int count = taskFuture.join();
            String detail = count + " active";
            if (animated) {
                animator.showLoadingStep(3,
                    StartupAnimationRenderer.formatLoadingStep("Restoring tasks", detail, true));
            }
        } catch (Exception e) {
            if (animated) {
                animator.showLoadingStep(3,
                    StartupAnimationRenderer.formatLoadingStep("Restoring tasks", e.getMessage(), false));
            }
        }

        // Phase 4 → 5 過渡：等一下讓用戶看到載入結果
        if (animated) {
            Thread.sleep(800);
        }

        // Phase 5: 清除動畫，顯示最終 Banner
        if (animated) {
            animator.clearAnimation();
        }

        // 取得 Banner 資料
        // 注意：lambda 內不能用 getClass()（會取到匿名類別），必須用明確的類別參考
        String version = GrimoStartupRunner.class.getPackage().getImplementationVersion();
        if (version == null) version = "dev";
        String agentId = grimoConfig.getDefaultAgent();
        if (agentId == null) agentId = "no agent";
        String model = grimoConfig.getDefaultModel();
        if (model == null) model = "unknown";
        String workspacePath = workspaceManager.root().toString()
            .replace(System.getProperty("user.home"), "~");
        int skillCount = skillRegistry.listAll().size();
        int mcpCount = mcpClientRegistry.listAll().size();
        int taskCount = taskSchedulerService.getScheduledTaskIds().size();

        // 輸出 Banner
        terminal.writer().print(bannerRenderer.render(version, agentId, model,
            workspacePath, skillCount, mcpCount, taskCount));
        terminal.writer().println();
        terminal.writer().flush();

        // === JLine 設定 ===

        // 啟用 AUTO_MENU：自動顯示候選選單
        lineReader.setOpt(LineReader.Option.AUTO_MENU);
        lineReader.setOpt(LineReader.Option.AUTO_LIST);
        lineReader.setVariable(LineReader.LIST_MAX, 20);

        // 註冊 / 鍵自訂 Widget：在行首輸入 / 時自動觸發 completion
        Widget slashAndComplete = () -> {
            if (lineReader.getBuffer().cursor() == 0) {
                lineReader.getBuffer().write('/');
                lineReader.callWidget(LineReader.COMPLETE);
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
```

- [ ] **Step 3: Run all tests to verify nothing is broken**

Run: `./gradlew test --info`
Expected: All existing tests PASS. Some may need adjustment if they depend on the startup runner output.

- [ ] **Step 4: Run the application to visually verify animation + banner + slash menu**

Run: `./gradlew bootRun`
Expected:
1. Star animation plays (~2s)
2. Ghost mascot appears with loading progress
3. Static banner with environment info displays
4. Typing `/` at prompt triggers auto-completion menu
5. Arrow keys navigate, Tab fills in selected command

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java
git commit -m "feat: integrate startup animation, banner, and slash command menu"
```

---

### Task 5: Polish and Edge Cases

**Files:**
- Modify: `src/main/java/io/github/samzhu/grimo/StartupAnimationRenderer.java` (if needed)
- Modify: `src/main/java/io/github/samzhu/grimo/GrimoStartupRunner.java` (if needed)

- [ ] **Step 1: Test dumb terminal fallback**

Run: `TERM=dumb ./gradlew bootRun`
Expected: No animation, banner displays as plain text, slash menu still works.

- [ ] **Step 2: Test with narrow terminal**

Resize terminal to < 40 columns, run `./gradlew bootRun`.
Expected: Animation and banner should not crash. May look compressed but should not throw exceptions.

- [ ] **Step 3: Test slash menu interaction**

In the running shell:
1. Type `/` → menu should appear with all commands
2. Type `/sk` → menu filters to `skill list`, `skill remove`, `skill install`
3. Press ↑↓ → highlight moves
4. Press Tab → selected command fills into input
5. Type `status` (without /) → normal completion should still work

- [ ] **Step 4: Fix any issues found in edge case testing**

Make targeted fixes as needed. Keep changes minimal.

- [ ] **Step 5: Run full test suite**

Run: `./gradlew test --info`
Expected: All tests PASS.

- [ ] **Step 6: Commit any fixes**

```bash
git add -u
git commit -m "fix: polish startup animation and slash menu edge cases"
```
