package com.wechatai.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Embedding 服务 — 调用 DashScope text-embedding-v3 原生 API 生成向量。
 * <p>
 * API 文档：https://help.aliyun.com/zh/model-studio/text-embedding-api
 * HTTP 客户端模式与 {@code LlmService} 一致：Spring RestClient + JDK HttpClient。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    private RestClient restClient;

    public EmbeddingService(@Value("${wechat.ai.embedding.api-key}") String apiKey,
                            @Value("${wechat.ai.embedding.base-url}") String baseUrl,
                            @Value("${wechat.ai.embedding.model}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @PostConstruct
    public void init() {
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
        log.info("✅ EmbeddingService 初始化完成, model={}", model);
    }

    /**
     * 将单条查询文本转为向量（text_type=query）。
     */
    public List<Float> embed(String text) {
        List<List<Float>> batch = doEmbed(List.of(text), "query");
        return batch.isEmpty() ? List.of() : batch.get(0);
    }

    /**
     * 批量生成向量（text_type=document，用于文档 chunk 索引）。
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        return doEmbed(texts, "document");
    }

    /**
     * 调 DashScope 原生 Embedding API。
     * <p>
     * 请求格式：{@code {model, input: {texts: [...]}, parameters: {text_type: "query"|"document"}}}
     * 响应格式：{@code {output: {embeddings: [{embedding: [...], text_index: 0}]}}}
     */
    private List<List<Float>> doEmbed(List<String> texts, String textType) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", Map.of("texts", texts));
        body.put("parameters", Map.of("text_type", textType));

        try {
            String responseBody = restClient.post()
                    .uri(baseUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embeddings = root.path("output").path("embeddings");
            if (!embeddings.isArray()) {
                log.warn("【Embedding】响应无 output.embeddings: {}", responseBody);
                return List.of();
            }

            List<List<Float>> results = new ArrayList<>();
            for (JsonNode item : embeddings) {
                JsonNode vec = item.path("embedding");
                if (vec.isArray()) {
                    List<Float> vector = new ArrayList<>();
                    for (JsonNode val : vec) {
                        vector.add(val.floatValue());
                    }
                    results.add(vector);
                }
            }

            log.debug("【Embedding】{} 条 {} 文本 → {} 个向量, 维度={}",
                    texts.size(), textType, results.size(),
                    results.isEmpty() ? 0 : results.get(0).size());
            return results;
        } catch (Exception e) {
            log.error("【Embedding】调用失败: {}", e.getMessage());
            throw new RuntimeException("Embedding 调用失败", e);
        }
    }
}
