package com.wechatai.ai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatai.tool.recognition.ImageRecognitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 多模态 LLM 封装 — 调用 Qwen-VL 识别图片内容。
 * <p>
 * 接收图片 byte[]，返回图片的中文文字描述。
 * 与 LlmService（调 DeepSeek）使用相同的 RestClient 模式，
 * 不增加任何额外依赖。
 */
@Component
public class QwenVLService implements ImageRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(QwenVLService.class);

    /** 调 Qwen-VL 的 prompt：通用识别，兼顾文字提取与场景描述 */
    private static final String PROMPT = """
            分析这张图片，输出以下内容：

            1. 【文字】如果图片中有文字，完整提取出来（这是最优先的）。没有文字则写"无"。
            2. 【描述】用一句话概括这张图片的内容：有什么物体/人物，在什么场景，在做什么。
            3. 【类型】图片属于：照片/截图/表情包/二维码/文档/其他

            注意：
            - 文字提取要准确，不要遗漏关键信息
            - 如果图片是表情包或梗图，说明文字内容和表达的情绪
            - 如果图片是二维码/码类，注明"包含码"即可，不需要解码
            - 整体控制在 100 字以内
            """;

    @Value("${qwen-vl.api-key}")
    private String apiKey;

    @Value("${qwen-vl.base-url}")
    private String baseUrl;

    @Value("${qwen-vl.model:qwen-vl-plus}")
    private String modelName;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public QwenVLService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 使用默认 prompt 识别图片内容。
     */
    @Override
    public String recognize(byte[] imageBytes) {
        return recognize(imageBytes, PROMPT);
    }

    /**
     * 使用自定义 prompt 识别图片内容。
     */
    @Override
    public String recognize(byte[] imageBytes, String customPrompt) {
        long start = System.currentTimeMillis();
        String prompt = customPrompt != null && !customPrompt.isBlank() ? customPrompt : PROMPT;
        try {
            // ① 图片转 base64
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            // ② 构建多模态消息体（text + image_url）
            Map<String, Object> requestBody = Map.of(
                    "model", modelName,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of("type", "text", "text", prompt),
                                    Map.of("type", "image_url",
                                            "image_url", Map.of("url", "data:image/jpeg;base64," + base64))
                            )
                    )),
                    "max_tokens", 300
            );

            // ③ 调 Qwen-VL API
            String responseBody = restClient.post()
                    .uri(baseUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            // ④ 解析返回结果
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices").get(0)
                    .path("message").path("content").asText("");

            long elapsed = System.currentTimeMillis() - start;
            log.info("【Qwen-VL】识别完成 ({}ms): {}", elapsed, content);

            return content != null && !content.isEmpty() ? content.trim() : "[图片内容无法识别]";

        } catch (Exception e) {
            log.error("【Qwen-VL】识别失败", e);
            return "[图片识别服务暂时不可用]";
        }
    }
}
