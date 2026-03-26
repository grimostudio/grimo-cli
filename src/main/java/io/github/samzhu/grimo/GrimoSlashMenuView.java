package io.github.samzhu.grimo;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * 斜線指令候選列表：最多 5 項、即時過濾、↑↓ 選擇。
 *
 * 設計說明：
 * - 純資料模型 + render() 產出 List<AttributedString>
 * - 選中項以品牌標誌色（ANSI 256 色碼 67, #5F87AF steel blue）渲染
 * - 未選中項以預設色渲染
 * - 作為 overlay 渲染到 content 底部（由 GrimoScreen 負責覆蓋）
 */
public class GrimoSlashMenuView {

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

    public GrimoSlashMenuView(List<MenuItem> items) {
        this.allItems = new ArrayList<>(items);
        this.filteredItems = new ArrayList<>(items);
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

    public void moveUp() {
        if (!filteredItems.isEmpty()) {
            selectedIndex = (selectedIndex - 1 + filteredItems.size()) % filteredItems.size();
        }
    }

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
     * 渲染斜線指令選單為 List<AttributedString>。
     *
     * @param cols 終端機寬度
     * @return 可見項目數量的行列表
     */
    public List<AttributedString> render(int cols) {
        List<AttributedString> result = new ArrayList<>();
        int visibleCount = getVisibleCount();

        for (int i = 0; i < visibleCount; i++) {
            var item = filteredItems.get(i);
            String text = String.format("  /%-20s %s", item.name(), item.description());
            if (text.length() > cols) {
                text = text.substring(0, cols);
            }

            AttributedStyle style = (i == selectedIndex)
                    ? AttributedStyle.DEFAULT.foreground(BRAND_COLOR)
                    : AttributedStyle.DEFAULT;
            result.add(new AttributedString(text, style));
        }

        return result;
    }
}
