package com.ts.travelserver.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 问数助手结果（管理层用大白话问，系统把真实数字讲成一句人话）。
 *
 * <p>返回体刻意带上 {@link #facts} 与 {@link #data} 两个字段，与制度问答（PolicyQaVO）同一条口径：
 * 页面上把「这个答案依据哪几条真实数字」一并摆出来，看的人自己就能核对，
 * 不用去信一个没有出处的回答。这与全系统「AI 结论必须可溯源」是一致的。
 *
 * <p>{@link #data} 是结构化数字（不同能力形状不同），前端照类型渲染成卡片或表格——
 * 即便模型没接上、answer 是规则文案，data 里的真实数字照样能看。
 */
@Data
public class DataQaVO {

    /** 管理层问的原话 */
    private String question;

    /** 命中的能力代码（MONTHLY_RATIO / PARTNER_TOP / APPLY_SUMMARY / BUDGET_LEFT / OVERDUE_LIST） */
    private String capability;

    /** 命中能力的可读名 */
    private String capabilityText;

    /** 回答：模型叙事或规则文案 */
    private String answer;

    /** 这个问题能不能答（解析不出能力、或没问出维度时为 false，answer 给引导语） */
    private Boolean answerable;

    /** 回答用到的真实数字原文（证据），页面摆在答案下面 */
    private List<String> facts = new ArrayList<>();

    /** 结构化真实数字，前端照能力类型渲染成卡片 / 表格 */
    private Map<String, Object> data;

    /** true = 模型没接上，answer 是 Java 直接陈列的真实数字 */
    private Boolean local;

    /** 模型名，未启用为空 */
    private String model;

    /** prompt 版本 */
    private String promptVersion;
}
