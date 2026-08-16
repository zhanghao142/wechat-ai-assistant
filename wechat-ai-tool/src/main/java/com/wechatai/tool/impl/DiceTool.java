package com.wechatai.tool.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 骰子表达式工具，支持常见桌游记法。
 */
@Component
public class DiceTool {

    private static final Pattern DICE_PATTERN = Pattern.compile("^(\\d{0,3})d(\\d{1,4})([+-]\\d{1,6})?$");
    private static final int MAX_DICE = 100;
    private static final int MAX_SIDES = 1000;

    private final SecureRandom random = new SecureRandom();

    @Tool("掷骰子工具。当用户要求掷骰、跑团检定或输入2d6、1d20+3等骰子表达式时调用，返回每颗骰子和总点数")
    public String rollDice(
            @P("骰子表达式，如1d20、2d6+3、4d10-2；最多100颗骰子，每颗最多1000面") String expression) {

        if (expression == null) {
            return "骰子表达式不能为空，例如：2d6+3";
        }

        String normalized = expression.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        Matcher matcher = DICE_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return "无法识别骰子表达式，请使用类似 1d20、2d6+3 或 4d10-2 的格式";
        }

        int count = matcher.group(1).isEmpty() ? 1 : Integer.parseInt(matcher.group(1));
        int sides = Integer.parseInt(matcher.group(2));
        int modifier = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));

        if (count < 1 || count > MAX_DICE) {
            return "骰子数量必须在1到" + MAX_DICE + "之间";
        }
        if (sides < 2 || sides > MAX_SIDES) {
            return "骰子面数必须在2到" + MAX_SIDES + "之间";
        }

        List<Integer> rolls = new ArrayList<>(count);
        long subtotal = 0;
        for (int i = 0; i < count; i++) {
            int roll = random.nextInt(sides) + 1;
            rolls.add(roll);
            subtotal += roll;
        }
        long total = subtotal + modifier;

        String modifierText = modifier == 0 ? "无" : (modifier > 0 ? "+" + modifier : String.valueOf(modifier));
        return "骰子：" + normalized + "\n"
                + "掷骰结果：" + rolls + "\n"
                + "点数小计：" + subtotal + "\n"
                + "修正值：" + modifierText + "\n"
                + "最终结果：" + total;
    }
}
