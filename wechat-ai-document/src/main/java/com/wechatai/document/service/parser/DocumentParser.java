package com.wechatai.document.service.parser;

import com.wechatai.common.enums.FileType;

import java.io.InputStream;

/**
 * 文档解析器抽象。
 * <p>
 * PDF 使用 Apache PDFBox 抽文本；Word（.doc/.docx）使用 Apache POI；
 * TXT 直接按字符集读取。实现类按 {@link #supports(FileType)} 选择，
 * 输出纯文本写入库表 content 字段供 AI 使用。
 */
public interface DocumentParser {

    /** 是否支持该文件类型 */
    boolean supports(FileType fileType);

    /** 从输入流解析为纯文本 */
    String parse(InputStream inputStream) throws Exception;
}
