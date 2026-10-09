package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 钻取解释（F-02 的解读层）：某个部门这个月的投入产出「为什么是这个样子」。
 *
 * <p>与 {@link MonthlyInsightVO} 的关系：那一份是月度、全公司、会被存下来的报告；
 * 这一份是单次、单部门、问一句答一句的解释，<b>不落库</b>。
 * 不落库是刻意的——它是人临时问的问题，存下来就会变成「又一份要维护的报告」，
 * 而它依赖的那份统计本来随时在变。
 *
 * <p>字段形状与 {@link MonthlyInsightVO} 保持一致（highlights + summary + local），
 * 这样前端复用同一张 AI 卡，不用为它再写一套渲染。
 */
@Data
public class DrillExplainVO {

    private Long deptId;

    private String deptName;

    /** 统计月份，yyyy-MM */
    private String month;

    /** 对比的上一个月 */
    private String prevMonth;

    private List<AiPointVO> highlights = new ArrayList<>();

    /** 一句话回答「为什么」，不超过 80 字 */
    private String summary;

    /** 这个部门这个月的已付款单数，也就是这份解释的样本量 */
    private Integer sampleCnt;

    private BigDecimal ratio;

    private BigDecimal prevRatio;

    /** 环比变化，正数表示变差 */
    private BigDecimal delta;

    /** true = 规则生成的本地版本，页面标「本地演示」 */
    private Boolean local;

    private String model;

    private String promptVersion;

    /** 数字出处，与月度洞察同一套，不上页面 */
    private List<String> sources = new ArrayList<>();
}
