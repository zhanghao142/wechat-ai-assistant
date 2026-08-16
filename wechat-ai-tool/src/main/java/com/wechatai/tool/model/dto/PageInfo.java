package com.wechatai.tool.model.dto;

/**
 * navigate / goBack / goForward 的返回结构。
 */
public class PageInfo {

    private final String title;
    private final String url;
    private final String summary;

    public PageInfo(String title, String url, String summary) {
        this.title = title;
        this.url = url;
        this.summary = summary;
    }

    public String getTitle() { return title; }
    public String getUrl() { return url; }
    public String getSummary() { return summary; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("页面标题: ").append(title).append("\n");
        sb.append("页面地址: ").append(url).append("\n");
        sb.append("页面摘要:\n").append(summary);
        return sb.toString();
    }
}
