package io.github.samzhu.grimo.tui.overlay;

import io.github.samzhu.grimo.tui.core.DisplayWidth;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 管理畫面的 overlay 資料模型 + render()。
 *
 * 設計說明：
 * - 跟隨 GrimoSlashMenuView 的 pattern（純資料 + render() 產出 List<AttributedString>）
 * - 不負責按鍵處理（由 GrimoTuiRunner.handleMcpManagerKey 負責）
 * - 不負責 config 寫入或 catalog 重建（由上層呼叫者負責）
 * - load() 重新載入 server 列表時自動 clamp selectedIndex
 */
public class McpPanel {

    /** 品牌標誌色 steel blue（ANSI 256 色碼 67），與 GrimoSlashMenuView 一致 */
    private static final int BRAND_COLOR = 67;
    private static final AttributedStyle BRAND_STYLE = AttributedStyle.DEFAULT.foreground(BRAND_COLOR);
    private static final AttributedStyle DIM_STYLE = AttributedStyle.DEFAULT.foreground(245);

    /** 有序的 server 名稱列表（LinkedHashMap 保持 config.yaml 定義順序） */
    private List<String> serverNames = List.of();
    /** server 定義（name → {type, url/command, ...}） */
    private Map<String, Map<String, Object>> servers = Map.of();
    /** 目前選中的索引 */
    private int selectedIndex = 0;

    /**
     * 載入 server 列表。呼叫時自動 clamp selectedIndex。
     * @param mcpServers 從 GrimoConfig.getMcpServers() 取得的 server 定義
     */
    public void load(Map<String, Map<String, Object>> mcpServers) {
        this.servers = mcpServers;
        this.serverNames = new ArrayList<>(mcpServers.keySet());
        // clamp：刪除最後一項後 index 不超出邊界
        if (!serverNames.isEmpty()) {
            selectedIndex = Math.min(selectedIndex, serverNames.size() - 1);
        } else {
            selectedIndex = 0;
        }
    }

    public void moveUp() {
        if (selectedIndex > 0) {
            selectedIndex--;
        }
    }

    public void moveDown() {
        if (selectedIndex < serverNames.size() - 1) {
            selectedIndex++;
        }
    }

    /**
     * 取得選中 server 的名稱。
     * @return server 名稱，或 null 若列表為空
     */
    public String getSelectedName() {
        if (serverNames.isEmpty()) return null;
        return serverNames.get(selectedIndex);
    }

    public boolean isEmpty() {
        return serverNames.isEmpty();
    }

    /**
     * 渲染 overlay 行。
     * @param cols 終端機寬度
     * @return overlay 的所有行
     */
    public List<AttributedString> render(int cols) {
        var lines = new ArrayList<AttributedString>();

        // 標題
        lines.add(new AttributedString("  Manage MCP servers", BRAND_STYLE));

        if (serverNames.isEmpty()) {
            lines.add(AttributedString.EMPTY);
            lines.add(new AttributedString("  No MCP servers configured. Press [a] to add.", DIM_STYLE));
            lines.add(AttributedString.EMPTY);
            lines.add(new AttributedString("  [a]dd · Esc close", DIM_STYLE));
            return lines;
        }

        // 數量
        String countText = "  " + serverNames.size()
                + (serverNames.size() == 1 ? " server" : " servers");
        lines.add(new AttributedString(countText, DIM_STYLE));
        lines.add(AttributedString.EMPTY);

        // Server 列表
        for (int i = 0; i < serverNames.size(); i++) {
            String name = serverNames.get(i);
            Map<String, Object> cfg = servers.get(name);
            String type = (String) cfg.getOrDefault("type", "stdio");
            String detail = type.equals("stdio")
                    ? (String) cfg.getOrDefault("command", "")
                    : (String) cfg.getOrDefault("url", "");

            String prefix = (i == selectedIndex) ? "  ❯ " : "    ";
            String namePad = DisplayWidth.padRight(name, 20);
            String typePad = DisplayWidth.padRight(type, 10);
            String line = DisplayWidth.padRight(prefix + namePad + " " + typePad + " " + detail, cols);

            AttributedStyle style = (i == selectedIndex) ? BRAND_STYLE : AttributedStyle.DEFAULT;
            lines.add(new AttributedString(line, style));
        }

        // 快捷鍵提示
        lines.add(AttributedString.EMPTY);
        lines.add(new AttributedString("  ↑↓ navigate · [a]dd · [d]elete · Esc close", DIM_STYLE));

        return lines;
    }
}
