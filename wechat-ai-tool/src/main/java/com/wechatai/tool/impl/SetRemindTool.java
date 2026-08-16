package com.wechatai.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatai.session.entity.RemindEntity;
import com.wechatai.session.service.RemindService;
import com.wechatai.tool.annotation.WriteTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定时提醒工具（WRITE）— 用户设定一个时间，到点后微信主动推送消息。
 * <p>
 * 支持绝对时间（yyyy-MM-dd HH:mm）和各种中文相对时间（一分钟后、明天、下周二等）。
 * 支持周期提醒：DAILY(每天)、WEEKLY(每周)、MONTHLY(每月)、INTERVAL(每N天)。
 * 中文相对时间由 LLM 内部转换，无需手写正则覆盖。
 * userId 由 ToolExecuteNode 自动注入。
 */
@Component
@WriteTool("设置定时提醒")
public class SetRemindTool {

    private static final Logger log = LoggerFactory.getLogger(SetRemindTool.class);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Value("${wechat.ai.llm.api-key}")
    private String apiKey;

    @Value("${wechat.ai.llm.base-url}")
    private String baseUrl;

    @Value("${wechat.ai.llm.model:deepseek-chat}")
    private String modelName;

    private final RemindService remindService;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private RestClient restClient;

    public SetRemindTool(RemindService remindService, RestClient.Builder restClientBuilder) {
        this.remindService = remindService;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        this.restClient = restClientBuilder.build();
    }

    @Tool("设置一个定时提醒。用户说'提醒我XXX'时调用此工具。注意：设置阶段只调用 setRemind 一个工具，用户提到的其他需求（查天气、搜信息等）全部放进 content 参数中，提醒触发时会自动处理。支持周期提醒：repeatType可选值 DAILY(每天)、WEEKLY(每周)、MONTHLY(每月)、INTERVAL(每N天)，WEEKLY的repeatValue为星期几(1-7)逗号分隔如'1,3,5'，MONTHLY的repeatValue为日期如'1,15'，INTERVAL的repeatValue为天数如'3'。repeatEndAt为截止日期(可选)")
    public String setRemind(
            @P("提醒内容，包含用户说的所有需求。例如用户说「一分钟后提醒我开会并查天气」则内容为「开会并查杭州天气」。注意：把用户提到的所有后续需求都放进此参数，不要自己调用其他工具去做") String content,
            @P("用户说的原始时间表达。用户怎么说就怎么传，不要自己换算。例如用户说「一分钟后」就传「一分钟后」") String remindAt,
            @P("重复类型：NONE(不重复)、DAILY(每天重复)、WEEKLY(每周重复)、MONTHLY(每月重复)、INTERVAL(每N天重复)。不重复时传NONE或null") String repeatType,
            @P("重复值：WEEKLY时为星期几(1-7)逗号分隔如'1,3,5'表示周一三五；MONTHLY时为日期(1-31)逗号分隔如'1,15'；INTERVAL时为天数如'3'表示每3天") String repeatValue,
            @P("重复截止日期，格式yyyy-MM-dd HH:mm。不设置则永久重复") String repeatEndAt,
            String userId,
            String sessionId,
            String channel) {

        if (content == null || content.trim().isEmpty()) {
            return "提醒内容不能为空，请告诉我提醒什么";
        }
        if (remindAt == null || remindAt.trim().isEmpty()) {
            return "提醒时间不能为空，请告诉我什么时间提醒";
        }
        if (userId == null) {
            return "用户 ID 不可用，无法设置提醒";
        }

        // LLM 常把可选参数填成字符串 "null"，视为未传
        repeatType = blankToNull(repeatType);
        repeatValue = blankToNull(repeatValue);
        repeatEndAt = blankToNull(repeatEndAt);

        // 解析时间：先试绝对格式，不行就调 LLM 转换
        LocalDateTime remindTime = parseRemindTime(remindAt.trim());
        if (remindTime == null) {
            return "时间格式不太理解，请用「X分钟后」「明天上午10点」或「yyyy-MM-dd HH:mm」格式告诉我";
        }

        // 规范化重复类型
        String normalizedRepeatType = normalizeRepeatType(repeatType);
        LocalDateTime repeatEndTime = null;
        if (repeatEndAt != null) {
            try {
                repeatEndTime = LocalDateTime.parse(repeatEndAt.trim(), FORMATTER);
            } catch (DateTimeParseException e) {
                return "重复截止日期格式错误，请使用 yyyy-MM-dd HH:mm 格式";
            }
        }

        // 周期提醒：如果时间已过期，自动跳到下一次未来时间
        if (!"NONE".equals(normalizedRepeatType) && remindTime.isBefore(LocalDateTime.now())) {
            LocalDateTime adjustedTime = calculateFutureTime(remindTime, normalizedRepeatType, repeatValue);
            if (adjustedTime != null) {
                log.info("【设置提醒】时间已过期，自动调整: {} -> {}", remindTime, adjustedTime);
                remindTime = adjustedTime;
            }
        } else if ("NONE".equals(normalizedRepeatType) && !remindTime.isAfter(LocalDateTime.now())) {
            return "提醒时间已过（" + remindTime.format(FORMATTER) + "），请换一个未来的时间";
        }

        String resolvedChannel = channel != null && !channel.isBlank() ? channel : "WECHAT";

        // 创建提醒（渠道 + 会话由运行时注入，不写死用户）
        RemindEntity entity = remindService.createRemind(
                userId, content.trim(), remindTime,
                normalizedRepeatType, repeatValue, repeatEndTime,
                resolvedChannel, sessionId);

        log.info("【设置提醒】userId={}, channel={}, sessionId={}, content={}, remindAt={}, repeatType={}, remindId={}",
                userId, resolvedChannel, sessionId, content, remindTime.format(FORMATTER),
                normalizedRepeatType, entity.getRemindId());

        String resultMsg = "好的，已设置提醒：「" + content.trim() + "」，我会在 " + remindTime.format(FORMATTER) + " 提醒你";
        if (!"NONE".equals(normalizedRepeatType)) {
            resultMsg += buildRepeatDescription(normalizedRepeatType, repeatValue, repeatEndTime);
        }
        return resultMsg;
    }

