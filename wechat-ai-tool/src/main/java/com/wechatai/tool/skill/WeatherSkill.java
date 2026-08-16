package com.wechatai.tool.skill;

import com.wechatai.common.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 天气 Skill — 绑定到 queryWeather 工具。
 * <p>
 * LLM 调用 queryWeather 后，下一轮推理自动加载此 Skill，
 * 包含天气回复格式和天气+景点的工具组合规则。
 */
@Component
public class WeatherSkill implements Skill {

    @Override
    public String getName() {
        return "weather";
    }

    @Override
    public List<String> getBoundTools() {
        return List.of("queryWeather");
    }

    @Override
    public String getPromptSegment() {
        return """
                ### 天气查询回复格式
                🌤 [城市名]目前[天气状况]，[温度]°C，湿度[湿度]%，[风向][风力]
                示例：🌤 南京目前阴，33°C，湿度65%，西南风4级

                ### 工具组合规则（天气）
                查完天气后，如果用户还问景点/活动推荐，根据天气搜索结果：
                - 下雨/中雨/大雨 → 搜"城市名 + 下雨天 + 室内景点"
                - 阴天/多云 → 搜"城市名 + 景点"
                - 晴天 → 搜"城市名 + 户外景点"
                """;
    }
}
