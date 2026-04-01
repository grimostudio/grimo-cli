package io.github.samzhu.grimo.tui.widget;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD tests for GroupedSelect<T> — accordion tree→flat widget.
 *
 * 設計說明：
 * - GroupedSelect composes ListSelect<T> using the tmux tree→flat pattern
 * - Groups are flattened into a single item list; expand/collapse rebuilds via setItems()
 * - Accordion mode: expanding a group auto-collapses any previously expanded group
 * - Group headers have value = null; leaf rows have real values
 */
class GroupedSelectTest {

    // ──────────────────────────── helpers ─────────────────────────────────

    private static <T> ListSelect.Item<T> item(String label, String desc, T val) {
        return new ListSelect.Item<>(label, desc, val);
    }

    private static <T> GroupedSelect.Group<T> group(String label, List<ListSelect.Item<T>> children) {
        return new GroupedSelect.Group<>(label, children);
    }

    /** Build a typical 3-group widget: Models (2 children), Providers (2 children), Tools (1 child). */
    private GroupedSelect<String> threeGroups() {
        var groups = List.of(
            group("Models", List.of(
                item("claude-opus-4", "Flagship", "opus"),
                item("claude-sonnet-4", "Balanced", "sonnet")
            )),
            group("Providers", List.of(
                item("anthropic", "Direct API", "ant"),
                item("openai", "OpenAI API", "oai")
            )),
            group("Tools", List.of(
                item("bash", "Shell tool", "bash")
            ))
        );
        return new GroupedSelect<>(groups, 10);
    }

    // ──────────────────────────── test 1 ──────────────────────────────────

