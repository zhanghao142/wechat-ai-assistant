package com.wechatai.proactive.config;

import com.wechatai.proactive.critic.CriticTriggerDecider;
import com.wechatai.proactive.critic.RuleInjector;
import com.wechatai.proactive.feedback.FeedbackParser;
import com.wechatai.proactive.path.ConfidenceUpdater;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 行为路径自学习系统配置。
 */
@Configuration
public class ProactiveConfig {

    @Value("${wechat.proactive.hot-threshold:0.75}")
    private double hotThreshold;

    @Value("${wechat.proactive.min-similarity:0.6}")
    private double minSimilarity;

    @Value("${wechat.proactive.retrieve-top-k:10}")
    private int retrieveTopK;

    @Value("${wechat.proactive.enabled:true}")
    private boolean enabled;

    @Bean
    public ConfidenceUpdater confidenceUpdater() {
        return new ConfidenceUpdater();
    }

    @Bean
    public FeedbackParser feedbackParser() {
        return new FeedbackParser();
    }

    @Bean
    public CriticTriggerDecider criticTriggerDecider() {
        return new CriticTriggerDecider();
    }

    @Bean
    public RuleInjector ruleInjector() {
        return new RuleInjector();
    }

    public double getHotThreshold() { return hotThreshold; }
    public double getMinSimilarity() { return minSimilarity; }
    public int getRetrieveTopK() { return retrieveTopK; }
    public boolean isEnabled() { return enabled; }
}
