package com.wechatai.tool.recognition;

/**
 * 图片识别服务接口 — 将图片 byte[] 转为中文文字描述。
 * <p>
 * 定义在 wechat-ai-tool 模块以避免循环依赖，
 * 由 wechat-ai-ai 模块的 {@code QwenVLService} 实现。
 * <p>
 * 主要用于浏览器工具截图后，将截图内容转为文字描述喂给 DeepSeek（纯文本 LLM），
 * 使其能"理解"截图内容，做出更准确的点击/操作决策。
 */
public interface ImageRecognitionService {

    /**
     * 使用默认 prompt 识别图片内容。
     *
     * @param imageBytes 图片原始数据
     * @return 图片的中文文字描述，失败时返回错误提示文本
     */
    String recognize(byte[] imageBytes);

    /**
     * 使用自定义 prompt 识别图片内容。
     *
     * @param imageBytes   图片原始数据
     * @param customPrompt 自定义识别指令
     * @return 图片的中文文字描述
     */
    String recognize(byte[] imageBytes, String customPrompt);
}
