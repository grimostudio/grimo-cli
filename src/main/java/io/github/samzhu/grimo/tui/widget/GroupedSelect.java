package io.github.samzhu.grimo.tui.widget;

import io.github.samzhu.grimo.tui.core.DisplayWidth;
import io.github.samzhu.grimo.tui.core.Renderable;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Accordion grouped selection widget — composes {@link ListSelect} using the tmux tree→flat pattern.
 *
 * 設計說明：
 * - Groups with children are flattened into a single item list at all times.
 * - Expand/collapse rebuilds the flat list via {@code ListSelect.setItems()}.
 * - Accordion mode: only one group can be expanded at a time; expanding a new group
 *   automatically collapses the previously expanded one.
 * - Group header items carry {@code value = null}; leaf items carry real values.
 *   The RowRenderer uses {@code item.value() == null} to distinguish them.
 * - Navigation (moveUp/moveDown) and scrolling are fully delegated to ListSelect.
 *
 * RowRenderer rendering rules:
 * - Group row (collapsed): "  ▶ label"  / selected: "> ▶ label" in BRAND_COLOR
 * - Group row (expanded):  "  ▼ label"  / selected: "> ▼ label" in BRAND_COLOR
 * - Leaf row:              "      label    desc" (6-char indent)
 *                          / selected: "    > label    desc" (4-char indent + ">") in BRAND_COLOR
 *
 * @param <T> the value type carried by leaf items
 *
 * @see ListSelect — underlying flat-list widget
 * @see Renderable — TUI element contract
 * @see <a href="https://github.com/tmux/tmux">tmux — tree→flat drawcb pattern</a>
 */
public class GroupedSelect<T> implements Renderable {

    /** Brand color: ANSI 256 color 67 (steel blue) — matches ListSelect. */
    private static final int BRAND_COLOR = 67;

    // ──────────────────────────── nested types ────────────────────────────

    /**
     * A named group with an ordered list of selectable leaf items.
     */
    public record Group<T>(String label, List<ListSelect.Item<T>> children) {}

    // ──────────────────────────── state ───────────────────────────────────

    private final List<Group<T>> groups;
    private final ListSelect<T> listSelect;

    /**
     * Index into {@code groups} of the currently expanded group, or -1 when all collapsed.
     */
    private int expandedGroupIndex = -1;

    /**
     * Parallel boolean list to the current flat item list: true = group header row, false = leaf row.
     * Rebuilt every time the flat list is rebuilt.
     */
    private List<Boolean> isGroupRow = new ArrayList<>();

    // ──────────────────────────── constructor ─────────────────────────────

    /**
     * Creates a GroupedSelect with all groups initially collapsed.
     *
     * @param groups     ordered list of groups (may contain empty groups)
     * @param maxVisible maximum number of rows visible at once (passed to ListSelect)
     */
    public GroupedSelect(List<Group<T>> groups, int maxVisible) {
        this.groups = List.copyOf(groups);
        // Build ListSelect with an empty initial list; rebuildFlatList will populate it.
        this.listSelect = new ListSelect<>(List.of(), maxVisible);
        // Install custom RowRenderer — closure captures `this` to access isGroupRow / expandedGroupIndex.
        this.listSelect.setRowRenderer(this::renderRow);
        rebuildFlatList();
    }

    // ──────────────────────────── navigation ──────────────────────────────

    /** Move selection up by one. Delegates to ListSelect. */
    public void moveUp() {
        listSelect.moveUp();
    }

    /** Move selection down by one. Delegates to ListSelect. */
    public void moveDown() {
        listSelect.moveDown();
    }

    /** Jump to first item. Delegates to ListSelect. */
    public void moveToFirst() {
        listSelect.moveToFirst();
    }

    /** Jump to last item. Delegates to ListSelect. */
    public void moveToLast() {
        listSelect.moveToLast();
    }

    // ──────────────────────────── accordion ───────────────────────────────

    /**
     * Toggle expand/collapse for the currently selected group header.
     * If the cursor is not on a group header, this is a no-op.
     *
     * Accordion behaviour:
     * - Same group toggled twice → collapses (expandedGroupIndex = -1).
     * - Different group → new group expands, old group collapses automatically.
     */
    public void toggle() {
        if (!isOnGroup()) return;

        int cursorGroupIndex = groupIndexAtCursor();
        if (cursorGroupIndex == expandedGroupIndex) {
            // Collapse
            expandedGroupIndex = -1;
        } else {
            // Expand new group (old one auto-collapses via expandedGroupIndex replacement)
            expandedGroupIndex = cursorGroupIndex;
        }
        rebuildFlatList();
    }

    // ──────────────────────────── accessors ───────────────────────────────

    /**
     * Returns true if the current selection is on a group header row.
     */
    public boolean isOnGroup() {
        int idx = listSelect.getSelectedIndex();
        if (isGroupRow.isEmpty() || idx >= isGroupRow.size()) return false;
        return isGroupRow.get(idx);
    }

