package com.wechatai.ai.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具名 → 桌面进度文案（开始 / 结束）。
 */
public final class ToolProgressTexts {

    private static final Pattern SEARCH_ITEM = Pattern.compile("(?m)^\\d+\\.\\s");
    private static final Pattern LINK_LINE = Pattern.compile("链接[:：]");

    private ToolProgressTexts() {}

    public static String start(String toolName) {
        if (toolName == null) return "正在执行";
        return switch (toolName) {
            case "searchInternet" -> "正在搜索网页";
            case "readUrl" -> "正在读取网页";
            case "navigate" -> "正在打开网页";
            case "getText", "getHtml", "click", "type", "scroll" -> "正在浏览网页";
            case "screenshot" -> "正在截图";
            case "generateDocument" -> "正在生成文档";
            case "renderChart" -> "正在生成图表";
            case "searchDocuments" -> "正在检索知识库";
            case "getWeather", "weather" -> "正在查询天气";
            case "setRemind", "listReminds", "cancelRemind" -> "正在处理提醒";
            case "translate" -> "正在翻译";
            case "generalAgent" -> "正在分析并执行任务…";
            case "taxiAgent" -> "正在处理出行";
            case "filesystemAgent" -> "正在处理文件";
            case "mcdonaldsAgent" -> "正在处理点餐";
            case "jobAgent" -> "正在处理求职";
            default -> "正在执行 " + toolName + "";
        };
    }

    public static String finish(String toolName, String result) {
        if (toolName == null) return "已完成";
        String r = result != null ? result : "";
        boolean err = r.contains("\"error\"") || r.startsWith("{\"error\"");
        return switch (toolName) {
            case "searchInternet" -> {
                if (r.contains("未找到")) yield "未搜到相关结果";
                int n = countSearchHits(r);
                yield n > 0 ? "搜到 " + n + " 条结果" : "搜索完成";
            }
            case "readUrl" -> err ? "网页读取失败" : "网页内容已获取";
            case "navigate" -> err ? "打开网页失败" : "网页已打开";
            case "screenshot" -> {
                // 勿用 r.contains("失败")：成功时 AI 描述/页面文案里也常出现「失败」字样
                if (err || r.contains("截图失败") || r.contains("写入失败")) {
                    yield "截图未成功，已跳过";
                }
                yield "截图完成";
            }
            case "generateDocument" -> {
                if (r.contains("needsConfirm") || r.contains("确认")) yield "文档已就绪，等待确认";
                yield err ? "文档生成失败" : "文档已生成";
            }
            case "renderChart" -> {
                if (r.contains("失败") || r.contains("无效") || r.contains("不足") || r.contains("不一致")) {
                    yield "图表未生成，已跳过";
                }
                yield r.contains("图表已生成") ? "图表已生成" : "图表处理完成";
            }
            case "searchDocuments" -> err ? "知识库检索失败" : "知识库检索完成";
            case "generalAgent", "taxiAgent", "filesystemAgent", "mcdonaldsAgent", "jobAgent" -> {
                if (r.contains("needsConfirm")) yield "需要确认操作";
                yield err ? "任务执行遇到问题" : "任务处理完成";
            }
            default -> err ? toolName + " 执行失败" : toolName + " 完成";
        };
    }

    private static int countSearchHits(String text) {
        int n = 0;
        Matcher m = SEARCH_ITEM.matcher(text);
        while (m.find()) n++;
        if (n > 0) return n;
        m = LINK_LINE.matcher(text);
        while (m.find()) n++;
        return n;
    }
}
