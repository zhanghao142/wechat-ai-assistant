package com.wechatai.wechat.config;

import com.wechatai.wechat.multi.WechatClientRegistry;
import com.wechatai.wechat.multi.WechatClientRegistryImpl;
import com.wechatai.wechat.multi.WechatLoginStateStore;
import com.wechatai.wechat.multi.WechatLoginStateStoreImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多账号自动配置 — 仅在 {@code wechat.multi-account.enabled=true} 时激活。
 */
@Configuration
@ConditionalOnProperty(name = "wechat.multi-account.enabled", havingValue = "true")
@EnableConfigurationProperties(WechatMultiAccountProperties.class)
public class WechatMultiAccountConfig {

    @Bean
    public WechatLoginStateStore wechatLoginStateStore(WechatMultiAccountProperties props) {
        return new WechatLoginStateStoreImpl(props);
    }

    @Bean
    public WechatClientRegistry wechatClientRegistry(WechatMultiAccountProperties props,
                                                      WechatLoginStateStore loginStateStore,
                                                      ApplicationEventPublisher eventPublisher) {
        WechatClientRegistryImpl registry = new WechatClientRegistryImpl(props, loginStateStore);
        registry.setEventPublisher(eventPublisher);
        return registry;
    }
}