    /**
     * All collapsed → only group headers visible, each decorated with ▶.
     */
    @Test void renderCollapsedGroups() {
        var gs = threeGroups();
        var lines = gs.render(40);

        // 3 groups, all collapsed
        assertThat(lines).hasSize(3);
        assertThat(lines.stream().allMatch(l -> l.toString().contains("▶"))).isTrue();
        assertThat(lines.stream().noneMatch(l -> l.toString().contains("▼"))).isTrue();
        // Group labels are visible
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("Models"))).isTrue();
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("Providers"))).isTrue();
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("Tools"))).isTrue();
        // No leaf items
        assertThat(lines.stream().noneMatch(l -> l.toString().contains("claude"))).isTrue();
    }

    // ──────────────────────────── test 2 ──────────────────────────────────

    /**
     * Enter on group → expand; header changes to ▼ and children appear below.
     */
    @Test void toggleExpandsGroup() {
        var gs = threeGroups();
        // Cursor starts at index 0 = "Models" group
        gs.toggle();

        var lines = gs.render(40);
        // Models (1) + 2 children + Providers (1) + Tools (1) = 5
        assertThat(lines).hasSize(5);
        // Models header now shows ▼
        assertThat(lines.getFirst().toString()).contains("▼");
        assertThat(lines.getFirst().toString()).contains("Models");
        // Children are present
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("claude-opus-4"))).isTrue();
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("claude-sonnet-4"))).isTrue();
    }

    // ──────────────────────────── test 3 ──────────────────────────────────

    /**
     * Toggle again on same group → collapse back to ▶.
     */
    @Test void toggleCollapsesGroup() {
        var gs = threeGroups();
        gs.toggle(); // expand
        gs.toggle(); // collapse

        var lines = gs.render(40);
        assertThat(lines).hasSize(3);
        assertThat(lines.stream().allMatch(l -> l.toString().contains("▶"))).isTrue();
        assertThat(lines.stream().noneMatch(l -> l.toString().contains("claude"))).isTrue();
    }

    // ──────────────────────────── test 4 ──────────────────────────────────

    /**
     * Accordion mode: expand group B → group A auto-collapses.
     */
    @Test void accordionMode() {
        var gs = threeGroups();

        // Expand Models (index 0)
        gs.toggle();
        // Navigate to Providers header: Models(1) + 2 children + Providers(1) = position 3
        gs.moveDown(); // claude-opus-4
        gs.moveDown(); // claude-sonnet-4
        gs.moveDown(); // Providers
        // Now cursor is on "Providers"
        assertThat(gs.isOnGroup()).isTrue();

        // Expand Providers — Models should auto-collapse
        gs.toggle();

        var lines = gs.render(40);
        // Providers expanded: Models(1) + Providers(1) + 2 children + Tools(1) = 5
        assertThat(lines).hasSize(5);
        // No claude entries — Models is collapsed
        assertThat(lines.stream().noneMatch(l -> l.toString().contains("claude"))).isTrue();
        // Providers children visible
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("anthropic"))).isTrue();
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("openai"))).isTrue();
        // Providers header shows ▼
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("▼") && l.toString().contains("Providers"))).isTrue();
    }

    // ──────────────────────────── test 5 ──────────────────────────────────

    /**
     * isOnGroup() returns true when cursor is on a group header.
     */
    @Test void isOnGroupReturnsTrueForGroupHeader() {
        var gs = threeGroups();
        // Cursor at index 0 = Models group header
        assertThat(gs.isOnGroup()).isTrue();
    }

    // ──────────────────────────── test 6 ──────────────────────────────────

    /**
     * isOnGroup() returns false when cursor is on a leaf item.
     */
    @Test void isOnGroupReturnsFalseForLeaf() {
        var gs = threeGroups();
        gs.toggle();    // expand Models
        gs.moveDown();  // cursor on claude-opus-4 (leaf)
        assertThat(gs.isOnGroup()).isFalse();
    }

    // ──────────────────────────── test 7 ──────────────────────────────────

    /**
     * getSelected() returns the leaf Item when cursor is on a leaf.
     */
    @Test void getSelectedReturnsLeafItem() {
        var gs = threeGroups();
        gs.toggle();    // expand Models
        gs.moveDown();  // cursor on claude-opus-4

        var selected = gs.getSelected();
        assertThat(selected).isNotNull();
        assertThat(selected.value()).isEqualTo("opus");
        assertThat(selected.label()).isEqualTo("claude-opus-4");
    }

    // ──────────────────────────── test 8 ──────────────────────────────────

    /**
     * getSelected() returns null when cursor is on a group header.
     */
    @Test void getSelectedReturnsNullOnGroup() {
        var gs = threeGroups();
        // Cursor is on "Models" group header
        assertThat(gs.getSelected()).isNull();
    }

    // ──────────────────────────── test 9 ──────────────────────────────────

    /**
     * ↓ navigates through expanded group children in order.
     */
    @Test void navigationThroughExpandedGroup() {
        var gs = threeGroups();
        gs.toggle(); // expand Models

        // Flat list: [Models, claude-opus-4, claude-sonnet-4, Providers, Tools]
        assertThat(gs.isOnGroup()).isTrue(); // at Models

        gs.moveDown();
        assertThat(gs.isOnGroup()).isFalse();
        assertThat(gs.getSelected().value()).isEqualTo("opus");

        gs.moveDown();
        assertThat(gs.isOnGroup()).isFalse();
        assertThat(gs.getSelected().value()).isEqualTo("sonnet");

        gs.moveDown();
        assertThat(gs.isOnGroup()).isTrue(); // at Providers

        gs.moveDown();
        assertThat(gs.isOnGroup()).isTrue(); // at Tools
    }

    // ──────────────────────────── test 10 ─────────────────────────────────

    /**
     * When expanded group has many children exceeding maxVisible, scroll hints appear.
     */
    @Test void scrollHintsWithExpandedGroup() {
        // Create a group with 10 children, maxVisible = 5
        var children = List.of(
            item("item-a", "", "a"), item("item-b", "", "b"), item("item-c", "", "c"),
            item("item-d", "", "d"), item("item-e", "", "e"), item("item-f", "", "f"),
            item("item-g", "", "g"), item("item-h", "", "h"), item("item-i", "", "i"),
            item("item-j", "", "j")
        );
        var groups = List.of(
            group("BigGroup", children),
            group("SmallGroup", List.of(item("x", "", "x")))
        );
        var gs = new GroupedSelect<>(groups, 5);
        gs.toggle(); // expand BigGroup

        // Total flat items = BigGroup(1) + 10 children + SmallGroup(1) = 12 > maxVisible(5)
        var lines = gs.render(40);
        assertThat(lines).hasSize(5); // capped at maxVisible
        // Scroll hints should appear (↓ at minimum)
        assertThat(lines.stream().anyMatch(l -> l.toString().contains("↓"))).isTrue();
    }

    // ──────────────────────────── test 11 ─────────────────────────────────

    /**
     * Empty group expands but adds no child rows — only the group header appears.
     */
    @Test void emptyGroupRendersCorrectly() {
        List<GroupedSelect.Group<String>> groups = List.of(
            group("EmptyGroup", List.of()),
            group("FullGroup", List.of(item("item", "", "v")))
        );
        var gs = new GroupedSelect<>(groups, 7);

        // Initially 2 collapsed headers
        assertThat(gs.render(40)).hasSize(2);

        // Toggle empty group — no children to add
        gs.toggle();
        var lines = gs.render(40);
        // EmptyGroup(1, expanded) + FullGroup(1) = 2
        assertThat(lines).hasSize(2);
        // EmptyGroup header shows ▼
        assertThat(lines.getFirst().toString()).contains("▼");
        assertThat(lines.getFirst().toString()).contains("EmptyGroup");
    }

    // ──────────────────────────── test 12 ─────────────────────────────────

    /**
     * Pressing ↓ on the last child of an expanded group moves to the next group header.
     */
    @Test void moveDownFromLastChildToNextGroup() {
        var gs = threeGroups();
        gs.toggle(); // expand Models: [Models, opus, sonnet, Providers, Tools]

        // Navigate to last child of Models (claude-sonnet-4)
        gs.moveDown(); // opus
        gs.moveDown(); // sonnet

        assertThat(gs.isOnGroup()).isFalse();
        assertThat(gs.getSelected().value()).isEqualTo("sonnet");

        // One more ↓ should land on Providers group header
        gs.moveDown();
        assertThat(gs.isOnGroup()).isTrue();
        // The selected item should be the Providers group header (null value)
        assertThat(gs.getSelected()).isNull();
    }
}
