package com.wechatai.tool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Browser Tool 配置 — 绑定 application.properties 中 browser.* 前缀。
 */
@Configuration
@ConfigurationProperties(prefix = "browser")
public class BrowserProperties {

    /** 浏览器可执行文件路径，留空则 Playwright 自动查找系统已安装的 Chrome/Edge */
    private String executablePath = "";

    /** 是否无头模式 */
    private boolean headless = true;

    /** 单次操作超时 ms */
    private int timeout = 30000;

    /** 页面加载超时 ms */
    private int pageLoadTimeout = 30000;

    /** 空闲关闭时间 秒 */
    private int idleTimeoutSeconds = 300;

    /** 最大并发浏览器实例数 */
    private int maxInstances = 10;

    /** URL 白名单，逗号分隔 */
    private String urlWhitelist;

    /** URL 黑名单，逗号分隔 */
    private String urlBlacklist;

    /** 页面文本提取最大长度 */
    private int textMaxLength = 1500;

    /** 页面文本提取最小长度 */
    private int textMinLength = 1000;

    // ==================== click 高级配置 ====================

    /** NETWORKIDLE 等待超时 ms（超时后降级为 LOAD，适配 SPA 页面） */
    private int networkIdleTimeout = 5000;

    /** 点击操作独立超时 ms */
    private int clickTimeout = 10000;

    /** 点击失败重试次数（0 = 不重试） */
    private int clickRetryCount = 1;

    /** 重试间隔 ms */
    private int clickRetryDelay = 500;

    /** 点击后等待策略：NETWORKIDLE / LOAD / DOMCONTENTLOADED */
    private String clickWaitStrategy = "NETWORKIDLE";

    /** 文本/选择器匹配元素数上限（超过则返回候选列表而非直接拒绝） */
    private int maxClickCandidates = 20;

    /** 候选列表最大返回条数 */
    private int maxCandidateListSize = 10;

    /** 是否在点击前自动关闭常见遮挡弹窗（cookie 横幅、模态框等） */
    private boolean overlayDismissEnabled = true;

    /** 遮挡关闭选择器，逗号分隔（常见 cookie 弹窗/模态框关闭按钮的 CSS 选择器） */
    private String overlayDismissSelectors =
            "[aria-label*='关闭'],[aria-label*='Close'],[aria-label*='Dismiss'],"
          + "[aria-label*='同意'],[aria-label*='Accept'],"
          + "button:has-text('同意'),button:has-text('Accept'),button:has-text('Accept all'),"
          + "[class*='cookie'] button:not([class*='settings']),[id*='cookie'] button,"
          + ".modal .close,.modal-close,.popup-close,.overlay-close,"
          + "[data-dismiss='modal'],[class*='dialog'] [class*='close'],"
          + ".banner .dismiss,.toast .close,.notification .close";

    /** 是否在主 frame 找不到元素时搜索 iframe */
    private boolean searchIframes = true;

    /** 截图子配置 */
    private ScreenshotConfig screenshot = new ScreenshotConfig();

    // ==================== getters / setters ====================

    public String getExecutablePath() { return executablePath; }
    public void setExecutablePath(String executablePath) { this.executablePath = executablePath; }

    public boolean isHeadless() { return headless; }
    public void setHeadless(boolean headless) { this.headless = headless; }

    public int getTimeout() { return timeout; }
    public void setTimeout(int timeout) { this.timeout = timeout; }

    public int getPageLoadTimeout() { return pageLoadTimeout; }
    public void setPageLoadTimeout(int pageLoadTimeout) { this.pageLoadTimeout = pageLoadTimeout; }

    public int getIdleTimeoutSeconds() { return idleTimeoutSeconds; }
    public void setIdleTimeoutSeconds(int idleTimeoutSeconds) { this.idleTimeoutSeconds = idleTimeoutSeconds; }

    public int getMaxInstances() { return maxInstances; }
    public void setMaxInstances(int maxInstances) { this.maxInstances = maxInstances; }

    public String getUrlWhitelist() { return urlWhitelist; }
    public void setUrlWhitelist(String urlWhitelist) { this.urlWhitelist = urlWhitelist; }

    public String getUrlBlacklist() { return urlBlacklist; }
    public void setUrlBlacklist(String urlBlacklist) { this.urlBlacklist = urlBlacklist; }

    public int getTextMaxLength() { return textMaxLength; }
    public void setTextMaxLength(int textMaxLength) { this.textMaxLength = textMaxLength; }

    public int getTextMinLength() { return textMinLength; }
    public void setTextMinLength(int textMinLength) { this.textMinLength = textMinLength; }

    // ==================== click 高级配置 getters/setters ====================

    public int getNetworkIdleTimeout() { return networkIdleTimeout; }
    public void setNetworkIdleTimeout(int networkIdleTimeout) { this.networkIdleTimeout = networkIdleTimeout; }

    public int getClickTimeout() { return clickTimeout; }
    public void setClickTimeout(int clickTimeout) { this.clickTimeout = clickTimeout; }

    public int getClickRetryCount() { return clickRetryCount; }
    public void setClickRetryCount(int clickRetryCount) { this.clickRetryCount = clickRetryCount; }

    public int getClickRetryDelay() { return clickRetryDelay; }
    public void setClickRetryDelay(int clickRetryDelay) { this.clickRetryDelay = clickRetryDelay; }

    public String getClickWaitStrategy() { return clickWaitStrategy; }
    public void setClickWaitStrategy(String clickWaitStrategy) { this.clickWaitStrategy = clickWaitStrategy; }

    public int getMaxClickCandidates() { return maxClickCandidates; }
    public void setMaxClickCandidates(int maxClickCandidates) { this.maxClickCandidates = maxClickCandidates; }

    public int getMaxCandidateListSize() { return maxCandidateListSize; }
    public void setMaxCandidateListSize(int maxCandidateListSize) { this.maxCandidateListSize = maxCandidateListSize; }

    public boolean isOverlayDismissEnabled() { return overlayDismissEnabled; }
    public void setOverlayDismissEnabled(boolean overlayDismissEnabled) { this.overlayDismissEnabled = overlayDismissEnabled; }

    public String getOverlayDismissSelectors() { return overlayDismissSelectors; }
    public void setOverlayDismissSelectors(String overlayDismissSelectors) { this.overlayDismissSelectors = overlayDismissSelectors; }

    public boolean isSearchIframes() { return searchIframes; }
    public void setSearchIframes(boolean searchIframes) { this.searchIframes = searchIframes; }

    // ==================== 截图子配置 getters/setters ====================

    public ScreenshotConfig getScreenshot() { return screenshot; }
    public void setScreenshot(ScreenshotConfig screenshot) { this.screenshot = screenshot; }

    // ==================== 截图子配置 ====================

    public static class ScreenshotConfig {
        private int maxWidth = 1920;
        private int quality = 80;
        private String type = "jpeg";
        /** 截图保存目录（相对于项目根目录），默认 ./screenshots */
        private String saveDir = "./screenshots";

        public int getMaxWidth() { return maxWidth; }
        public void setMaxWidth(int maxWidth) { this.maxWidth = maxWidth; }

        public int getQuality() { return quality; }
        public void setQuality(int quality) { this.quality = quality; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getSaveDir() { return saveDir; }
        public void setSaveDir(String saveDir) { this.saveDir = saveDir; }
    }
}
