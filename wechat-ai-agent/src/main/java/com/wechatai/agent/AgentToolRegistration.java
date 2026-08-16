package com.wechatai.agent;

import com.wechatai.common.enums.ToolCategory;
import com.wechatai.tool.model.vo.ToolDefinitionVO;
import com.wechatai.tool.registry.AgentToolRegistry;
import com.wechatai.tool.registry.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 将子 Agent 注册为中央 Orchestrator 可选的"专家工具"。
 * <p>
 * 中央 LLM 看到 generalAgent / taxiAgent 两个工具，
 * 选中后由 ToolExecuteNode 路由到 AgentInvoker 执行子 ReAct。
 */
@Component
public class AgentToolRegistration {

    private static final Logger log = LoggerFactory.getLogger(AgentToolRegistration.class);

    private final ToolRegistry toolRegistry;
    private final AgentToolRegistry agentToolRegistry;

    public AgentToolRegistration(@Lazy ToolRegistry toolRegistry,
                                  AgentToolRegistry agentToolRegistry) {
        this.toolRegistry = toolRegistry;
        this.agentToolRegistry = agentToolRegistry;
    }

    @PostConstruct
    public void register() {
        registerAgentDef("generalAgent",
                "处理日常问答、文档搜索、天气查询、翻译、浏览器操作、邮件发送、" +
                "提醒设置、股票查询、内容转换等通用任务。" +
                "用户不涉及打车/出行、文件系统操作、求职招聘时选我。",
                ToolCategory.READ, "AGENT:General");

        registerAgentDef("taxiAgent",
                "处理打车、路线规划、地点搜索等出行需求。" +
                "用户提到打车、叫车、导航、路线、怎么去多远、附近有什么、" +
                "公交地铁步行骑行、订单查询取消时选我。",
                ToolCategory.READ, "AGENT:Taxi");

        registerAgentDef("filesystemAgent",
                "处理文件读写、目录操作等文件系统任务。" +
                "用户提到读文件、写文件、保存内容到文件、查看目录、列出文件、" +
                "创建文件夹、删除文件时选我。",
                ToolCategory.READ, "AGENT:Filesystem");

        registerAgentDef("jobAgent",
                "处理简历查询与编辑、职位搜索、职位投递等求职招聘任务。" +
                "用户提到看简历、改简历、搜职位、找工作、投递、应聘时选我。",
                ToolCategory.READ, "AGENT:Job");

        registerAgentDef("mcdonaldsAgent",
                "处理所有麦当劳相关任务：点餐下单（到店/外送/得来速）、菜单与营养查询、" +
                "优惠券查询与领取、积分查询与兑换、营销活动日历、配送地址管理等。" +
                "用户提到麦当劳、麦乐送、麦当劳优惠券、麦当劳点餐、麦当劳积分、麦麦省时选我。",
                ToolCategory.READ, "AGENT:McDonalds");

        log.info("✅ Agent 工具注册完成: generalAgent + taxiAgent + filesystemAgent + jobAgent + mcdonaldsAgent");
    }

    private void registerAgentDef(String name, String description,
                                   ToolCategory category, String source) {
        ToolDefinitionVO def = new ToolDefinitionVO();
        def.setName(name);
        def.setDescription(description);
        def.setCategory(category);
        def.setEnabled(true);
        def.setParameters(Map.of(
                "type", "object",
                "properties", Map.of(
                        "task", Map.of("type", "string",
                                "description", "需要交给该专家处理的任务描述，用中文自然语言")
                ),
                "required", List.of("task")
        ));
        def.setSource(source);
        toolRegistry.registerExternalTool(def, source);
        log.info("  🤖 Agent 工具已注册: {} ({})", name, source);
    }
}