    /**
     * 规范化重复类型
     */
    private String normalizeRepeatType(String repeatType) {
        if (repeatType == null || repeatType.trim().isEmpty()) {
            return "NONE";
        }
        String upper = repeatType.trim().toUpperCase();
        switch (upper) {
            case "DAILY":
            case "WEEKLY":
            case "MONTHLY":
            case "INTERVAL":
            case "NONE":
                return upper;
            default:
                return "NONE";
        }
    }

    /**
     * 构建重复提醒描述
     */
    private String buildRepeatDescription(String repeatType, String repeatValue, LocalDateTime repeatEndTime) {
        StringBuilder sb = new StringBuilder();
        sb.append("。该提醒会");
        switch (repeatType) {
            case "DAILY":
                sb.append("每天重复");
                break;
            case "WEEKLY":
                sb.append("每周重复，星期").append(convertWeekDay(repeatValue));
                break;
            case "MONTHLY":
                sb.append("每月").append(repeatValue).append("号重复");
                break;
            case "INTERVAL":
                sb.append("每 ").append(repeatValue).append(" 天重复");
                break;
        }
        if (repeatEndTime != null) {
            sb.append("，截止到 ").append(repeatEndTime.format(FORMATTER));
        } else {
            sb.append("，永久有效");
        }
        return sb.toString();
    }

    /**
     * 将星期数字转为中文
     */
    private String convertWeekDay(String weekDays) {
        if (weekDays == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] parts = weekDays.split(",");
        for (int i = 0; i < parts.length; i++) {
            String day = parts[i].trim();
            switch (day) {
                case "1": sb.append("一"); break;
                case "2": sb.append("二"); break;
                case "3": sb.append("三"); break;
                case "4": sb.append("四"); break;
                case "5": sb.append("五"); break;
                case "6": sb.append("六"); break;
                case "7": sb.append("日"); break;
                default: sb.append(day);
            }
            if (i < parts.length - 1) sb.append("、");
        }
        return sb.toString();
    }

    private static final java.util.regex.Pattern MINUTES_PATTERN =
            java.util.regex.Pattern.compile("([\\d一二两三四五六七八九十半]+)\\s*分[钟鐘]\\s*[后後]");
    private static final java.util.regex.Pattern HOURS_PATTERN =
            java.util.regex.Pattern.compile("(\\d+)\\s*小[时時]\\s*[后後]");
    private static final java.util.regex.Pattern SECONDS_PATTERN =
            java.util.regex.Pattern.compile("(\\d+)\\s*秒\\s*[后後]");

