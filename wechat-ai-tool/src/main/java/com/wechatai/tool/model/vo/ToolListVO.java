package com.wechatai.tool.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 工具列表响应包装（一期无分页，直接数组）。
 */
@Data
public class ToolListVO {

    private List<ToolDefinitionVO> tools;
}
