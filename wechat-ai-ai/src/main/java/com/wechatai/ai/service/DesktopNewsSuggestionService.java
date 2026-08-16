package com.wechatai.ai.service;

import lombok.Data;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 桌面欢迎页资讯 — 抓取必应新闻的标题与摘要正文，不改动 ReadUrlTool。
 * 每次请求实时搜索，不做缓存（新对话各自拉一次）。
 */
@Service
public class DesktopNewsSuggestionService {

    /** 偏国内中文热点，减少必应混入外文电讯 */
    private static final String NEWS_QUERY = "国内热点新闻";

    /** 必应卡片里常见的「N 天前 ·」前缀 */
    private static final Pattern TIME_PREFIX = Pattern.compile(
            "^(?:\\d+\\s*(?:秒|分钟|小时|天|周|月)前|刚刚|今天|昨天)\\s*[·•|]\\s*");

    /** 抓到页面内嵌 JSON / 结构化元数据时直接丢弃 */
    private static final Pattern JUNK_SNIPPET = Pattern.compile(
            "\\[\\s*\\{|\\{\\s*\"|\"id\"\\s*:|\"subdomain|\"sourceUrl\"|\"newsArticle\"|\"provider\"\\s*:");

    @Value("${url-read.timeout:15000}")
    private int timeout;

    public List<NewsItem> listNews(int limit) {
        int n = Math.max(1, Math.min(limit, 12));
        // 多抓一些再截断，避免前端 3/4/3 缺格
        List<NewsItem> items = fetchNews(Math.max(n * 2, 16));
        if (items.size() > n) {
            return new ArrayList<>(items.subList(0, n));
        }
        return items;
    }

    private List<NewsItem> fetchNews(int limit) {
        List<NewsItem> fromNews = fetchFromBingNews(limit);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<NewsItem> merged = new ArrayList<>();
        for (NewsItem item : fromNews) {
            if (!isChineseText(item.getTitle())) continue;
            if (seen.add(item.getTitle())) {
                merged.add(item);
            }
        }
        if (merged.size() < limit) {
            for (NewsItem item : fetchFromWebSearch(limit)) {
                if (merged.size() >= limit) break;
                if (!isChineseText(item.getTitle())) continue;
                if (seen.add(item.getTitle())) {
                    merged.add(item);
                }
            }
        }
        return merged;
    }

    /** 必应新闻频道：标题 + 摘要正文 */
    private List<NewsItem> fetchFromBingNews(int limit) {
        List<NewsItem> items = new ArrayList<>();
        try {
            String url = "https://cn.bing.com/news/search?q="
                    + URLEncoder.encode(NEWS_QUERY, StandardCharsets.UTF_8)
                    + "&qft=interval%3d\"7\"&form=PTFTNR&setlang=zh-hans&mkt=zh-CN&cc=CN";
            Document doc = connect(url);
            for (Element card : doc.select(".news-card, .t_s, .newsitem, div[class*=news-card]")) {
                if (items.size() >= limit) break;
                Element titleEl = card.selectFirst("a.title, a[class*=title], h2 a, a.t_t");
                if (titleEl == null) {
                    titleEl = card.selectFirst("a[href]");
                }
                if (titleEl == null) continue;
                String title = clean(titleEl.text());
                if (title.length() < 6) continue;
                if (!isChineseText(title)) continue;

                // 只取明确的摘要节点，避免宽泛的 p / t_s_raw 扫到卡片内嵌 JSON
                String content = extractSnippet(card, ".snippet, .sn_para, .news-card-snippet, div.snippet");
                String display = usableSnippet(content) ? content : title;
                items.add(NewsItem.of(title, display));
            }
        } catch (Exception ignored) {
            // 降级到网页搜索
        }
        return items;
    }

    /** 网页搜索兜底：标题 + caption 摘要 */
    private List<NewsItem> fetchFromWebSearch(int limit) {
        List<NewsItem> items = new ArrayList<>();
        try {
            String url = "https://cn.bing.com/search?q="
                    + URLEncoder.encode(NEWS_QUERY, StandardCharsets.UTF_8)
                    + "&count=" + Math.max(limit, 20)
                    + "&setlang=zh-hans&mkt=zh-CN&cc=CN";
            Document doc = connect(url);
            for (Element result : doc.select("#b_results .b_algo")) {
                if (items.size() >= limit) break;
                Element titleEl = result.selectFirst("h2 a");
                if (titleEl == null) continue;
                String title = clean(titleEl.text());
                if (title.length() < 6) continue;
                if (!isChineseText(title)) continue;
                String content = extractSnippet(result, ".b_caption p, .b_lineclamp2, .b_lineclamp3, .b_algoSlug");
                String display = usableSnippet(content) ? content : title;
                items.add(NewsItem.of(title, display));
            }
        } catch (Exception ignored) {
            // 欢迎页可空
        }
        return items;
    }

    private static String extractSnippet(Element root, String cssQuery) {
        for (Element el : root.select(cssQuery)) {
            String text = clean(el.ownText());
            if (text.isEmpty()) {
                text = clean(el.text());
            }
            String normalized = normalizeSnippet(text);
            if (usableSnippet(normalized)) {
                return normalized;
            }
        }
        return "";
    }

    static String normalizeSnippet(String text) {
        if (text == null || text.isBlank()) return "";
        return TIME_PREFIX.matcher(clean(text)).replaceFirst("").strip();
    }

    /** 可用摘要：中文为主，且不是必应卡片泄漏的 JSON / 时间元数据 */
    static boolean usableSnippet(String text) {
        String s = normalizeSnippet(text);
        if (s.length() < 8) return false;
        if (JUNK_SNIPPET.matcher(s).find()) return false;
        if (s.indexOf('{') >= 0 || s.indexOf('[') >= 0) return false;
        return isChineseText(s);
    }

    private Document connect(String url) throws Exception {
        return Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.1")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .timeout(timeout)
                .execute()
                .parse();
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\u00a0', ' ').replaceAll("\\s+", " ").strip();
    }

    /**
     * 只保留以中文为主的标题/摘要：要求有足够汉字，并排除假名较多的日文。
     */
    static boolean isChineseText(String text) {
        if (text == null || text.isBlank()) return false;
        int han = 0;
        int kana = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN) {
                han++;
            } else if (script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA) {
                kana++;
            } else if (script == Character.UnicodeScript.LATIN) {
                latin++;
            }
        }
        if (kana >= 2) return false;          // 日文假名
        if (han < 4) return false;            // 英文/数字为主
        if (latin > han * 2) return false;    // 英文字母远多于汉字
        return true;
    }

    @Data
    public static class NewsItem {
        /** 完整标题，用于发送给模型 */
        private String title;
        /** 胶囊展示用正文（摘要优先） */
        private String content;

        public static NewsItem of(String title, String content) {
            NewsItem item = new NewsItem();
            item.setTitle(title);
            item.setContent(content);
            return item;
        }
    }
}