    /** 中文数字映射 */
    private static final java.util.Map<String, Integer> CN_NUM = new java.util.LinkedHashMap<>();
    static {
        CN_NUM.put("一", 1); CN_NUM.put("二", 2); CN_NUM.put("两", 2);
        CN_NUM.put("三", 3); CN_NUM.put("四", 4); CN_NUM.put("五", 5);
        CN_NUM.put("六", 6); CN_NUM.put("七", 7); CN_NUM.put("八", 8);
        CN_NUM.put("九", 9); CN_NUM.put("十", 10); CN_NUM.put("半", 30);
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if ("null".equalsIgnoreCase(t) || "none".equalsIgnoreCase(t)
                || "undefined".equalsIgnoreCase(t) || "nil".equalsIgnoreCase(t)) {
            return null;
        }
        return t;
    }

    private static final Pattern DAY_CLOCK = Pattern.compile(
            "(今天|明天|后天)?\\s*(上午|早上|凌晨|中午|下午|晚上)?\\s*(\\d{1,2})\\s*[点时](?:\\s*(\\d{1,2})\\s*分)?");

    /**
     * 解析提醒时间。
     * 先尝试绝对格式 → 中文日+钟点 → 相对时间 → 最后调 LLM。
     */
    private LocalDateTime parseRemindTime(String input) {
        // 1. 试试绝对时间格式 yyyy-MM-dd HH:mm
        try {
            return LocalDateTime.parse(input, FORMATTER);
        } catch (DateTimeParseException ignored) {
        }

        // 2. 「明天上午10点」等
        LocalDateTime dayClock = parseDayClock(input);
        if (dayClock != null) return dayClock;

        // 3. 正则匹配中文相对时间（分钟/小时/秒）
        LocalDateTime parsed = parseRelative(input);
        if (parsed != null) return parsed;

        // 4. 调 LLM 兜底
        try {
            return convertByLlm(input);
        } catch (Exception e) {
            log.warn("LLM 时间转换失败: {}", e.getMessage());
            return null;
        }
    }

    /** 解析「明天上午10点」「今天下午3点半」类表达 */
    private LocalDateTime parseDayClock(String input) {
        Matcher m = DAY_CLOCK.matcher(input);
        if (!m.find()) return null;

        String dayWord = m.group(1);
        String period = m.group(2);
        int hour = Integer.parseInt(m.group(3));
        int minute = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
        if (input.contains("半") && m.group(4) == null) minute = 30;

        if (period != null) {
            if (("下午".equals(period) || "晚上".equals(period)) && hour > 0 && hour < 12) {
                hour += 12;
            } else if ("中午".equals(period) && hour < 12) {
                hour = hour == 0 ? 12 : hour;
            } else if (("上午".equals(period) || "早上".equals(period) || "凌晨".equals(period)) && hour == 12) {
                hour = 0;
            }
        }
        if (hour > 23 || minute > 59) return null;

        LocalDate day = LocalDate.now();
        if ("明天".equals(dayWord)) day = day.plusDays(1);
        else if ("后天".equals(dayWord)) day = day.plusDays(2);

        LocalDateTime dt = LocalDateTime.of(day, LocalTime.of(hour, minute));
        log.info("【时间解析】中文日钟点 '{}' → {}", input, dt.format(FORMATTER));
        return dt;
    }

    /** 正则解析中文相对时间：X分钟后、X小时后、半小时后 等 */
    private LocalDateTime parseRelative(String input) {
        LocalDateTime now = LocalDateTime.now();

        java.util.regex.Matcher m = MINUTES_PATTERN.matcher(input);
        if (m.find()) {
            int mins = parseNumber(m.group(1));
            if (mins > 0) return now.plusMinutes(mins);
        }
        m = HOURS_PATTERN.matcher(input);
        if (m.find()) {
            int hrs = parseNumber(m.group(1));
            if (hrs > 0) return now.plusHours(hrs);
        }
        m = SECONDS_PATTERN.matcher(input);
        if (m.find()) {
            int secs = parseNumber(m.group(1));
            if (secs > 0) return now.plusSeconds(secs);
        }
        return null;
    }

