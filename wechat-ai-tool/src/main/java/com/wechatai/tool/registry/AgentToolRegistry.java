package com.wechatai.tool.registry;

import com.wechatai.tool.model.vo.ToolDefinitionVO;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 视角的工具有限视图。
 * <p>
 * 不改 ToolRegistry 注册机制，只做过滤。
 * 每个 Agent 通过此类获取自己管辖的工具子集，而非看到全量 40 个工具。
 *
 * <pre>
 * 使用方式：
 *   // GeneralAgent：排除所有 MCP 工具
 *   agentToolRegistry.filter("!MCP:")
 *
 *   // TaxiAgent：只看滴滴 MCP 工具
 *   agentToolRegistry.filter("MCP:DIDI")
 *
 *   // 调试：看全部
 *   agentToolRegistry.filter(null)
 * </pre>
 */
@Component
public class AgentToolRegistry {

    private final ToolRegistry toolRegistry;

    public AgentToolRegistry(@Lazy ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 按 source 前缀过滤工具列表。
     *
     * @param sourceFilter 过滤规则：
     *        null / ""           → 返回全部
     *        "MCP:DIDI"          → source 精确等于 "MCP:DIDI"
     *        "!MCP:"             → 排除 source 以 "MCP:" 开头的（即只看本地工具）
     *        "MCP:"              → 只看 source 以 "MCP:" 开头的（所有 MCP 工具）
     * @return 过滤后的已启用工具定义列表
     */
    public List<ToolDefinitionVO> filter(String sourceFilter) {
        List<ToolDefinitionVO> all = toolRegistry.listTools(null, null)
                .stream()
                .filter(ToolDefinitionVO::isEnabled)
                .toList();

        if (sourceFilter == null || sourceFilter.isEmpty()) {
            return all;
        }

        // 排除模式："!MCP:" → 只看 source 不以 "MCP:" 开头的（包括 null/LOCAL）
        if (sourceFilter.startsWith("!")) {
            String exclude = sourceFilter.substring(1);
            return all.stream()
                    .filter(t -> t.getSource() == null || !t.getSource().startsWith(exclude))
                    .toList();
        }

        // 前缀匹配："MCP:" → 匹配所有 source 以 "MCP:" 开头的
        if (sourceFilter.endsWith(":")) {
            return all.stream()
                    .filter(t -> t.getSource() != null && t.getSource().startsWith(sourceFilter))
                    .toList();
        }

        // 精确匹配："MCP:DIDI" → source == "MCP:DIDI"
        return all.stream()
                .filter(t -> sourceFilter.equals(t.getSource()))
                .toList();
    }

    // ── 便捷方法 ──

    /** GeneralAgent：本地工具 + 猎聘 MCP（排除滴滴/文件系统 MCP 和 Agent 工具） */
    public List<ToolDefinitionVO> getGeneralTools() {
        return filter("!MCP:").stream()
                .filter(t -> t.getSource() == null || !t.getSource().startsWith("AGENT:"))
                .toList();
    }

    /** TaxiAgent：13 个滴滴 MCP 工具 */
    public List<ToolDefinitionVO> getTaxiTools() {
        return filter("MCP:DIDI");
    }

    /** FilesystemAgent：文件系统 MCP 工具 */
    public List<ToolDefinitionVO> getFilesystemTools() {
        return filter("MCP:FILESYSTEM");
    }

    /** 猎聘 MCP：简历查询、职位搜索、一键投递等 */
    public List<ToolDefinitionVO> getLiepinTools() {
        return filter("MCP:LIEPIN");
    }

    /** 麦当劳 MCP：优惠券查询/领取、营销日历等 */
    public List<ToolDefinitionVO> getMcdonaldsTools() {
        return filter("MCP:MCDONALDS");
    }

    /**
     * 中央 Orchestrator 可选的 Agent 工具。
     * <p>
     * 底层 MCP/工具未就绪的专家不暴露给中央 LLM，避免选中后无工具幻觉。
     */
    public List<ToolDefinitionVO> getAgentTools() {
        return filter("AGENT:").stream()
                .filter(this::isAgentBackendReady)
                .toList();
    }

    private boolean isAgentBackendReady(ToolDefinitionVO agent) {
        String name = agent.getName();
        if (name == null) return true;
        return switch (name) {
            case "filesystemAgent" -> !getFilesystemTools().isEmpty();
            case "taxiAgent" -> !getTaxiTools().isEmpty();
            case "jobAgent" -> !getLiepinTools().isEmpty();
            case "mcdonaldsAgent" -> !getMcdonaldsTools().isEmpty();
            case "generalAgent" -> true; // 本地工具始终可用
            default -> true;
        };
    }
}
