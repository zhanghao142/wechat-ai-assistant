package com.wechatai.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 快递100查询工具 — 使用快递100官方API查询物流。
 * <p>
 * 提供物流轨迹查询、快递公司识别、按手机号查询快递等能力。
 * 未配置 API Key 时使用免费查询接口。
 */
@Slf4j
@Component
public class Kuaidi100Tool {

    /** 快递100查询API（免费版，无需API Key） */
    private static final String QUERY_URL = "https://www.kuaidi100.com/query";

    @Value("${kuaidi100.api-key:${KUAIDI100_API_KEY:}}")
    private String apiKey;

    @Value("${kuaidi100.enabled:true}")
    private boolean enabled;

    @Value("${kuaidi100.timeout-seconds:10}")
    private int timeoutSeconds;

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private RestClient restClient;

    /** 快递公司编码映射 */
    private static final java.util.Map<String, String> COMPANY_MAP = new java.util.HashMap<>();
    static {
        COMPANY_MAP.put("圆通", "yto");
        COMPANY_MAP.put("圆通速递", "yto");
        COMPANY_MAP.put("yto", "yto");
        COMPANY_MAP.put("中通", "zto");
        COMPANY_MAP.put("中通快递", "zto");
        COMPANY_MAP.put("zto", "zto");
        COMPANY_MAP.put("申通", "sto");
        COMPANY_MAP.put("申通快递", "sto");
        COMPANY_MAP.put("sto", "sto");
        COMPANY_MAP.put("韵达", "yda");
        COMPANY_MAP.put("韵达快递", "yda");
        COMPANY_MAP.put("yda", "yda");
        COMPANY_MAP.put("顺丰", "sf");
        COMPANY_MAP.put("顺丰速运", "sf");
        COMPANY_MAP.put("sf", "sf");
        COMPANY_MAP.put("百世", "huitongkuaidi");
        COMPANY_MAP.put("百世快递", "huitongkuaidi");
        COMPANY_MAP.put("huitongkuaidi", "huitongkuaidi");
        COMPANY_MAP.put("邮政", "youzhengguonei");
        COMPANY_MAP.put("邮政EMS", "youzhengguonei");
        COMPANY_MAP.put("youzhengguonei", "youzhengguonei");
        COMPANY_MAP.put("极兔", "jtexpress");
        COMPANY_MAP.put("极兔速递", "jtexpress");
        COMPANY_MAP.put("jtexpress", "jtexpress");
        COMPANY_MAP.put("德邦", "debangwuliu");
        COMPANY_MAP.put("德邦快递", "debangwuliu");
        COMPANY_MAP.put("debangwuliu", "debangwuliu");
    }

    public Kuaidi100Tool(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        this.restClient = restClientBuilder.build();
        String mode = (apiKey == null || apiKey.isBlank()) ? "免费查询" : "API Key";
        log.info("📦 快递100工具已加载（模式：{}，超时：{}s）", mode, timeoutSeconds);
    }

    /**
     * 智能识别快递公司
     */
    @Tool("根据快递单号前缀智能识别快递公司名称和编码。当用户不知道快递单号是哪家公司时使用。")
    public String autoNumber(
            @P("快递单号，如 SF1234567890、YT8891309913608、ZTO731234567890") String num) {

        if (!checkEnabled()) return disabledMessage();
        if (num == null || num.trim().isEmpty()) {
            return "❌ 请提供快递单号，例如 SF1234567890";
        }

        String trackingNo = num.trim().toUpperCase();
        log.info("【快递100】识别快递公司: {}", trackingNo);

        // 根据单号前缀判断快递公司
        String company = detectCompanyByNumber(trackingNo);
        return company;
    }

