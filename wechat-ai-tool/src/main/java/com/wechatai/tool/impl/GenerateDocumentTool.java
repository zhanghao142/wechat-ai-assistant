package com.wechatai.tool.impl;

import com.wechatai.document.service.DocumentService;
import com.wechatai.tool.annotation.WriteTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 文档生成工具（WRITE）— 将 AI 生成的内容保存为 .txt 或 .docx 文件并录入文档库。
 * <p>
 * 做三件事：
 * 1. 写本地文件 → 供微信发送给用户
 * 2. 写入 MySQL document 表（status=PARSED）→ 可被 searchDocument 搜索
 * 3. 返回文件路径 → LLM 在回复末尾加 [FILE:path] 标记，触发微信自动发送
 * <p>
 * 文件名以 .docx 结尾则生成 Word 文档，否则生成纯文本文件。
 */
@Component
@WriteTool("生成文档")
public class GenerateDocumentTool {

    private static final Logger log = LoggerFactory.getLogger(GenerateDocumentTool.class);

    private final DocumentService documentService;
    private final Path storagePath;

    public GenerateDocumentTool(DocumentService documentService,
                                @Value("${docgen.storage-path:./AItext}") String storagePath) {
        this.documentService = documentService;
        this.storagePath = Paths.get(storagePath);
    }

    @Tool("将AI生成的文档内容保存为文件并录入文档库。"
            + "当用户说「帮我写一份xxx」「生成一个文档」「保存为文件」时调用。"
            + "注意：由AI自己生成文档内容后调此工具保存。"
            + "此工具会返回文件路径，在最终回复末尾加上【[FILE:文件路径]】标记（单独一行，不要加其他字符），"
            + "系统会自动将文件发送给用户。"
            + "例如回复末尾另起一行写：[FILE:D:/wechat_files/generated/2026-07-22/g_xxx.txt]"
            + "如果用户要求 Word 文档或 .docx 格式，在 fileName 参数末尾加 .docx，会自动生成 Word 格式。"
            + "保存后不要在回复里重复展示文档全文，简要告知用户即可")
    public String generateDocument(
            @P("文档内容，由AI自己生成后传入此参数") String content,
            @P("文件名，如「租房合同」「工作总结」「学习笔记」。需要 Word 格式则在末尾加 .docx，如「租房合同.docx」") String fileName,
            String userId,
            String sessionId) {

        if (content == null || content.trim().isEmpty()) {
            return "文档内容不能为空";
        }

        String safeName = (fileName != null && !fileName.trim().isEmpty())
                ? fileName.trim() : generateSmartFileName(content);
        if (!safeName.contains(".")) {
            safeName = safeName + ".txt";
        }

        boolean isDocx = safeName.toLowerCase().endsWith(".docx");
        boolean isMarkdown = safeName.toLowerCase().endsWith(".md");

        // 1. 写入 DB（status=PARSED，可搜索。不论存什么格式，DB 里都存纯文本）
        String fileId = documentService.createTextDocument(userId, sessionId, safeName, content);

        // 2. 写本地文件 → 供微信发送
        try {
            String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            Path dir = storagePath.resolve(dateStr);
            Files.createDirectories(dir);

            String ext;
            if (isDocx) {
                ext = ".docx";
            } else if (isMarkdown) {
                ext = ".md";
            } else {
                ext = ".txt";
            }
            
            String baseName = safeName.substring(0, safeName.lastIndexOf("."));
            String cleanBaseName = sanitizeFileName(baseName);
            if (cleanBaseName.isEmpty()) {
                cleanBaseName = "文档";
            }
            Path filePath = dir.resolve(cleanBaseName + ext);

            if (isDocx) {
                writeDocx(content, filePath);
            } else {
                Files.writeString(filePath, content);
            }

            log.info("【生成文档】fileId={}, fileName={}, path={}, 字数={}", fileId, safeName, filePath, content.length());

            String pathForMark = filePath.toString().replace("\\", "/");

            return String.format("文档已保存（共%d字）\n[FILE:%s]",
                    content.length(), pathForMark);

        } catch (Exception e) {
            log.error("【生成文档】保存文件失败", e);
            return String.format("文档已保存到文档库（共%d字），但文件发送失败，请稍后重试", content.length());
        }
    }

    /**
     * 将纯文本内容生成为 .docx Word 文档。
     * <p>
     * 排版规则：
     * - 第一行作为标题 → 居中 + 加粗 + 16 号字
     * - 其余按空行分段落 → 两端对齐 + 正常字号
     * - 每段首行缩进 2 字符
     */
    private void writeDocx(String content, Path filePath) throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            String[] paragraphs = content.split("\n\\s*\n");

            for (int i = 0; i < paragraphs.length; i++) {
                String paraText = paragraphs[i].trim();
                if (paraText.isEmpty()) continue;

                XWPFParagraph para = doc.createParagraph();

                if (i == 0) {
                    // 第一段：标题
                    para.setAlignment(ParagraphAlignment.CENTER);
                    XWPFRun run = para.createRun();
                    run.setText(paraText);
                    run.setBold(true);
                    run.setFontSize(16);
                    run.setFontFamily("SimSun");
                } else {
                    // 正文段落
                    para.setAlignment(ParagraphAlignment.BOTH);
                    para.setIndentationFirstLine(480); // 首行缩进 2 字符（≈480 twips）

                    XWPFRun run = para.createRun();
                    run.setText(paraText);
                    run.setFontSize(12);
                    run.setFontFamily("SimSun");

                    // 加段间距
                    para.setSpacingAfter(200);
                }
            }

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                doc.write(baos);
                Files.write(filePath, baos.toByteArray());
            }
        }
    }

    private String generateSmartFileName(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "文档.txt";
        }
        
        String trimmed = content.trim();
        String[] lines = trimmed.split("\n");
        
        String title = "";
        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty()) {
                if (line.startsWith("# ")) {
                    title = line.substring(2).trim();
                } else if (line.startsWith("## ")) {
                    title = line.substring(3).trim();
                } else {
                    title = line;
                }
                break;
            }
        }
        
        if (title.isEmpty()) {
            title = "文档";
        }
        
        title = title.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (title.length() > 50) {
            title = title.substring(0, 50);
        }
        
        return title + ".txt";
    }

    private String sanitizeFileName(String name) {
        if (name == null) {
            return "";
        }
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        sanitized = sanitized.trim();
        if (sanitized.length() > 100) {
            sanitized = sanitized.substring(0, 100);
        }
        return sanitized;
    }
}
