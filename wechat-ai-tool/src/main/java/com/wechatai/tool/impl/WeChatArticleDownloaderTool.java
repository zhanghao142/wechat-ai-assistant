package com.wechatai.tool.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

@Component
public class WeChatArticleDownloaderTool {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final int TIMEOUT = 15000;

    @Tool("将微信公众号文章链接转换为Markdown文本。当用户分享微信文章链接并要求转换为md文件时使用")
    public String downloadAsMarkdown(
            @P("微信公众号文章链接，必须包含 /s/ 路径，如 https://mp.weixin.qq.com/s/xxx")
            String url) {

        if (url == null || url.trim().isEmpty()) {
            return "错误：链接不能为空";
        }

        if (!url.contains("/s/")) {
            return "错误：仅支持标准的微信公众号文章链接";
        }

        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT)
                    .execute()
                    .parse();

            Element content = doc.selectFirst("#js_content");
            if (content == null) {
                content = doc.selectFirst(".rich_media_content");
            }
            if (content == null) {
                return "错误：未能提取文章正文";
            }

            processImages(content);
            processVideos(content);
            removeUnwantedElements(content);

            StringBuilder markdownBuilder = new StringBuilder();

            String title = extractTitle(doc);
            if (!title.isEmpty()) {
                markdownBuilder.append("# ").append(title).append("\n\n");
            }

            String author = extractAuthor(doc);
            if (!author.isEmpty()) {
                markdownBuilder.append("**").append(author).append("**\n\n");
            }

            htmlToMarkdown(content, markdownBuilder);

            return markdownBuilder.toString().trim();

        } catch (Exception e) {
            return "错误：下载文章失败: " + e.getMessage();
        }
    }

    private String extractTitle(Document doc) {
        Element titleEl = doc.selectFirst("#activity-name");
        if (titleEl != null) {
            return titleEl.text().trim();
        }
        Element headTitle = doc.selectFirst("h1");
        if (headTitle != null) {
            return headTitle.text().trim();
        }
        return doc.title().replace("- 微信公众平台", "").replace("- 腾讯新闻", "").trim();
    }

    private String extractAuthor(Document doc) {
        Elements metaTexts = doc.select(".rich_media_meta .rich_media_meta_text");
        for (Element meta : metaTexts) {
            String text = meta.text().trim();
            if (!text.contains("发表于") && !text.contains("阅读") && !text.contains("赞")) {
                return text;
            }
        }
        return "";
    }

    private void processImages(Element content) {
        Elements images = content.select("img");
        for (Element img : images) {
            String dataSrc = img.attr("data-src");
            if (!dataSrc.isEmpty()) {
                img.attr("src", dataSrc);
            }
            String lazySrc = img.attr("data-lazy-src");
            if (img.attr("src").isEmpty() && !lazySrc.isEmpty()) {
                img.attr("src", lazySrc);
            }
            img.removeAttr("data-src");
            img.removeAttr("data-lazy-src");
            img.removeAttr("data-w");
            img.removeAttr("data-h");
            img.removeAttr("class");
            img.removeAttr("style");
        }
    }

    private void processVideos(Element content) {
        Elements videos = content.select("video");
        for (Element video : videos) {
            String poster = video.attr("poster");
            if (!poster.isEmpty()) {
                Element placeholder = new Element("img");
                placeholder.attr("src", poster);
                placeholder.attr("alt", "[视频]");
                video.replaceWith(placeholder);
            } else {
                video.remove();
            }
        }
    }

    private void removeUnwantedElements(Element content) {
        content.select("script").remove();
        content.select("style").remove();
        content.select("iframe").remove();
        content.select("noscript").remove();
        content.select("link").remove();
        content.select("[style*=display: none]").remove();
        content.select("[style*=visibility: hidden]").remove();
    }

    private void htmlToMarkdown(Element element, StringBuilder sb) {
        for (org.jsoup.nodes.Node child : element.childNodes()) {
            if (child instanceof TextNode) {
                String text = ((TextNode) child).text().trim();
                if (!text.isEmpty()) {
                    sb.append(text).append(" ");
                }
            } else if (child instanceof Element) {
                Element el = (Element) child;
                processElement(el, sb);
            }
        }
    }

    private void processElement(Element el, StringBuilder sb) {
        String tag = el.tagName().toLowerCase();
        switch (tag) {
            case "h1":
                sb.append("\n# ").append(el.text().trim()).append("\n\n");
                break;
            case "h2":
                sb.append("\n## ").append(el.text().trim()).append("\n\n");
                break;
            case "h3":
                sb.append("\n### ").append(el.text().trim()).append("\n\n");
                break;
            case "h4":
                sb.append("\n#### ").append(el.text().trim()).append("\n\n");
                break;
            case "h5":
                sb.append("\n##### ").append(el.text().trim()).append("\n\n");
                break;
            case "h6":
                sb.append("\n###### ").append(el.text().trim()).append("\n\n");
                break;
            case "p":
                sb.append("\n");
                htmlToMarkdown(el, sb);
                sb.append("\n\n");
                break;
            case "br":
            case "hr":
                sb.append("\n");
                break;
            case "strong":
            case "b":
                sb.append("**").append(el.text().trim()).append("**");
                break;
            case "em":
            case "i":
                sb.append("*").append(el.text().trim()).append("*");
                break;
            case "a":
                String href = el.attr("href");
                String linkText = el.text().trim();
                if (!href.isEmpty()) {
                    sb.append("[").append(linkText.isEmpty() ? href : linkText).append("](").append(href).append(")");
                } else {
                    sb.append(linkText);
                }
                break;
            case "img":
                String src = el.attr("src");
                String alt = el.attr("alt");
                if (!src.isEmpty()) {
                    sb.append("\n![").append(alt.isEmpty() ? "" : alt).append("](").append(src).append(")\n\n");
                }
                break;
            case "ul":
                sb.append("\n");
                processList(el, sb, "- ");
                sb.append("\n");
                break;
            case "ol":
                sb.append("\n");
                processOrderedList(el, sb);
                sb.append("\n");
                break;
            case "blockquote":
                sb.append("\n> ").append(el.text().trim().replace("\n", "\n> ")).append("\n\n");
                break;
            case "table":
                processTable(el, sb);
                break;
            case "div":
            case "span":
            case "section":
            case "article":
                htmlToMarkdown(el, sb);
                break;
            default:
                htmlToMarkdown(el, sb);
                break;
        }
    }

    private void processList(Element list, StringBuilder sb, String bullet) {
        Elements items = list.select("li");
        for (Element item : items) {
            sb.append(bullet).append(item.text().trim()).append("\n");
        }
    }

    private void processOrderedList(Element list, StringBuilder sb) {
        Elements items = list.select("li");
        int index = 1;
        for (Element item : items) {
            sb.append(index).append(". ").append(item.text().trim()).append("\n");
            index++;
        }
    }

    private void processTable(Element table, StringBuilder sb) {
        Elements headers = table.select("thead th, tr th");
        Elements rows = table.select("tbody tr, tr:not(:has(th))");

        if (headers.isEmpty()) return;

        sb.append("\n");
        sb.append("| ");
        for (Element header : headers) {
            sb.append(header.text().trim()).append(" | ");
        }
        sb.append("\n");

        sb.append("| ");
        for (int i = 0; i < headers.size(); i++) {
            sb.append("--- | ");
        }
        sb.append("\n");

        for (Element row : rows) {
            Elements cells = row.select("td");
            sb.append("| ");
            for (Element cell : cells) {
                sb.append(cell.text().trim()).append(" | ");
            }
            sb.append("\n");
        }
        sb.append("\n");
    }
}