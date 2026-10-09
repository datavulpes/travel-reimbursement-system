package com.ts.travelserver.eval;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 评价因子。
 *
 * <p>设计报告 6.7 定死四个因子与权重：F1 历史产出率 0.35、F2 成本占对象产出比 0.30、
 * F3 频次异常度 0.20、F4 事由完整度 0.15。权重写在 application.yml 的 travel.eval.weight 里，
 * 各因子自己用 @Value 取，方便答辩时现场调权重看结论怎么变。
 *
 * <p>每个因子既要给分，也要给一句「这个分是怎么来的」——审批人看到的不该只有一个数字。
 */
public abstract class EvalFactor {

    /** 因子键，与前端 evalData.factors[].key 对齐 */
    public abstract String key();

    /** 因子名称 */
    public abstract String name();

    /** 权重，四项之和为 1 */
    public abstract BigDecimal weight();

    /**
     * 算这一项得分。
     *
     * @param ctx 评价输入，预演与正式评分共用
     * @return 得分与依据描述
     */
    public abstract EvalScore calc(EvalContext ctx);

    /**
     * 单因子的计算结果。
     */
    @Data
    @AllArgsConstructor
    public static class EvalScore {

        /** 得分，0~100，超出范围由引擎统一夹到边界 */
        private BigDecimal score;

        /** 原始取值与命中描述，例如「该对象历史 5 趟，有效产出 3 次，产出率 60%」 */
        private String value;
    }
}
