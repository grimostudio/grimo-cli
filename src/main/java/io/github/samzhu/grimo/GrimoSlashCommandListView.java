package io.github.samzhu.grimo;

import org.springframework.shell.jline.tui.component.view.control.BoxView;
import org.springframework.shell.jline.tui.component.view.screen.Screen;
import org.springframework.shell.jline.tui.geom.Rectangle;

import java.util.ArrayList;
import java.util.List;

/**
 * 斜線指令候選列表：modal overlay，最多 5 項、即時過濾、↑↓ 選擇。
 *
 * 設計說明：
 * - 基於 BoxView + setDrawFunction()（ListView 的 ItemStyle 不支援純色彩高亮）
 * - 選中項以品牌標誌色（ANSI 256 色碼 67, #5F87AF steel blue）渲染
 * - 未選中項以預設色渲染
 * - 最多顯示 5 項，隨輸入即時過濾
 * - 作為 modal overlay 覆蓋 content 底部，input/status 不動
 *
 * @see <a href="https://docs.spring.io/spring-shell/reference/tui/views/box.html">BoxView :: Spring Shell</a>
 */
public class GrimoSlashCommandListView extends BoxView {

    /** 品牌標誌色 steel blue（ANSI 256 色碼 67） */
    private static final int BRAND_COLOR = 67;
    private static final int MAX_VISIBLE = 5;

    /** 所有可用的斜線指令 */
    private final List<MenuItem> allItems;
    /** 目前過濾後的候選項 */
    private List<MenuItem> filteredItems;
    /** 目前選中的索引 */
    private int selectedIndex = 0;

    /**
     * 選單項目：指令名稱 + 描述。
     */
    public record MenuItem(String name, String description) {}

    public GrimoSlashCommandListView(List<MenuItem> items) {
        this.allItems = new ArrayList<>(items);
        this.filteredItems = new ArrayList<>(items);
        setDrawFunction(this::drawList);
    }

    /**
     * 以前綴過濾候選項。
     * @param prefix 過濾前綴（不含 /，例如 "ag"）
     */
    public void filter(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            filteredItems = new ArrayList<>(allItems);
        } else {
            String lowerPrefix = prefix.toLowerCase();
            filteredItems = allItems.stream()
                    .filter(item -> item.name().toLowerCase().contains(lowerPrefix))
                    .toList();
        }
        selectedIndex = 0;
    }

    /**
     * 顯示全部候選項（無過濾）。
     */
    public void filterAll() {
        filteredItems = new ArrayList<>(allItems);
        selectedIndex = 0;
    }

    /**
     * 移動選中項：上移。
     */
    public void moveUp() {
        if (!filteredItems.isEmpty()) {
            selectedIndex = (selectedIndex - 1 + filteredItems.size()) % filteredItems.size();
        }
    }

    /**
     * 移動選中項：下移。
     */
    public void moveDown() {
        if (!filteredItems.isEmpty()) {
            selectedIndex = (selectedIndex + 1) % filteredItems.size();
        }
    }

    /**
     * 取得選中的指令名稱。
     * @return 選中的指令名（不含 /），或 null 若列表為空
     */
    public String getSelected() {
        if (filteredItems.isEmpty()) return null;
        return filteredItems.get(selectedIndex).name();
    }

    /**
     * 取得目前可見的候選項數量（最多 MAX_VISIBLE）。
     */
    public int getVisibleCount() {
        return Math.min(filteredItems.size(), MAX_VISIBLE);
    }

    /**
     * 是否有候選項可顯示。
     */
    public boolean hasItems() {
        return !filteredItems.isEmpty();
    }

    /**
     * 自訂繪製：選中項用標誌色，未選中用預設色。
     */
    private Rectangle drawList(Screen screen, Rectangle rect) {
        int visibleCount = getVisibleCount();
        if (visibleCount == 0) return rect;

        var brandWriter = screen.writerBuilder().color(BRAND_COLOR).build();
        var defaultWriter = screen.writerBuilder().build();

        for (int i = 0; i < visibleCount; i++) {
            var item = filteredItems.get(i);
            // 格式：  /command-name    Description
            String line = String.format("  /%-20s %s", item.name(), item.description());
            // 截斷到 view 寬度
            if (line.length() > rect.width()) {
                line = line.substring(0, rect.width());
            }

            if (i == selectedIndex) {
                brandWriter.text(line, rect.x(), rect.y() + i);
            } else {
                defaultWriter.text(line, rect.x(), rect.y() + i);
            }
        }

        return rect;
    }
}