    /**
     * 查询物流轨迹
     */
    @Tool("查询快递物流轨迹，返回包含最新状态和完整物流时间线的中文描述。当用户想知道快递到哪了、物流状态时使用。")
    public String queryTrace(
            @P("快递单号，如 SF1234567890、YT8891309913608") String num,
            @P("快递公司名称或编码，如 圆通、中通、顺丰、yto、zto。如果不确定可不填，系统自动识别") String com,
            @P("收件人手机号后四位（顺丰/中通必填，其他可选）") String phone) {

        if (!checkEnabled()) return disabledMessage();
        if (num == null || num.trim().isEmpty()) {
            return "❌ 请提供快递单号，例如 SF1234567890";
        }

        String trackingNo = num.trim();
        
        // 确定快递公司编码
        String type;
        String companyName;
        if (com != null && !com.trim().isEmpty()) {
            type = getCompanyCode(com.trim());
            companyName = getCompanyNameByCode(type);
        } else {
            // 自动识别
            String detected = detectCompanyByNumber(trackingNo);
            if (detected.contains("未识别")) {
                return "⚠️ 无法自动识别该单号的快递公司，请告知是哪家快递公司。";
            }
            // 从识别结果中提取编码
            type = extractCodeFromDetection(detected);
            companyName = getCompanyNameByCode(type);
        }

        // 顺丰/中通需要手机号后四位
        boolean needPhone = "sf".equals(type) || "zto".equals(type) 
                || "zhongtong".equals(type) || "shunfeng".equals(type);
        if (needPhone && (phone == null || phone.trim().isEmpty() || phone.length() < 4)) {
            return "📱 查询" + companyName + "快递需要手机号后四位验证，请提供收件人的手机号后4位。";
        }

        try {
            // 构建查询参数
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append(QUERY_URL)
                    .append("?type=").append(type)
                    .append("&postid=").append(java.net.URLEncoder.encode(trackingNo, "UTF-8"));

            // 顺丰/中通加手机号
            if (phone != null && !phone.trim().isEmpty()) {
                urlBuilder.append("&phone=").append(java.net.URLEncoder.encode(phone.trim(), "UTF-8"));
            }

            String url = urlBuilder.toString();
            log.info("【快递100】查询轨迹: type={}, num={}, phone={}", type, trackingNo, phone);

            String response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            log.debug("【快递100】原始响应: {}", truncate(response));

            // 解析并格式化响应
            return formatTraceResponse(response, trackingNo, companyName);

        } catch (Exception e) {
            log.error("【快递100】查询轨迹异常", e);
            return "❌ 查询失败：" + e.getMessage() + "\n请稍后重试或拨打" + getSupportPhone(type) + "客服电话查询。";
        }
    }

    /**
     * 根据手机号查询快递（手机号查询所有关联的快递）
     */
    @Tool("根据手机号查询该手机号关联的所有快递信息。当用户问'我的快递到哪了'、'查询这个电话的快递'时使用。")
    public String queryByPhone(
            @P("收件人完整手机号，如 13812345678") String phone) {

        if (!checkEnabled()) return disabledMessage();
        if (phone == null || phone.trim().isEmpty()) {
            return "❌ 请提供完整的11位手机号，例如 13812345678";
        }

        String phoneNumber = phone.trim();
        if (phoneNumber.length() < 11) {
            return "❌ 请提供完整的11位手机号";
        }

        // 提取后4位作为验证
        String last4Digits = phoneNumber.substring(phoneNumber.length() - 4);
        
        log.info("【快递100】按手机号查询: phone={}", phoneNumber.substring(0, 3) + "****" + last4Digits);

        StringBuilder result = new StringBuilder();
        result.append("📋 手机号 ").append(phoneNumber.substring(0, 3)).append("****").append(last4Digits)
              .append(" 关联的快递查询说明：\n\n");
        result.append("由于安全原因，无法直接通过手机号查询所有快递。\n");
        result.append("请提供具体的**快递单号**，我可以帮你查询：\n\n");
        result.append("🔍 顺丰：需提供手机号后4位验证\n");
        result.append("🔍 中通：需提供手机号后4位验证\n");
        result.append("🔍 其他快递：直接提供单号即可查询\n\n");
        result.append("💡 提示：你可以在快递短信、收件记录中找到快递单号。");

        return result.toString();
    }