    private int parseNumber(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) {}
        Integer cn = CN_NUM.get(s);
        return cn != null ? cn : 0;
    }

    /**
     * 调 DeepSeek 将中文相对时间转为 yyyy-MM-dd HH:mm 格式。
     */
    private LocalDateTime convertByLlm(String rawTime) {
        try {
            String now = LocalDateTime.now().format(FORMATTER);

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("messages", new Object[]{
                    Map.of("role", "system", "content",
                            "你是一个时间解析器。当前时间是 " + now + "。"
                            + "用户输入了一个中文时间表达，请输出 yyyy-MM-dd HH:mm 格式的绝对时间。"
                            + "只输出时间，不要任何其他文字。"),
                    Map.of("role", "user", "content", rawTime)
            });
            requestBody.put("temperature", 0);
            requestBody.put("max_tokens", 50);

            String responseBody = restClient.post()
                    .uri(baseUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(responseBody);
            String result = root.path("choices").get(0).path("message").path("content").asText("").trim();
            // 模型偶发夹杂说明文字，抽出 yyyy-MM-dd HH:mm
            Matcher dm = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}\\s+\\d{1,2}:\\d{2})").matcher(result);
            if (dm.find()) {
                result = dm.group(1).replaceAll("\\s+", " ");
                // 补零小时
                String[] parts = result.split(" ");
                if (parts.length == 2 && parts[1].indexOf(':') == 1) {
                    result = parts[0] + " 0" + parts[1];
                }
            }

            log.info("【时间转换】'{}' → '{}'", rawTime, result);
            if (result.isEmpty()) return null;

            return LocalDateTime.parse(result, FORMATTER);
        } catch (Exception e) {
            log.warn("LLM 时间转换失败 rawTime={}", rawTime, e);
            return null;
        }
    }

    /**
     * 计算周期提醒的未来时间（用于创建时跳过已过期的时间点）
     */
    private LocalDateTime calculateFutureTime(LocalDateTime baseTime, String repeatType, String repeatValue) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime result = baseTime;

        switch (repeatType) {
            case "DAILY":
                while (result.isBefore(now)) {
                    result = result.plusDays(1);
                }
                break;

            case "WEEKLY":
                if (repeatValue == null || repeatValue.isEmpty()) {
                    result = result.plusDays(1);
                    while (result.isBefore(now)) {
                        result = result.plusDays(7);
                    }
                } else {
                    String[] days = repeatValue.split(",");
                    int maxIterations = 52;
                    int iteration = 0;
                    while (result.isBefore(now) && iteration < maxIterations) {
                        for (String dayStr : days) {
                            try {
                                int targetDay = Integer.parseInt(dayStr.trim());
                                DayOfWeek targetWeekDay = DayOfWeek.of(targetDay);
                                LocalDate nextDate = result.toLocalDate().with(
                                        TemporalAdjusters.next(targetWeekDay));
                                result = nextDate.atTime(result.toLocalTime());
                                if (result.isAfter(now)) {
                                    break;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        iteration++;
                    }
                }
                break;

            case "MONTHLY":
                if (repeatValue == null || repeatValue.isEmpty()) {
                    result = result.plusMonths(1);
                    while (result.isBefore(now)) {
                        result = result.plusMonths(1);
                    }
                } else {
                    String[] days = repeatValue.split(",");
                    int maxIterations = 24;
                    int iteration = 0;
                    while (result.isBefore(now) && iteration < maxIterations) {
                        LocalDate nextMonth = result.toLocalDate().plusMonths(1);
                        LocalDateTime candidate = null;
                        for (String dayStr : days) {
                            try {
                                int dayOfMonth = Integer.parseInt(dayStr.trim());
                                int actualDay = Math.min(dayOfMonth, nextMonth.lengthOfMonth());
                                LocalDate targetDate = nextMonth.withDayOfMonth(actualDay);
                                LocalDateTime c = targetDate.atTime(result.toLocalTime());
                                if (candidate == null || c.isBefore(candidate)) {
                                    candidate = c;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        if (candidate != null) {
                            result = candidate;
                        } else {
                            result = nextMonth.atTime(result.toLocalTime());
                        }
                        iteration++;
                    }
                }
                break;

            case "INTERVAL":
                try {
                    int days = Integer.parseInt(repeatValue);
                    while (result.isBefore(now)) {
                        result = result.plusDays(days);
                    }
                } catch (NumberFormatException e) {
                    result = result.plusDays(1);
                }
                break;
        }

        return result;
    }
}
