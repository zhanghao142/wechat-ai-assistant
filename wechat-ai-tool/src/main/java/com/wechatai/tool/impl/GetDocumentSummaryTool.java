package com.wechatai.tool.impl;

import com.wechatai.document.model.vo.DocumentContentVO;
import com.wechatai.document.service.DocumentService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 文档摘要工具（READ）。
 * <p>
 * 从已解析文档的 content 字段统计总字数、段落数，并取前 200 字作为摘要。
 */
@Component
public class GetDocumentSummaryTool {

    private final DocumentService documentService;

    public GetDocumentSummaryTool(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Tool("获取指定文档的摘要信息，返回总字数、段落数、开头预览")
    public DocumentSummary getDocumentSummary(@P("文档 ID") String fileId) {
        DocumentContentVO content = documentService.getContent(fileId);
        if (content == null || content.getContent() == null) {
            return new DocumentSummary(0, 0, "文档不存在或暂无内容");
        }

        String text = content.getContent();
        int totalChars = text.length();
        int paragraphs = text.split("\n").length;
        String preview = text.substring(0, Math.min(200, totalChars));

        return new DocumentSummary(totalChars, paragraphs, preview);
    }

    /**
     * 摘要返回结构
     */
    public record DocumentSummary(int totalChars, int paragraphs, String preview) {
    }
}
