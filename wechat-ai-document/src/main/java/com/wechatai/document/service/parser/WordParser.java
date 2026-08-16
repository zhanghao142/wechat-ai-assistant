package com.wechatai.document.service.parser;

import com.wechatai.common.enums.FileType;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Word 解析器 — 基于 Apache POI 5.x。
 * <p>
 * .doc → HWPF (WordExtractor)，.docx → XWPF (XWPFWordExtractor)。
 */
@Component
public class WordParser implements DocumentParser {

    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.DOC || fileType == FileType.DOCX;
    }

    @Override
    public String parse(InputStream inputStream) throws Exception {
        // 先探测是 .doc 还是 .docx：POI 的 OLE2 探测
        byte[] bytes = inputStream.readAllBytes();

        // 尝试 docx 优先（OOXML），失败回退 doc（OLE2）
        try {
            XWPFDocument xdoc = new XWPFDocument(new java.io.ByteArrayInputStream(bytes));
            XWPFWordExtractor extractor = new XWPFWordExtractor(xdoc);
            return extractor.getText();
        } catch (Exception ignored) {
            // 不是 docx 格式，尝试 doc
        }

        try {
            HWPFDocument hdoc = new HWPFDocument(new java.io.ByteArrayInputStream(bytes));
            WordExtractor extractor = new WordExtractor(hdoc);
            return extractor.getText();
        } catch (Exception e) {
            throw new RuntimeException("无法解析 Word 文件（不支持格式或已损坏）", e);
        }
    }
}
