package io.github.samzhu.grimo.shared.tui;

import java.util.function.IntConsumer;

/**
 * 拖曳邊緣自動捲動。
 *
 * 設計說明：
 * - 參考 tmux WINDOW_COPY_DRAG_REPEAT_TIME = 50ms
 * - 拖曳到 content 區域頂部（screenRow == 0）或底部（screenRow == contentHeight-1）時啟動
 * - Virtual thread timer，每 50ms 捲動 1 行
 * - 透過回呼注入 scrollUp/scrollDown/onScrolled/setDirty，不依賴 View 層
 *
 * @see <a href="https://github.com/tmux/tmux/blob/master/window-copy.c#L316">tmux drag timer</a>
 */
public class AutoScroller {

    private static final long INTERVAL_MS = 50;

    private final Runnable scrollUp;
    private final Runnable scrollDown;
    private final IntConsumer onScrolled;
    private final Runnable setDirty;
    private volatile Thread timerThread;
    private volatile Direction direction;

    private enum Direction { UP, DOWN }

    public AutoScroller(Runnable scrollUp, Runnable scrollDown,
                        IntConsumer onScrolled, Runnable setDirty) {
        this.scrollUp = scrollUp;
        this.scrollDown = scrollDown;
        this.onScrolled = onScrolled;
        this.setDirty = setDirty;
    }

    public void update(int screenRow, int contentHeight) {
        if (screenRow == 0) {
            startIfNeeded(Direction.UP);
        } else if (screenRow == contentHeight - 1) {
            startIfNeeded(Direction.DOWN);
        } else {
            stop();
        }
    }

    public void stop() {
        direction = null;
        Thread t = timerThread;
        if (t != null) {
            t.interrupt();
            timerThread = null;
        }
    }

    private void startIfNeeded(Direction dir) {
        if (this.direction == dir && timerThread != null && timerThread.isAlive()) {
            return;
        }
        stop();
        this.direction = dir;
        timerThread = Thread.ofVirtual().name("grimo-autoscroll").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    if (dir == Direction.UP) {
                        scrollUp.run();
                        onScrolled.accept(-1);
                    } else {
                        scrollDown.run();
                        onScrolled.accept(1);
                    }
                    setDirty.run();
                    Thread.sleep(INTERVAL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
