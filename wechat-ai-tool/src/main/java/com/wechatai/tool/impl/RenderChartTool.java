package com.wechatai.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 简报插图工具 — 用 QuickChart（无需 API Key）生成柱状/折线/饼图 URL，供写入 Markdown。
 */
@Component
public class RenderChartTool {

    private static final Logger log = LoggerFactory.getLogger(RenderChartTool.class);

    private static final String[] BAR_COLORS = {
            "rgb(59,130,246)", "rgb(16,185,129)", "rgb(245,158,11)",
            "rgb(239,68,68)", "rgb(139,92,246)", "rgb(6,182,212)"
    };

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String chartBaseUrl;

    public RenderChartTool(
            @Value("${quickchart.base-url:https://quickchart.io/chart}") String chartBaseUrl) {
        this.chartBaseUrl = chartBaseUrl != null && !chartBaseUrl.isBlank()
                ? chartBaseUrl.trim()
                : "https://quickchart.io/chart";
    }

    @Tool("根据结构化数据生成图表图片链接（QuickChart），返回可直接粘贴进 Markdown 的 ![](url) 行。"
            + "写对比简报/研究报告且有 ≥2 个可比数值时调用；一张报告通常 1～2 张图即可。"
            + "数字必须来自已检索材料，禁止编造。多对象同一指标用 bar，多时期趋势用 line，占比构成用 pie（类目≤6）。"
            + "先调本工具拿到图片行，再把该行原样写入 generateDocument 的 content。")
    public String renderChart(
            @P("图表类型：bar（柱状对比）| line（折线趋势）| pie（饼图占比）") String type,
            @P("图表标题，用结论短句，如「瑞幸/星巴克/库迪门店量对比」") String title,
            @P("横轴/扇区标签列表，与 values 一一对应，例如 [\"瑞幸\",\"星巴克\",\"库迪\"]") List<String> labels,
            @P("数值列表，与 labels 一一对应，例如 [16240, 6800, 7000]") List<Object> values,
            @P("可选：数值单位说明，如「家」「亿元」「%」，可空") String unit) {

        String chartType = normalizeType(type);
        if (chartType == null) {
            return "图表类型无效，请使用 bar、line 或 pie";
        }

        List<String> labs = cleanLabels(labels);
        List<Double> nums = cleanValues(values);
        if (labs.size() < 2 || nums.size() < 2) {
            return "至少需要 2 个标签和 2 个数值才能画图；数据不足请写文字对比，不要强行出图";
        }
        if (labs.size() != nums.size()) {
            return "labels 与 values 数量不一致（labels=" + labs.size()
                    + ", values=" + nums.size() + "），请对齐后再调用";
        }
        if ("pie".equals(chartType) && labs.size() > 6) {
            return "饼图类目过多（>" + 6 + "），请改用 bar，或合并为不超过 6 类";
        }
        if (labs.size() > 12) {
            return "类目过多（>12），请只保留关键对比项再画图";
        }

        String safeTitle = (title == null || title.isBlank()) ? "数据对比" : title.trim();
        String safeUnit = unit == null ? "" : unit.trim();

        try {
            Map<String, Object> config = buildChartConfig(chartType, safeTitle, labs, nums, safeUnit);
            String json = objectMapper.writeValueAsString(config);
            String encoded = URLEncoder.encode(json, StandardCharsets.UTF_8)
                    .replace("+", "%20");
            String url = chartBaseUrl
                    + (chartBaseUrl.contains("?") ? "&" : "?")
                    + "width=640&height=360&devicePixelRatio=2&c=" + encoded;

            log.info("【renderChart】type={} labels={} titleLen={}",
                    chartType, labs.size(), safeTitle.length());

            return "图表已生成。请将下面【整行】原样插入 Markdown 文档（不要改写 URL，不要拆行）：\n\n"
                    + "![" + escapeAlt(safeTitle) + "](" + url + ")\n\n"
                    + "说明：类型=" + chartType
                    + "；数据点=" + labs.size()
                    + (safeUnit.isEmpty() ? "" : "；单位=" + safeUnit);
        } catch (Exception e) {
            log.warn("【renderChart】失败: {}", e.getMessage());
            return "图表生成失败：" + e.getMessage() + "。请改为文字表格描述数据。";
        }
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) return "bar";
        String t = type.trim().toLowerCase(Locale.ROOT);
        if (t.contains("line") || t.contains("折")) return "line";
        if (t.contains("pie") || t.contains("饼") || t.contains("环")) return "pie";
        if (t.contains("bar") || t.contains("柱") || t.contains("条")) return "bar";
        if ("bar".equals(t) || "line".equals(t) || "pie".equals(t)) return t;
        return null;
    }

    private static List<String> cleanLabels(List<String> labels) {
        List<String> out = new ArrayList<>();
        if (labels == null) return out;
        for (String l : labels) {
            if (l == null || l.isBlank()) continue;
            String s = l.trim();
            if (s.length() > 40) s = s.substring(0, 40);
            out.add(s);
        }
        return out;
    }

    private static List<Double> cleanValues(List<?> values) {
        List<Double> out = new ArrayList<>();
        if (values == null) return out;
        for (Object v : values) {
            if (v == null) continue;
            if (v instanceof Number n) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) continue;
                out.add(d);
                continue;
            }
            String s = String.valueOf(v).trim().replace(",", "");
            if (s.isEmpty()) continue;
            try {
                out.add(Double.parseDouble(s));
            } catch (NumberFormatException ignored) {
                // skip non-numeric
            }
        }
        return out;
    }

    private Map<String, Object> buildChartConfig(String type, String title,
                                                   List<String> labels, List<Double> values,
                                                   String unit) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", type);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", labels);

        Map<String, Object> dataset = new LinkedHashMap<>();
        String dsLabel = unit.isEmpty() ? title : title + "（" + unit + "）";
        dataset.put("label", dsLabel);
        dataset.put("data", values);

        List<String> colors = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            colors.add(BAR_COLORS[i % BAR_COLORS.length]);
        }
        if ("pie".equals(type)) {
            dataset.put("backgroundColor", colors);
        } else if ("bar".equals(type)) {
            dataset.put("backgroundColor", colors);
        } else {
            dataset.put("borderColor", BAR_COLORS[0]);
            dataset.put("backgroundColor", "rgba(59,130,246,0.15)");
            dataset.put("fill", true);
            dataset.put("tension", 0.25);
        }
        data.put("datasets", List.of(dataset));
        root.put("data", data);

        Map<String, Object> titlePlugin = new LinkedHashMap<>();
        titlePlugin.put("display", true);
        titlePlugin.put("text", title);
        titlePlugin.put("fontSize", 14);

        Map<String, Object> legend = new LinkedHashMap<>();
        legend.put("display", "pie".equals(type) || values.size() <= 1);

        Map<String, Object> plugins = new LinkedHashMap<>();
        plugins.put("title", titlePlugin);
        plugins.put("legend", legend);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("plugins", plugins);
        options.put("responsive", true);
        if (!"pie".equals(type)) {
            Map<String, Object> y = new LinkedHashMap<>();
            y.put("beginAtZero", true);
            Map<String, Object> scales = new LinkedHashMap<>();
            scales.put("yAxes", List.of(y)); // Chart.js v2 style used by QuickChart default
            // Also set v3 style for compatibility
            Map<String, Object> y3 = new LinkedHashMap<>();
            y3.put("beginAtZero", true);
            scales.put("y", y3);
            options.put("scales", scales);
        }
        root.put("options", options);
        return root;
    }

    private static String escapeAlt(String s) {
        return s.replace("[", "(").replace("]", ")").replace("\n", " ");
    }
}
