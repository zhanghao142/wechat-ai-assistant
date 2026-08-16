package com.wechatai.document.service.parser;

import com.wechatai.common.enums.FileType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * PDF 解析器 — 基于 Apache PDFBox 3.x。
 * <p>
 * 用 PDFTextStripper 抽取纯文本，默认支持 Unicode（中文无需额外字体配置）。
 */
@Component
public class PdfParser implements DocumentParser {

    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.PDF;
    }

    @Override
    public String parse(InputStream inputStream) throws Exception {
        try (PDDocument doc = Loader.loadPDF(inputStream.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }
}
