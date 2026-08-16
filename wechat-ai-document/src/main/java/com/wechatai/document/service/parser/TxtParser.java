package com.wechatai.document.service.parser;

import com.wechatai.common.enums.FileType;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * TXT 解析器 — 直接按 UTF-8 读取文本内容。
 */
@Component
public class TxtParser implements DocumentParser {

    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.TXT;
    }

    @Override
    public String parse(InputStream inputStream) throws Exception {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
}
