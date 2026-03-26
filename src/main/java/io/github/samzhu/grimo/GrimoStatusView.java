package io.github.samzhu.grimo;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.List;

/**
 * Status 區：顯示 agent/model/workspace/計數資訊（1 行）。
 *
 * 設計說明：
 * - 取代 Spring Shell StatusBarView
 * - 純資料模型 + render() 產出 List<AttributedString>
 */
public class GrimoStatusView {

    private static final AttributedStyle STATUS_STYLE = AttributedStyle.DEFAULT.foreground(245);

    private String statusText;

    public GrimoStatusView(String statusText) {
        this.statusText = statusText;
    }

    public void setStatusText(String statusText) {
        this.statusText = statusText;
    }

    /**
     * 渲染 status 區域為 List<AttributedString>（1 行）。
     *
     * @param cols 終端機寬度
     * @return 固定 1 行的列表
     */
    public List<AttributedString> render(int cols) {
        String text = statusText;
        if (text.length() > cols) {
            text = text.substring(0, cols);
        }
        return List.of(new AttributedString(text, STATUS_STYLE));
    }
}
