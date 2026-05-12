package harshal.temkar.depmanagement.dto;

import java.util.List;

/**
 * Immutable record carrying data for a single ECharts chart.
 *
 * @param chartId   identifier matching the HTML canvas element
 * @param labels    ordered list of category labels
 * @param values    ordered list of numeric values corresponding to labels
 * @param title     human-readable chart title (i18n key resolved before this is set)
 * @author Harshal Temkar
 */
public record ChartDataDTO(
    String chartId,
    List<String> labels,
    List<Integer> values,
    String title
) {
    /**
     * Returns {@code true} if there is no chart data to render.
     *
     * @return {@code true} when values list is empty
     */
    public boolean isEmpty() {
        return values == null || values.isEmpty();
    }
}
