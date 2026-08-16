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

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/**
 * Qdrant 向量库服务 — REST API（搜索用 Spring RestClient，写入用原生 HttpURLConnection 避 JDK HttpClient 兼容问题）。
 * <p>
 * 隔离：每条 chunk payload 携带 sessionId，检索时 filter 限定。
 */
@Service
public class QdrantService {

    private static final Logger log = LoggerFactory.getLogger(QdrantService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String collection;
    private final int vectorSize;

    private RestClient restClient;
    private final int connectTimeoutSec;
    private final int readTimeoutSec;

    public QdrantService(@Value("${wechat.qdrant.url}") String url,
                         @Value("${wechat.qdrant.api-key:}") String apiKey,
                         @Value("${wechat.qdrant.collection}") String collection,
                         @Value("${wechat.qdrant.vector-size}") int vectorSize,
                         @Value("${wechat.qdrant.connect-timeout-sec:5}") int connectTimeoutSec,
                         @Value("${wechat.qdrant.read-timeout-sec:8}") int readTimeoutSec) {
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.apiKey = apiKey;
        this.collection = collection;
        this.vectorSize = vectorSize;
        this.connectTimeoutSec = Math.max(2, connectTimeoutSec);
        this.readTimeoutSec = Math.max(2, readTimeoutSec);
    }

    @PostConstruct
    public void init() {
        // 云端 Qdrant 偶发 Connection reset；超时宜短，失败即降级，避免拖垮整轮对话
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSec))
                .build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSec));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        ensureCollection();
        log.info("✅ QdrantService 初始化完成, collection={}, connect={}s read={}s",
                collection, connectTimeoutSec, readTimeoutSec);
    }

    public void indexChunks(String fileId, String sessionId, String userId,
                            String fileName, List<String> chunks,
                            List<List<Float>> embeddings) {
        if (chunks.isEmpty() || embeddings.isEmpty()) return;

        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < chunks.size() && i < embeddings.size(); i++) {
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("id", UUID.randomUUID().toString());
            pt.put("vector", embeddings.get(i));
            pt.put("payload", Map.of(
                    "userId", userId, "sessionId", sessionId,
                    "fileId", fileId, "fileName", fileName,
                    "text", chunks.get(i), "chunkIndex", i));
            points.add(pt);
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of("points", points));
            int code = putJson("/collections/" + collection + "/points", body);
            if (code == 200) {
                log.info("【Qdrant】索引完成: fileId={}, chunks={}", fileId, points.size());
            } else {
                throw new RuntimeException("HTTP " + code);
            }
        } catch (Exception e) {
            throw new RuntimeException("Qdrant 索引失败: " + e.getMessage(), e);
        }
    }

    public List<ChunkResult> search(String sessionId, List<Float> queryEmbedding,
                                    int topK, float minScore) {
        if (queryEmbedding == null || queryEmbedding.isEmpty()) return List.of();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", queryEmbedding);
        body.put("limit", topK > 0 ? topK : 5);
        body.put("score_threshold", minScore);
        body.put("with_payload", true);
        body.put("filter", Map.of("must", List.of(
                Map.of("key", "sessionId", "match", Map.of("value", sessionId)))));

        try {
            String response = restClient.post()
                    .uri(baseUrl + "/collections/{collection}/points/search", collection)
                    .headers(h -> { if (apiKey != null && !apiKey.isBlank()) h.set("api-key", apiKey); })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode result = root.get("result");
            if (result == null || !result.isArray()) return List.of();

            List<ChunkResult> chunks = new ArrayList<>();
            for (JsonNode p : result) {
                JsonNode payload = p.get("payload");
                if (payload == null) continue;
                chunks.add(new ChunkResult(
                        payload.has("fileId") ? payload.get("fileId").asText() : "",
                        payload.has("fileName") ? payload.get("fileName").asText() : "",
                        payload.has("text") ? payload.get("text").asText() : "",
                        p.get("score").floatValue()));
            }
            log.info("【Qdrant】检索完成: sessionId={}, 命中={}", sessionId, chunks.size());
            return chunks;
        } catch (Exception e) {
            log.warn("【Qdrant】检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    public void deleteByFileId(String fileId) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("filter", Map.of("must", List.of(
                    Map.of("key", "fileId", "match", Map.of("value", fileId))))));
            postJson("/collections/" + collection + "/points/delete", body);
            log.info("【Qdrant】删除完成: fileId={}", fileId);
        } catch (Exception e) {
            log.warn("【Qdrant】删除失败: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
    }

    // ---- 内部 ----

    private void ensureCollection() {
        try {
            restClient.get()
                    .uri(baseUrl + "/collections/{collection}", collection)
                    .headers(h -> { if (apiKey != null && !apiKey.isBlank()) h.set("api-key", apiKey); })
                    .retrieve().body(String.class);
            log.info("【Qdrant】collection 已存在: {}", collection);
        } catch (Exception ignored) {
            try {
                String body = objectMapper.writeValueAsString(
                        Map.of("vectors", Map.of("size", vectorSize, "distance", "Cosine")));
                int code = putJson("/collections/" + collection, body);
                // 200 新建成功；409 表示已存在（GET 偶发失败时常见）
                if (code != 200 && code != 409) {
                    throw new RuntimeException("HTTP " + code);
                }
                if (code == 200) {
                    log.info("【Qdrant】创建 collection: {}, size={}", collection, vectorSize);
                } else {
                    log.info("【Qdrant】collection 已存在(409): {}", collection);
                }
                createPayloadIndex("sessionId");
                createPayloadIndex("fileId");
            } catch (Exception e) {
                // 不阻断启动：向量检索可降级，主对话仍可用
                log.warn("【Qdrant】ensure collection 失败，将以降级模式运行: {}", e.getMessage());
                return;
            }
        }
        createPayloadIndex("sessionId");
        createPayloadIndex("fileId");
    }

    private void createPayloadIndex(String fieldName) {
        try {
            String body = objectMapper.writeValueAsString(
                    Map.of("field_name", fieldName, "field_schema", "keyword"));
            int code = putJson("/collections/" + collection + "/index", body);
            if (code == 200 || code == 400 || code == 409) {
                log.debug("【Qdrant】payload 索引 {}: HTTP {}", fieldName, code);
            }
        } catch (Exception ignored) {}
    }

    /** 原生 HttpURLConnection PUT JSON（避 JDK HttpClient PUT 兼容问题）。 */
    private int putJson(String path, String json) throws Exception {
        return sendJson("PUT", path, json);
    }

    private int postJson(String path, String json) throws Exception {
        return sendJson("POST", path, json);
    }

    private int sendJson(String method, String path, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30_000);  // gRPC 端口用 6334
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            conn.setRequestProperty("api-key", apiKey);
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return conn.getResponseCode();
    }

    public record ChunkResult(String fileId, String fileName, String text, float score) {}
}
