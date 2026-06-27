package com.example.webboard.content.mirror;

import java.util.Map;

/**
 * Human-readable Chinese labels for known DisplaySource types.
 * Used by the dashboard frontend to display friendly names instead of raw
 * ResourceLocation IDs like "create:time_of_day".
 *
 * The map is static and immutable. Unknown sources fall back to the short form
 * (namespace-stripped ID).
 */
public final class SourceLabels {
    private SourceLabels() {}

    private static final Map<String, String> LABELS = Map.ofEntries(
        // Create built-in DisplaySources (29 total)
        Map.entry("create:death_count", "死亡计数"),
        Map.entry("create:scoreboard", "计分板"),
        Map.entry("create:enchant_power", "附魔等级"),
        Map.entry("create:redstone_power", "红石信号"),
        Map.entry("create:nixie_tube", "数码管"),
        Map.entry("create:item_names", "物品名称"),
        Map.entry("create:boiler", "锅炉状态"),
        Map.entry("create:current_floor", "当前楼层"),
        Map.entry("create:fill_level", "填充量"),
        Map.entry("create:gauge_status", "仪表盘状态"),
        Map.entry("create:entity_name", "实体名称"),
        Map.entry("create:time_of_day", "游戏时间"),
        Map.entry("create:stopwatch", "秒表"),
        Map.entry("create:kinetic_speed", "转速"),
        Map.entry("create:kinetic_stress", "应力"),
        Map.entry("create:station_summary", "站点信息"),
        Map.entry("create:train_status", "列车状态"),
        Map.entry("create:observed_train_name", "列车名称"),
        Map.entry("create:accumulate_items", "物品累计"),
        Map.entry("create:item_throughput", "物品吞吐量"),
        Map.entry("create:count_items", "物品计数"),
        Map.entry("create:list_items", "物品列表"),
        Map.entry("create:count_fluids", "流体计数"),
        Map.entry("create:list_fluids", "流体列表"),
        Map.entry("create:read_package_address", "包裹地址"),
        Map.entry("create:computer", "计算机输出"),
        // Common addon mods (best-effort, verify from actual IDs if possible)
        // Addons with DisplaySource support are rare; these are common ones:
        Map.entry("createrailwaysnavigator:train_display", "列车导航"),
        Map.entry("extremereactors2createcompat:reactor_global_status", "反应堆状态"),
        Map.entry("extremereactors2createcompat:turbine_global_status", "涡轮状态"),
        Map.entry("extremereactors2createcompat:energizer_global_status", "充能器状态")
    );

    /**
     * Returns the Chinese label for the given source type, or the short form
     * (namespace stripped) if not found in the map.
     */
    public static String label(String sourceType) {
        if (sourceType == null) return "未知";
        String label = LABELS.get(sourceType);
        return label != null ? label : shortForm(sourceType);
    }

    /** "create:time_of_day" → "time_of_day"; "foo" → "foo" */
    private static String shortForm(String sourceType) {
        int i = sourceType.indexOf(':');
        return i >= 0 ? sourceType.substring(i + 1) : sourceType;
    }

    /**
     * Returns the full label map as a JSON object string.
     * Used by the {@code GET /api/source-labels} REST endpoint.
     */
    public static String allLabelsAsJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : LABELS.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }
}
