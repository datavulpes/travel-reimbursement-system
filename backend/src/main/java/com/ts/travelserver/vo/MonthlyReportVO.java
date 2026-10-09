package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 部门费效比月报（F-01，F-04 的四个关键数字也用这份）。
 *
 * <p>指标口径（设计报告 6.6）：
 * 部门投入 = 该部门该月实付净额之和；部门产出 = 该部门该月业务结果折算产出之和；
 * 费效比 = 投入 ÷ 产出（越小越划算）；可疑差旅占比 = 评价档位为「关注」的单数 ÷ 总单数。
 *
 * <p>除了部门维度，这里还带三份「结构」数据：出差类型维度（{@code typeRows}）、
 * 产出构成（{@code outputStruct}）、数据可信度（{@code fillRate}）。
 * 加它们是因为单看一个费效比回答不了「钱花在哪、产出从哪来、这些数字可信吗」——
 * 而这正好是管理层看完月报后最先问的三句。
 *
 * <p>钻取路径：月报部门柱 → 该部门单据列表 → 单张申请号 → 票据、审批依据、业务结果（FR-0402）。
 */
@Data
public class MonthlyReportVO {

    /** 月份，yyyy-MM */
    private String month;

    /** 按部门汇总的行 */
    private List<DeptRowVO> rows;

    /** 全公司投入合计 */
    private BigDecimal totalPay;

    /** 全公司产出合计 */
    private BigDecimal totalOutput;

    /** 全公司费效比 = 投入 ÷ 产出，越小越划算；产出为 0 时记 0 */
    private BigDecimal ratio;

    /** 评价档位为「关注」的单数，算可疑差旅占比用 */
    private Integer attentionCnt;

    /** 该月申请总单数，算可疑差旅占比的分母 */
    private Integer applyCnt;

    /** 事前拦截金额：差标超限且最终未成行的预估合计，反映管控前置效果 */
    private BigDecimal blockedAmount;

    // ---------- 数据可信度：结论能不能信，先说清楚 ----------

    /**
     * 本月已付款的单数。分母口径：只有付了钱的单才算真正发生过的投入，
     * 申请了没走完的单不进统计，也不进回填率的分母。
     */
    private Integer paidCnt;

    /**
     * 已付款单据里回填了业务结果的单数（填「无进展」也算回填了——
     * 这里衡量的是数据完整度，不是产出好坏）。
     */
    private Integer filledCnt;

    /**
     * 产出回填率 = filledCnt ÷ paidCnt。产出全靠人工回填，
     * 回填率 40% 和 90% 下算出来的费效比可信度完全不同，这个数必须跟着结论一起出现。
     */
    private BigDecimal fillRate;

    /** 可疑差旅占比 = attentionCnt ÷ applyCnt */
    private BigDecimal attentionRatio;

    /** 按出差类型汇总的行：回答「哪类出差划算」 */
    private List<TypeRowVO> typeRows;

    /** 产出构成：回答「产出从哪来、多少趟没结果」 */
    private OutputStructVO outputStruct;

    /**
     * 本月结论：把上面的数字写成几句话。
     *
     * <p>由 {@code MonthlyConclusionBuilder} 用模板生成，不进模型——
     * 结论里的每个数字都来自本报表自己的统计，交给我模型只会增加说错数字的风险。
     */
    private ConclusionVO conclusion;

    /**
     * 本月结论。字段名与前端 mock 的 aiMonthlySummary 返回值一致，
     * 页面直接把 highlights 当 AI 卡的 points 用。
     */
    @Data
    public static class ConclusionVO {

        /** 结论要点，最好与最差各一条 */
        private List<AiPointVO> highlights;

        /** 拼好的一句话总结 */
        private String text;
    }

    /**
     * 按出差类型汇总的一行。
     *
     * <p>出差类型（验货 / 商务拜访 / 项目交付 / 供应商审核 / 展会）是比部门更能指导行动的一个维度：
     * 部门是组织边界，改不了；类型是可以调的——「展会这类出差费效比常年难看」
     * 直接对应到「下次展会少派人」这样的动作。
     */
    @Data
    public static class TypeRowVO {

        /** 原始取值；未填类型时这里是 null */
        private String tripType;

        /** 页面显示名，未填类型时为「未填类型」 */
        private String label;

        private BigDecimal totalPay;

        private BigDecimal totalOutput;

        private BigDecimal ratio;

        private Integer applyCnt;
    }

    /**
     * 产出构成：本月回填的业务结果按类型拆开。
     *
     * <p>「无进展率」比费效比更好解释：费效比差可能是花多了，也可能是白跑了，
     * 这两个问题的处理方式完全不同——一个是控成本，一个是别再派人去。
     */
    @Data
    public static class OutputStructVO {

        /** 回填的业务结果总条数 */
        private Integer totalCnt;

        /** 无进展条数 ÷ 总条数；为 null 表示本月还没回填过 */
        private BigDecimal noProgressRate;

        /** 各类型的条数与折算产出，按条数降序 */
        private List<OutputItemVO> items = new ArrayList<>();
    }

    /**
     * 产出构成里的一项。
     */
    @Data
    public static class OutputItemVO {

        /** 签单 / 验收 / 交付 / 无进展 */
        private String resultType;

        private Integer cnt;

        private BigDecimal amount;
    }

    /**
     * 月报里的一行：一个部门一个月的投入产出。
     */
    @Data
    public static class DeptRowVO {

        private Long deptId;

        private String deptName;

        /** 部门投入 */
        private BigDecimal totalPay;

        /** 部门产出 */
        private BigDecimal totalOutput;

        /** 费效比 = 投入 ÷ 产出，越小越划算；产出为 0 时记 0 */
        private BigDecimal ratio;

        /** 出差单数 */
        private Integer applyCnt;
    }
}
