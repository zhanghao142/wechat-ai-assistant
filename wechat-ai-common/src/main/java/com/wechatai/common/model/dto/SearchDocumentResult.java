package com.wechatai.common.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * searchDocument 工具返回结构（扁平列表，便于 LLM 消费）。
 * <p>
 * 一期检索基于 MySQL FULLTEXT（关键词 + 相关性分），非向量语义检索。
 * 放在 common 模块中以便 document 和 tool 模块共用，避免循环依赖。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchDocumentResult {

    private List<MatchItem> matches;
    private int totalMatches;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatchItem {
        private String fileId;
        private String fileName;
        /** 页码（若可解析），可空 */
        private Integer pageNum;
        /** 匹配文本片段 */
        private String text;
        /** 相关性分数 */
        private double relevance;
    }
}
