package com.wechatai.document.service;

import com.wechatai.document.model.vo.DocumentContentVO;
import com.wechatai.document.model.vo.DocumentVO;
import com.wechatai.document.model.vo.ParseTaskVO;
import com.wechatai.common.model.dto.SearchDocumentResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理服务。
 * <p>
 * 主流程：上传落库（UPLOADED）→ 发起解析入 Redis 队列 → Worker 用 PDFBox/POI 解析 →
 * 状态变为 PARSED/FAILED。一期限制：pdf/doc/docx/txt，单文件 ≤ 50MB。
 * {@link #getContent} 供 AI 模块同 JVM 直接调用取纯文本，不必走 HTTP。
 * <p>
 * 两条上传入口：
 * <ul>
 *   <li>{@link #upload(MultipartFile, String, String)} — HTTP 接口（调试/后台用）</li>
 *   <li>{@link #upload(String, String, String, byte[])} — iLink SDK 直传 byte[]（生产主路径）</li>
 * </ul>
 */
public interface DocumentService {

    /**
     * 上传文档并落库，状态 UPLOADED（HTTP 接口用）。
     * 内部委托给 {@link #upload(String, String, String, byte[])}。
     */
    DocumentVO upload(MultipartFile file, String sessionId, String userId);

    /**
     * 上传文档并落库，状态 UPLOADED（iLink SDK 直调用）。
     * 与 HTTP 入口共享同一套校验 + 存储逻辑。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param fileName  文件名（含扩展名）
     * @param fileBytes 文件二进制（已由 SDK 解密）
     */
    DocumentVO upload(String userId, String sessionId, String fileName, byte[] fileBytes);

    /** 将解析任务入队，返回 taskId 与预估耗时 */
    ParseTaskVO parse(String fileId);

    DocumentVO getStatus(String fileId);

    List<DocumentVO> listBySession(String sessionId, String status);

    void delete(String fileId);

    /**
     * 获取已解析文档纯文本。
     * 主要给 AI 引擎同进程调用，而非对外 HTTP 消费。
     */
    DocumentContentVO getContent(String fileId);

    /**
     * 直接从文本创建文档（AI 生成内容），跳过上传和解析流程。
     * 状态直接设为 PARSED，不存物理文件。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param fileName  文件名（如"租房合同.txt"）
     * @param content   文档全文内容
     * @return 文档 fileId
     */
    String createTextDocument(String userId, String sessionId, String fileName, String content);
}
