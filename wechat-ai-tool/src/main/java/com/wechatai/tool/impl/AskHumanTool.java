package com.wechatai.tool.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 向用户追问工具（SYSTEM）— 当 AI 需要更多信息时调用。
 * <p>
 * 此工具不执行实际业务操作，而是将问题原样返回给 LLM，
 * 由 LLM 整合后向用户发起追问。
 */
@Component
public class AskHumanTool {

    private static final Logger log = LoggerFactory.getLogger(AskHumanTool.class);

    @Tool("向用户追问更多信息，适用于参数缺失或意图不明确时。调用此工具后 LLM 会暂停等待用户补充信息")
    public String askHuman(@P("需要向用户确认或追问的问题") String question) {
        if (question == null || question.trim().isEmpty()) {
            return "已请求用户补充更多信息";
        }
        log.info("向用户追问: {}", question);
        return "已向用户提问: " + question + "。请等待用户回复后继续处理。";
    }
}
