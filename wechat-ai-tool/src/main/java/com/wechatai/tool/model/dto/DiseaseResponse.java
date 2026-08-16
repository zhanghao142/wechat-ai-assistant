package com.wechatai.tool.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DiseaseResponse {

    private Integer code;
    private String msg;
    private List<DiseaseItem> data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DiseaseItem {
        private List<String> acompany;
        private List<String> category;
        private String cause;
        private String desc;
        private String symptom;
        private String treatment;
        private String prevent;
        private List<String> cure_department;
        private String name;
        private String cured_prob;
        private String get_way;
    }
}