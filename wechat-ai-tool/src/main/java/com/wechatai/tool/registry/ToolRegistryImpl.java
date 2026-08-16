package com.wechatai.tool.registry;

import com.wechatai.common.enums.ToolCategory;
import com.wechatai.tool.annotation.WriteTool;
import com.wechatai.tool.mcp.McpClientManager;
import com.wechatai.tool.model.vo.ToolDefinitionVO;
import com.wechatai.tool.model.vo.ToolHistoryVO;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 工具注册中心实现：扫描 Spring 容器中所有带 @Tool 方法的 Bean，
 * 提取工具定义，维护启用状态。
 * <p>
 * 标记 @Lazy 避免与 ApplicationContext 的循环依赖。
 */
@Lazy
@Component
public class ToolRegistryImpl implements ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryImpl.class);

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<String, ToolEntry> toolMap = new LinkedHashMap<>();
    private final Map<String, Object> toolBeanMap = new LinkedHashMap<>();
    private final List<ToolHistoryVO> historyList = new CopyOnWriteArrayList<>();

    private final ApplicationContext applicationContext;
    private final McpClientManager mcpClientManager;

    public ToolRegistryImpl(ApplicationContext applicationContext, @Lazy McpClientManager mcpClientManager) {
        this.applicationContext = applicationContext;
        this.mcpClientManager = mcpClientManager;
    }

    @PostConstruct
    public void init() {
        scanToolBeans();
        log.info("✅ 工具扫描完成，共发现 {} 个工具: {}",
                toolMap.size(), new ArrayList<>(toolMap.keySet()));
    }

    private void scanToolBeans() {
        String[] beanNames = applicationContext.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> beanClass = getRealClass(bean);

            for (Method method : beanClass.getMethods()) {
                Tool toolAnn = method.getAnnotation(Tool.class);
                if (toolAnn == null) continue;

                String toolName = method.getName();
                String[] descArr = toolAnn.value();
                String description = descArr.length > 0 ? descArr[0] : "";

                if (toolMap.containsKey(toolName)) {
                    log.warn("⚠ 工具名冲突: {} 已在 {} 中注册", toolName,
                            toolMap.get(toolName).bean.getClass().getName());
                    continue;
                }

                ToolDefinitionVO def = buildToolDefinition(toolName, description, method, beanClass);
                toolMap.put(toolName, new ToolEntry(def, bean));
                toolBeanMap.put(toolName, bean);

                log.info("  📦 工具: {} → {}", toolName, beanClass.getSimpleName());
            }
        }
    }

    private Class<?> getRealClass(Object bean) {
        Class<?> clazz = bean.getClass();
        while (clazz.getName().contains("$$")) {
            clazz = clazz.getSuperclass();
        }
        return clazz;
    }

    /** SYSTEM 类工具名集合（其余默认为 READ） */
    private static final Set<String> SYSTEM_TOOLS = Set.of("askHuman", "exitConversation");

    /** WRITE 类工具名集合 — 有副作用，失败不可盲目重试 */
    private static final Set<String> WRITE_TOOLS = Set.of("transformContent");

    private ToolDefinitionVO buildToolDefinition(String toolName, String description, Method method, Class<?> beanClass) {
        ToolDefinitionVO def = new ToolDefinitionVO();
        def.setName(toolName);
        def.setDescription(description);
        def.setEnabled(true);
        // 工具分类：SYSTEM / WRITE 按名匹配，其余默认 READ
        if (SYSTEM_TOOLS.contains(toolName)) {
            def.setCategory(ToolCategory.SYSTEM);
        } else if (WRITE_TOOLS.contains(toolName)) {
            def.setCategory(ToolCategory.WRITE);
        } else {
            def.setCategory(ToolCategory.READ);
        }

        // @WriteTool 注解覆盖：类上标注的优先级高于按名匹配
        if (beanClass.isAnnotationPresent(WriteTool.class)) {
            def.setCategory(ToolCategory.WRITE);
        }

        def.setSource("LOCAL");

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Parameter param : method.getParameters()) {
                if (param.getType().getName().startsWith("dev.langchain4j")) continue;
                // 没有 @P 注解的参数视为内部注入参数，不暴露给 LLM
                if (!param.isAnnotationPresent(P.class)) continue;

                properties.put(param.getName(), buildParameterSchema(param));

                // 约定：@P 描述以 "可选" 开头的参数为 optional，不加入 required
                P pAnn = param.getAnnotation(P.class);
                boolean isOptional = pAnn != null && pAnn.value().startsWith("可选");
                if (!isOptional) {
                    required.add(param.getName());
                }
            }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        def.setParameters(schema);
        return def;
    }

    private Map<String, Object> buildParameterSchema(Parameter param) {
        Map<String, Object> prop = new LinkedHashMap<>();
        Class<?> type = param.getType();

        if (type == String.class) prop.put("type", "string");
        else if (type == int.class || type == Integer.class) prop.put("type", "integer");
        else if (type == long.class || type == Long.class) prop.put("type", "integer");
        else if (type == double.class || type == Double.class) prop.put("type", "number");
        else if (type == boolean.class || type == Boolean.class) prop.put("type", "boolean");
        else if (type == List.class || type == Set.class || type == Collection.class) {
            prop.put("type", "array");
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("type", "string");
            prop.put("items", items);
        } else prop.put("type", "object");

        P pAnn = param.getAnnotation(P.class);
        if (pAnn != null && !pAnn.value().isEmpty()) {
            prop.put("description", pAnn.value());
        }
        return prop;
    }

    @Override
    public List<ToolDefinitionVO> listTools(String sessionId, ToolCategory category) {
        return toolMap.values().stream()
                .map(e -> e.definition)
                .filter(def -> category == null || def.getCategory() == category)
                .collect(Collectors.toList());
    }

    @Override
    public void toggle(String toolName, boolean enabled) {
        ToolEntry entry = toolMap.get(toolName);
        if (entry == null) throw new IllegalArgumentException("工具不存在: " + toolName);
        entry.definition.setEnabled(enabled);
        log.info("{} 工具: {}", enabled ? "启用" : "禁用", toolName);
    }

    @Override
    public List<Object> getEnabledToolBeans() {
        return toolMap.values().stream()
                .filter(e -> e.definition.isEnabled())
                .filter(e -> !e.isExternal())  // 排除 MCP 等外部工具（无 Bean 可反射）
                .map(e -> e.bean)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public void recordHistory(String toolCallId, String toolName, String arguments,
                              boolean success, String resultSummary, long latencyMs) {
        ToolHistoryVO vo = new ToolHistoryVO();
        vo.setToolCallId(toolCallId);
        vo.setToolName(toolName);
        vo.setArguments(arguments);
        vo.setSuccess(success);
        vo.setResultSummary(resultSummary);
        vo.setLatencyMs(latencyMs);
        vo.setCalledAt(LocalDateTime.now().format(DTF));
        historyList.add(0, vo); // 最新在前
        // 只保留最近200条
        while (historyList.size() > 200) {
            historyList.remove(historyList.size() - 1);
        }
    }

    @Override
    public List<ToolHistoryVO> getHistory(String sessionId) {
        return new ArrayList<>(historyList);
    }

    // ── 外部工具支持（MCP 等） ──

    @Override
    public void registerExternalTool(ToolDefinitionVO def, String source) {
        String toolName = def.getName();
        if (toolMap.containsKey(toolName)) {
            log.warn("⚠ 外部工具名冲突: {} 已被本地工具占用，跳过注册", toolName);
            return;
        }
        def.setSource(source);
        toolMap.put(toolName, new ToolEntry(def, null, source));
        log.info("  📎 外部工具已注册: {} (来源: {})", toolName, source);
    }

    @Override
    public String getSource(String toolName) {
        ToolEntry entry = toolMap.get(toolName);
        return entry != null ? entry.source : null;
    }

    @Override
    public boolean isExternalTool(String toolName) {
        ToolEntry entry = toolMap.get(toolName);
        return entry != null && entry.isExternal();
    }

    @Override
    public String executeExternalTool(String toolName, String argsJson) {
        ToolEntry entry = toolMap.get(toolName);
        if (entry == null || !entry.isExternal()) {
            throw new IllegalArgumentException("工具不是外部工具: " + toolName);
        }
        // 根据 source 动态路由到 McpClientManager（所有 MCP 源统一走此路径）
        return mcpClientManager.executeTool(toolName, argsJson);
    }

    private static class ToolEntry {
        final ToolDefinitionVO definition;
        final Object bean;        // null 表示外部工具（MCP 等）
        final String source;      // "LOCAL" / "MCP:DIDI"

        ToolEntry(ToolDefinitionVO definition, Object bean) {
            this(definition, bean, "LOCAL");
        }

        ToolEntry(ToolDefinitionVO definition, Object bean, String source) {
            this.definition = definition;
            this.bean = bean;
            this.source = source;
        }

        boolean isExternal() {
            return !"LOCAL".equals(source);
        }
    }
}
