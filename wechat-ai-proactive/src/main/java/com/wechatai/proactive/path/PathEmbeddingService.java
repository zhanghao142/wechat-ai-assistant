package com.wechatai.proactive.path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatai.document.service.EmbeddingService;
import com.wechatai.document.service.QdrantService;
import com.wechatai.proactive.model.BehaviorPath;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/**
 * 行为路径 Embedding 与 Qdrant 存储服务。
 * <p>
 * 封装 {@link EmbeddingService} 生成向量，通过 Qdrant REST API 存储和检索行为路径。
 * 使用独立的 collection（behavior_paths），与文档 chunk 隔离。
 */
@Service
public class PathEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(PathEmbeddingService.class);

    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    private final String qdrantUrl;
    private final String qdrantApiKey;
    private final String collection;
    private final int vectorSize;

    private org.springframework.web.client.RestClient restClient;

    private final int connectTimeoutSec;
    private final int readTimeoutSec;

    public PathEmbeddingService(EmbeddingService embeddingService,
                                @Value("${wechat.qdrant.url}") String qdrantUrl,
                                @Value("${wechat.qdrant.api-key:}") String qdrantApiKey,
                                @Value("${wechat.proactive.qdrant.collection:behavior_paths}") String collection,
                                @Value("${wechat.qdrant.vector-size}") int vectorSize,
                                @Value("${wechat.qdrant.connect-timeout-sec:5}") int connectTimeoutSec,
                                @Value("${wechat.qdrant.read-timeout-sec:8}") int readTimeoutSec) {
        this.embeddingService = embeddingService;
        this.objectMapper = new ObjectMapper();
        this.qdrantUrl = qdrantUrl.endsWith("/") ? qdrantUrl.substring(0, qdrantUrl.length() - 1) : qdrantUrl;
        this.qdrantApiKey = qdrantApiKey;
        this.collection = collection;
        this.vectorSize = vectorSize;
        this.connectTimeoutSec = Math.max(2, connectTimeoutSec);
        this.readTimeoutSec = Math.max(2, readTimeoutSec);
    }

    @PostConstruct
    public void init() {
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSec))
                .build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSec));
        this.restClient = org.springframework.web.client.RestClient.builder().requestFactory(factory).build();
        ensureCollection();
        log.info("✅ PathEmbeddingService 初始化完成, collection={}, connect={}s read={}s",
                collection, connectTimeoutSec, readTimeoutSec);
    }

    /**
     * 将触发事件文本转为向量。
     */
    public List<Float> embed(String eventText) {
        return embeddingService.embed(eventText);
    }

    /**
     * 将行为路径索引到 Qdrant。
     */
    public void indexPath(BehaviorPath path, List<Float> embedding) {
        if (embedding == null || embedding.isEmpty()) return;

        try {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", UUID.randomUUID().toString());
            point.put("vector", embedding);
            point.put("payload", Map.of(
                    "pathId", path.getPathId(),
                    "userId", path.getUserId() != null ? path.getUserId() : "",
                    "triggerIntent", path.getTriggerIntent() != null ? path.getTriggerIntent() : "",
                    "triggerSummary", path.getTriggerSummary() != null ? path.getTriggerSummary() : "",
                    "alpha", path.getAlpha(),
                    "beta", path.getBeta(),
                    "useCount", path.getUseCount(),
                    "successCount", path.getSuccessCount()
            ));

            String body = objectMapper.writeValueAsString(Map.of("points", List.of(point)));
            int code = putJson("/collections/" + collection + "/points", body);
            if (code == 200) {
                log.debug("【路径索引】pathId={} 已写入 Qdrant", path.getPathId());
            } else {
                log.warn("【路径索引】pathId={} 写入 Qdrant 返回 HTTP {}", path.getPathId(), code);
            }
        } catch (Exception e) {
            log.error("【路径索引】pathId={} 写入 Qdrant 失败: {}", path.getPathId(), e.getMessage());
        }
    }

    /**
     * 更新路径的 payload（如置信度变化时同步 Qdrant）。
     */
    public void updatePathPayload(String pathId, int alpha, int beta, int useCount) {
        try {
            Map<String, Object> payload = Map.of(
                    "alpha", alpha,
                    "beta", beta,
                    "useCount", useCount
            );
            Map<String, Object> body = Map.of(
                    "points", List.of(Map.of("id", pathId, "payload", payload))
            );
            String json = objectMapper.writeValueAsString(body);
            int code = putJson("/collections/" + collection + "/points", json);
            if (code != 200) {
                log.warn("【路径更新】pathId={} 更新 Qdrant 返回 HTTP {}", pathId, code);
            }
        } catch (Exception e) {
            log.warn("【路径更新】pathId={} 更新 Qdrant 失败: {}", pathId, e.getMessage());
        }
    }

    /**
     * 向量检索 Top-K 相似行为路径。
     */
    public List<PathMatch> search(List<Float> queryEmbedding, int topK, float minScore) {
        if (queryEmbedding == null || queryEmbedding.isEmpty()) return List.of();

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", queryEmbedding);
            body.put("limit", topK > 0 ? topK : 5);
            body.put("score_threshold", minScore);
            body.put("with_payload", true);

            String response = restClient.post()
                    .uri(qdrantUrl + "/collections/{collection}/points/search", collection)
                    .headers(h -> { if (qdrantApiKey != null && !qdrantApiKey.isBlank()) h.set("api-key", qdrantApiKey); })
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(String.class);

            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response);
            com.fasterxml.jackson.databind.JsonNode result = root.get("result");
            if (result == null || !result.isArray()) return List.of();

            List<PathMatch> matches = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode p : result) {
                com.fasterxml.jackson.databind.JsonNode payload = p.get("payload");
                if (payload == null) continue;

                PathMatch match = new PathMatch(
                        payload.has("pathId") ? payload.get("pathId").asText() : "",
                        payload.has("userId") ? payload.get("userId").asText() : "",
                        payload.has("triggerIntent") ? payload.get("triggerIntent").asText() : "",
                        payload.has("triggerSummary") ? payload.get("triggerSummary").asText() : "",
                        payload.has("alpha") ? payload.get("alpha").asInt() : 1,
                        payload.has("beta") ? payload.get("beta").asInt() : 1,
                        payload.has("useCount") ? payload.get("useCount").asInt() : 0,
                        payload.has("successCount") ? payload.get("successCount").asInt() : 0,
                        p.get("score").floatValue()
                );
                matches.add(match);
            }

            log.info("【路径检索】命中 {} 条路径", matches.size());
            return matches;
        } catch (Exception e) {
            log.warn("【路径检索】失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 删除指定路径 */
    public void deletePath(String pathId) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("filter", Map.of("must", List.of(
                    Map.of("key", "pathId", "match", Map.of("value", pathId))))));
            postJson("/collections/" + collection + "/points/delete", body);
        } catch (Exception e) {
            log.warn("【路径删除】pathId={} 失败: {}", pathId, e.getMessage());
        }
    }

    // ── 内部 ──

    private void ensureCollection() {
        try {
            restClient.get()
                    .uri(qdrantUrl + "/collections/{collection}", collection)
                    .headers(h -> { if (qdrantApiKey != null && !qdrantApiKey.isBlank()) h.set("api-key", qdrantApiKey); })
                    .retrieve().body(String.class);
            log.info("【Qdrant】collection 已存在: {}", collection);
        } catch (Exception ignored) {
            try {
                String body = objectMapper.writeValueAsString(
                        Map.of("vectors", Map.of("size", vectorSize, "distance", "Cosine")));
                int code = putJson("/collections/" + collection, body);
                // 409 = 已存在（GET 偶发失败/超时后误走创建）
                if (code == 200) {
                    log.info("【Qdrant】创建 collection: {}, size={}", collection, vectorSize);
                } else if (code == 409) {
                    log.info("【Qdrant】collection 已存在 (HTTP 409): {}", collection);
                } else {
                    throw new RuntimeException("HTTP " + code);
                }
            } catch (Exception e) {
                // 不阻断启动：路径检索运行时本就会降级
                log.warn("【Qdrant】ensure collection {} 失败，路径检索将降级: {}",
                        collection, e.getMessage());
                return;
            }
        }
        createPayloadIndex("pathId");
        createPayloadIndex("triggerIntent");
    }

    private void createPayloadIndex(String fieldName) {
        try {
            String body = objectMapper.writeValueAsString(
                    Map.of("field_name", fieldName, "field_schema", "keyword"));
            int code = putJson("/collections/" + collection + "/index", body);
            if (code == 200 || code == 400) {
                log.debug("【Qdrant】payload 索引 {}: HTTP {}", fieldName, code);
            }
        } catch (Exception ignored) {}
    }

    private int putJson(String path, String json) throws Exception {
        return sendJson("PUT", path, json);
    }

    private int postJson(String path, String json) throws Exception {
        return sendJson("POST", path, json);
    }

    private int sendJson(String method, String path, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(qdrantUrl + path).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Content-Type", "application/json");
        if (qdrantApiKey != null && !qdrantApiKey.isBlank()) {
            conn.setRequestProperty("api-key", qdrantApiKey);
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return conn.getResponseCode();
    }

    // ── 检索匹配结果 ──

    /**
     * Qdrant 检索匹配结果。
     */
    public record PathMatch(String pathId, String userId, String triggerIntent,
                            String triggerSummary, int alpha, int beta,
                            int useCount, int successCount, float score) {

        public double getConfidence() {
            return (double) alpha / (alpha + beta);
        }
    }
}
