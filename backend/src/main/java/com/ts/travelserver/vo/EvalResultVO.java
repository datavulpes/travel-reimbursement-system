package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合理性评价结论与依据明细（G-01，事前提示 G-02 也用这份）。
 *
 * <p>四因子加权：F1 历史产出率 0.35、F2 成本占对象产出比 0.30、
 * F3 频次异常度 0.20、F4 事由完整度 0.15。
 * 分档 ≥75 建议、55~74 提示、＜55 关注。
 *
 * <p>结论只作建议，不自动拦截（BR-08）。factors 逐项列出取值与得分来源，
 * 做到分数可解释——审批人看到的不该只是一个数字。
 */
@Data
public class EvalResultVO {

    /** 申请号 */
    private String applyNo;

    /** F1 历史产出率得分 */
    private BigDecimal fHistory;

    /** F2 成本占对象产出比得分 */
    private BigDecimal fCost;

    /** F3 频次异常度得分 */
    private BigDecimal fFreq;

    /** F4 事由完整度得分 */
    private BigDecimal fComplete;

    /** 加权总分 */
    private BigDecimal totalScore;

    /** 档位：SUGGEST / NOTICE / ATTENTION */
    private String level;

    /** 评价时间，yyyy-MM-dd HH:mm:ss */
    private String evalAt;

    /** 四因子的明细，前端按行展示 */
    private List<EvalFactorVO> factors;

    /**
     * AI 语义评价结论。为 null 表示这次评价没有 AI 参与（未开启或降级）。
     *
     * <p>注意 {@link #totalScore} 已经是「规则分 + Δ」之后的值——
     * AI 不单独给分，只挪动规则分，所以前端不需要再自己加一次。
     */
    private AiSemanticVO ai;

    /**
     * 事前提示命中的条目。G-02 预演时把命中的特征列出来，
     * 例如「目的地等于常住地」「30 天内第 3 次去同一对象」。
     */
    private List<String> hints;

    /**
     * 单个因子的得分明细，对应前端 evalData.factors[]。
     */
    @Data
    public static class EvalFactorVO {

        /** 因子键：F_HISTORY / F_COST / F_FREQ / F_COMPLETE */
        private String key;

        /** 因子名称 */
        private String name;

        /** 该因子得分，0~100 */
        private BigDecimal score;

        /** 权重，四项之和为 1 */
        private BigDecimal weight;

        /** 原始取值与命中描述，例如「该对象近 12 个月产出率 5.2，高于均值」 */
        private String value;
    }
}
