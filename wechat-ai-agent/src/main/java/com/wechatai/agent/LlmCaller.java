package com.wechatai.agent;

import com.wechatai.tool.model.vo.ToolDefinitionVO;

import java.util.List;
import java.util.Map;

/**
 * LLM 调用契约 — agent 模块不直接依赖 ai 模块，只依赖此接口。
 * <p>
 * 由 ai 模块的 {@code LlmService} 实现（RestClient → DeepSeek API）。
 * 返回 Map：{content, toolCalls, elapsed}
 */
public interface LlmCaller {

    Map<String, Object> callLlm(List<Map<String, Object>> messages,
                                 List<ToolDefinitionVO> toolDefs);
}
