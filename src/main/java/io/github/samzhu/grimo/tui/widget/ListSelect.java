package io.github.samzhu.grimo.tui.widget;

import io.github.samzhu.grimo.tui.core.DisplayWidth;
import io.github.samzhu.grimo.tui.core.Renderable;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用單選列表 widget，支援 viewport scrolling、scroll hints、RowRenderer。
 *
 * 設計說明：
 * - 有狀態泛型 widget — 持有 items、selectedIndex、viewportStart
 * - Linear navigation（不循環）：到頂/底即停
 * - Viewport 跟隨選中項（selectedIndex 不出 viewport 範圍）
 * - Scroll hints 佔用 maxVisible 中的行數：有 hint 就減少 item slots
 * - RowRenderer 可客製化每行渲染（tmux drawcb pattern）
 * - 每行 columnLength == width（用 DisplayWidth.padRight 保證）
 * - Brand color: ANSI 256 color 67 (steel blue)
 *
 * @param <T> the value type carried by each item
 *
 * @see Renderable — TUI 元件契約
 * @see DisplayWidth — 寬度感知字串操作
 * @see <a href="https://github.com/tmux/tmux">tmux — drawcb / tree→flat pattern</a>
 */
public class ListSelect<T> implements Renderable {

    /** Brand color: ANSI 256 color 67 (steel blue) */
    private static final int BRAND_COLOR = 67;

    /** Gray for description and scroll hints */
    private static final int GRAY_COLOR = 245;

    // ──────────────────────────── nested types ────────────────────────────

    /**
     * Immutable item record carrying display data and a typed value.
     */
    public record Item<T>(String label, String description, T value) {}

    /**
     * Custom row renderer — receives item, selection state, and available width.
     * Returned AttributedString is used as-is (no further padding by ListSelect).
     */
    @FunctionalInterface
    public interface RowRenderer<T> {
        AttributedString render(Item<T> item, boolean selected, int width);
    }

    // ──────────────────────────── state ────────────────────────────────────

    private List<Item<T>> items;
    private final int maxVisible;
    private int selectedIndex = 0;
    private int viewportStart = 0;
    private RowRenderer<T> rowRenderer;

    // ──────────────────────────── constructor ──────────────────────────────

    public ListSelect(List<Item<T>> items, int maxVisible) {
        this.items = new ArrayList<>(items);
        this.maxVisible = maxVisible;
    }

    // ──────────────────────────── navigation ───────────────────────────────

    /**
     * Move selection up by one. Clamps at index 0. Adjusts viewport upward if needed.
     */
    public void moveUp() {
        if (items.isEmpty()) return;
        selectedIndex = Math.max(0, selectedIndex - 1);
        if (selectedIndex < viewportStart) {
            viewportStart = selectedIndex;
        }
    }

    /**
     * Move selection down by one. Clamps at last index. Adjusts viewport downward if needed.
     *
     * Navigation strategy: reserve slots for both scroll hints (maxVisible - 2, min 1) so the
     * viewport scrolls eagerly enough to reveal context above and below the selection.
     * After scrolling, clamp viewportStart so the bottom of the list is fully visible
     * (no "phantom" empty rows) — max viewportStart = items.size() - (maxVisible - 1).
     */
    public void moveDown() {
        if (items.isEmpty()) return;
        selectedIndex = Math.min(items.size() - 1, selectedIndex + 1);
        int navSlots = Math.max(1, maxVisible - 2);
        if (selectedIndex >= viewportStart + navSlots) {
            viewportStart = selectedIndex - navSlots + 1;
            // Clamp: don't scroll past the point where bottom items are visible
            int maxVp = Math.max(0, items.size() - (maxVisible - 1));
            viewportStart = Math.min(viewportStart, maxVp);
        }
    }

    /**
     * Jump to first item. Resets viewport to top.
     */
    public void moveToFirst() {
        selectedIndex = 0;
        viewportStart = 0;
    }

    /**
     * Jump to last item. Adjusts viewport to show last item.
     */
    public void moveToLast() {
        if (items.isEmpty()) return;
        selectedIndex = items.size() - 1;
        adjustViewportForSelected();
    }

    // ──────────────────────────── mutation ─────────────────────────────────

    /**
     * Replace items list. Preserves selectedIndex if still valid; clamps otherwise.
     * Resets viewportStart if selectedIndex is now outside viewport.
     */
    public void setItems(List<Item<T>> newItems) {
        this.items = new ArrayList<>(newItems);
        if (items.isEmpty()) {
            selectedIndex = 0;
            viewportStart = 0;
            return;
        }
        // Clamp selectedIndex to new size
        selectedIndex = Math.min(selectedIndex, items.size() - 1);
        // Reset viewportStart if it now exceeds item count
        if (viewportStart >= items.size()) {
            viewportStart = Math.max(0, items.size() - 1);
        }
        // Ensure selected is visible
        adjustViewportForSelected();
    }

    public void setRowRenderer(RowRenderer<T> rowRenderer) {
        this.rowRenderer = rowRenderer;
    }

