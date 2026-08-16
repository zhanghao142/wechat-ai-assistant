package com.wechatai.tool.model.dto;

/**
 * getText 的返回结构。
 */
public class TextResult {

    private final String text;
    private final int charCount;

    public TextResult(String text, int charCount) {
        this.text = text;
        this.charCount = charCount;
    }

    public String getText() { return text; }
    public int getCharCount() { return charCount; }

    @Override
    public String toString() {
        return "✅ 提取文本 (" + charCount + " 字):\n\n" + text;
    }
}
