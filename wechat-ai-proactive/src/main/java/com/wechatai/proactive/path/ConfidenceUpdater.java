package com.wechatai.proactive.path;

import com.wechatai.proactive.model.BehaviorPath;

import java.time.Instant;

/**
 * 置信度更新器 — Beta 分布模型，纯数学，无外部依赖。
 * <p>
 * 先验 Beta(α=1, β=1)，每次成功 α+=1，失败 β+=1。
 * 置信度 = α / (α + β)。
 */
public class ConfidenceUpdater {

    /**
     * 更新置信度。
     *
     * @param path    当前路径
     * @param success 本次执行是否成功
     * @return 更新后的路径
     */
    public BehaviorPath update(BehaviorPath path, boolean success) {
        if (success) {
            path.setAlpha(path.getAlpha() + 1);
            path.setSuccessCount(path.getSuccessCount() + 1);
        } else {
            path.setBeta(path.getBeta() + 1);
            path.setFailCount(path.getFailCount() + 1);
        }
        path.setUseCount(path.getUseCount() + 1);
        path.setLastUsed(Instant.now());
        return path;
    }

    /** 获取当前置信度 (0.0 ~ 1.0) */
    public double confidence(BehaviorPath path) {
        if (path.getAlpha() + path.getBeta() == 0) return 0.5;
        return (double) path.getAlpha() / (path.getAlpha() + path.getBeta());
    }

    /** 获取不确定性（方差） */
    public double uncertainty(BehaviorPath path) {
        double a = path.getAlpha();
        double b = path.getBeta();
        return (a * b) / (Math.pow(a + b, 2) * (a + b + 1));
    }
}
