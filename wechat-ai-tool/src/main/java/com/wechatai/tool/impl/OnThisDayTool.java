package com.wechatai.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

/**
 * 历史上的今天查询工具，数据来自 Wikimedia On This Day API。
 */
@Component
public class OnThisDayTool {

    private static final Set<String> SUPPORTED_TYPES = Set.of("all", "events", "births", "deaths", "holidays", "selected");

    private final ObjectMapper objectMapper;

    @Value("${on-this-day.base-url:https://api.wikimedia.org/feed/v1/wikipedia/zh/onthisday}")
    private String baseUrl;

    @Value("${on-this-day.timeout:10000}")
    private int timeout;

    @Value("${on-this-day.max-items:5}")
    private int maxItems;

    public OnThisDayTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Tool("查询历史上的今天。当用户询问某月某日发生过什么、历史事件、名人出生逝世或节日时调用，数据来自Wikimedia")
    public String queryOnThisDay(
            @P("月份，1到12") int month,
            @P("日期，1到31，必须是该月份存在的日期") int day,
            @P("查询类型：all全部、events事件、births出生、deaths逝世、holidays节日、selected精选") String type) {

        try {
            LocalDate.of(2000, month, day);
        } catch (DateTimeException e) {
            return "日期无效，请提供真实存在的月和日";
        }

        String normalizedType = type == null || type.isBlank()
                ? "all" : type.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(normalizedType)) {
            return "不支持的查询类型。可选：all、events、births、deaths、holidays、selected";
        }

        String url = String.format("%s/%s/%02d/%02d", stripTrailingSlash(baseUrl), normalizedType, month, day);
        try {
            Connection.Response response = Jsoup.connect(url)
                    .ignoreContentType(true)
                    .userAgent("wechat-ai-assistant/1.0")
                    .header("Accept", "application/json")
                    .timeout(timeout)
                    .execute();

            JsonNode root = objectMapper.readTree(response.body());
            StringBuilder result = new StringBuilder(month + "月" + day + "日 · 历史上的今天\n");
            int remaining = Math.max(1, Math.min(maxItems, 10));

            if ("all".equals(normalizedType)) {
                remaining = appendItems(result, root.path("selected"), "精选", remaining);
                remaining = appendItems(result, root.path("events"), "历史事件", remaining);
                remaining = appendItems(result, root.path("births"), "人物出生", remaining);
                remaining = appendItems(result, root.path("deaths"), "人物逝世", remaining);
                appendItems(result, root.path("holidays"), "节日与纪念日", remaining);
            } else {
                appendItems(result, root.path(normalizedType), typeLabel(normalizedType), remaining);
            }

            if (result.toString().lines().count() <= 1) {
                return "暂未查询到" + month + "月" + day + "日的相关历史资料";
            }
            return result.toString().stripTrailing();
        } catch (Exception e) {
            return "历史资料查询暂时不可用，请稍后重试";
        }
    }

    private int appendItems(StringBuilder result, JsonNode items, String label, int remaining) {
        if (remaining <= 0 || !items.isArray() || items.isEmpty()) {
            return remaining;
        }

        int before = remaining;
        StringBuilder section = new StringBuilder("\n【" + label + "】\n");
        for (JsonNode item : items) {
            if (remaining <= 0) break;
            String text = item.path("text").asText("").trim();
            if (text.isEmpty()) continue;
            int year = item.path("year").asInt(Integer.MIN_VALUE);
            section.append("- ");
            if (year != Integer.MIN_VALUE) {
                section.append(formatYear(year)).append("：");
            }
            section.append(text).append('\n');
            remaining--;
        }
        if (remaining < before) {
            result.append(section);
        }
        return remaining;
    }

    private String formatYear(int year) {
        return year < 0 ? "公元前" + Math.abs(year) + "年" : year + "年";
    }

    private String typeLabel(String type) {
        return switch (type) {
            case "events" -> "历史事件";
            case "births" -> "人物出生";
            case "deaths" -> "人物逝世";
            case "holidays" -> "节日与纪念日";
            case "selected" -> "精选";
            default -> "历史资料";
        };
    }

    private String stripTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
