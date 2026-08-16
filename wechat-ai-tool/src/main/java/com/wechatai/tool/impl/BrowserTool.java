package com.wechatai.tool.impl;

import com.wechatai.tool.config.BrowserProperties;
import com.wechatai.tool.model.dto.*;
import com.wechatai.tool.recognition.ImageRecognitionService;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.ViewportSize;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 浏览器操控工具（WRITE）— 提供 7 个 @Tool 方法，支持页面导航、点击、填表、截图等操作。
 *
 * <p>同一 session 内的多次调用共享浏览器状态（cookie、localStorage），实现登录态保持。
 */
@Component
public class BrowserTool {

    private static final Logger log = LoggerFactory.getLogger(BrowserTool.class);

    /** 裸标签选择器黑名单 — 这些选择器过于宽泛，禁止在 click/fill 中使用 */
    private static final Set<String> BROAD_SELECTOR_BLACKLIST = Set.of(
            "a", "button", "div", "span", "p", "li", "ul", "ol",
            "input", "select", "textarea", "form", "img", "table",
            "tr", "td", "th", "section", "article", "nav", "header",
            "footer", "main", "aside", "h1", "h2", "h3", "h4", "h5", "h6",
            "label", "svg", "path", "*", "body", "html"
    );

    /** 选择器匹配元素数上限 — 超过此值的 click/fill 操作会返回候选列表而非直接拒绝 */
    private static final int MAX_SELECTOR_MATCHES = 20;

    /** getPageElements 单次返回元素数上限 */
    private static final int MAX_PAGE_ELEMENTS = 50;

    /** 逐个验证 text-match 的 CSS 匹配时，最多遍历前 N 个候选 */
    private static final int MAX_TEXT_CANDIDATE_SCAN = 50;

    /** 文本匹配过多时，返回候选列表的最大条数 */
    private static final int MAX_CANDIDATE_LIST_SIZE = 15;

    /** 截图识别专用 prompt — 聚焦可交互元素和页面结构 */
    private static final String SCREENSHOT_RECOGNITION_PROMPT = """
            分析这张网页截图，重点识别以下内容：

            1. 【可点击元素】列出页面上所有可见的按钮、链接、导航项（如"登录"、"注册"、"搜索"、"提交"等），说明它们的大致位置（顶部/左侧/中间/右侧/底部）

            2. 【输入框】页面上有哪些输入框或搜索框，它们旁边有什么提示文字或标签

            3. 【主要内容】当前页面显示的主要信息是什么（搜索结果、商品列表、文章内容、表单等）

            4. 【特殊状态】是否有弹窗、提示框、验证码、错误信息等需要处理的内容

            注意：
            - 把按钮/链接的文字写清楚，这些文字将用于后续的精确点击操作
            - 如果页面有多个相似按钮（如多个"购买"），说明它们分别对应什么内容
            - 控制在 200 字以内
            """;

    private final BrowserSessionManager sessionManager;
    private final BrowserProperties props;
    private final ImageRecognitionService imageRecognitionService;

    public BrowserTool(BrowserSessionManager sessionManager, BrowserProperties props,
                       ImageRecognitionService imageRecognitionService) {
        this.sessionManager = sessionManager;
        this.props = props;
        this.imageRecognitionService = imageRecognitionService;
    }

    // ==================== 1. navigate ====================

