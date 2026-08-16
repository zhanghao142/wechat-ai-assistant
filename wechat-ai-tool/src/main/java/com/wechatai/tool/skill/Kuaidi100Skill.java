package com.wechatai.tool.skill;

import com.wechatai.common.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 快递100查询 Skill — 绑定到 Kuaidi100Tool 的工具。
 * <p>
 * 触发场景：用户提到"快递单号""查快递""到哪了""预计送达""运费"等关键词时触发。
 * 工具调用顺序：先 autoNumber 识别快递公司 → 再 queryTrace 查轨迹。
 * 顺丰/中通必须向用户索取手机号后四位。
 */
@Component
public class Kuaidi100Skill implements Skill {

    @Override
    public String getName() {
        return "kuaidi100";
    }

    @Override
    public List<String> getBoundTools() {
        return List.of("autoNumber", "queryTrace", "queryByPhone", "estimatePrice");
    }

    @Override
    public String getPromptSegment() {
        return """
                ### 快递查询技能规则

                #### 触发条件
                当用户提到以下任何一种情况时，优先使用快递100工具：
                - 提供了快递单号，想查询物流状态（如：SF1234567890 到哪了）
                - 想估算寄快递的费用（如：从上海寄到北京多少钱）
                - 想根据手机号查询快递（如：查询13812345678的快递）
                - 询问快递公司类型（如：这个单号是什么快递）

                #### 工具选择规则
                1. 用户只提供了快递单号、未指定快递公司时：
                   - 先调用 `autoNumber` 识别快递公司
                   - 根据识别结果再调用 `queryTrace` 查询轨迹
                2. 用户明确说了快递公司（如"圆通""中通"）：
                   - 直接调用 `queryTrace`
                3. 用户提供了手机号想查快递：
                   - 调用 `queryByPhone`，返回查询说明，引导用户提供具体单号
                4. 用户问运费：
                   - 调用 `estimatePrice`

                #### 手机号验证规则
                - 查询 **顺丰**(sf) 或 **中通**(zto) 快递时，必须向用户索取 **收件人手机号后四位**
                - 如果 `queryTrace` 返回需要手机号的提示，立即回复用户："查询该快递需要手机号后4位验证，请提供收件人的手机号后4位"
                - 其他快递公司（如圆通、韵达、申通等）不需要手机号

                #### 输出格式要求
                - 工具返回的结果已经是格式化好的中文文本
                - 直接转发给用户即可，不要添加链接或额外说明
                - 如果查询失败，如实告知用户失败原因，并建议联系快递公司客服

                #### 常见快递公司客服电话
                - 顺丰：95338
                - 圆通：95554
                - 中通：95311
                - 申通：95543
                - 韵达：95546
                - 极兔：4008201666
                - 邮政：11183
                """;
    }
}
