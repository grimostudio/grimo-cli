package io.github.samzhu.grimo.shared.tui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class AutoScrollerTest {

    private AutoScroller scroller;

    @AfterEach
    void cleanup() {
        if (scroller != null) scroller.stop();
    }

    @Test
    void topEdgeTriggersScrollUp() throws InterruptedException {
        var upCount = new AtomicInteger();
        var downCount = new AtomicInteger();
        scroller = new AutoScroller(
                () -> upCount.incrementAndGet(),
                () -> downCount.incrementAndGet(),
                delta -> {},
                () -> {}
        );
        scroller.update(0, 20);
        Thread.sleep(200);
        scroller.stop();
        assertThat(upCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(downCount.get()).isZero();
    }

    @Test
    void bottomEdgeTriggersScrollDown() throws InterruptedException {
        var downCount = new AtomicInteger();
        scroller = new AutoScroller(
                () -> {}, () -> downCount.incrementAndGet(), delta -> {}, () -> {}
        );
        scroller.update(19, 20);
        Thread.sleep(200);
        scroller.stop();
        assertThat(downCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void middlePositionDoesNotScroll() throws InterruptedException {
        var count = new AtomicInteger();
        scroller = new AutoScroller(
                () -> count.incrementAndGet(),
                () -> count.incrementAndGet(),
                delta -> {}, () -> {}
        );
        scroller.update(10, 20);
        Thread.sleep(150);
        scroller.stop();
        assertThat(count.get()).isZero();
    }

    @Test
    void stopCancelsScrolling() throws InterruptedException {
        var count = new AtomicInteger();
        scroller = new AutoScroller(
                () -> count.incrementAndGet(), () -> {}, delta -> {}, () -> {}
        );
        scroller.update(0, 20);
        Thread.sleep(100);
        scroller.stop();
        int afterStop = count.get();
        Thread.sleep(200);
        assertThat(count.get()).isLessThanOrEqualTo(afterStop + 1);
    }

    @Test
    void directionSwitchWorks() throws InterruptedException {
        var upCount = new AtomicInteger();
        var downCount = new AtomicInteger();
        scroller = new AutoScroller(
                () -> upCount.incrementAndGet(),
                () -> downCount.incrementAndGet(),
                delta -> {}, () -> {}
        );
        scroller.update(0, 20);
        Thread.sleep(150);
        scroller.update(19, 20);
        Thread.sleep(150);
        scroller.stop();
        assertThat(upCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(downCount.get()).isGreaterThanOrEqualTo(1);
    }
}
