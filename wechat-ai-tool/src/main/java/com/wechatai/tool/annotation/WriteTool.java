package com.wechatai.tool.annotation;

import java.lang.annotation.*;

/**
 * WRITE 类型工具标记 — 有副作用的工具（发送邮件、生成文件、设置提醒等）。
 * <p>
 * 图中断会自动在 {@code tool_execute} 节点执行前暂停，等待用户确认后才放行。
 * 组员新增 WRITE 工具时，在类上加上此注解即可被图中断自动识别。
 * <p>
 * 使用方式：
 * <pre>{@code
 * @Component
 * @WriteTool("发送电子邮件")
 * public class SendEmailTool { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface WriteTool {

    /**
     * 对人类展示的操作简述，如 "发送邮件给 xxx"、"生成合同文档"。
     * 用于组装确认消息。
     */
    String value() default "";
}
