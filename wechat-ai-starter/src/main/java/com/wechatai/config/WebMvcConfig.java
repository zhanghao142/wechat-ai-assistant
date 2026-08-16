package com.wechatai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * WebMvc 全局配置。
 * <p>
 * 注册 TraceId 拦截器、统一日期格式、跨域、截图静态资源映射等。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${browser.screenshot.save-dir:./screenshots}")
    private String screenshotSaveDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 映射 /screenshots/** → 本地截图目录，使截图可通过 URL 直接访问
        String absolutePath = Paths.get(screenshotSaveDir).toAbsolutePath().toUri().toString();
        registry.addResourceHandler("/screenshots/**")
                .addResourceLocations(absolutePath);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
