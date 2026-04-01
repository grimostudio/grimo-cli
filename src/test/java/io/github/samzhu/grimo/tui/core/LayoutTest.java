package io.github.samzhu.grimo.tui.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LayoutTest {

    @Test
    void horizontalShouldAllocateFixedSlots() {
        int[] result = Layout.horizontal(40, 0,
                new Layout.Fixed(2),
                new Layout.Fixed(12),
                new Layout.Fixed(10));
        assertThat(result).containsExactly(2, 12, 10);
    }

    @Test
    void horizontalShouldFillRemainingSpace() {
        int[] result = Layout.horizontal(40, 0,
                new Layout.Fixed(2),
                new Layout.Fixed(12),
                new Layout.Fill());
        assertThat(result).containsExactly(2, 12, 26);
    }

    @Test
    void horizontalShouldDistributeFillEvenly() {
        int[] result = Layout.horizontal(40, 0,
                new Layout.Fill(),
                new Layout.Fixed(10),
                new Layout.Fill());
        assertThat(result).containsExactly(15, 10, 15);
    }

    @Test
    void horizontalShouldAccountForGap() {
        int[] result = Layout.horizontal(40, 1,
                new Layout.Fixed(2),
                new Layout.Fixed(12),
                new Layout.Fill());
        assertThat(result).containsExactly(2, 12, 24);
    }

    @Test
    void verticalShouldAllocateFixedSlots() {
        int[] result = Layout.vertical(30, 0,
                new Layout.Fixed(6),
                new Layout.Fill(),
                new Layout.Fixed(2),
                new Layout.Fixed(1));
        assertThat(result).containsExactly(6, 21, 2, 1);
    }

    @Test
    void verticalShouldHandleGap() {
        int[] result = Layout.vertical(30, 1,
                new Layout.Fixed(6),
                new Layout.Fill(),
                new Layout.Fixed(2),
                new Layout.Fixed(1));
        assertThat(result).containsExactly(6, 18, 2, 1);
    }

    @Test
    void fillShouldNotGoNegative() {
        int[] result = Layout.horizontal(10, 0,
                new Layout.Fixed(8),
                new Layout.Fixed(8),
                new Layout.Fill());
        assertThat(result[2]).isGreaterThanOrEqualTo(0);
    }
}
