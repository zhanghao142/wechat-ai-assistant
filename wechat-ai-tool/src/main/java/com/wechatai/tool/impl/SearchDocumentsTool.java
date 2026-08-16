package com.wechatai.tool.impl;

import com.wechatai.document.service.EmbeddingService;
import com.wechatai.document.service.QdrantService;
import com.wechatai.document.service.QdrantService.ChunkResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话文档语义检索（READ）— 供 generalAgent 按需调用。
 * <p>
 * 中央 Orchestrator 不再自动 RAG；问上传文档内容时由本工具 embed + Qdrant 检索。
 * sessionId 由 ToolExecuteNode 注入，无需 LLM 填写。
 */
@Component
public class SearchDocumentsTool {

    private static final Logger log = LoggerFactory.getLogger(SearchDocumentsTool.class);

    private final EmbeddingService embeddingService;
    private final QdrantService qdrantService;

    @Value("${wechat.rag.retrieve.top-k:5}")
    private int topK;

    @Value("${wechat.rag.retrieve.min-score:0.5}")
    private float minScore;

    public SearchDocumentsTool(EmbeddingService embeddingService, QdrantService qdrantService) {
        this.embeddingService = embeddingService;
        this.qdrantService = qdrantService;
    }

    @Tool("搜索用户上传到当前会话的文档内容（语义检索）。当用户询问文档/合同/PDF/笔记里的条款、数字、结论，" +
          "或说「根据我上传的文件」「文档里怎么写的」时必须调用。不要编造文档内容。")
    public String searchDocuments(
            @P("检索问题或关键词，尽量具体，例如：违约金比例、交货日期") String query,
            String sessionId) {

        if (query == null || query.isBlank()) {
            return "检索问题不能为空";
        }
        if (sessionId == null || sessionId.isBlank()) {
            return "会话 ID 不可用，无法检索文档";
        }

        long start = System.currentTimeMillis();
        try {
            log.info("【文档检索】开始 queryLen={} sessionId={}", query.length(), sessionId);
            List<Float> embedding = embeddingService.embed(query.strip());
            List<ChunkResult> chunks = qdrantService.search(sessionId, embedding, topK, minScore);
            long ms = System.currentTimeMillis() - start;

            if (chunks == null || chunks.isEmpty()) {
                log.info("【文档检索】无命中 ({}ms) sessionId={}", ms, sessionId);
                return "未在当前会话上传的文档中找到与「" + query.strip() + "」相关的内容。"
                        + "可请用户确认是否已上传文档，或换个关键词再试。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(chunks.size()).append(" 处相关文档片段：\n\n");
            int i = 1;
            for (ChunkResult c : chunks) {
                sb.append(i++).append(". 【来源：").append(nullToEmpty(c.fileName())).append("】");
                sb.append(" score=").append(String.format("%.3f", c.score())).append('\n');
                sb.append(nullToEmpty(c.text()).strip()).append("\n\n");
            }
            log.info("【文档检索】命中 {} 条 ({}ms) sessionId={}", chunks.size(), ms, sessionId);
            return sb.toString().strip();
        } catch (Exception e) {
            log.warn("【文档检索】失败 ({}ms): {}", System.currentTimeMillis() - start, e.getMessage());
            return "文档检索暂时不可用：" + e.getMessage();
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
