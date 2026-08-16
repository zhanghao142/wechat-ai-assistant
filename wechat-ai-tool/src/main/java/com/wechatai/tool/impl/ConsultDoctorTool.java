package com.wechatai.tool.impl;

import com.wechatai.tool.model.dto.DiseaseResponse;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class ConsultDoctorTool {

    private static final String DISEASE_API_URL = "https://v2.xxapi.cn/api/disease";

    private static final List<String> EMERGENCY_KEYWORDS = Arrays.asList(
            "胸痛", "胸闷", "呼吸困难", "气短", "咯血", "吐血", "大出血",
            "意识障碍", "昏迷", "抽搐", "惊厥", "剧烈头痛", "喷射性呕吐",
            "偏瘫", "失语", "剧烈腹痛", "板状腹", "严重外伤", "烧伤",
            "中毒", "过敏反应", "喉头水肿", "窒息", "胎儿异常", "临产征兆",
            "自杀倾向", "精神异常", "高热惊厥", "新生儿异常"
    );

    private static final String DISCLAIMER = "【免责声明】⚠️ 本回复由 AI 基于公开医学资料生成，仅供健康参考，**不构成诊断、治疗建议或处方**。具体问题请咨询执业医师。";

    private final RestClient.Builder restClientBuilder;
    private RestClient restClient;

    public ConsultDoctorTool(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @PostConstruct
    public void init() {
        restClient = restClientBuilder.build();
    }

    @Tool("健康咨询与智能分诊工具。当用户描述身体不适、症状，或询问'挂什么科'、'看什么科'、'该看哪个科'、'什么科室'、'什么科'、'我该看什么科'、'这种情况严不严重'、'需不需要去医院'、'头痛该挂什么科'、'嗓子疼挂什么科'等情况时使用。本工具基于公开医学资料提供：①可能相关科室推荐 ②就医紧迫性评估 ③问诊前准备事项 ④健康科普。注意：本工具不作疾病诊断、不提供治疗方案、不开具处方、不推荐具体药物或检查项目。")
    public String consultDoctor(
            @P("用户描述的症状、不适或疾病名，如：持续头痛三天、饭后胃疼、咳嗽伴低烧、胸闷")
            String symptom) {

        if (symptom == null || symptom.trim().isEmpty()) {
            return DISCLAIMER + "\n\n健康咨询暂时不可用，请您直接前往正规医院就诊。";
        }

        boolean isEmergency = isEmergency(symptom);

        String encodedSymptom = URLEncoder.encode(symptom, StandardCharsets.UTF_8);
        String uri = UriComponentsBuilder.fromHttpUrl(DISEASE_API_URL)
                .queryParam("word", encodedSymptom)
                .toUriString();

        log.info("[健康咨询] 请求: {}, 紧急: {}", uri, isEmergency);

        try {
            DiseaseResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(DiseaseResponse.class);

            if (response != null && response.getCode() == 200 &&
                    response.getData() != null && !response.getData().isEmpty()) {
                DiseaseResponse.DiseaseItem item = response.getData().get(0);
                log.info("[健康咨询] 成功: {}", item.getName());
                return buildResponse(item, symptom, isEmergency);
            }

            log.warn("[健康咨询] 第一次查询返回空，尝试提取关键词");
            String keyword = extractKeyword(symptom);
            if (!keyword.equals(symptom)) {
                String retryUri = UriComponentsBuilder.fromHttpUrl(DISEASE_API_URL)
                        .queryParam("word", URLEncoder.encode(keyword, StandardCharsets.UTF_8))
                        .toUriString();
                log.info("[健康咨询] 重试请求: {}", retryUri);

                DiseaseResponse retryResponse = restClient.get()
                        .uri(retryUri)
                        .retrieve()
                        .body(DiseaseResponse.class);

                if (retryResponse != null && retryResponse.getCode() == 200 &&
                        retryResponse.getData() != null && !retryResponse.getData().isEmpty()) {
                    DiseaseResponse.DiseaseItem item = retryResponse.getData().get(0);
                    log.info("[健康咨询] 重试成功: {}", item.getName());
                    return buildResponse(item, symptom, isEmergency);
                }
            }

            log.warn("[健康咨询] 未查询到相关疾病信息");
            return DISCLAIMER + "\n\n未查询到相关疾病信息，建议您直接前往正规医院就诊。";

        } catch (RestClientException e) {
            log.warn("[健康咨询] 请求失败: {}", e.getMessage());
            return DISCLAIMER + "\n\n健康咨询暂时不可用，建议您直接前往正规医院就诊。";
        } catch (Exception e) {
            log.error("[健康咨询] 异常: {}", e.getMessage(), e);
            return DISCLAIMER + "\n\n健康咨询暂时不可用，建议您直接前往正规医院就诊。";
        }
    }

    private boolean isEmergency(String symptom) {
        for (String keyword : EMERGENCY_KEYWORDS) {
            if (symptom.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String buildResponse(DiseaseResponse.DiseaseItem item, String symptom, boolean isEmergency) {
        StringBuilder sb = new StringBuilder();

        sb.append(DISCLAIMER).append("\n");

        sb.append("\n");
        sb.append("建议就诊科室：").append(getDepartmentText(item)).append("\n");

        sb.append("\n");
        sb.append("建议").append(getUrgencyText(item, symptom, isEmergency)).append("\n");

        sb.append("\n");
        sb.append("就医前建议：携带身份证、医保卡、既往病历和检查报告；准备好告诉医生症状起始时间、诱因、伴随症状、既往病史和过敏史。\n");

        sb.append("\n");
        sb.append("日常注意事项：").append(getHealthText(item));

        if (isEmergency) {
            sb.append("\n\n🚨 紧急提醒：您描述的症状可能涉及急症，请立即拨打 120 或前往最近医院急诊科。");
        }

        return sb.toString();
    }

    private String getDepartmentText(DiseaseResponse.DiseaseItem item) {
        List<String> cureDepts = item.getCure_department();
        List<String> categories = item.getCategory();

        if (cureDepts != null && !cureDepts.isEmpty()) {
            return String.join("、", cureDepts);
        } else if (categories != null && !categories.isEmpty()) {
            StringBuilder depts = new StringBuilder();
            for (String cat : categories) {
                if (cat.contains("科") || Arrays.asList("内科", "外科", "妇产科", "儿科").contains(cat)) {
                    if (depts.length() > 0) depts.append("、");
                    depts.append(cat);
                }
            }
            if (depts.length() > 0) return depts.toString();
        }
        return "内科（建议先到综合医院初诊）";
    }

    private String getUrgencyText(DiseaseResponse.DiseaseItem item, String symptom, boolean isEmergency) {
        if (isEmergency) {
            return "立即急诊。您描述的症状可能涉及急症，请立即前往医院急诊科。";
        }
        if (symptom.contains("持续") || symptom.contains("反复") || symptom.contains("多天") || symptom.contains("很久")) {
            return "尽快就医（本周内）。症状持续时间较长，建议及时就诊明确原因。";
        }
        return "先观察 2-3 天。轻微症状可先观察，若持续或加重请及时就医。";
    }

    private String getHealthText(DiseaseResponse.DiseaseItem item) {
        String prevent = item.getPrevent();
        if (prevent != null && !prevent.isEmpty()) {
            return truncate(prevent, 80);
        }
        return "注意休息，保持良好的生活习惯，饮食清淡，避免刺激性食物。";
    }

    private String extractDepartments(DiseaseResponse.DiseaseItem item) {
        List<String> categories = item.getCategory();
        List<String> cureDepts = item.getCure_department();

        StringBuilder depts = new StringBuilder();

        if (cureDepts != null && !cureDepts.isEmpty()) {
            for (String dept : cureDepts) {
                if (depts.length() > 0) depts.append(" / ");
                depts.append(dept);
            }
        } else if (categories != null && !categories.isEmpty()) {
            for (String cat : categories) {
                if (isDepartment(cat)) {
                    if (depts.length() > 0) depts.append(" / ");
                    depts.append(cat);
                }
            }
        }

        if (depts.length() == 0) {
            return "建议先到综合医院内科初诊";
        }

        return "建议就诊科室：" + depts.toString();
    }

    private boolean isDepartment(String name) {
        return name.contains("科") ||
                Arrays.asList("内科", "外科", "妇产科", "儿科", "急诊科", "精神科").contains(name);
    }

    private String evaluateUrgency(DiseaseResponse.DiseaseItem item, String symptom, boolean isEmergency) {
        if (isEmergency) {
            return "需立即急诊\n理由：您描述的症状可能涉及急症，请立即就医";
        }

        if (symptom.contains("持续") || symptom.contains("反复") ||
                symptom.contains("多天") || symptom.contains("高烧") ||
                symptom.contains("剧烈")) {
            return "建议尽快就医（本周内）\n理由：症状持续不退或较为严重，建议及时就诊";
        }

        String cause = item.getCause();
        if (cause != null && (cause.contains("严重") || cause.contains("危及") || cause.contains("并发症"))) {
            return "建议尽快就医（本周内）\n理由：根据医学资料，该症状可能存在潜在风险";
        }

        return "可先观察 2-3 天\n理由：轻微症状可先观察，若持续或加重请及时就医";
    }

    private String generateConsultationTips(DiseaseResponse.DiseaseItem item) {
        StringBuilder tips = new StringBuilder();
        tips.append("- 建议携带：身份证、医保卡、既往病历和检查报告\n");
        tips.append("- 医生可能会询问：症状起始时间、诱因、伴随症状、既往病史、过敏史\n");

        String symptom = item.getSymptom();
        if (symptom != null && !symptom.isEmpty()) {
            tips.append("- 就医时可告知医生：").append(truncate(symptom, 50)).append("\n");
        }

        return tips.toString();
    }

    private String generateHealthTips(DiseaseResponse.DiseaseItem item) {
        StringBuilder tips = new StringBuilder();

        String prevent = item.getPrevent();
        if (prevent != null && !prevent.isEmpty()) {
            tips.append("- 预防建议：").append(truncate(prevent, 80)).append("\n");
        }

        String cause = item.getCause();
        if (cause != null && !cause.isEmpty()) {
            tips.append("- 病因参考：").append(truncate(cause, 80)).append("\n");
        }

        List<String> acompany = item.getAcompany();
        if (acompany != null && !acompany.isEmpty()) {
            tips.append("- 可能并发症：").append(String.join("、", acompany)).append("\n");
        }

        if (tips.length() == 0) {
            tips.append("- 注意休息，保持良好的生活习惯\n");
            tips.append("- 饮食清淡，避免刺激性食物\n");
            tips.append("- 如有不适加重，请及时就医\n");
        }

        return tips.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private String extractDepartmentsSimple(DiseaseResponse.DiseaseItem item) {
        List<String> categories = item.getCategory();
        List<String> cureDepts = item.getCure_department();

        StringBuilder depts = new StringBuilder();
        depts.append("建议就诊科室：");

        if (cureDepts != null && !cureDepts.isEmpty()) {
            for (int i = 0; i < Math.min(cureDepts.size(), 3); i++) {
                if (i > 0) depts.append("、");
                depts.append(cureDepts.get(i));
            }
        } else if (categories != null && !categories.isEmpty()) {
            for (String cat : categories) {
                if (isDepartment(cat)) {
                    if (depts.length() > 6) depts.append("、");
                    depts.append(cat);
                }
            }
        }

        if (depts.length() == 6) {
            depts.append("内科（建议先到综合医院初诊）");
        }

        return depts.toString();
    }

    private String evaluateUrgencySimple(DiseaseResponse.DiseaseItem item, String symptom, boolean isEmergency) {
        if (isEmergency) {
            return "建议立即急诊。您描述的症状可能涉及急症，请立即前往医院急诊科。";
        }

        if (symptom.contains("持续") || symptom.contains("反复") ||
                symptom.contains("多天") || symptom.contains("很久")) {
            return "建议尽快就医（本周内）。症状持续时间较长，建议及时就诊明确原因。";
        }

        return "可先观察 2-3 天。轻微症状可先观察，若持续或加重请及时就医。";
    }

    private String generateConsultationTipsSimple(DiseaseResponse.DiseaseItem item) {
        StringBuilder tips = new StringBuilder();
        tips.append("就医前建议：携带身份证、医保卡、既往病历和检查报告；准备好告诉医生症状起始时间、诱因、伴随症状、既往病史和过敏史。");
        return tips.toString();
    }

    private String generateHealthTipsSimple(DiseaseResponse.DiseaseItem item) {
        StringBuilder tips = new StringBuilder();
        tips.append("日常注意事项：");

        String prevent = item.getPrevent();
        if (prevent != null && !prevent.isEmpty()) {
            tips.append(truncate(prevent, 60));
        } else {
            tips.append("注意休息，保持良好的生活习惯，饮食清淡，避免刺激性食物，如有不适加重请及时就医。");
        }

        return tips.toString();
    }

    private String extractKeyword(String symptom) {
        List<String> diseaseKeywords = Arrays.asList(
                "头痛", "头晕", "发烧", "发烧", "咳嗽", "感冒", "胃痛", "腹痛",
                "腹泻", "便秘", "呕吐", "恶心", "胸闷", "胸痛", "呼吸困难",
                "心悸", "失眠", "疲劳", "乏力", "关节痛", "腰痛", "背痛",
                "皮疹", "皮肤瘙痒", "耳鸣", "视力模糊", "鼻出血", "牙龈出血",
                "尿频", "尿急", "尿痛", "血尿", "水肿", "体重下降", "食欲减退"
        );

        for (String keyword : diseaseKeywords) {
            if (symptom.contains(keyword)) {
                return keyword;
            }
        }

        String cleaned = symptom.replaceAll("[\\d一二三四五六七八九十]+天", "")
                .replaceAll("[\\d一二三四五六七八九十]+周", "")
                .replaceAll("[\\d一二三四五六七八九十]+个月", "")
                .replaceAll("[\\d一二三四五六七八九十]+年", "")
                .replaceAll("持续|反复|偶尔|经常|最近|今天|昨天|前天", "")
                .replaceAll("伴有|伴随|还有|同时", "")
                .replaceAll("出现|发生|开始|感觉|觉得|发现", "")
                .replaceAll("严重|轻微|稍微|有点|很|非常", "")
                .trim();

        if (!cleaned.isEmpty() && cleaned.length() <= 5) {
            return cleaned;
        }

        return symptom;
    }
}
