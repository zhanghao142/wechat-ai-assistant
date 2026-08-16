package com.wechatai.tool.model.dto;

/**
 * executeScript 的返回结构。
 */
public class ScriptResult {

    private final boolean success;
    private final String result;
    private final String type;     // string / number / boolean / object / undefined
    private final String error;

    private ScriptResult(boolean success, String result, String type, String error) {
        this.success = success;
        this.result = result;
        this.type = type;
        this.error = error;
    }

    public static ScriptResult ok(String result, String type) {
        return new ScriptResult(true, result, type, null);
    }

    public static ScriptResult fail(String error) {
        return new ScriptResult(false, null, null, error);
    }

    public boolean isSuccess() { return success; }
    public String getResult() { return result; }
    public String getType() { return type; }
    public String getError() { return error; }

    @Override
    public String toString() {
        if (!success) return "❌ JS 执行失败: " + error;
        return "✅ JS 执行成功\n类型: " + type + "\n结果: " + result;
    }
}
