package com.ts.travelserver.vo;

import lombok.Data;

/**
 * 预检的一条结论（D-02 / D-04）。
 *
 * <p>字段与 Apifox 文档的 PrecheckIssue 一致：type / desc / level。
 * 原先后端只有 List&lt;String&gt; checks，一行纯文本，前端没法按严重度上色，
 * 答辩时也讲不清"预检到底检了什么"。改成对象后可以按 level 分色展示。
 *
 * <p>level 三档：WARN 提醒、FORCE 必须写强制说明才能提交、BLOCK 直接拦下。
 */
@Data
public class PrecheckIssueVO {

    /** 检查项：INVOICE 票据 / STANDARD 差标 / AMOUNT 金额 / DATE 日期 */
    private String type;

    /** 可读的问题描述，直接展示给员工和财务 */
    private String desc;

    /** 严重度：WARN / FORCE / BLOCK */
    private String level;
}
