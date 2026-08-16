package com.wechatai.tool.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class QueryStockTool {

    private static final String SINA_API = "https://hq.sinajs.cn/list=";
    private static final String REFERER = "https://finance.sina.com.cn";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern DATA_PATTERN = Pattern.compile("var hq_str_\\w+=\"([^\"]+)\"");

    private final HttpClient httpClient;

    public QueryStockTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Tool("查询A股/港股/美股实时行情。当用户询问股票价格、涨跌情况时使用。" +
            "参数支持：A股代码(如600519会自动识别为sh600519，或000001识别为sz000001)、" +
            "带市场前缀的代码(如sh600519, sz000001, hk00700)、美股代码(如AAPL)。" +
            "返回股票名称、现价、涨跌幅、今开、昨收、最高、最低等核心行情数据。")
    public String queryStock(
            @P("股票代码或名称。A股可只输数字代码(如600519)，" +
                    "也可带市场前缀(如sh600519)；港股加hk前缀(如hk00700)；美股直接输代码(如AAPL)")
            String code) {

        if (code == null || code.trim().isEmpty()) {
            return "请输入股票代码";
        }

        String normalizedCode = normalizeCode(code);
        log.info("[股票查询] 请求代码: {} (规范化后: {})", code, normalizedCode);

        try {
            String url = SINA_API + normalizedCode;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Referer", REFERER)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                log.warn("[股票查询] HTTP状态码: {}", response.statusCode());
                return "抱歉，查询股票 " + code + " 失败，HTTP状态码: " + response.statusCode();
            }

            String content = new String(response.body(), Charset.forName("GBK"));
            log.debug("[股票查询] 响应内容: {}", content);

            Matcher matcher = DATA_PATTERN.matcher(content);
            if (!matcher.find()) {
                log.warn("[股票查询] 未找到行情数据，响应: {}", content);
                return "抱歉，股票 " + code + " 暂无行情数据或代码有误";
            }

            String dataStr = matcher.group(1);
            String[] fields = dataStr.split(",");

            if (fields.length < 32) {
                log.warn("[股票查询] 数据字段不足，长度: {}", fields.length);
                return "抱歉，股票 " + code + " 数据不完整，请稍后重试";
            }

            String name = fields[0].trim();
            String open = fields[1].trim();
            String yesterdayClose = fields[2].trim();
            String currentPrice = fields[3].trim();
            String high = fields[4].trim();
            String low = fields[5].trim();
            String date = fields[30].trim();
            String time = fields[31].trim();

            double changePercent;
            try {
                double current = Double.parseDouble(currentPrice);
                double yesterday = Double.parseDouble(yesterdayClose);
                changePercent = (current - yesterday) / yesterday * 100;
            } catch (NumberFormatException e) {
                log.warn("[股票查询] 解析价格失败: {}", e.getMessage());
                return "抱歉，股票 " + code + " 价格数据解析失败";
            }

            String result = String.format("📈 %s（%s）\n" +
                            "现价：%s 元\n" +
                            "涨跌幅：%+.2f%%（昨收 %s）\n" +
                            "今开：%s｜最高：%s｜最低：%s\n" +
                            "更新时间：%s %s",
                    name, normalizedCode, currentPrice, changePercent, yesterdayClose,
                    open, high, low, date, time);

            log.info("[股票查询] 成功: {} = {}", normalizedCode, name);
            return result;

        } catch (Exception e) {
            log.warn("[股票查询] 查询失败 code={}, error={}", normalizedCode, e.getMessage());
            return "抱歉，查询股票 " + code + " 失败，请稍后重试";
        }
    }

    private String normalizeCode(String code) {
        code = code.trim().toLowerCase();
        if (code.startsWith("sh") || code.startsWith("sz") || code.startsWith("hk")) {
            return code;
        }
        if (code.matches("\\d+")) {
            if (code.startsWith("6")) return "sh" + code;
            if (code.startsWith("0") || code.startsWith("3")) return "sz" + code;
        }
        return code;
    }
}