    /**
     * 估算运费（基于经验值）
     */
    @Tool("根据寄收地址估算快递运费。当用户问寄快递多少钱、运费多少时使用。")
    public String estimatePrice(
            @P("寄件人地址或城市，如 上海市浦东新区") String from,
            @P("收件人地址或城市，如 北京市朝阳区") String to,
            @P("物品重量（kg），如 1.0") String weight,
            @P("物品类型，如 文件、日用品、电子产品") String type) {

        if (!checkEnabled()) return disabledMessage();
        if (from == null || from.trim().isEmpty() || to == null || to.trim().isEmpty()) {
            return "❌ 请提供寄件地址和收件地址";
        }

        double w = 1.0;
        try {
            if (weight != null && !weight.trim().isEmpty()) {
                w = Double.parseDouble(weight);
            }
        } catch (NumberFormatException ignored) {}

        // 基于距离和重量的简单估算
        double distanceFactor = estimateDistance(from.trim(), to.trim());
        double basePrice = 8.0 + w * 5.0 * distanceFactor;
        double minPrice = 8.0;
        double maxPrice = Math.min(basePrice * 2, 200.0);

        StringBuilder result = new StringBuilder();
        result.append("💰 快递运费预估\n");
        result.append("──────────────\n");
        result.append("📦 寄件：").append(from.trim()).append("\n");
        result.append("🏠 收件：").append(to.trim()).append("\n");
        result.append("⚖️ 重量：").append(w).append("kg");
        if (type != null && !type.trim().isEmpty()) {
            result.append("\n📋 类型：").append(type.trim());
        }
        result.append("\n──────────────\n");
        result.append("💰 预估费用：¥").append(String.format("%.1f", minPrice)).append(" - ¥").append(String.format("%.1f", maxPrice)).append("\n");
        result.append("⏱️ 预计时效：").append(estimateDays(distanceFactor)).append("\n\n");
        result.append("📌 快递公司参考价：\n");
        result.append("  • 圆通/中通/韵达：¥").append(String.format("%.1f", basePrice * 0.9)).append("\n");
        result.append("  • 顺丰：¥").append(String.format("%.1f", basePrice * 1.3)).append("\n");
        result.append("  • 邮政EMS：¥").append(String.format("%.1f", basePrice * 1.1)).append("\n");
        result.append("\n⚠️ 实际价格以快递公司报价为准");

        log.info("【快递100】运费估算: {} → {}, {}kg", from, to, w);
        return result.toString();
    }

    // ========================================================================
    // 内部辅助方法
    // ========================================================================

