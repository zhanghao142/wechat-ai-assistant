package com.wechatai.wechat.remind;

import com.wechatai.ai.remind.DesktopRemindDelivery;
import com.wechatai.common.enums.Channel;
import com.wechatai.session.entity.RemindEntity;
import com.wechatai.session.service.RemindService;
import jakarta.annotation.PostConstruct;
import com.wechatai.wechat.message.WechatChatBridge;
import com.wechatai.wechat.message.WechatOutboundSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时提醒扫描器 — 按 channel 分叉出站：
 * WECHAT → Bridge + iLink；DESKTOP → 落库通知，不走 iLink。
 */
@Component
public class RemindScheduler {

    private static final Logger log = LoggerFactory.getLogger(RemindScheduler.class);

    private static final int BATCH_SIZE = 10;
    private static final int DEBOUNCE_SECONDS = 30;

    private final RemindService remindService;
    private final WechatChatBridge chatBridge;
    private final WechatOutboundSender sender;
    private final DesktopRemindDelivery desktopRemindDelivery;

    private final ConcurrentHashMap<String, LocalDateTime> recentProcessed = new ConcurrentHashMap<>();

    public RemindScheduler(RemindService remindService, WechatChatBridge chatBridge,
                           WechatOutboundSender sender,
                           DesktopRemindDelivery desktopRemindDelivery) {
        this.remindService = remindService;
        this.chatBridge = chatBridge;
        this.sender = sender;
        this.desktopRemindDelivery = desktopRemindDelivery;
    }

    @PostConstruct
    public void init() {
        log.info("⏰ 提醒扫描器已启动，每秒扫描一次（支持 WECHAT/DESKTOP 渠道）");
    }

