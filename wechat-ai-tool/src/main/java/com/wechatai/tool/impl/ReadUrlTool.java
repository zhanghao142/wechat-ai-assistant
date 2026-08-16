package com.wechatai.tool.impl;

import com.wechatai.tool.node.NodeScriptRunner;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页内容读取工具 — 抓取指定 URL 的正文内容，供 LLM 阅读总结。
 * <p>
 * 联网搜索优先走 Node 脚本 {@code scripts/web-search.js}，失败再回退 Java 爬取。
 */
@Component
public class ReadUrlTool {

    private static final Logger log = LoggerFactory.getLogger(ReadUrlTool.class);

    /** 百度结果块：tpl=... mu="真实URL" ... <h3>...<a>标题</a> */
    private static final Pattern BAIDU_MU_BLOCK = Pattern.compile(
            "mu=\"(https?://[^\"]+)\"[\\s\\S]{0,3000}?<h3[\\s\\S]*?<a[\\s\\S]*?>([\\s\\S]*?)</a>",
            Pattern.CASE_INSENSITIVE);

    private final NodeScriptRunner nodeScriptRunner;

    @Value("${url-read.timeout:15000}")
    private int timeout;

    @Value("${url-read.max-length:8000}")
    private int maxLength;

    public ReadUrlTool(NodeScriptRunner nodeScriptRunner) {
        this.nodeScriptRunner = nodeScriptRunner;
    }

    @Tool("读取指定URL链接的网页正文内容并返回可读文本。当用户分享链接或询问某个网页内容时使用。" +
           "url 必须来自 searchInternet 返回的「链接」字段，禁止编造或猜测网址")
    public String readUrl(
            @P("完整的URL地址，必须以http://或https://开头，且必须来自 searchInternet 结果") String url) {

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "错误：请输入以 http:// 或 https:// 开头的有效网址";
        }

