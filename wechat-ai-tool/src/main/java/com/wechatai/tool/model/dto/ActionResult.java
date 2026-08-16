package com.wechatai.tool.model.dto;

import java.util.List;

/**
 * click / fill 等操作的通用返回结构。
 * <p>
 * 新增 errorCode / candidates 等字段用于结构化错误反馈，帮助 LLM 自我修正。
 */
public class ActionResult {

    private final boolean success;
    private final String message;
    private final String pageTitle;    // 操作后页面标题（可选）
    private final String pageUrl;      // 操作后页面 URL（可选）

    // ==================== 新增字段（向后兼容：旧构造器默认为 null） ====================

    /** 错误码：CLICK_NO_TARGET / CLICK_TOO_MANY / CLICK_TIMEOUT / CLICK_OVERLAY_BLOCKED / CLICK_DETACHED / CLICK_NOT_FOUND / SELECTOR_BLACKLISTED */
    private final String errorCode;

    /** 匹配过多时的候选元素描述列表（供 LLM 缩小范围后重试） */
    private final List<String> candidates;

    /** 总匹配数 */
    private final int candidateCount;

    /** 实际点击的元素索引（从 0 开始） */
    private final int clickedIndex;

    /** 是否在 iframe 内完成点击 */
    private final boolean usedIframe;

    // ==================== 保持向后兼容的构造器 ====================

    public ActionResult(boolean success, String message) {
        this(success, message, null, null, null, null, 0, -1, false);
    }

    public ActionResult(boolean success, String message, String pageTitle, String pageUrl) {
        this(success, message, pageTitle, pageUrl, null, null, 0, -1, false);
    }

    /** 全参构造器 */
    public ActionResult(boolean success, String message, String pageTitle, String pageUrl,
                        String errorCode, List<String> candidates, int candidateCount,
                        int clickedIndex, boolean usedIframe) {
        this.success = success;
        this.message = message;
        this.pageTitle = pageTitle;
        this.pageUrl = pageUrl;
        this.errorCode = errorCode;
        this.candidates = candidates;
        this.candidateCount = candidateCount;
        this.clickedIndex = clickedIndex;
        this.usedIframe = usedIframe;
    }

    // ==================== 工厂方法 ====================

    /** 成功点击 */
    public static ActionResult ok(String message, String pageTitle, String pageUrl,
                                   int clickedIndex, boolean usedIframe) {
        return new ActionResult(true, message, pageTitle, pageUrl,
                null, null, 0, clickedIndex, usedIframe);
    }

    /** 匹配过多 — 返回候选列表让 LLM 选择 */
    public static ActionResult tooMany(int totalCount, List<String> candidates, int maxCandidates) {
        String msg = "文本匹配了 " + totalCount + " 个元素（上限 " + maxCandidates + "），"
                + "以下是最可能的候选，请用更具体的文本或结合 selector 精确锁定：";
        return new ActionResult(false, msg, null, null,
                "CLICK_TOO_MANY", candidates, totalCount, -1, false);
    }

    /** 带错误码的失败 */
    public static ActionResult fail(String errorCode, String message) {
        return new ActionResult(false, message, null, null,
                errorCode, null, 0, -1, false);
    }

    /** 带错误码和候选列表的失败 */
    public static ActionResult fail(String errorCode, String message,
                                     List<String> candidates, int candidateCount) {
        return new ActionResult(false, message, null, null,
                errorCode, candidates, candidateCount, -1, false);
    }

    // ==================== getters ====================

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public String getPageTitle() { return pageTitle; }
    public String getPageUrl() { return pageUrl; }
    public String getErrorCode() { return errorCode; }
    public List<String> getCandidates() { return candidates; }
    public int getCandidateCount() { return candidateCount; }
    public int getClickedIndex() { return clickedIndex; }
    public boolean isUsedIframe() { return usedIframe; }

    // ==================== toString（LLM 可读） ====================

    @Override
    public String toString() {
        String icon = success ? "✅" : "❌";
        StringBuilder sb = new StringBuilder();
        sb.append(icon).append(" ").append(message);
        if (errorCode != null && !errorCode.isEmpty()) {
            sb.append("\n错误码: ").append(errorCode);
        }
        if (pageTitle != null && !pageTitle.isEmpty()) {
            sb.append("\n当前页面: ").append(pageTitle);
        }
        if (candidates != null && !candidates.isEmpty()) {
            sb.append("\n候选元素 (共 ").append(candidateCount).append(" 个):");
            for (int i = 0; i < candidates.size(); i++) {
                sb.append("\n  [").append(i).append("] ").append(candidates.get(i));
            }
            sb.append("\n💡 提示：请用候选元素的文本或选择器缩小范围后重试。");
        }
        if (usedIframe) {
            sb.append("\n(操作在 iframe 内完成)");
        }
        return sb.toString();
    }
}
