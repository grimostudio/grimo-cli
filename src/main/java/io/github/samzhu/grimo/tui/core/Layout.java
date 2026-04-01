package io.github.samzhu.grimo.tui.core;

/**
 * 佈局切分計算。
 * 純算術，不持有狀態。
 *
 * 設計說明：
 * - 借鑑 Ratatui Constraint（Fixed/Fill）
 * - 借鑑 OpenCode box flexGrow
 * - 用 sealed interface 限制 Slot 類型，編譯期安全
 *
 * @see <a href="https://github.com/ratatui/ratatui">Ratatui — Layout + Constraint</a>
 * @see <a href="https://github.com/anomalyco/opencode">OpenCode — flexGrow</a>
 */
public final class Layout {

    private Layout() {}

    public sealed interface Slot permits Fixed, Fill {}
    public record Fixed(int size) implements Slot {}
    public record Fill() implements Slot {}

    /**
     * 水平切分：將 totalCols 分配給各 Slot。
     * Fixed 取固定值，Fill 均分剩餘空間。
     * gap 是 slot 間的間距（不含首尾）。
     */
    public static int[] horizontal(int totalCols, int gap, Slot... slots) {
        return split(totalCols, gap, slots);
    }

    /**
     * 垂直切分：將 totalRows 分配給各 Slot。
     */
    public static int[] vertical(int totalRows, int gap, Slot... slots) {
        return split(totalRows, gap, slots);
    }

    private static int[] split(int total, int gap, Slot[] slots) {
        int gapTotal = gap * Math.max(0, slots.length - 1);
        int fixedTotal = 0;
        int fillCount = 0;

        for (var slot : slots) {
            switch (slot) {
                case Fixed f -> fixedTotal += f.size();
                case Fill ignored -> fillCount++;
            }
        }

        int remaining = Math.max(0, total - fixedTotal - gapTotal);
        int fillSize = fillCount > 0 ? remaining / fillCount : 0;
        int fillRemainder = fillCount > 0 ? remaining % fillCount : 0;

        int[] result = new int[slots.length];
        int fillIndex = 0;
        for (int i = 0; i < slots.length; i++) {
            result[i] = switch (slots[i]) {
                case Fixed f -> f.size();
                case Fill ignored -> {
                    fillIndex++;
                    yield fillSize + (fillIndex == fillCount ? fillRemainder : 0);
                }
            };
        }
        return result;
    }
}