    /**
     * Returns the selected leaf {@link ListSelect.Item}, or {@code null} if:
     * <ul>
     *   <li>the list is empty, or</li>
     *   <li>the cursor is on a group header (value is null by design).</li>
     * </ul>
     */
    public ListSelect.Item<T> getSelected() {
        if (isOnGroup()) return null;
        return listSelect.getSelected();
    }

    /** Returns true if the flat list has no items (all groups empty and no headers would appear). */
    public boolean isEmpty() {
        return listSelect.isEmpty();
    }

    /** Number of items currently visible (delegates to ListSelect). */
    public int getVisibleCount() {
        return listSelect.getVisibleCount();
    }

    // ──────────────────────────── rendering ───────────────────────────────

    /**
     * Render the widget. Delegates entirely to ListSelect, which applies the custom RowRenderer.
     */
    @Override
    public List<AttributedString> render(int width) {
        return listSelect.render(width);
    }

    // ──────────────────────────── private: flat list ──────────────────────

    /**
     * Rebuild the flat item list from the current groups and {@code expandedGroupIndex}.
     *
     * For each group at index i:
     *   - Emit a group header item (value = null).
     *   - If i == expandedGroupIndex, emit all children immediately after.
     *
     * Calls {@code listSelect.setItems()} which preserves selectedIndex (clamped if needed).
     */
    private void rebuildFlatList() {
        var flatItems = new ArrayList<ListSelect.Item<T>>();
        var groupRowFlags = new ArrayList<Boolean>();

        for (int i = 0; i < groups.size(); i++) {
            Group<T> g = groups.get(i);
            // Group header: label = group label, description = "", value = null
            flatItems.add(new ListSelect.Item<>(g.label(), "", null));
            groupRowFlags.add(true);

            if (i == expandedGroupIndex) {
                for (ListSelect.Item<T> child : g.children()) {
                    flatItems.add(child);
                    groupRowFlags.add(false);
                }
            }
        }

        this.isGroupRow = groupRowFlags;
        listSelect.setItems(flatItems);
    }

    // ──────────────────────────── private: renderer ───────────────────────

    /**
     * Custom RowRenderer for ListSelect — distinguishes group headers from leaf items.
     *
     * Group header (value == null):
     *   Normal:   "  ▶ label" or "  ▼ label" (2-char indent)
     *   Selected: "> ▶ label" or "> ▼ label" in BRAND_COLOR
     *
     * Leaf item:
     *   Normal:   "      label  description" (6-char indent)
     *   Selected: "    > label  description" (4-char indent + ">") in BRAND_COLOR
     *
     * Width guarantee: DisplayWidth.padRight pads/truncates to exactly {@code width} columns.
     */
    private AttributedString renderRow(ListSelect.Item<T> item, boolean selected, int width) {
        boolean isGroup = (item.value() == null);

        if (isGroup) {
            return renderGroupRow(item.label(), selected, width);
        } else {
            return renderLeafRow(item, selected, width);
        }
    }

    private AttributedString renderGroupRow(String label, boolean selected, int width) {
        // Determine arrow: expanded group uses ▼, collapsed uses ▶
        boolean isExpanded = expandedGroupIndex >= 0
                && expandedGroupIndex < groups.size()
                && groups.get(expandedGroupIndex).label().equals(label);

        String arrow = isExpanded ? "▼" : "▶";
        String prefix = selected ? "> " : "  ";
        String text = DisplayWidth.padRight(prefix + arrow + " " + label, width);

        var sb = new AttributedStringBuilder();
        if (selected) {
            sb.styled(AttributedStyle.DEFAULT.foreground(BRAND_COLOR), text);
        } else {
            sb.append(text);
        }
        return sb.toAttributedString();
    }

    private AttributedString renderLeafRow(ListSelect.Item<T> item, boolean selected, int width) {
        String label = item.label() != null ? item.label() : "";
        String desc  = item.description() != null ? item.description() : "";

        // Selected leaf: "    > label  desc"  (4 spaces + ">")
        // Normal leaf:   "      label  desc"  (6 spaces)
        String prefix = selected ? "    > " : "      ";
        String text = DisplayWidth.padRight(prefix + label + "  " + desc, width);

        var sb = new AttributedStringBuilder();
        if (selected) {
            sb.styled(AttributedStyle.DEFAULT.foreground(BRAND_COLOR), text);
        } else {
            sb.append(text);
        }
        return sb.toAttributedString();
    }

    // ──────────────────────────── private: helpers ────────────────────────

    /**
     * Determine which group index the cursor is currently pointing at.
     * Walks through the flat list to find the group header at selectedIndex.
     *
     * Precondition: {@code isOnGroup()} must be true.
     */
    private int groupIndexAtCursor() {
        int flatIdx = listSelect.getSelectedIndex();
        int groupCount = 0;
        for (int i = 0; i <= flatIdx && i < isGroupRow.size(); i++) {
            if (isGroupRow.get(i)) {
                if (i == flatIdx) return groupCount;
                groupCount++;
            }
        }
        return groupCount;
    }
}