        try {
            Document doc = connect(url);

            String title = doc.title();
            String body = extractText(doc);

            if (body.length() > maxLength) {
                body = body.substring(0, maxLength)
                        + "\n\n...（以下内容已截断，全文共 " + body.length() + " 字）";
            }

            return "标题: " + title + "\n\n正文:\n" + body;

        } catch (Exception e) {
            return "无法读取该网页: " + e.getMessage();
        }
    }

    @Tool("搜索互联网获取最新信息。当用户需要实时信息或你不确定答案时使用。" +
           "返回标题/链接/摘要；后续 readUrl 或浏览器 navigate 只能使用这里返回的真实链接，禁止编造 URL。" +
           "仅明确的实物网购查询会附加京东/淘宝等站内限定；机票、住宿、旅游等价格查询不会改写。")
    public String searchInternet(
            @P("搜索关键词，简明扼要的中文关键词。实物网购可带商品名") String query) {

        String enhancedQuery = enhanceShoppingQuery(query);

        // 1) 优先 Node 脚本（反爬表现通常优于 Jsoup）
        Optional<String> fromNode = nodeScriptRunner.run(
                "web-search.js",
                List.of("--limit", "8", enhancedQuery));
        if (fromNode.isPresent()) {
            String text = fromNode.get();
            if (text.contains("链接:") || text.contains("链接：")) {
                if (!query.equals(enhancedQuery) && text.startsWith("搜索结果: ")) {
                    text = text.replaceFirst(
                            Pattern.quote("搜索结果: " + enhancedQuery),
                            "搜索结果: " + query + "（已自动优化为电商比价搜索）");
                }
                return truncateSearchResult(text);
            }
            log.warn("【searchInternet】Node 结果异常，回退 Java 爬取");
        }

        // 2) 回退：百度 + 必应 Jsoup
        try {
            Map<String, SearchHit> merged = new LinkedHashMap<>();
            for (SearchHit hit : searchBaidu(enhancedQuery, 10)) {
                merged.putIfAbsent(hit.url(), hit);
            }
            for (SearchHit hit : searchBing(enhancedQuery, 8)) {
                merged.putIfAbsent(hit.url(), hit);
            }

            List<SearchHit> hits = filterJunk(new ArrayList<>(merged.values()));
            hits = preferRelevant(hits, query);

            if (hits.isEmpty()) {
                return "未找到相关结果，请尝试更换搜索关键词: " + query;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("搜索结果: ").append(query);
            if (!query.equals(enhancedQuery)) {
                sb.append("（已自动优化为电商比价搜索）");
            }
            sb.append("\n\n");
            sb.append("【重要】下面「链接」均为真实 URL。后续 readUrl / navigate 只能使用这些链接，禁止编造或拼接网址。\n\n");

            int count = 0;
            for (SearchHit hit : hits) {
                if (count >= 5) break;
                sb.append("标题: ").append(hit.title()).append("\n");
                sb.append("链接: ").append(hit.url()).append("\n");
                sb.append("摘要: ").append(hit.snippet()).append("\n\n");
                count++;
            }

            return truncateSearchResult(sb.toString());

        } catch (Exception e) {
            return "搜索失败: " + e.getMessage() + "，请稍后重试或更换关键词";
        }
    }

    private String truncateSearchResult(String result) {
        if (result.length() <= maxLength) {
            return result;
        }
        return result.substring(0, maxLength) + "\n\n...（以下结果已截断）";
    }

    /**
     * 百度网页搜索：Jsoup 选择器 + 正则兜底（反爬页结构变化时仍尽量抽出 mu 链接）。
     */
    private List<SearchHit> searchBaidu(String query, int limit) {
        List<SearchHit> hits = new ArrayList<>();
        try {
            String searchUrl = "https://www.baidu.com/s?ie=utf-8&wd="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&rn=" + Math.max(limit, 10);
            Document doc = connect(searchUrl);

            Map<String, SearchHit> byUrl = new LinkedHashMap<>();
            for (Element container : doc.select("div[mu], div.c-container[mu], div.result.c-container[mu]")) {
                String url = normalizeUrl(container.attr("mu"));
                if (url.isBlank() || byUrl.containsKey(url)) continue;

                Element titleEl = container.selectFirst("h3 a, h3");
                if (titleEl == null) continue;
                String title = cleanText(titleEl.text());
                if (title.length() < 4) continue;

                Element snippetEl = container.selectFirst(
                        ".c-abstract, .content-right_8Zsoc, span[class*=content-right], .c-span-last");
                String snippet = snippetEl != null ? cleanText(snippetEl.text()) : "";
                byUrl.put(url, new SearchHit(title, url, snippet));
            }

            // 正则兜底：选择器未命中时从原始 HTML 抽 mu+标题
            if (byUrl.size() < 2) {
                Matcher m = BAIDU_MU_BLOCK.matcher(doc.html());
                while (m.find() && byUrl.size() < limit) {
                    String url = normalizeUrl(m.group(1));
                    String title = cleanText(m.group(2).replaceAll("(?s)<!--.*?-->", "")
                            .replaceAll("<[^>]+>", ""));
                    if (url.isBlank() || title.length() < 4 || byUrl.containsKey(url)) continue;
                    byUrl.put(url, new SearchHit(title, url, ""));
                }
            }

            hits.addAll(byUrl.values());
            if (hits.size() > limit) {
                hits = new ArrayList<>(hits.subList(0, limit));
            }
            log.debug("【searchInternet】百度命中 {} 条 query={}", hits.size(),
                    query.length() > 40 ? query.substring(0, 40) + "..." : query);
        } catch (Exception e) {
            log.warn("【searchInternet】百度搜索失败: {}", e.getMessage());
        }
        return hits;
    }

    private List<SearchHit> searchBing(String query, int limit) {
        List<SearchHit> hits = new ArrayList<>();
        try {
            String searchUrl = "https://cn.bing.com/search?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&count=" + Math.max(limit, 8);
            Document doc = connect(searchUrl);

            Map<String, SearchHit> byUrl = new LinkedHashMap<>();
            for (Element result : doc.select("#b_results .b_algo")) {
                Element titleEl = result.selectFirst("h2 a");
                if (titleEl == null) continue;

                String title = cleanText(titleEl.text());
                String url = normalizeUrl(titleEl.attr("href"));
                if (title.isBlank() || url.isBlank() || byUrl.containsKey(url)) continue;

                Element snippetEl = result.selectFirst(".b_caption p, .b_lineclamp2, .b_lineclamp3");
                String snippet = snippetEl != null ? cleanText(snippetEl.text()) : "";
                byUrl.put(url, new SearchHit(title, url, snippet));
            }
            hits.addAll(byUrl.values());
            if (hits.size() > limit) {
                hits = new ArrayList<>(hits.subList(0, limit));
            }
            log.debug("【searchInternet】必应命中 {} 条", hits.size());
        } catch (Exception e) {
            log.warn("【searchInternet】必应搜索失败: {}", e.getMessage());
        }
        return hits;
    }

    private Document connect(String url) throws Exception {
        return Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Cache-Control", "no-cache")
                .header("Upgrade-Insecure-Requests", "1")
                .referrer("https://www.baidu.com/")
                .timeout(timeout)
                .followRedirects(true)
                .maxBodySize(2 * 1024 * 1024)
                .execute()
                .parse();
    }

    private static List<SearchHit> filterJunk(List<SearchHit> hits) {
        List<SearchHit> out = new ArrayList<>();
        for (SearchHit hit : hits) {
            if (!isJunkHit(hit)) {
                out.add(hit);
            }
        }
        return out;
    }

    /**
     * 有查询词命中时优先返回相关结果；若全部不相关则原样返回（避免误杀）。
     */
    private static List<SearchHit> preferRelevant(List<SearchHit> hits, String query) {
        if (hits.isEmpty() || query == null || query.isBlank()) return hits;
        List<String> tokens = extractTokens(query);
        if (tokens.isEmpty()) return hits;

        List<SearchHit> relevant = new ArrayList<>();
        for (SearchHit hit : hits) {
            String blob = ((hit.title() == null ? "" : hit.title()) + " "
                    + (hit.snippet() == null ? "" : hit.snippet()) + " "
                    + (hit.url() == null ? "" : hit.url())).toLowerCase(Locale.ROOT);
            for (String t : tokens) {
                if (blob.contains(t.toLowerCase(Locale.ROOT))) {
                    relevant.add(hit);
                    break;
                }
            }
        }
        return relevant.isEmpty() ? hits : relevant;
    }

    private static List<String> extractTokens(String query) {
        List<String> tokens = new ArrayList<>();
        // 连续中文 2+ 字
        Matcher cn = Pattern.compile("[\\u4e00-\\u9fff]{2,}").matcher(query);
        while (cn.find()) {
            String t = cn.group();
            if (t.length() > 8) {
                // 长词再切：保留整体 + 常见短核
                tokens.add(t);
            } else {
                tokens.add(t);
            }
        }
        // 英文词 3+
        Matcher en = Pattern.compile("[A-Za-z]{3,}").matcher(query);
        while (en.find()) {
            tokens.add(en.group());
        }
        // 去过宽停用
        tokens.removeIf(t -> t.equals("相关") || t.equals("数据") || t.equals("中国")
                || t.equals("网站") || t.equals("研究") || t.equals("site"));
        return tokens;
    }

    /**
     * 过滤门户首页 / 百度内部伪链 / 繁体政府网网关 / 与查询无关的百科国家首页等噪声。
     */
    private static boolean isJunkHit(SearchHit hit) {
        String url = hit.url() == null ? "" : hit.url().toLowerCase(Locale.ROOT);
        String title = hit.title() == null ? "" : hit.title();
        String decoded = url;
        try {
            decoded = URLDecoder.decode(url, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            // keep raw
        }

        if (url.isBlank() || url.startsWith("javascript:")) return true;
        if (url.contains("nourl.ubs.baidu.com")) return true;
        if (url.contains("recommend_list.baidu.com")) return true;
        if (url.contains("top.baidu.com/board")) return true;
        if (url.contains("fakeurl.baidu.com")) return true;

        // 繁体政府网 Big5 网关 / 纯门户 —— 反爬污染时几乎每条都是它
        if (url.contains("big5.www.gov.cn") || url.contains("/gate/big5/")) return true;
        if (title.contains("中國政府網") || title.contains("中央人民政府門戶")) return true;

        // 航运公司 CMA CGM 等与气象局无关的同名站点
        if (url.contains("cma-cgm.com")) return true;
        if (url.contains("travelchina.gov.cn") || title.toLowerCase(Locale.ROOT).contains("travel china")) {
            return true;
        }

        // 百科「中华人民共和国 / 中国」国家首页
        if (decoded.contains("baike.baidu.com/item/中华人民共和国")
                || url.contains("baike.baidu.com/item/%e4%b8%ad%e5%8d%8e%e4%ba%ba%e6%b0%91%e5%85%b1%e5%92%8c%e5%9b%bd")) {
            return true;
        }
        if (title.contains("中华人民共和国_百度百科")
                || title.equals("中国（世界四大文明古国之一）_百度百科")
                || title.startsWith("china（词语）_百度百科")
                || title.startsWith("第（汉语汉字）_百度百科")) {
            return true;
        }

        if (isBareHomepage(url)) return true;

        return false;
    }

    private static boolean isBareHomepage(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);

            // 政府网 / 门户的网关路径也视为首页噪声
            if (host.contains("gov.cn") && path != null
                    && (path.contains("/gate/") || path.equals("/") || path.isBlank()
                    || path.equals("/index.htm") || path.equals("/index.html"))) {
                return true;
            }

            if (path == null || path.isBlank() || "/".equals(path) || "/index.htm".equals(path)
                    || "/index.html".equals(path)) {
                return host.equals("www.gov.cn")
                        || host.equals("gov.cn")
                        || host.equals("www.china.com.cn")
                        || host.equals("www.china.com")
                        || host.equals("www.people.com.cn")
                        || host.equals("www.stats.gov.cn")
                        || host.equals("www.chnmuseum.cn")
                        || host.equals("www.cma.gov.cn")
                        || host.equals("www.ncc-cma.net")
                        || host.equals("ncc-cma.net");
            }
        } catch (Exception ignored) {
            // ignore
        }
        return false;
    }

    private static String normalizeUrl(String raw) {
        if (raw == null) return "";
        String u = raw.strip()
                .replace("&amp;", "&")
                .replace("&#38;", "&");
        if (u.startsWith("//")) {
            u = "https:" + u;
        }
        return u;
    }

    private static String cleanText(String s) {
        if (s == null) return "";
        return s.replace('\u00a0', ' ').replaceAll("\\s+", " ").strip();
    }

    /**
     * 仅对「实物电商购物」加站内限定。机票/高铁/住宿/景点等含「价格」的查询绝不能改写成京东淘宝，
     * 否则结果会变成百科首页或完全跑偏（日志里「已自动优化为电商比价搜索」即此误伤）。
     */
    private String enhanceShoppingQuery(String query) {
        if (query == null || query.isBlank()) return query;
        if (query.contains("site:")) return query;

        // 出行/服务价：不要电商站内搜
        if (query.matches(".*(机票|航班|高铁|动车|火车|车票|船票|住宿|民宿|客栈|酒店|青旅|旅舍|"
                + "门票|景区|景点|旅游|行程|攻略|租车|打车|滴滴|快递运费|挂号费).*")) {
            return query;
        }

        // 明确购物意图才加站内
        boolean hasShoppingIntent = query.matches(".*(购买|哪里买|在哪买|网购|下单|加购|比价|"
                + "京东|淘宝|天猫|苏宁|拼多多|售价|现价|原价|降价|打折|满减|促销).*")
                || (query.matches(".*(多少钱|报价|便宜|最低价|价钱|价格|性价比).*")
                && query.matches(".*(手机|电脑|耳机|键盘|显示器|显卡|平板|相机|家电|数码|"
                + "零食|奶粉|衣服|鞋子|包包|化妆品|面膜|洗衣液|路由器|充电器).*"));

        if (!hasShoppingIntent) return query;

        return query + " site:jd.com OR site:taobao.com OR site:suning.com";
    }

    private String extractText(Document doc) {
        Element article = doc.selectFirst("article");
        if (article != null) return article.text();

        Element main = doc.selectFirst(
                "main, .post-content, .article-content, #content, .content");
        if (main != null) return main.text();

        return doc.body().text();
    }

    private record SearchHit(String title, String url, String snippet) {}
}