    @Tool("打开指定网页，返回页面标题、URL 和文本摘要")
    public PageInfo navigate(
            @P("目标 URL，必须以 http:// 或 https:// 开头") String url,
            @P("会话 ID，自动注入") String sessionId) {

        if (url == null || url.isBlank()) {
            return new PageInfo("", "", "错误：URL 不能为空");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return new PageInfo("", "", "错误：URL 必须以 http:// 或 https:// 开头");
        }

        try {
            sessionManager.checkUrlAllowed(url);
        } catch (SecurityException e) {
            return new PageInfo("", url, "安全限制: " + e.getMessage());
        }

        Page page = sessionManager.getPage(sessionId);
        synchronized (page) {
            try {
                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout((double) props.getPageLoadTimeout()));

                page.waitForLoadState(LoadState.LOAD,
                        new Page.WaitForLoadStateOptions()
                                .setTimeout((double) props.getPageLoadTimeout()));

                String title = page.title();
                String currentUrl = page.url();
                String summary = extractBodyText(page);

                log.info("navigate 完成: {} → {}", url, currentUrl);
                return new PageInfo(title, currentUrl, summary);

            } catch (TimeoutError e) {
                log.warn("navigate 超时: {} - {}", url, e.getMessage());
                return new PageInfo("加载超时", url, "页面加载超过 "
                        + props.getPageLoadTimeout() / 1000 + " 秒，请检查网络或简化操作");
            } catch (Exception e) {
                log.error("navigate 失败: {}", url, e);
                return new PageInfo("出错", url, "导航失败: " + e.getMessage());
            }
        }
    }

    // ==================== 2. click ====================

    @Tool("点击页面元素。优先同时提供text(缩小范围)和selector(精确锁定)；仅填text也可以自动匹配")
    public ActionResult click(
            @P("要点击元素的可见文本（如\"登录\"、\"提交\"），用于缩小候选范围") String text,
            @P("可选：CSS选择器，与text联合过滤精确定位。从getPageElements获取，不要用a/button/div等裸标签名") String selector,
            @P("会话 ID，自动注入") String sessionId,
            @P("可选：匹配多个时选择第N个点击（从0开始），默认点第一个") Integer nth) {

        // ====== 1. 参数校验 ======
        boolean hasText = text != null && !text.isBlank();
        boolean hasSelector = selector != null && !selector.isBlank();

        if (!hasText && !hasSelector) {
            return ActionResult.fail("CLICK_NO_TARGET",
                    "错误：必须提供 text（要点击的元素文字）或 selector（CSS 选择器）。"
                            + "建议先用 getPageElements 查看页面有哪些可交互元素。");
        }
        if (nth == null) nth = 0;

        Page page = sessionManager.getPage(sessionId);
        synchronized (page) {
            try {
                // ====== 2. 自动关闭常见遮挡弹窗 ======
                if (props.isOverlayDismissEnabled()) {
                    dismissOverlays(page);
                }

                // ====== 3. 选择器黑名单检查 ======
                if (hasSelector) {
                    ActionResult blacklistResult = validateSelector(selector);
                    if (blacklistResult != null) return blacklistResult;
                }

                // ====== 4. 按分支定位并点击 ======
                if (hasText && hasSelector) {
                    // ★ 核心模式：文本缩小范围 → CSS 精确锁定
                    return clickByTextAndSelector(page, text, selector, nth);
                } else if (hasText) {
                    return clickByTextOnly(page, text, nth);
                } else {
                    return clickBySelectorOnly(page, selector, nth, hasText);
                }

            } catch (ClickFailedException e) {
                return handleClickError(e, text, selector);
            }
        }
    }

    // ==================== click 核心辅助方法 ====================

    /**
     * ★ 核心算法：文本缩小范围 → CSS 精确锁定。
     * <p>
     * 1. getByText(text) 找到包含文本的元素
     * 2. 逐个 evaluate 验证 CSS 选择器是否匹配自身（非后代）+ 可见性检查
     * 3. 选第 nth 个匹配项执行点击
     */
    private ActionResult clickByTextAndSelector(Page page, String text, String selector, int nth) {
        var textMatches = page.getByText(text);
        int textCount = textMatches.count();

        if (textCount == 0) {
            // 主 frame 找不到，尝试 iframe
            if (props.isSearchIframes()) {
                ActionResult iframeResult = searchInIframes(page, text, selector, nth);
                if (iframeResult != null) return iframeResult;
            }
            return ActionResult.fail("CLICK_NOT_FOUND",
                    "未找到包含文本 \"" + text + "\" 的元素。"
                            + "请用 getPageElements 查看当前页面有哪些可交互元素。");
        }

        if (textCount > props.getMaxClickCandidates()) {
            // 文本匹配过多，先尝试用 CSS 联合过滤缩小范围
            List<Integer> matchingIndices = findMatchingTextIndices(page, textMatches,
                    Math.min(textCount, MAX_TEXT_CANDIDATE_SCAN), selector);

            if (matchingIndices.isEmpty()) {
                // CSS 也没能缩小范围，返回候选列表
                List<String> candidates = collectCandidates(page, textMatches,
                        Math.min(textCount, MAX_CANDIDATE_LIST_SIZE));
                return ActionResult.tooMany(textCount, candidates, props.getMaxClickCandidates());
            }

            if (nth >= matchingIndices.size()) {
                return ActionResult.fail("CLICK_INDEX_OUT_OF_RANGE",
                        "指定的第 " + nth + " 个匹配项超出范围（文本+CSS 联合过滤后仅剩 "
                                + matchingIndices.size() + " 个元素）。");
            }

            int targetIdx = matchingIndices.get(nth);
            return performClick(page, textMatches.nth(targetIdx),
                    "文本+选择器: \"" + text + "\" + " + selector
                            + "（文本匹配 " + textCount + " 个，CSS 过滤后第 " + nth + " 个）",
                    targetIdx, false);
        }

        // 文本匹配数在合理范围内，逐个验证 CSS
        List<Integer> matchingIndices = findMatchingTextIndices(page, textMatches,
                textCount, selector);

        if (matchingIndices.isEmpty()) {
            // 文本匹配到了但 CSS 不匹配 — 返回候选列表让 LLM 调整策略
            List<String> candidates = collectCandidates(page, textMatches, textCount);
            return ActionResult.fail("CLICK_CSS_MISMATCH",
                    "文本 \"" + text + "\" 匹配了 " + textCount + " 个元素，"
                            + "但没有一个同时匹配 CSS 选择器 \"" + selector + "\"。",
                    candidates, textCount);
        }

        if (nth >= matchingIndices.size()) {
            return ActionResult.fail("CLICK_INDEX_OUT_OF_RANGE",
                    "第 " + nth + " 个匹配项超出范围（仅 " + matchingIndices.size() + " 个同时满足文本和 CSS）。");
        }

        int targetIdx = matchingIndices.get(nth);
        return performClick(page, textMatches.nth(targetIdx),
                "文本+选择器: \"" + text + "\" + " + selector
                        + (matchingIndices.size() > 1 ? "（" + matchingIndices.size() + " 个联合匹配，点击第 " + nth + " 个）" : ""),
                targetIdx, false);
    }

    /**
     * 纯文本匹配点击（无 selector）。
     */
    private ActionResult clickByTextOnly(Page page, String text, int nth) {
        var textMatches = page.getByText(text);
        int count = textMatches.count();

        if (count == 0) {
            if (props.isSearchIframes()) {
                ActionResult iframeResult = searchInIframes(page, text, null, nth);
                if (iframeResult != null) return iframeResult;
            }
            return ActionResult.fail("CLICK_NOT_FOUND",
                    "未找到包含文本 \"" + text + "\" 的可点击元素。"
                            + "请用 getPageElements 查看当前页面有哪些可交互元素。");
        }

        if (count > props.getMaxClickCandidates()) {
            List<String> candidates = collectCandidates(page, textMatches,
                    Math.min(count, MAX_CANDIDATE_LIST_SIZE));
            return ActionResult.tooMany(count, candidates, props.getMaxClickCandidates());
        }

        if (nth >= count) {
            return ActionResult.fail("CLICK_INDEX_OUT_OF_RANGE",
                    "第 " + nth + " 个匹配项超出范围（仅 " + count + " 个文本匹配）。");
        }

        // 可见性检查
        Locator target = textMatches.nth(nth);
        if (!isElementVisible(target)) {
            List<String> candidates = collectCandidates(page, textMatches,
                    Math.min(count, MAX_CANDIDATE_LIST_SIZE));
            return ActionResult.fail("CLICK_NOT_VISIBLE",
                    "第 " + nth + " 个文本匹配 \"" + text + "\" 不可见（可能被隐藏或尺寸为0）。",
                    candidates, count);
        }

        return performClick(page, target,
                "文本匹配: \"" + text + "\""
                        + (count > 1 ? "（匹配到 " + count + " 个元素，点击第 " + nth + " 个）" : ""),
                nth, false);
    }

    /**
     * 纯 CSS 选择器点击（无 text）。
     */
    private ActionResult clickBySelectorOnly(Page page, String selector, int nth, boolean hasText) {
        int count = page.locator(selector).count();

        if (count == 0) {
            if (props.isSearchIframes() && !hasText) {
                ActionResult iframeResult = searchInIframes(page, null, selector, nth);
                if (iframeResult != null) return iframeResult;
            }
            return ActionResult.fail("CLICK_NOT_FOUND",
                    "选择器 \"" + selector + "\" 未匹配到任何元素。"
                            + "请用 getPageElements 获取精确选择器。");
        }

        if (count > props.getMaxClickCandidates()) {
            // 选择器匹配过多 — 收集候选
            List<String> candidates = new ArrayList<>();
            for (int i = 0; i < Math.min(count, MAX_CANDIDATE_LIST_SIZE); i++) {
                try {
                    String elText = page.locator(selector).nth(i).innerText();
                    if (elText != null && !elText.isBlank()) {
                        candidates.add("\"" + truncateText(elText, 40) + "\" → " + selector + " [第" + i + "个]");
                    }
                } catch (Exception ignored) {}
            }
            return ActionResult.tooMany(count, candidates, props.getMaxClickCandidates());
        }

        if (nth >= count) {
            return ActionResult.fail("CLICK_INDEX_OUT_OF_RANGE",
                    "第 " + nth + " 个匹配项超出范围（仅 " + count + " 个元素匹配选择器）。");
        }

        Locator target = page.locator(selector).nth(nth);
        if (!isElementVisible(target)) {
            return ActionResult.fail("CLICK_NOT_VISIBLE",
                    "选择器 \"" + selector + "\" 第 " + nth + " 个匹配项不可见。");
        }

        return performClick(page, target,
                "选择器: " + selector
                        + (count > 1 ? "（匹配到 " + count + " 个元素，点击第 " + nth + " 个）" : ""),
                nth, false);
    }

    /**
     * 逐个验证 text-matched 元素中哪些也匹配 CSS 选择器（自身匹配 + 可见）。
     */
    private List<Integer> findMatchingTextIndices(Page page, Locator textMatches,
                                                   int scanCount, String cssSelector) {
        List<Integer> indices = new ArrayList<>();
        // 对 CSS selector 做安全转义（单引号）
        String safeSelector = cssSelector.replace("\\", "\\\\").replace("'", "\\'");
        for (int i = 0; i < scanCount; i++) {
            try {
                Object matchesObj = textMatches.nth(i).evaluate(
                        "el => el.matches('" + safeSelector + "')");
                Object visibleObj = textMatches.nth(i).evaluate(
                        "el => { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; }");
                if (Boolean.TRUE.equals(matchesObj) && Boolean.TRUE.equals(visibleObj)) {
                    indices.add(i);
                }
            } catch (Exception e) {
                log.debug("findMatchingTextIndices 跳过索引 {}: {}", i, e.getMessage());
            }
        }
        return indices;
    }

    /**
     * 执行点击 + 等待页面稳定 + 重试 + NETWORKIDLE 降级。
     */
    private ActionResult performClick(Page page, Locator target, String desc,
                                       int clickedIndex, boolean usedIframe) {
        int maxRetries = props.getClickRetryCount();
        int delayMs = props.getClickRetryDelay();
        int clickTimeout = props.getClickTimeout();
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // 滚动元素到可视区
                target.scrollIntoViewIfNeeded(
                        new Locator.ScrollIntoViewIfNeededOptions()
                                .setTimeout((double) clickTimeout));

                // 执行点击（遵守 actionability：可见、启用、稳定、可接收事件）
                target.click(new Locator.ClickOptions()
                        .setTimeout((double) clickTimeout));

                // 等待页面稳定
                waitForPageStable(page);

                String msg = "已点击 " + desc;
                if (attempt > 0) msg += "（重试 " + attempt + " 次后成功）";
                return ActionResult.ok(msg, page.title(), page.url(), clickedIndex, usedIframe);

            } catch (TimeoutError e) {
                lastException = e;
                // NETWORKIDLE 可能永不触发 → 降级为 LOAD
                if (attempt == 0 && "NETWORKIDLE".equalsIgnoreCase(props.getClickWaitStrategy())) {
                    try {
                        page.waitForLoadState(LoadState.LOAD,
                                new Page.WaitForLoadStateOptions()
                                        .setTimeout((double) clickTimeout));
                        String msg = "已点击 " + desc + "（页面降级为 LOAD 等待）";
                        return ActionResult.ok(msg, page.title(), page.url(), clickedIndex, usedIframe);
                    } catch (TimeoutError ignored) {
                        lastException = e; // 保留原始错误
                    }
                }
                if (attempt < maxRetries) {
                    log.info("点击重试 {}/{} (timeout): {}", attempt + 1, maxRetries, desc);
                    sleep(delayMs);
                }
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    log.info("点击重试 {}/{} (error): {} - {}", attempt + 1, maxRetries, desc, e.getMessage());
                    sleep(delayMs);
                }
            }
        }

        // 所有重试失败，抛出分类异常
        throw new ClickFailedException(classifyClickError(lastException), lastException.getMessage(), lastException);
    }

    /**
     * 等待页面稳定：优先 NETWORKIDLE，超时降级 LOAD。
     */
    private void waitForPageStable(Page page) {
        String strategy = props.getClickWaitStrategy();
        try {
            LoadState state = LoadState.valueOf(strategy.toUpperCase());
            page.waitForLoadState(state, new Page.WaitForLoadStateOptions()
                    .setTimeout((double) props.getNetworkIdleTimeout()));
        } catch (TimeoutError e) {
            if ("NETWORKIDLE".equalsIgnoreCase(strategy)) {
                log.debug("NETWORKIDLE 等待超时，降级为 LOAD");
                try {
                    page.waitForLoadState(LoadState.LOAD,
                            new Page.WaitForLoadStateOptions()
                                    .setTimeout((double) props.getClickTimeout()));
                } catch (TimeoutError ignored) {
                    // LOAD 也超时，接受当前状态
                }
            }
        } catch (IllegalArgumentException e) {
            log.warn("未知的等待策略: {}，使用 LOAD", strategy);
            page.waitForLoadState(LoadState.LOAD,
                    new Page.WaitForLoadStateOptions()
                            .setTimeout((double) props.getClickTimeout()));
        }
    }

    /**
     * 关闭常见遮挡弹窗（cookie 横幅、模态框等）。
     */
    private void dismissOverlays(Page page) {
        String selectorsConfig = props.getOverlayDismissSelectors();
        if (selectorsConfig == null || selectorsConfig.isBlank()) return;

        for (String cssSelector : selectorsConfig.split(",")) {
            String sel = cssSelector.trim();
            if (sel.isEmpty()) continue;
            try {
                Locator overlay = page.locator(sel);
                if (overlay.count() > 0 && overlay.first().isVisible()) {
                    overlay.first().click(new Locator.ClickOptions()
                            .setTimeout(2000).setNoWaitAfter(true));
                    log.info("已关闭覆盖层: {}", sel);
                    page.waitForTimeout(300); // 等待动画完成
                }
            } catch (Exception e) {
                // 关闭失败是预期内的 — 选择器可能存在但不适合当前页面
                log.debug("关闭覆盖层 {} 跳过: {}", sel, e.getMessage());
            }
        }
    }

    /**
     * 选择器校验：黑名单 + 伪类检查。通过返回 null，失败返回 ActionResult。
     */
    private ActionResult validateSelector(String selector) {
        String trimmed = selector.trim();
        if (BROAD_SELECTOR_BLACKLIST.contains(trimmed)
                || trimmed.matches("^[a-z][a-z0-9]*$")) {
            return ActionResult.fail("SELECTOR_BLACKLISTED",
                    "选择器 \"" + selector + "\" 过于宽泛（裸标签名），可能匹配大量元素。"
                            + "请先用 getPageElements 获取精确选择器，或改用 text 参数。");
        }
        if (trimmed.startsWith(":")) {
            return ActionResult.fail("SELECTOR_PSEUDO_ONLY",
                    "选择器 \"" + selector + "\" 缺少具体标签或属性。请用 getPageElements 获取精确选择器。");
        }
        return null; // 校验通过
    }

    /**
     * 在主 frame 找不到元素时，遍历 iframe 搜索。
     */
    private ActionResult searchInIframes(Page page, String text, String selector, int nth) {
        for (Frame frame : page.frames()) {
            if (frame == page.mainFrame()) continue; // 跳过主 frame（已检查过）
            try {
                Locator target = null;
                if (text != null && !text.isBlank()) {
                    var frameMatches = frame.getByText(text);
                    if (frameMatches.count() > 0) {
                        if (selector != null && !selector.isBlank()) {
                            // 文本+CSS 联合
                            for (int i = 0; i < Math.min(frameMatches.count(), MAX_TEXT_CANDIDATE_SCAN); i++) {
                                try {
                                    String safeSel = selector.replace("\\", "\\\\").replace("'", "\\'");
                                    Object m = frameMatches.nth(i).evaluate("el => el.matches('" + safeSel + "')");
                                    Object v = frameMatches.nth(i).evaluate(
                                            "el => { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; }");
                                    if (Boolean.TRUE.equals(m) && Boolean.TRUE.equals(v) && nth <= 0) {
                                        target = frameMatches.nth(i);
                                        break;
                                    }
                                    if (Boolean.TRUE.equals(m) && Boolean.TRUE.equals(v)) nth--;
                                } catch (Exception ignored) {}
                            }
                        } else {
                            target = frameMatches.nth(nth);
                        }
                    }
                } else if (selector != null) {
                    var frameLoc = frame.locator(selector);
                    if (frameLoc.count() > 0) target = frameLoc.nth(nth);
                }

                if (target != null) {
                    log.info("在 iframe [{}] 中找到目标元素", frame.name().isBlank() ? frame.url() : frame.name());
                    // 先在 iframe 内点击（Playwright 自动处理 frame 上下文）
                    return performClick(page, target,
                            (text != null ? "\"" + text + "\"" : "") + " (iframe:"
                                    + (frame.name().isBlank() ? frame.url() : frame.name()) + ")",
                            nth, true);
                }
            } catch (Exception e) {
                // 跨域 iframe 不可访问 — 跳过
                log.debug("iframe 搜索跳过: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * 收集匹配元素的候选描述列表（供 LLM 选择）。
     */
    private List<String> collectCandidates(Page page, Locator locator, int count) {
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < Math.min(count, MAX_CANDIDATE_LIST_SIZE); i++) {
            try {
                String elText = locator.nth(i).innerText();
                if (elText == null || elText.isBlank()) {
                    elText = locator.nth(i).getAttribute("aria-label");
                }
                if (elText == null || elText.isBlank()) elText = "(无文本)";
                candidates.add("\"" + truncateText(elText, 40) + "\" [第" + i + "个]");
            } catch (Exception e) {
                candidates.add("[第" + i + "个] 无法读取: " + e.getMessage());
            }
        }
        return candidates;
    }

    /**
     * 检查元素是否可见（bounding rect 非零）。
     */
    private boolean isElementVisible(Locator locator) {
        try {
            Object visible = locator.evaluate(
                    "el => { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; }");
            return Boolean.TRUE.equals(visible);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 分类点击异常。
     */
    private String classifyClickError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (e instanceof TimeoutError) return "CLICK_TIMEOUT";
        if (e instanceof com.microsoft.playwright.PlaywrightException) {
            if (msg.contains("covered") || msg.contains("overlap") || msg.contains("obscured"))
                return "CLICK_OVERLAY_BLOCKED";
            if (msg.contains("not visible") || msg.contains("hidden"))
                return "CLICK_NOT_VISIBLE";
            if (msg.contains("detached") || msg.contains("stale"))
                return "CLICK_DETACHED";
            if (msg.contains("disabled") || msg.contains("not enabled"))
                return "CLICK_DISABLED";
        }
        return "CLICK_FAILED";
    }

    /**
     * 结构化错误处理 — 给 LLM 提供分类错误码和修复建议。
     */
    private ActionResult handleClickError(ClickFailedException e, String text, String selector) {
        String code = e.getErrorCode();
        String detail = e.getMessage();

        Map<String, String> suggestions = Map.of(
                "CLICK_TIMEOUT", "页面加载或元素响应超时。建议：1)先截图查看页面状态 2)尝试更短的等待策略(LOAD) 3)检查网络",
                "CLICK_OVERLAY_BLOCKED", "目标元素被其他元素遮挡（弹窗/导航栏）。建议：1)先截图确认 2)手动关闭弹窗后重试",
                "CLICK_NOT_VISIBLE", "目标元素不可见。建议：1)确认页面已滚动到目标区域 2)检查元素是否在折叠区域下方",
                "CLICK_DETACHED", "目标元素已从 DOM 移除。建议：1)页面可能已跳转，先用 navigate/goBack 回到目标页面 2)重新调用 getPageElements",
                "CLICK_DISABLED", "目标元素处于禁用状态。建议：1)检查是否需要先完成前置操作 2)确认元素是否被禁用",
                "CLICK_FAILED", "点击失败。建议：用 getPageElements 查看当前页面结构后重试"
        );

        String suggestion = suggestions.getOrDefault(code,
                "建议：用 getPageElements 查看页面元素，或截图确认页面状态后重试。");

        String targetDesc = (text != null ? "\"" + text + "\"" : "") +
                            (selector != null ? " selector:" + selector : "");

        return ActionResult.fail(code, "点击 " + targetDesc + " 失败[" + code + "]: " + detail + "\n" + suggestion);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private String truncateText(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ==================== ClickFailedException 内部类 ====================

    /** 点击失败异常 — 携带分类错误码，供外层统一处理 */
    private static class ClickFailedException extends RuntimeException {
        private final String errorCode;

        ClickFailedException(String errorCode, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }

        String getErrorCode() { return errorCode; }
    }

    // ==================== 3. fill ====================

    @Tool("在输入框中填写内容")
    public ActionResult fill(
            @P("目标输入框的 CSS 选择器，建议先用 getPageElements 获取精确选择器，不要用裸标签名如 input") String selector,
            @P("要填入的文字内容") String text,
            @P("可选：填写完成后是否按回车提交，默认 false") Boolean submit,
            @P("会话 ID，自动注入") String sessionId) {

        if (selector == null || selector.isBlank()) {
            return ActionResult.fail("FILL_NO_SELECTOR",
                    "错误：选择器不能为空。请先用 getPageElements 查看输入框的精确选择器。");
        }
        if (text == null) {
            text = "";
        }

        Page page = sessionManager.getPage(sessionId);
        synchronized (page) {
            // 宽泛度检查
            ActionResult blacklistResult = validateSelector(selector);
            if (blacklistResult != null) return blacklistResult;

            try {
                int count = page.locator(selector).count();
                if (count > props.getMaxClickCandidates()) {
                    return ActionResult.fail("FILL_TOO_MANY",
                            "选择器 \"" + selector + "\" 匹配了 " + count + " 个元素（上限 "
                                    + props.getMaxClickCandidates() + "），过于宽泛。"
                                    + "请用 getPageElements 获取精确选择器。");
                }

                page.locator(selector).first().fill(text,
                        new Locator.FillOptions().setTimeout((double) props.getTimeout()));

                if (Boolean.TRUE.equals(submit)) {
                    page.keyboard().press("Enter");
                    waitForPageStable(page);
                }

                return ActionResult.ok(
                        "已在 [" + selector + "] 填入内容"
                                + (Boolean.TRUE.equals(submit) ? " 并按回车提交" : ""),
                        page.title(), page.url(), 0, false);

            } catch (Exception e) {
                log.error("fill 失败: {}", selector, e);
                return ActionResult.fail("FILL_FAILED",
                        "填写失败: " + e.getMessage()
                                + "。请确认选择器 [" + selector + "] 存在且为输入框。"
                                + "建议先用 getPageElements 查看输入框的精确选择器。");
            }
        }
    }

    // ==================== 3.5. uploadFile ====================

    @Tool("上传文件到指定选择器，用于填简历、传图片等。传的是本地文件路径，如 C:/upload/resume.pdf")
    public ActionResult uploadFile(
            @P("文件上传 input 的 CSS 选择器，从 getPageElements 获取，如 input[type=file]") String selector,
            @P("本地文件的绝对路径，可从 filesystemAgent 或 generateDocument 获取") String filePath,
            @P("会话 ID") String sessionId) {

        Page page = sessionManager.getPage(sessionId);
        synchronized (page) {
            try {
                Path path = Paths.get(filePath);
                if (!Files.exists(path)) {
                    return new ActionResult(false, "文件不存在: " + filePath);
                }
                page.locator(selector).setInputFiles(path);
                log.info("文件已上传: {} → {}", filePath, selector);
                return new ActionResult(true, "文件上传成功: " + filePath);
            } catch (Exception e) {
                log.error("上传失败: {}", filePath, e);
                return new ActionResult(false, "上传失败: " + e.getMessage());
            }
        }
    }

    // ==================== 4. screenshot ====================

    @Tool("截取当前页面的屏幕截图，保存为文件并返回访问地址。"
            + "默认只截可视区域；除非用户明确要求整页，否则不要传 fullPage=true。"
            + "整页失败时会自动降级为可视区域截图。")
    public ScreenshotResult screenshot(
            @P("可选：仅截取指定元素，CSS 选择器") String selector,
            @P("可选：true=整页滚动截图（易超时，非必要勿用）；false或省略=仅可视区域（推荐）") Boolean fullPage,
            @P("会话 ID，自动注入") String sessionId) {

        Page page = sessionManager.getPage(sessionId);
        synchronized (page) {
            try {
                byte[] bytes;
                int width, height;
                String type = props.getScreenshot().getType();
                // 截图单独放宽超时；整页失败再降级可视区
                double shotTimeoutMs = Math.max(props.getTimeout(), 60_000);

                if (selector != null && !selector.isBlank()) {
                    bytes = page.locator(selector).first().screenshot(
                            new Locator.ScreenshotOptions()
                                    .setTimeout(shotTimeoutMs)
                                    .setType(ScreenshotType.valueOf(type.toUpperCase()))
                                    .setQuality(props.getScreenshot().getQuality()));
                    String escapedSelector = selector.replace("'", "\\'");
                    Object w = page.evaluate(
                            "(function(){ var e = document.querySelector('" + escapedSelector + "'); return e ? e.offsetWidth : 0; })()");
                    Object h = page.evaluate(
                            "(function(){ var e = document.querySelector('" + escapedSelector + "'); return e ? e.offsetHeight : 0; })()");
                    width = w instanceof Number ? ((Number) w).intValue() : 0;
                    height = h instanceof Number ? ((Number) h).intValue() : 0;
                } else {
                    boolean wantFullPage = Boolean.TRUE.equals(fullPage);
                    try {
                        bytes = page.screenshot(new Page.ScreenshotOptions()
                                .setFullPage(wantFullPage)
                                .setTimeout(shotTimeoutMs)
                                .setType(ScreenshotType.valueOf(type.toUpperCase()))
                                .setQuality(props.getScreenshot().getQuality()));
                    } catch (Exception fullPageErr) {
                        if (!wantFullPage) {
                            throw fullPageErr;
                        }
                        log.warn("整页截图失败，降级为可视区域: {}", fullPageErr.getMessage());
                        bytes = page.screenshot(new Page.ScreenshotOptions()
                                .setFullPage(false)
                                .setTimeout(shotTimeoutMs)
                                .setType(ScreenshotType.valueOf(type.toUpperCase()))
                                .setQuality(props.getScreenshot().getQuality()));
                    }
                    ViewportSize vp = page.viewportSize();
                    width = vp != null ? vp.width : 0;
                    height = vp != null ? vp.height : 0;
                }

                // ———— 写入本地文件 ————
                String filename = "screenshot_" + UUID.randomUUID().toString().substring(0, 8)
                        + "." + type;
                Path saveDir = Paths.get(props.getScreenshot().getSaveDir());
                Files.createDirectories(saveDir);
                Path filePath = saveDir.resolve(filename);
                Files.write(filePath, bytes);

                String fileUrl = "/screenshots/" + filename;

                // ———— AI 识别截图内容 ————
                String description = "";
                if (imageRecognitionService != null) {
                    try {
                        long recogStart = System.currentTimeMillis();
                        description = imageRecognitionService.recognize(bytes, SCREENSHOT_RECOGNITION_PROMPT);
                        long recogElapsed = System.currentTimeMillis() - recogStart;
                        log.info("screenshot AI 识别完成 ({}ms): {}", recogElapsed, description);
                    } catch (Exception re) {
                        log.warn("screenshot AI 识别失败，将不附带描述: {}", re.getMessage());
                        description = "[截图内容识别暂时不可用]";
                    }
                }

                log.info("screenshot 已保存: {} ({}x{}, {}字节)",
                        filePath, width, height, bytes.length);
                return ScreenshotResult.ok(
                        filePath.toAbsolutePath().toString(),
                        fileUrl, width, height, bytes.length, description);

            } catch (IOException e) {
                log.error("screenshot 文件写入失败", e);
                return ScreenshotResult.fail("截图文件写入失败: " + e.getMessage());
            } catch (Exception e) {
                log.error("screenshot 失败", e);
                return ScreenshotResult.fail("截图失败: " + e.getMessage());
            }
        }
    }

    // ==================== 5. getText ====================

    @Tool("获取页面或指定元素的文本内容")
    public TextResult getText(
            @P("可选：CSS 选择器，不传则返回整页正文") String selector,
            @P("会话 ID，自动注入") String sessionId) {

        Page page = sessionManager.getPage(sessionId);
        synchronized (page) {
            try {
                String text;
                if (selector != null && !selector.isBlank()) {
                    text = page.locator(selector).first().innerText(
                            new Locator.InnerTextOptions().setTimeout((double) props.getTimeout()));
                } else {
                    text = extractBodyText(page);
                }

                return new TextResult(text, text.length());

            } catch (Exception e) {
                log.error("getText 失败", e);
                return new TextResult("获取文本失败: " + e.getMessage(), 0);
            }
        }
    }

    // ==================== 5b. getPageElements ====================

    @Tool("获取当前页面所有可交互元素（链接、按钮、输入框），返回每个元素的文本和精确 CSS 选择器，供点击/填写前参考")
    public PageElementsResult getPageElements(
            @P("会话 ID，自动注入") String sessionId) {

        Page page = sessionManager.getPage(sessionId);
        synchronized (page) {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rawElements =
                        (List<Map<String, Object>>) page.evaluate(buildElementExtractionScript());

                int total = rawElements.size();
                boolean truncated = false;
                List<PageElementsResult.ElementInfo> elements = new ArrayList<>();

                int textIdx = 0; // 递增的 textIndex，供 click(nth) 使用

                for (Map<String, Object> raw : rawElements) {
                    if (elements.size() >= MAX_PAGE_ELEMENTS) {
                        truncated = true;
                        break;
                    }

                    String tag = String.valueOf(raw.getOrDefault("tag", "?"));
                    String elText = String.valueOf(raw.getOrDefault("text", ""));
                    String sel = String.valueOf(raw.getOrDefault("selector", ""));
                    String type = String.valueOf(raw.getOrDefault("type", ""));
                    String placeholder = String.valueOf(raw.getOrDefault("placeholder", ""));

                    // 跳过无文本且无 placeholder 的元素（对 LLM 无用）
                    boolean textBlank = elText == null || elText.isBlank() || "null".equals(elText);
                    boolean placeholderBlank = placeholder == null || placeholder.isBlank() || "null".equals(placeholder);
                    if (textBlank && placeholderBlank) continue;

                    // 截断过长文本
                    if (elText.length() > 60) {
                        elText = elText.substring(0, 57) + "...";
                    }

                    // 解析新增字段
                    boolean selectorStable = !Boolean.parseBoolean(
                            String.valueOf(raw.getOrDefault("selectorUnstable", "false")));
                    String frameId = String.valueOf(raw.getOrDefault("frameId", ""));
                    String stableAlt = String.valueOf(raw.getOrDefault("stableAlternative", ""));
                    if ("null".equals(frameId)) frameId = "";
                    if ("null".equals(stableAlt)) stableAlt = "";

                    elements.add(new PageElementsResult.ElementInfo(
                            elText, tag, sel,
                            "null".equals(type) ? "" : type,
                            "null".equals(placeholder) ? "" : placeholder,
                            selectorStable,
                            frameId,
                            stableAlt,
                            textIdx));
                    textIdx++;
                }

                log.info("getPageElements 完成: 原始={}, 输出={}, 截断={}",
                        total, elements.size(), truncated);
                return new PageElementsResult(elements, total, truncated);

            } catch (Exception e) {
                log.error("getPageElements 失败", e);
                return new PageElementsResult(Collections.emptyList(), 0, false);
            }
        }
    }

    /**
     * 构建 JS 脚本：遍历页面可交互元素，提取文本和选择器。
     * <p>
     * 选择器优先级：id → data-testid → aria-label → name/type → 稳定class → href → DOM路径
     * <p>
     * 新增：动态类名检测 → selectorUnstable / stableAlternative / frameId
     */
    private String buildElementExtractionScript() {
        return """
            (function() {
                var tags = ['a','button','input','select','textarea'];
                var results = [];
                var seen = new Set();

                // 检测动态/哈希类名
                function isDynamicClass(cls) {
                    if (!cls) return false;
                    // CSS Modules: css-1a2b3c4, _abc123_456
                    if (/^(css|scoped|styled|emotion)[-_]/.test(cls)) return true;
                    if (/^_[a-zA-Z0-9]{6,}/.test(cls)) return true;
                    // styled-components: sc-bdVaJa-xxxxx
                    if (/^sc-[a-zA-Z]+-[a-zA-Z0-9]+/.test(cls)) return true;
                    // 纯哈希（过短且无语义）
                    if (cls.length > 8 && !/[aeiou]/i.test(cls) && /[a-z][0-9]/.test(cls)) return true;
                    return false;
                }

                function buildSelector(el) {
                    var stableAlt = '';
                    if (el.id) return { sel: '#' + CSS.escape(el.id), stable: true };

                    // data-testid / data-test / data-cy (testing attributes — very stable)
                    var testId = el.getAttribute('data-testid') || el.getAttribute('data-test') || el.getAttribute('data-cy');
                    if (testId) return { sel: el.tagName.toLowerCase() + '[data-testid="' + CSS.escape(testId) + '"]', stable: true };

                    // aria-label (accessibility — usually stable)
                    var ariaLabel = el.getAttribute('aria-label');
                    if (ariaLabel) return { sel: el.tagName.toLowerCase() + '[aria-label="' + CSS.escape(ariaLabel) + '"]', stable: true };

                    // name + tag (form elements)
                    if (el.name) return { sel: el.tagName.toLowerCase() + '[name="' + CSS.escape(el.name) + '"]', stable: true };

                    // type + tag (input[type=submit])
                    if (el.type && el.tagName === 'INPUT') return { sel: 'input[type="' + CSS.escape(el.type) + '"]', stable: true };

                    // class — 但检测是否为动态
                    if (el.className && typeof el.className === 'string') {
                        var classes = el.className.trim().split(/\\s+/).filter(Boolean);
                        if (classes.length > 0) {
                            var hasDynamic = classes.some(isDynamicClass);
                            var sel = el.tagName.toLowerCase() + '.' + classes.map(function(c) {
                                return CSS.escape(c);
                            }).join('.');
                            return { sel: sel, stable: !hasDynamic,
                                     unstable: hasDynamic,
                                     stableAlt: hasDynamic ? (el.tagName.toLowerCase()) : '' };
                        }
                    }

                    // href (links)
                    if (el.tagName === 'A' && el.getAttribute('href') && !el.getAttribute('href').startsWith('javascript:')) {
                        var href = el.getAttribute('href');
                        if (href.length < 80) return { sel: 'a[href="' + CSS.escape(href) + '"]', stable: true };
                    }

                    return { sel: el.tagName.toLowerCase(), stable: false,
                             unstable: true, stableAlt: '' };
                }

                function getElText(el) {
                    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                        return (el.value || '').substring(0, 60);
                    }
                    var t = (el.textContent || '').replace(/\\s+/g, ' ').trim();
                    return t.substring(0, 60);
                }

                function getFrameId() {
                    try {
                        if (window.frameElement && window.frameElement.id) return window.frameElement.id;
                        if (window.frameElement && window.frameElement.name) return window.frameElement.name;
                        if (window !== window.top) return window.location.hostname || '(iframe)';
                    } catch(e) { return '(cross-origin-iframe)'; }
                    return '';
                }

                var frameId = getFrameId();

                for (var t = 0; t < tags.length; t++) {
                    var nodes = document.querySelectorAll(tags[t]);
                    for (var i = 0; i < nodes.length; i++) {
                        var el = nodes[i];
                        var rect = el.getBoundingClientRect();
                        if (rect.width === 0 && rect.height === 0) continue;
                        var selInfo = buildSelector(el);
                        if (seen.has(selInfo.sel)) continue;
                        seen.add(selInfo.sel);

                        var entry = {
                            tag: el.tagName.toLowerCase(),
                            text: getElText(el),
                            selector: selInfo.sel,
                            type: (el.tagName === 'INPUT') ? (el.type || 'text') : '',
                            placeholder: el.getAttribute('placeholder') || '',
                            selectorUnstable: selInfo.unstable || false,
                            stableAlternative: selInfo.stableAlt || '',
                            frameId: frameId
                        };
                        results.push(entry);
                        if (results.length >= 200) break;
                    }
                    if (results.length >= 200) break;
                }
                return results;
            })()
        """;
    }

    // ==================== 6. goBack ====================

    @Tool("浏览器后退到上一页")
    public PageInfo goBack(@P("会话 ID，自动注入") String sessionId) {
        Page page = sessionManager.getPage(sessionId);
        synchronized (page) {
            try {
                page.goBack(new Page.GoBackOptions()
                        .setTimeout((double) props.getPageLoadTimeout()));
                page.waitForLoadState(LoadState.LOAD,
                        new Page.WaitForLoadStateOptions()
                                .setTimeout((double) props.getPageLoadTimeout()));

                return new PageInfo(page.title(), page.url(), extractBodyText(page));

            } catch (Exception e) {
                log.error("goBack 失败", e);
                return new PageInfo("", "", "后退失败: " + e.getMessage() + "（可能在第一页）");
            }
        }
    }

    // ==================== 7. goForward ====================

    @Tool("浏览器前进到下一页")
    public PageInfo goForward(@P("会话 ID，自动注入") String sessionId) {
        Page page = sessionManager.getPage(sessionId);
        synchronized (page) {
            try {
                page.goForward(new Page.GoForwardOptions()
                        .setTimeout((double) props.getPageLoadTimeout()));
                page.waitForLoadState(LoadState.LOAD,
                        new Page.WaitForLoadStateOptions()
                                .setTimeout((double) props.getPageLoadTimeout()));

                return new PageInfo(page.title(), page.url(), extractBodyText(page));

            } catch (Exception e) {
                log.error("goForward 失败", e);
                return new PageInfo("", "", "前进失败: " + e.getMessage() + "（可能在最后一页）");
            }
        }
    }

    // ==================== 文本提取工具方法 ====================

    /**
     * 提取页面正文，压缩空白并截断到配置的长度范围
     */
    private String extractBodyText(Page page) {
        try {
            String text = (String) page.evaluate(
                    "document.body ? document.body.innerText : ''");
            if (text == null || text.isBlank()) return "(页面无文本内容)";

            // 压缩连续换行为双换行
            text = text.replaceAll("\\n{3,}", "\n\n").trim();

            int maxLen = props.getTextMaxLength();
            int minLen = props.getTextMinLength();

            if (text.length() > maxLen) {
                // 在 minLen ~ maxLen 之间找最后一个句号截断
                String window = text.substring(minLen, Math.min(maxLen, text.length()));
                int cut = window.lastIndexOf("。");
                if (cut > 0) {
                    return text.substring(0, minLen + cut + 1)
                            + "\n\n...（已截断，全文共 " + text.length() + " 字）";
                }
                return text.substring(0, maxLen)
                        + "\n\n...（已截断，全文共 " + text.length() + " 字）";
            }
            return text;
        } catch (Exception e) {
            log.warn("提取页面文本失败: {}", e.getMessage());
            return "(文本提取失败: " + e.getMessage() + ")";
        }
    }
}
