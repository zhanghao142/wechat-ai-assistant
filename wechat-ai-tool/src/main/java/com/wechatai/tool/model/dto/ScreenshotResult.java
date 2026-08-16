package com.wechatai.tool.model.dto;

/**
 * screenshot 的返回结构 — 截图保存为文件，不返回 base64 数据。
 * <p>
 * Jackson 序列化时输出 { success, filePath, fileUrl, width, height, fileSize, description, error }，
 * 其中 description 由多模态模型识别截图内容后注入，供纯文本 LLM 理解截图画面。
 */
public class ScreenshotResult {

    private final boolean success;
    /** 文件在服务器上的绝对/相对路径（用于内部定位） */
    private final String filePath;
    /** 文件对外访问 URL（可通过静态资源映射访问） */
    private final String fileUrl;
    private final int width;
    private final int height;
    /** 文件大小（字节） */
    private final long fileSize;
    /** 截图内容的 AI 识别描述（多模态模型识别后注入，供纯文本 LLM 理解截图内容） */
    private final String description;
    private final String error;

    private ScreenshotResult(boolean success, String filePath, String fileUrl,
                             int width, int height, long fileSize, String description, String error) {
        this.success = success;
        this.filePath = filePath;
        this.fileUrl = fileUrl;
        this.width = width;
        this.height = height;
        this.fileSize = fileSize;
        this.description = description;
        this.error = error;
    }

    public static ScreenshotResult ok(String filePath, String fileUrl,
                                       int width, int height, long fileSize,
                                       String description) {
        return new ScreenshotResult(true, filePath, fileUrl, width, height, fileSize, description, null);
    }

    public static ScreenshotResult fail(String error) {
        return new ScreenshotResult(false, null, null, 0, 0, 0, null, error);
    }

    // ==================== getters（供 Jackson 序列化） ====================

    public boolean isSuccess() { return success; }
    public String getFilePath() { return filePath; }
    public String getFileUrl() { return fileUrl; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public long getFileSize() { return fileSize; }
    public String getDescription() { return description; }
    public String getError() { return error; }

    // ==================== 供 LLM 阅读的文本摘要 ====================

    @Override
    public String toString() {
        if (!success) return "❌ 截图失败: " + error;
        StringBuilder sb = new StringBuilder();
        sb.append("✅ 截图已自动发送给用户（微信行内图片）\n");
        sb.append("尺寸: ").append(width).append("x").append(height).append("\n");
        sb.append("文件大小: ").append(formatSize(fileSize)).append("\n");
        if (description != null && !description.isBlank()) {
            sb.append("\n📷 【截图画面内容】\n").append(description).append("\n");
        }
        sb.append("\n⚠ 重要：图片已由系统自动发送。请根据上方「截图画面内容」的描述理解当前页面状态，做出后续操作决策。");
        return sb.toString();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
