package com.wechatai.common.enums;

/**
 * 一期支持的文档格式：pdf / doc / docx / txt。
 * <p>
 * 上传校验与解析器（PDFBox / POI）选型均依据此枚举。
 */
public enum FileType {
    PDF,  // PDF，PDFBox 解析
    DOC,  // 旧版 Word，POI 解析
    DOCX, // 新版 Word，POI 解析
    TXT;  // 纯文本

    public static FileType fromExtension(String extension) {
        return valueOf(extension.toUpperCase());
    }
}