    /**
     * 根据单号前缀识别快递公司
     */
    private String detectCompanyByNumber(String trackingNo) {
        // 常见前缀映射
        if (trackingNo.startsWith("SF") || trackingNo.matches("SF\\d{10,}")) {
            return "✅ 识别结果：\n快递公司：**顺丰速运**\n编码：sf\n客服电话：95338\n\n查询时需要提供收件人手机号后4位验证。";
        }
        if (trackingNo.startsWith("YT") || trackingNo.startsWith("YTK")) {
            return "✅ 识别结果：\n快递公司：**圆通速递**\n编码：yto\n客服电话：95554\n\n可直接查询物流轨迹。";
        }
        if (trackingNo.startsWith("ZTO") || trackingNo.matches("ZTO\\d{10,}") 
                || (trackingNo.length() == 12 && trackingNo.startsWith("73"))) {
            return "✅ 识别结果：\n快递公司：**中通快递**\n编码：zto\n客服电话：95311\n\n查询时需要提供收件人手机号后4位验证。";
        }
        if (trackingNo.startsWith("STO") || (trackingNo.length() == 12 && trackingNo.startsWith("77"))) {
            return "✅ 识别结果：\n快递公司：**申通快递**\n编码：sto\n客服电话：95543\n\n可直接查询物流轨迹。";
        }
        if (trackingNo.startsWith("YDA") || (trackingNo.length() == 13 && trackingNo.startsWith("31"))) {
            return "✅ 识别结果：\n快递公司：**韵达快递**\n编码：yda\n客服电话：95546\n\n可直接查询物流轨迹。";
        }
        if (trackingNo.startsWith("JT") || trackingNo.startsWith("J&T")) {
            return "✅ 识别结果：\n快递公司：**极兔速递**\n编码：jtexpress\n客服电话：4008201666\n\n可直接查询物流轨迹。";
        }
        if (trackingNo.startsWith("YTO")) {
            return "✅ 识别结果：\n快递公司：**圆通速递**\n编码：yto\n客服电话：95554\n\n可直接查询物流轨迹。";
        }
        if (trackingNo.startsWith("ZJS") || trackingNo.matches("ZJS\\d{10,}")) {
            return "✅ 识别结果：\n快递公司：**宅急送**\n编码：zhaijisong\n客服电话：4006789000\n\n可直接查询物流轨迹。";
        }
        
        // 未能识别，尝试用 auto 类型查询
        return "⚠️ 无法根据单号前缀自动识别快递公司。\n建议：\n1. 查看快递面单或短信中的快递公司名称\n2. 直接告诉我快递公司名称（如圆通、中通、顺丰）\n3. 或拨打客服电话：\n   • 顺丰：95338\n   • 圆通：95554\n   • 中通：95311\n   • 申通：95543\n   • 韵达：95546";
    }

