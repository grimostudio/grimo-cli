package io.github.samzhu.grimo;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GrimoMcpManagerViewTest {

    private Map<String, Map<String, Object>> twoServers() {
        var servers = new LinkedHashMap<String, Map<String, Object>>();
        servers.put("deepwiki", Map.of("type", "sse", "url", "https://mcp.deepwiki.com/sse"));
        servers.put("filesystem", Map.of("type", "stdio", "command", "npx"));
        return servers;
    }

    @Test
    void renderShouldShowTitleAndServers() {
        var view = new GrimoMcpManagerView();
        view.load(twoServers());

        List<AttributedString> lines = view.render(80);

        String joined = lines.stream().map(AttributedString::toString).reduce("", (a, b) -> a + "\n" + b);
        assertThat(joined).contains("Manage MCP servers");
        assertThat(joined).contains("2 servers");
        assertThat(joined).contains("deepwiki");
        assertThat(joined).contains("filesystem");
        assertThat(joined).contains("[a]dd");
        assertThat(joined).contains("[d]elete");
    }

    @Test
    void renderEmptyListShouldShowMessage() {
        var view = new GrimoMcpManagerView();
        view.load(Map.of());

        List<AttributedString> lines = view.render(80);

        String joined = lines.stream().map(AttributedString::toString).reduce("", (a, b) -> a + "\n" + b);
        assertThat(joined).contains("No MCP servers configured");
        assertThat(joined).contains("[a]");
    }

    @Test
    void selectedNameShouldReturnFirstByDefault() {
        var view = new GrimoMcpManagerView();
        view.load(twoServers());

        assertThat(view.getSelectedName()).isEqualTo("deepwiki");
    }

    @Test
    void moveDownShouldChangeSelection() {
        var view = new GrimoMcpManagerView();
        view.load(twoServers());

        view.moveDown();

        assertThat(view.getSelectedName()).isEqualTo("filesystem");
    }

    @Test
    void moveUpAtTopShouldStay() {
        var view = new GrimoMcpManagerView();
        view.load(twoServers());

        view.moveUp(); // already at 0

        assertThat(view.getSelectedName()).isEqualTo("deepwiki");
    }

    @Test
    void moveDownAtBottomShouldStay() {
        var view = new GrimoMcpManagerView();
        view.load(twoServers());

        view.moveDown(); // index 1
        view.moveDown(); // should stay at 1 (not wrap)

        assertThat(view.getSelectedName()).isEqualTo("filesystem");
    }

    @Test
    void refreshShouldClampIndex() {
        var view = new GrimoMcpManagerView();
        view.load(twoServers());
        view.moveDown(); // index 1

        // Reload with only 1 server — index should clamp to 0
        view.load(Map.of("deepwiki", Map.of("type", "sse", "url", "https://mcp.deepwiki.com/sse")));

        assertThat(view.getSelectedName()).isEqualTo("deepwiki");
    }

    @Test
    void getSelectedNameOnEmptyListShouldReturnNull() {
        var view = new GrimoMcpManagerView();
        view.load(Map.of());

        assertThat(view.getSelectedName()).isNull();
    }
}
