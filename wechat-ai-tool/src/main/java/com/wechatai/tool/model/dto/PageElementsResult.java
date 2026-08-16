package com.wechatai.tool.model.dto;

import java.util.List;

/**
 * getPageElements 的返回结构 — 列出页面可交互元素，供 LLM 精确点击前参考。
 * <p>
 * 覆盖三类可交互元素：链接 (&lt;a&gt;)、按钮 (&lt;button&gt; / input[type=submit])、
 * 输入控件 (&lt;input&gt; / &lt;select&gt; / &lt;textarea&gt;)。
 */
public class PageElementsResult {

    private final List<ElementInfo> elements;
    private final int totalCount;
    private final boolean truncated;

    public PageElementsResult(List<ElementInfo> elements, int totalCount, boolean truncated) {
        this.elements = elements != null ? elements : List.of();
        this.totalCount = totalCount;
        this.truncated = truncated;
    }

    // ==================== getters（供 Jackson 序列化） ====================

    public List<ElementInfo> getElements() { return elements; }
    public int getTotalCount() { return totalCount; }
    public boolean isTruncated() { return truncated; }

    // ==================== 供 LLM 阅读的文本摘要 ====================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("页面可交互元素 (共 ").append(totalCount).append(" 个)");
        if (truncated) {
            sb.append("（列表已截断，仅显示前 ").append(elements.size()).append(" 个）");
        }
        sb.append(":\n");

        for (int i = 0; i < elements.size(); i++) {
            ElementInfo el = elements.get(i);
            sb.append("  [").append(i).append("] ");
            sb.append("<").append(el.tag());
            if (el.type() != null && !el.type().isEmpty()) {
                sb.append(" type=").append(el.type());
            }
            sb.append(">");
            if (el.text() != null && !el.text().isBlank()) {
                sb.append(" \"").append(truncate(el.text(), 40)).append("\"");
            }
            if (el.placeholder() != null && !el.placeholder().isBlank()) {
                sb.append(" placeholder=\"").append(truncate(el.placeholder(), 30)).append("\"");
            }
            sb.append("  → ").append(el.selector());
            // 动态类名标记
            if (!el.selectorStable()) {
                sb.append(" [动态⚠]");
                if (el.stableAlternative() != null && !el.stableAlternative().isBlank()) {
                    sb.append(" 稳定替代: ").append(el.stableAlternative());
                }
            }
            // iframe 标记
            if (el.iframeId() != null && !el.iframeId().isBlank()) {
                sb.append(" (iframe:").append(el.iframeId()).append(")");
            }
            sb.append("\n");
        }

        if (totalCount == 0) {
            sb.append("  (未发现可交互元素，页面可能是纯文本或动态渲染的)\n");
        }
        sb.append("\n💡 提示：使用各元素的 selector 进行精确点击/填写操作。[稳定✓] 的选择器优先使用，[动态⚠] 的选择器可能下次失效。");
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ==================== 内部记录类 ====================

    /**
     * 单个页面元素的信息。
     *
     * @param text              可见文本（截断后），如 "登录"、"提交订单"
     * @param tag               HTML 标签名 (a / button / input / select / textarea)
     * @param selector          可用于 page.locator(selector) 的 CSS 选择器
     * @param type              元素类型属性（input 的 type、button 的 type），无则为空
     * @param placeholder       input/textarea 的 placeholder，无则为空
     * @param selectorStable    选择器是否稳定（false = 类名为动态哈希，下次可能失效）
     * @param iframeId          若元素在 iframe 内，iframe 的 id 或 name，否则为空
     * @param stableAlternative 当 selectorStable=false 时的稳定替代选择器（data-testid/aria-label/id 等）
     * @param textIndex         在 getByText 匹配中的索引（供 click(nth) 使用）
     */
    public record ElementInfo(
            String text,
            String tag,
            String selector,
            String type,
            String placeholder,
            boolean selectorStable,
            String iframeId,
            String stableAlternative,
            int textIndex
    ) {
        /** 兼容旧代码的构造器（无新字段，默认值） */
        public ElementInfo(String text, String tag, String selector, String type, String placeholder) {
            this(text, tag, selector, type, placeholder, true, "", "", -1);
        }
    }
}
