package com.wechatai.document.model.vo;

import com.wechatai.common.enums.TaskStatus;
import lombok.Data;

/**
 * 解析任务入队后的即时响应：返回 taskId 供后续轮询任务状态。
 */
@Data
public class ParseTaskVO {

    private String taskId;
    private String fileId;
    private TaskStatus status;
    /** 预估耗时（秒），仅提示用途 */
    private Integer estimatedSeconds;
}
