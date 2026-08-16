package com.wechatai.tool.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 候选项随机抽取工具。
 */
@Component
public class RandomChoiceTool {

    private static final int MAX_OPTIONS = 100;

    private final SecureRandom random = new SecureRandom();

    @Tool("随机选择工具。当用户说随机选、抽签、今晚吃什么、去哪玩或难以在多个候选项中决定时调用，公平地抽取不重复结果")
    public String makeRandomChoice(
            @P("候选项列表，例如：[火锅, 烧烤, 日料]") List<String> options,
            @P("需要抽取的数量，必须小于或等于有效候选项数量") int count) {

        if (options == null || options.isEmpty()) {
            return "候选项不能为空，请至少提供两个不同选项";
        }

        LinkedHashSet<String> uniqueOptions = new LinkedHashSet<>();
        for (String option : options) {
            if (option != null && !option.isBlank()) {
                uniqueOptions.add(option.trim());
            }
        }

        if (uniqueOptions.size() < 2) {
            return "去除空白和重复内容后，至少需要两个不同候选项";
        }
        if (uniqueOptions.size() > MAX_OPTIONS) {
            return "候选项不能超过" + MAX_OPTIONS + "个";
        }
        if (count < 1 || count > uniqueOptions.size()) {
            return "抽取数量必须在1到" + uniqueOptions.size() + "之间";
        }

        List<String> shuffled = new ArrayList<>(uniqueOptions);
        Collections.shuffle(shuffled, random);
        List<String> selected = List.copyOf(shuffled.subList(0, count));

        if (count == 1) {
            return "本次共有" + uniqueOptions.size() + "个有效候选项\n随机结果：" + selected.getFirst();
        }
        return "本次共有" + uniqueOptions.size() + "个有效候选项\n"
                + "随机抽取" + count + "项（不重复）：" + selected;
    }
}
