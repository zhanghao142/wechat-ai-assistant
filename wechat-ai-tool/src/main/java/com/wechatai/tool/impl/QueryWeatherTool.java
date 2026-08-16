package com.wechatai.tool.impl;

import com.wechatai.tool.model.dto.WeatherResponse;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 查询天气行情工具。
 * 支持查询指定城市的天气，支持实时天气和未来最多7天预报，包含温度、湿度、风力、空气质量(AQI)、污染物分项、生活指数等全方位气象信息。
 */



@Slf4j
@Component
public class QueryWeatherTool {

    private static final String WEATHER_API_URL = "https://uapis.cn/api/v1/misc/weather";

    private final RestClient.Builder restClientBuilder;

    private RestClient restClient;

    public QueryWeatherTool(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @PostConstruct
    public void init() {
        restClient = restClientBuilder.build();
    }

    @Tool("查询指定城市的天气，支持实时天气和未来最多7天预报，包含温度、湿度、风力、空气质量(AQI)、污染物分项、生活指数等全方位气象信息。当用户询问天气、气温、是否下雨、风向风力、空气好不好、需不需要带伞、适不适合出门、未来几天天气等情况时使用。支持中国城市及国际主要城市（需将城市名译为国际公认的英文名称，如北京→Beijing、杭州→Hangzhou、东京→Tokyo、伦敦→London、纽约→New York）")
    public WeatherResponse queryWeather(
            @P("城市英文名，如 Beijing、Hangzhou、Tokyo、London、New York") String city) {

        if (city == null || city.trim().isEmpty()) {
            log.warn("[天气查询] 城市名称为空");
            return createEmptyResponse("未知城市", "请输入城市名称");
        }

        String uri = UriComponentsBuilder.fromHttpUrl(WEATHER_API_URL)
                .queryParam("city", city.trim())
                .queryParam("lang", "zh")
                .queryParam("extended", "true")
                .queryParam("forecast", "true")
                .queryParam("indices", "true")
                .toUriString();

        log.info("[天气查询] 请求: {}", uri);

        try {
            WeatherResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(WeatherResponse.class);

            if (response != null) {
                log.info("[天气查询] 成功: {} {} {}°C",
                        response.getCity(), response.getWeather(), response.getTemperature());
            }
            return response;

        } catch (RestClientException e) {
            log.warn("[天气查询] 请求失败: {}", e.getMessage());
            return createEmptyResponse(city, "查询天气失败，请稍后重试");
        } catch (Exception e) {
            log.error("[天气查询] 异常: {}", e.getMessage(), e);
            return createEmptyResponse(city, "查询天气异常，请稍后重试");
        }
    }

    private WeatherResponse createEmptyResponse(String city, String message) {
        WeatherResponse response = new WeatherResponse();
        response.setCity(city);
        response.setWeather(message);
        response.setTemperature(0);
        return response;
    }
}
