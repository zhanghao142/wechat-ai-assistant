package com.wechatai.document.service;

import com.wechatai.common.enums.FileStatus;
import com.wechatai.common.enums.FileType;
import com.wechatai.common.enums.TaskStatus;
import com.wechatai.document.entity.DocumentEntity;
import com.wechatai.document.mapper.DocumentMapper;
import com.wechatai.document.model.vo.DocumentContentVO;
import com.wechatai.document.model.vo.DocumentVO;
import com.wechatai.document.model.vo.ParseTaskVO;
import com.wechatai.document.service.parser.DocumentParser;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 文档服务实现。
 * <p>
 * upload：同步解析 + RAG 索引（chunk → embedding → Qdrant）；
 * parse：兼容保留，手动触发重新解析（含索引更新）；
 * 文档检索：由 {@link com.wechatai.ai.ai.LlmService} 内嵌的 RAG 检索完成，不再通过 tool 调用。
 */
@Service
public class DocumentServiceImpl implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    private static final List<String> ALLOWED_EXTS = List.of("pdf", "doc", "docx", "txt");
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024; // 50MB

    private final DocumentMapper documentMapper;
    private final Path uploadDir;
    private final List<DocumentParser> parsers;
    private final ChunkService chunkService;
    private final EmbeddingService embeddingService;
    private final QdrantService qdrantService;

    /** AI 生成文档的 RAG 索引线程池：不阻塞 generateDocument 工具返回 */
    private final ExecutorService ragIndexExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "rag-index");
        t.setDaemon(true);
        return t;
    });

    public DocumentServiceImpl(DocumentMapper documentMapper,
                                @Value("${wechat.storage.local-path}") String storagePath,
                                List<DocumentParser> parsers,
                                ChunkService chunkService,
                                EmbeddingService embeddingService,
                                QdrantService qdrantService) {
        this.documentMapper = documentMapper;
        this.uploadDir = Paths.get(storagePath);
        this.parsers = parsers;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
        this.qdrantService = qdrantService;
    }

    @PreDestroy
    public void shutdown() {
        ragIndexExecutor.shutdown();
        try {
            if (!ragIndexExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ragIndexExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ragIndexExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ========== 上传（两条入口，共享解析逻辑） ==========

    @Override
    public DocumentVO upload(MultipartFile file, String sessionId, String userId) {
        try {
            return upload(userId, sessionId, file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new RuntimeException("读取上传文件失败", e);
        }
    }

    @Override
    public DocumentVO upload(String userId, String sessionId, String fileName, byte[] fileBytes) {
        // ① 校验
        String ext = extractExt(fileName);
        if (!ALLOWED_EXTS.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型: " + ext);
        }
        if (fileBytes.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小超出限制 (最大 50MB): " + fileBytes.length);
        }

        // ② 生成 fileId + 按日期分目录保存文件
        String fileId = "f_" + UUID.randomUUID().toString().replace("-", "");
        String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Path savePath;
        try {
            Path saveDir = uploadDir.resolve(dateDir);
            Files.createDirectories(saveDir);
            savePath = saveDir.resolve(fileId + "." + ext);
            Files.write(savePath, fileBytes);
        } catch (IOException e) {
            throw new RuntimeException("保存文件失败", e);
        }

        // ③ 写入 MySQL（初始状态 UPLOADED）
        DocumentEntity entity = new DocumentEntity();
        entity.setFileId(fileId);
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setFileName(fileName);
        entity.setFileType(FileType.fromExtension(ext));
        entity.setFileSize((long) fileBytes.length);
        entity.setFilePath(savePath.toString());
        entity.setStatus(FileStatus.UPLOADED);
        documentMapper.insert(entity);

        // ④ 同步解析
        try {
            String content = parseFile(entity);
            documentMapper.updateParseResult(fileId, "PARSED", content);
            log.info("文档解析成功: fileId={}, 长度={}字符", fileId, content.length());
            // RAG：切块 + 向量化 + 索引
            indexToQdrant(fileId, sessionId, userId, fileName, content);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "解析异常";
            documentMapper.updateParseError(fileId, msg);
            log.warn("文档解析失败: fileId={}, 原因={}", fileId, msg);
        }

        // ⑤ 返回 VO（重新查一次拿最新状态）
        DocumentEntity updated = documentMapper.findByFileId(fileId);
        return toVO(updated);
    }

    // ========== 发起解析（兼容保留，手动重新解析用） ==========

    @Override
    public ParseTaskVO parse(String fileId) {
        DocumentEntity entity = documentMapper.findByFileId(fileId);
        if (entity == null) {
            throw new IllegalArgumentException("文档不存在: " + fileId);
        }

        // 更新状态为 PARSING
        entity.setStatus(FileStatus.PARSING);
        documentMapper.updateStatus(entity);

        // 同步解析（不走 Redis 队列）
        try {
            String content = parseFile(entity);
            documentMapper.updateParseResult(fileId, "PARSED", content);
            log.info("重新解析成功: fileId={}", fileId);
            // RAG：先删旧 chunks 再重新索引
            qdrantService.deleteByFileId(fileId);
            indexToQdrant(fileId, entity.getSessionId(), entity.getUserId(), entity.getFileName(), content);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "解析异常";
            documentMapper.updateParseError(fileId, msg);
            log.warn("重新解析失败: fileId={}, 原因={}", fileId, msg);
        }

        ParseTaskVO vo = new ParseTaskVO();
        vo.setTaskId("task_" + fileId);
        vo.setFileId(fileId);
        vo.setStatus(TaskStatus.DONE);
        vo.setEstimatedSeconds(0);
        return vo;
    }

    // ========== AI 生成文档 ==========

    @Override
    public String createTextDocument(String userId, String sessionId, String fileName, String content) {
        String fileId = "g_" + UUID.randomUUID().toString().replace("-", "");

        // 如果没有扩展名，补 .txt
        if (!fileName.contains(".")) {
            fileName = fileName + ".txt";
        }

        DocumentEntity entity = new DocumentEntity();
        entity.setFileId(fileId);
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setFileName(fileName);
        entity.setFileType(FileType.TXT);
        entity.setFileSize((long) content.length());
        entity.setFilePath("[GENERATED]");
        entity.setStatus(FileStatus.PARSED);
        entity.setContent(content);
        documentMapper.insert(entity);
        documentMapper.updateParseResult(fileId, "PARSED", content);

        log.info("【AI 生成文档】fileId={}, fileName={}, 字数={}", fileId, fileName, content.length());
        // RAG 异步索引：Qdrant 慢/超时不能拖垮 generateDocument（否则 WRITE 确认 60s 看门狗误杀）
        indexToQdrantAsync(fileId, sessionId, userId, fileName, content);
        return fileId;
    }

    // ========== 查询 ==========

    @Override
    public DocumentVO getStatus(String fileId) {
        DocumentEntity entity = documentMapper.findByFileId(fileId);
        if (entity == null) return null;
        return toVO(entity);
    }

    @Override
    public List<DocumentVO> listBySession(String sessionId, String status) {
        List<DocumentEntity> entities = documentMapper.findBySessionId(sessionId, status);
        return entities.stream().map(this::toVO).toList();
    }

    // ========== 删除 ==========

    @Override
    public void delete(String fileId) {
        DocumentEntity entity = documentMapper.findByFileId(fileId);
        if (entity == null) return;

        try {
            Files.deleteIfExists(Paths.get(entity.getFilePath()));
        } catch (IOException ignored) {
            // 文件不存在或删除失败不影响 DB 删除
        }

        documentMapper.deleteByFileId(fileId);
        // RAG：清理 Qdrant 中的 chunks
        qdrantService.deleteByFileId(fileId);
    }

    // ========== 获取解析文本 ==========

    @Override
    public DocumentContentVO getContent(String fileId) {
        DocumentEntity entity = documentMapper.findByFileId(fileId);
        if (entity == null) return null;

        if (entity.getStatus() != FileStatus.PARSED) {
            throw new IllegalStateException("文档尚未完成解析，当前状态: " + entity.getStatus());
        }

        DocumentContentVO vo = new DocumentContentVO();
        vo.setFileId(entity.getFileId());
        vo.setFileName(entity.getFileName());
        vo.setContent(entity.getContent());
        vo.setContentType("PLAIN_TEXT");
        return vo;
    }

    // ========== 内部方法 ==========

    /**
     * 根据文件类型选解析器，读文件并解析为纯文本。
     */
    private String parseFile(DocumentEntity entity) throws Exception {
        FileType fileType = entity.getFileType();
        DocumentParser parser = parsers.stream()
                .filter(p -> p.supports(fileType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("无对应解析器: " + fileType));

        try (InputStream is = Files.newInputStream(Paths.get(entity.getFilePath()))) {
            return parser.parse(is);
        }
    }

    /** 异步提交 RAG 索引，立即返回。 */
    private void indexToQdrantAsync(String fileId, String sessionId, String userId,
                                    String fileName, String content) {
        ragIndexExecutor.execute(() -> indexToQdrant(fileId, sessionId, userId, fileName, content));
    }

    /**
     * RAG 索引：切块 → Embedding → Qdrant。
     */
    private void indexToQdrant(String fileId, String sessionId, String userId,
                               String fileName, String content) {
        log.info("【RAG】开始索引: fileId={}, 长度={}字符", fileId, content != null ? content.length() : 0);
        try {
            List<String> chunks = chunkService.split(content);
            log.info("【RAG】切块完成: {} 个 chunks", chunks.size());
            if (chunks.isEmpty()) return;
            List<List<Float>> embeddings = embeddingService.embedBatch(chunks);
            log.info("【RAG】向量化完成: {} 个向量", embeddings.size());
            qdrantService.indexChunks(fileId, sessionId, userId, fileName, chunks, embeddings);
        } catch (Exception e) {
            log.warn("【RAG】索引失败，文件仍可正常使用: fileId={}, error={}", fileId, e.getMessage());
        }
    }

    private String extractExt(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            throw new IllegalArgumentException("文件名缺少扩展名: " + fileName);
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }

    private DocumentVO toVO(DocumentEntity entity) {
        DocumentVO vo = new DocumentVO();
        vo.setFileId(entity.getFileId());
        vo.setFileName(entity.getFileName());
        vo.setFileSize(entity.getFileSize());
        vo.setFileType(entity.getFileType());
        vo.setStatus(entity.getStatus());
        vo.setUploadedAt(entity.getUploadedAt() != null
                ? entity.getUploadedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                : null);
        vo.setParsedAt(entity.getParsedAt() != null
                ? entity.getParsedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                : null);
        return vo;
    }
}