    /**
     * 从识别结果中提取编码
     */
    private String extractCodeFromDetection(String detection) {
        for (java.util.Map.Entry<String, String> entry : COMPANY_MAP.entrySet()) {
            if (detection.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "auto";
    }

    /**
     * 获取快递公司编码
     */
    private String getCompanyCode(String company) {
        if (company == null) return "auto";
        String c = company.trim().toLowerCase();
        for (java.util.Map.Entry<String, String> entry : COMPANY_MAP.entrySet()) {
            if (c.contains(entry.getKey().toLowerCase())) {
                return entry.getValue();
            }
        }
        // 直接匹配编码
        if (COMPANY_MAP.containsValue(c)) {
            return c;
        }
        return "auto";
    }

    /**
     * 根据编码获取公司名称
     */
    private String getCompanyNameByCode(String code) {
        for (java.util.Map.Entry<String, String> entry : COMPANY_MAP.entrySet()) {
            if (code.equals(entry.getValue()) && !entry.getKey().equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return code;
    }

    /**
     * 获取客服电话
     */
    private String getSupportPhone(String type) {
        switch (type) {
            case "sf": case "shunfeng": case "顺丰": return "95338";
            case "yto": case "圆通": return "95554";
            case "zto": case "zhongtong": case "中通": return "95311";
            case "sto": case "申通": return "95543";
            case "yda": case "韵达": return "95546";
            case "jtexpress": case "极兔": return "4008201666";
            case "huitongkuaidi": case "百世": return "4009956789";
            case "youzhengguonei": case "邮政": return "11183";
            case "debangwuliu": case "德邦": return "95353";
            default: return "11183";
        }
    }

    /**
     * 格式化轨迹响应
     */
    private String formatTraceResponse(String json, String trackingNo, String companyName) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String status = root.path("status").asText();
            String message = root.path("message").asText();
            JsonNode data = root.path("data");

            // 检查状态码
            if (!"200".equals(status)) {
                return "❌ 查询失败：" + message + "\n\n" +
                       "可能原因：\n" +
                       "1. 快递单号不正确\n" +
                       "2. 该单号暂无物流信息（可能还未发货）\n" +
                       "3. 快递公司系统繁忙\n\n" +
                       "建议：\n" +
                       "• 请确认单号是否正确\n" +
                       "• 联系" + companyName + "客服：" + getSupportPhone(getCompanyCode(companyName));
            }

            if (data == null || !data.isArray() || data.isEmpty()) {
                return "📦 【" + companyName + "】运单号: " + trackingNo + "\n\n" +
                       "⚠️ 暂无物流信息\n\n" +
                       "可能原因：\n" +
                       "• 包裹还未发货\n" +
                       "• 快递公司尚未揽收\n\n" +
                       "请耐心等待，通常发货后24小时内会有物流更新。";
            }

            // 格式化物流轨迹
            StringBuilder result = new StringBuilder();
            result.append("📦 【").append(companyName).append("】运单号: ").append(trackingNo).append("\n\n");

            JsonNode latest = data.get(0);
            String latestStatus = latest.path("status").asText();
            String latestTime = latest.path("time").asText();
            String latestDesc = latest.path("desc").asText();

            // 最新状态
            result.append(getStatusEmoji(latestStatus)).append(" **").append(latestDesc).append("**\n");
            result.append("   🕐 ").append(latestTime).append("\n\n");

            // 历史轨迹（只显示前5条）
            int count = Math.min(data.size(), 5);
            for (int i = 1; i < count; i++) {
                JsonNode node = data.get(i);
                String time = node.path("time").asText();
                String desc = node.path("desc").asText();
                result.append("───\n");
                result.append("• ").append(desc).append("\n");
                result.append("   🕐 ").append(time).append("\n");
            }

            if (data.size() > count) {
                result.append("\n📌 共 ").append(data.size()).append(" 条物流记录，已显示最新 ").append(count).append(" 条");
            }

            return result.toString();

        } catch (Exception e) {
            log.error("【快递100】解析响应异常", e);
            return "❌ 数据解析失败，请稍后重试。\n\n" +
                   "原始返回：" + truncate(json);
        }
    }

    /**
     * 获取状态对应的 emoji
     */
    private String getStatusEmoji(String status) {
        if (status == null) return "📬";
        switch (status.toLowerCase()) {
            case "已签收": case "signed": case "delivered": return "✅";
            case "派送中": case "delivering": case "out_for_delivery": return "🚚";
            case "运输中": case "transit": case "in_transit": return "🚛";
            case "已到达": case "arrived": return "📍";
            case "已揽收": case "picked_up": case "accepted": return "📥";
            case "已下单": case "ordered": case "pending": return "📋";
            default: return "📬";
        }
    }

    /**
     * 估算距离因子（简化版）
     */
    private double estimateDistance(String from, String to) {
        if (from.equals(to)) return 1.0; // 同城
        // 东西部粗略判断
        String[] eastCities = {"上海", "江苏", "浙江", "福建", "山东", "安徽", "江西", "辽宁", "河北", "天津", "北京"};
        String[] westCities = {"新疆", "西藏", "青海", "甘肃", "宁夏", "内蒙古", "云南", "贵州", "四川", "广西", "海南"};
        
        boolean fromEast = false, toEast = false;
        for (String city : eastCities) {
            if (from.contains(city)) fromEast = true;
            if (to.contains(city)) toEast = true;
        }
        for (String city : westCities) {
            if (from.contains(city)) fromEast = false;
            if (to.contains(city)) toEast = false;
        }
        
        if (fromEast == toEast) return 1.5; // 同区域
        return 2.5; // 跨区域
    }

    /**
     * 估算天数
     */
    private String estimateDays(double distanceFactor) {
        if (distanceFactor <= 1.0) return "1天内";
        if (distanceFactor <= 1.5) return "1-2天";
        if (distanceFactor <= 2.0) return "2-3天";
        return "3-5天";
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    private boolean checkEnabled() {
        if (!enabled) {
            log.warn("【快递100】功能已禁用 (kuaidi100.enabled=false)");
            return false;
        }
        return true;
    }

    private String disabledMessage() {
        return "❌ 快递查询功能已禁用，请联系管理员开启。";
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