    // ──────────────────────────── accessors ────────────────────────────────

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    /**
     * Returns the selected item, or null if list is empty.
     */
    public Item<T> getSelected() {
        if (items.isEmpty()) return null;
        return items.get(selectedIndex);
    }

    public int getVisibleCount() {
        return Math.min(items.size(), maxVisible);
    }

    // ──────────────────────────── rendering ────────────────────────────────

    @Override
    public List<AttributedString> render(int width) {
        if (items.isEmpty()) return List.of();

        boolean hasAbove = viewportStart > 0;
        int scrollHintCount = 0;
        if (hasAbove) scrollHintCount++;

        // We need to compute hasBelow, but slots depend on scrollHintCount which depends on hasBelow.
        // Resolve: compute slots with tentative scrollHintCount, then determine hasBelow.
        int tentativeSlots = Math.min(items.size(), maxVisible) - scrollHintCount;
        boolean hasBelow = viewportStart + tentativeSlots < items.size();
        if (hasBelow) scrollHintCount++;

        // Re-compute slots with final scrollHintCount
        int visibleItemSlots = Math.min(items.size(), maxVisible) - scrollHintCount;

        var lines = new ArrayList<AttributedString>(maxVisible);

        // Scroll hint: above
        if (hasAbove) {
            int aboveCount = viewportStart;
            String hint = DisplayWidth.padRight("  \u2191 " + aboveCount + " more", width);
            var sb = new AttributedStringBuilder();
            sb.styled(AttributedStyle.DEFAULT.foreground(GRAY_COLOR), hint);
            lines.add(sb.toAttributedString());
        }

        // Item rows
        for (int i = viewportStart; i < viewportStart + visibleItemSlots && i < items.size(); i++) {
            Item<T> item = items.get(i);
            boolean selected = (i == selectedIndex);
            AttributedString line;
            if (rowRenderer != null) {
                line = rowRenderer.render(item, selected, width);
            } else {
                line = defaultRender(item, selected, width);
            }
            lines.add(line);
        }

        // Scroll hint: below
        if (hasBelow) {
            int belowCount = items.size() - (viewportStart + visibleItemSlots);
            String hint = DisplayWidth.padRight("  \u2193 " + belowCount + " more", width);
            var sb = new AttributedStringBuilder();
            sb.styled(AttributedStyle.DEFAULT.foreground(GRAY_COLOR), hint);
            lines.add(sb.toAttributedString());
        }

        return lines;
    }

    // ──────────────────────────── private helpers ──────────────────────────

    /**
     * Default row renderer:
     * - Selected: "> label  description" in BRAND_COLOR (67), padded to width
     * - Normal:   "  label  description" with label in default, description in gray (245)
     */
    private AttributedString defaultRender(Item<T> item, boolean selected, int width) {
        String prefix = selected ? "> " : "  ";
        String label = item.label() != null ? item.label() : "";
        String desc = item.description() != null ? item.description() : "";

        var sb = new AttributedStringBuilder();
        if (selected) {
            // Whole line in brand color
            String full = DisplayWidth.padRight(prefix + label + "  " + desc, width);
            sb.styled(AttributedStyle.DEFAULT.foreground(BRAND_COLOR), full);
        } else {
            // prefix + label in default color
            sb.append(prefix);
            sb.append(label);
            // gap
            sb.append("  ");
            // description in gray
            // Calculate remaining width for padding
            int usedWidth = DisplayWidth.of(prefix) + DisplayWidth.of(label) + 2 + DisplayWidth.of(desc);
            sb.styled(AttributedStyle.DEFAULT.foreground(GRAY_COLOR), desc);
            // Pad remainder in default style
            int remaining = width - usedWidth;
            if (remaining > 0) {
                sb.append(" ".repeat(remaining));
            } else if (remaining < 0) {
                // Need to truncate — rebuild
                String full = DisplayWidth.padRight(prefix + label + "  " + desc, width);
                return new AttributedString(full);
            }
        }
        return sb.toAttributedString();
    }

    /**
     * Compute visibleItemSlots for the current state (used during navigation).
     * Mirrors the render() logic without actually rendering.
     */
    private int visibleItemSlots() {
        if (items.isEmpty()) return 0;
        boolean hasAbove = viewportStart > 0;
        int scrollHintCount = hasAbove ? 1 : 0;
        int tentativeSlots = Math.min(items.size(), maxVisible) - scrollHintCount;
        boolean hasBelow = viewportStart + tentativeSlots < items.size();
        if (hasBelow) scrollHintCount++;
        return Math.min(items.size(), maxVisible) - scrollHintCount;
    }

    /**
     * Adjust viewportStart so that selectedIndex is visible.
     */
    private void adjustViewportForSelected() {
        if (items.isEmpty()) return;
        int slots = visibleItemSlots();
        if (selectedIndex < viewportStart) {
            viewportStart = selectedIndex;
        } else if (selectedIndex >= viewportStart + slots) {
            viewportStart = selectedIndex - slots + 1;
        }
        if (viewportStart < 0) viewportStart = 0;
    }
}
