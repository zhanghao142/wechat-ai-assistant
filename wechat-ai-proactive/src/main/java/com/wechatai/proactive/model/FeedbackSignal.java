package com.wechatai.proactive.model;

/**
 * 用户反馈信号。
 */
public class FeedbackSignal {

    public enum Type {
        /** 显式正面：谢谢、太有用了 */
        EXPLICIT_POSITIVE,
        /** 显式负面：不对、你搞错了 */
        EXPLICIT_NEGATIVE,
        /** 隐式正面：追问相关问题 */
        IMPLICIT_POSITIVE,
        /** 隐式负面：忽略、切话题 */
        IMPLICIT_NEGATIVE,
        /** 无信号 */
        NEUTRAL,
        /** 未解析 */
        NONE
    }

    private final Type type;
    private final double weight;  // 0.0 ~ 1.0

    public FeedbackSignal(Type type, double weight) {
        this.type = type;
        this.weight = weight;
    }

    public Type getType() { return type; }
    public double getWeight() { return weight; }

    public boolean isPositive() {
        return type == Type.EXPLICIT_POSITIVE || type == Type.IMPLICIT_POSITIVE;
    }

    public boolean isNegative() {
        return type == Type.EXPLICIT_NEGATIVE || type == Type.IMPLICIT_NEGATIVE;
    }

    public boolean isNone() {
        return type == Type.NONE || type == Type.NEUTRAL;
    }

    @Override
    public String toString() {
        return "FeedbackSignal{type=" + type + ", weight=" + weight + "}";
    }
}