    @Scheduled(fixedRate = 1000)
    public void scan() {
        try {
            cleanExpiredDebounceEntries();

            List<RemindEntity> pending = remindService.findPendingBefore(LocalDateTime.now(), BATCH_SIZE);
            if (pending.isEmpty()) {
                return;
            }

            for (RemindEntity remind : pending) {
                if (isRecentlyProcessed(remind.getRemindId())) {
                    log.debug("⏰ 跳过防抖中的提醒: remindId={}", remind.getRemindId());
                    continue;
                }

                try {
                    markRecentlyProcessed(remind.getRemindId());
                    Channel channel = Channel.from(remind.getChannel());
                    if (channel == Channel.DESKTOP) {
                        deliverDesktop(remind);
                    } else {
                        deliverWechat(remind);
                    }
                    handleAfterSend(remind);
                } catch (Exception e) {
                    log.error("⏰ 提醒处理失败 remindId={} channel={}",
                            remind.getRemindId(), remind.getChannel(), e);
                    if (Channel.from(remind.getChannel()) == Channel.WECHAT) {
                        try {
                            sender.sendText(remind.getUserId(), "🔔 提醒：" + remind.getContent());
                            handleAfterSend(remind);
                        } catch (Exception ex) {
                            log.error("⏰ 提醒降级发送也失败 remindId={}", remind.getRemindId(), ex);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("⏰ 提醒扫描异常", e);
        }
    }

    private void deliverWechat(RemindEntity remind) {
        String prompt = "⏰ 提醒时间到：" + remind.getContent()
                + " —— 根据提醒内容决定是否需要调用工具，然后回复用户";
        chatBridge.think(remind.getUserId(), prompt, "TEXT");
        log.info("⏰ 微信提醒已触发AI: remindId={}, userId={}, content={}",
                remind.getRemindId(), remind.getUserId(), remind.getContent());
    }

    private void deliverDesktop(RemindEntity remind) {
        boolean ok = desktopRemindDelivery.deliver(remind);
        if (!ok) {
            throw new IllegalStateException("桌面提醒投递失败: " + remind.getRemindId());
        }
    }

    private void handleAfterSend(RemindEntity remind) {
        String repeatType = remind.getRepeatType();
        if (repeatType == null || "NONE".equals(repeatType)) {
            remindService.markSent(remind.getRemindId());
        } else {
            LocalDateTime nextTime = calculateNextValidTime(remind);
            if (nextTime != null && (remind.getRepeatEndAt() == null || nextTime.isBefore(remind.getRepeatEndAt()))) {
                remindService.updateForNextRepeat(remind.getRemindId(), nextTime);
                log.info("⏰ 周期提醒已更新下一次: remindId={}, nextTime={}, repeatCount={}",
                        remind.getRemindId(), nextTime, remind.getRepeatCount() + 1);
            } else {
                remindService.markCompleted(remind.getRemindId());
                log.info("⏰ 周期提醒已完成: remindId={}", remind.getRemindId());
            }
        }
    }

    private LocalDateTime calculateNextValidTime(RemindEntity remind) {
        String repeatType = remind.getRepeatType();
        LocalDateTime currentTime = remind.getRemindAt();
        String repeatValue = remind.getRepeatValue();
        if (currentTime == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextTime = calculateNextRemindTime(remind);
        int safetyCounter = 0;
        while (nextTime != null && nextTime.isBefore(now) && safetyCounter < 100) {
            RemindEntity tempEntity = new RemindEntity();
            tempEntity.setRepeatType(repeatType);
            tempEntity.setRemindAt(nextTime);
            tempEntity.setRepeatValue(repeatValue);
            nextTime = calculateNextRemindTime(tempEntity);
            safetyCounter++;
        }
        return nextTime;
    }

    private LocalDateTime calculateNextRemindTime(RemindEntity remind) {
        String repeatType = remind.getRepeatType();
        LocalDateTime currentTime = remind.getRemindAt();
        String repeatValue = remind.getRepeatValue();
        if (currentTime == null) {
            return null;
        }
        switch (repeatType) {
            case "DAILY":
                return currentTime.plusDays(1);
            case "WEEKLY":
                return calculateWeeklyNextTime(currentTime, repeatValue);
            case "MONTHLY":
                return calculateMonthlyNextTime(currentTime, repeatValue);
            case "INTERVAL":
                try {
                    int days = Integer.parseInt(repeatValue);
                    return currentTime.plusDays(days);
                } catch (NumberFormatException e) {
                    log.warn("⏰ INTERVAL类型重复值解析失败: repeatValue={}", repeatValue);
                    return null;
                }
            default:
                return null;
        }
    }

    private LocalDateTime calculateWeeklyNextTime(LocalDateTime currentTime, String repeatValue) {
        if (repeatValue == null || repeatValue.isEmpty()) {
            return currentTime.plusWeeks(1);
        }
        String[] days = repeatValue.split(",");
        LocalDate startDate = currentTime.toLocalDate().plusDays(1);
        LocalDateTime result = null;
        for (String dayStr : days) {
            try {
                int day = Integer.parseInt(dayStr.trim());
                DayOfWeek targetWeekDay = DayOfWeek.of(day);
                LocalDate nextDate = startDate.with(TemporalAdjusters.next(targetWeekDay));
                LocalDateTime candidate = nextDate.atTime(currentTime.toLocalTime());
                if (result == null || candidate.isBefore(result)) {
                    result = candidate;
                }
            } catch (IllegalArgumentException e) {
                log.warn("⏰ WEEKLY重复值解析失败: dayStr={}", dayStr);
            }
        }
        return result;
    }

    private LocalDateTime calculateMonthlyNextTime(LocalDateTime currentTime, String repeatValue) {
        if (repeatValue == null || repeatValue.isEmpty()) {
            LocalDate nextMonth = currentTime.toLocalDate().plusMonths(1);
            int actualDay = Math.min(1, nextMonth.lengthOfMonth());
            return nextMonth.withDayOfMonth(actualDay).atTime(currentTime.toLocalTime());
        }
        String[] days = repeatValue.split(",");
        LocalDateTime result = null;
        for (String dayStr : days) {
            try {
                int dayOfMonth = Integer.parseInt(dayStr.trim());
                if (dayOfMonth < 1 || dayOfMonth > 31) {
                    continue;
                }
                LocalDate nextMonth = currentTime.toLocalDate().plusMonths(1);
                int actualDay = Math.min(dayOfMonth, nextMonth.lengthOfMonth());
                LocalDate targetDate = nextMonth.withDayOfMonth(actualDay);
                LocalDateTime candidate = targetDate.atTime(currentTime.toLocalTime());
                if (result == null || candidate.isBefore(result)) {
                    result = candidate;
                }
            } catch (NumberFormatException e) {
                log.warn("⏰ MONTHLY重复值解析失败: dayStr={}", dayStr);
            }
        }
        return result;
    }

    private boolean isRecentlyProcessed(String remindId) {
        LocalDateTime lastProcessed = recentProcessed.get(remindId);
        if (lastProcessed == null) {
            return false;
        }
        return lastProcessed.plusSeconds(DEBOUNCE_SECONDS).isAfter(LocalDateTime.now());
    }

    private void markRecentlyProcessed(String remindId) {
        recentProcessed.put(remindId, LocalDateTime.now());
    }

    private void cleanExpiredDebounceEntries() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(DEBOUNCE_SECONDS * 2);
        recentProcessed.entrySet().removeIf(entry -> entry.getValue().isBefore(threshold));
    }
}
