package com.wechatai.tool.model.vo;

import com.wechatai.common.enums.ToolCategory;
import lombok.Data;

import java.io.Serializable;

/**
 * 工具元数据展示：名称、描述、分类、是否启用及参数 Schema。
 * description 来自 {@code @Tool}，直接影响模型选工具。
 */
@Data
public class ToolDefinitionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private String description;
    /** READ / WRITE / SYSTEM */
    private ToolCategory category;
    private boolean enabled;
    /** JSON Schema 或参数说明对象 */
    private Object parameters;
    /** 来源标记：null="LOCAL"（本地@Tool），"MCP:DIDI" 等 */
    private String source;
}
