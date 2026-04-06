package io.github.samzhu.grimo.tui.widget;

import io.github.samzhu.grimo.tui.core.DisplayWidth;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reaction Indicator：將 agent 執行狀態映射為 emoji reaction。
 *
 * 設計說明（參考 OpenClaw Reaction Lifecycle）：
 * - 不在 ContentView buffer 裡 — 是 render 時動態加入的浮動行
 * - isActive() 為 false 時 render() 回傳 null，ContentView 不加入任何行
 * - Thinking 狀態支援隨機文字循環（每 3 秒換）+ 超過 5 秒顯示計時
 * - 純 Java widget，不依賴 Spring（由 TuiEventBridge 驅動）
 * - 動畫取消：stop() 設 animating=false + interrupt animationThread
 */
public class ReactionIndicator {

    public enum State {
        IDLE, QUEUED, THINKING, TOOL, CODING, WEB, RESPONDING, DONE, ERROR
    }

    private static final AttributedStyle DIM_STYLE = AttributedStyle.DEFAULT.foreground(245);

    private static final List<String> THINKING_TEXTS = List.of(
        "Thinking...",
        "Pondering the possibilities...",
        "Connecting the dots...",
        "Mulling it over...",
        "Assembling neurons...",
        "Turning the gears...",
        "Letting ideas simmer...",
        "Brewing an answer...",
        "Chewing on that...",
        "Contemplating deeply...",
        "Weighing the options...",
        "Crystallizing a response...",
        "Composing thoughts..."
    );

    private volatile State state = State.IDLE;
    private volatile String emoji = "";
    private volatile String text = "";
    private volatile long startMs;
    private volatile boolean animating;
    private volatile Thread animationThread;
    private String lastThinkingText = "";
    private final Runnable setDirty;

    public ReactionIndicator(Runnable setDirty) {
        this.setDirty = setDirty;
    }

    public void setState(State state, String text) {
        stopAnimation();
        this.state = state;
        this.emoji = emojiFor(state);
        this.text = text;
        this.startMs = System.currentTimeMillis();
    }

    public void startThinkingAnimation() {
        stopAnimation();
        this.state = State.THINKING;
        this.emoji = emojiFor(State.THINKING);
        this.text = randomThinkingText();
        this.startMs = System.currentTimeMillis();
        this.animating = true;

        this.animationThread = Thread.ofVirtual()
                .name("grimo-reaction-anim")
                .start(() -> {
                    try {
                        while (animating) {
                            Thread.sleep(3000);
                            if (!animating) break;
                            this.text = randomThinkingText();
                            setDirty.run();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
    }

    public void stop() {
        this.state = State.IDLE;
        stopAnimation();
    }

    public boolean isActive() {
        return state != State.IDLE;
    }

    public AttributedString render(int cols) {
        if (state == State.IDLE) return null;

        long elapsed = (System.currentTimeMillis() - startMs) / 1000;
        String display;
        if (state == State.THINKING && elapsed >= 5) {
            display = emoji + " " + text + " (" + elapsed + "s)";
        } else {
            display = emoji + " " + text;
        }

        String fitted = DisplayWidth.padRight(DisplayWidth.truncate(display, cols), cols);
        return new AttributedString(fitted, DIM_STYLE);
    }

    // --- internal ---

    private void stopAnimation() {
        animating = false;
        var thread = animationThread;
        if (thread != null) {
            thread.interrupt();
            animationThread = null;
        }
    }

    private String randomThinkingText() {
        String picked;
        do {
            picked = THINKING_TEXTS.get(
                    ThreadLocalRandom.current().nextInt(THINKING_TEXTS.size()));
        } while (picked.equals(lastThinkingText) && THINKING_TEXTS.size() > 1);
        lastThinkingText = picked;
        return picked;
    }

    private static String emojiFor(State state) {
        return switch (state) {
            case QUEUED -> "👀";
            case THINKING -> "🤔";
            case TOOL -> "🔥";
            case CODING -> "👨‍💻";
            case WEB -> "⚡";
            case RESPONDING -> "✨";
            case DONE -> "👍";
            case ERROR -> "😱";
            case IDLE -> "";
        };
    }
}
