package com.wechatai.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多语言翻译工具（READ）— 对接百度AI开放平台翻译 API。
 * <p>
 * 支持中、英、日、韩、法、德、西、俄等常见语言互译，自动检测源语言。
 * 用户提供语言名称或留空均可正确处理。
 */
@Component
public class TranslateTool {

    private static final Logger log = LoggerFactory.getLogger(TranslateTool.class);

    private static final String TRANSLATE_API = "https://fanyi-api.baidu.com/ait/api/aiTextTranslate";

    /**
     * 语言名称 → API 语言代码映射表
     */
    private static final Map<String, String> LANG_MAP = new LinkedHashMap<>();

    static {
        LANG_MAP.put("中文", "zh");
        LANG_MAP.put("英语", "en");
        LANG_MAP.put("英文", "en");
        LANG_MAP.put("日语", "jp");
        LANG_MAP.put("日文", "jp");
        LANG_MAP.put("韩语", "kor");
        LANG_MAP.put("韩文", "kor");
        LANG_MAP.put("法语", "fra");
        LANG_MAP.put("法文", "fra");
        LANG_MAP.put("德语", "de");
        LANG_MAP.put("德文", "de");
        LANG_MAP.put("西班牙语", "spa");
        LANG_MAP.put("西语", "spa");
        LANG_MAP.put("俄语", "ru");
        LANG_MAP.put("俄文", "ru");
        LANG_MAP.put("葡萄牙语", "pt");
        LANG_MAP.put("阿拉伯语", "ara");
        LANG_MAP.put("泰语", "th");
        LANG_MAP.put("越南语", "vie");
        LANG_MAP.put("意大利语", "it");
        LANG_MAP.put("荷兰语", "nl");
        // 语言代码直接传入的也保留
        LANG_MAP.put("zh", "zh");
        LANG_MAP.put("en", "en");
        LANG_MAP.put("jp", "jp");
        LANG_MAP.put("kor", "kor");
        LANG_MAP.put("fra", "fra");
        LANG_MAP.put("de", "de");
        LANG_MAP.put("spa", "spa");
        LANG_MAP.put("ru", "ru");
        LANG_MAP.put("pt", "pt");
        LANG_MAP.put("ara", "ara");
        LANG_MAP.put("th", "th");
        LANG_MAP.put("vie", "vie");
        LANG_MAP.put("it", "it");
        LANG_MAP.put("nl", "nl");
    }

    @Value("${translate.api-key}")
    private String apiKey;

    @Value("${translate.app-id}")
    private String appId;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TranslateTool(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = new ObjectMapper();
    }

    @Tool("多语言翻译工具，支持中文、英语、日语、韩语、法语、德语、西班牙语、俄语等常见语言互译。"
            + "重要规则：任何涉及翻译或语言转换的需求，你必须调用此工具完成，"
            + "不得使用自身语言能力直接回复翻译结果。你的自身语言能力仅用于理解和交流，不用于翻译。"
            + "触发场景："
            + "① 用户说「翻译xxx」「帮我翻译」「xxx用英语怎么说」「xxx是什么意思」「xxx翻译成xxx」"
            + "② 用户发了一段你看不懂的文字要你翻译——必须调用此工具"
            + "③ 用户说「把xxx翻译成xxx」或类似的表达——必须调用此工具"
            + "自动检测源语言，用户不需要告诉你是从什么语言翻译。"
            + "如果用户只说了内容没指定目标语言，默认翻译为中文。"
            + "如果用户说「翻译成xxx」，xxx为目标语言。"
            + "⚠️ 不触发翻译的场景：用户问你会不会某种语言、能不能用某种语言交流——此时直接回复，不调此工具。")
    public String translate(
            @P("需要翻译的文本内容") String text,
            @P("目标语言，如：中文、英语、日语、法语。不传则自动判断并翻译为最合适的语言")
            String targetLang) {

        if (text == null || text.trim().isEmpty()) {
            return "请提供需要翻译的内容";
        }

        // 转换目标语言代码
        String toCode = "zh";  // 默认翻译为中文
        if (targetLang != null && !targetLang.trim().isEmpty()) {
            String code = LANG_MAP.get(targetLang.trim());
            if (code != null) {
                toCode = code;
            } else {
                // 可能是用户直接传了语言代码
                toCode = targetLang.trim();
            }
        }

        try {
            // 构建请求体
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("appid", appId);
            requestBody.put("from", "auto");   // 自动检测源语言
            requestBody.put("to", toCode);
            requestBody.put("q", text);

            log.info("【翻译】text={}, from=auto, to={}", text, toCode);

            // 调用百度翻译 API
            String responseBody = restClient.post()
                    .uri(TRANSLATE_API)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            // 解析响应
            JsonNode root = objectMapper.readTree(responseBody);

            // 检查业务码
            int errorCode = root.path("error_code").asInt(0);
            if (errorCode != 0) {
                String errorMsg = root.path("error_msg").asText("未知错误");
                log.warn("【翻译】API 报错: code={}, msg={}", errorCode, errorMsg);
                return "翻译服务暂时不可用（" + errorMsg + "），请稍后重试";
            }

            // 提取翻译结果
            JsonNode transResult = root.path("trans_result");
            if (!transResult.isArray() || transResult.isEmpty()) {
                return "翻译服务返回异常，请稍后重试";
            }

            // 取第一条翻译结果
            JsonNode firstResult = transResult.get(0);
            String src = firstResult.path("src").asText("");
            String dst = firstResult.path("dst").asText("");

            if (dst.isEmpty()) {
                return "翻译结果为空，请检查输入内容";
            }

            // 获取源语言和目标语言代码（根级别）
            String srcLang = root.path("from").asText("");
            String dstLang = root.path("to").asText("");

            // 构建友好回复
            StringBuilder sb = new StringBuilder();
            String srcLangDisplay = LANG_MAP.entrySet().stream()
                    .filter(e -> e.getValue().equals(srcLang))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(srcLang);
            String dstLangDisplay = LANG_MAP.entrySet().stream()
                    .filter(e -> e.getValue().equals(dstLang))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(dstLang);

            sb.append("【").append(srcLangDisplay).append(" → ").append(dstLangDisplay).append("】\n");
            sb.append("原文：").append(src).append("\n");
            sb.append("译文：").append(dst);

            log.info("【翻译】完成: {} → {} ({}→{} lang)", src, dst, srcLang, dstLang);
            return sb.toString();

        } catch (Exception e) {
            log.error("【翻译】调用异常", e);
            return "翻译服务暂时不可用，请稍后重试";
        }
    }
}
