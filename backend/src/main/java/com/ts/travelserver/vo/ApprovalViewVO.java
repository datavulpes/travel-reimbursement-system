package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 审批详情（B-02）：一次取齐，审批人不用到处点。
 *
 * <p>设计报告 6.3.6 要求这一页同时给出：单据信息、同类参考
 * （本次预估在区间中的位置，超区间高亮）、差标校验结论与超标说明、
 * 评价三档结论、该对象历史出差与产出。
 *
 * <p>点「同意 / 驳回」时，后端把页面当时展示的这些值序列化写进依据快照。
 */
@Data
public class ApprovalViewVO {

    /** 单据本身，含行程段与已挂接票据 */
    private ApplyDetailVO apply;

    /** 同类花费参考。样本量不足 5 条时为 null，前端不展示参考卡 */
    private CostRefVO costRef;

    /** 差标校验明细，逐段列出上限与预估 */
    private List<StandardHintVO> standardHints;

    /** 合理性评价结论与依据 */
    private EvalResultVO eval;

    /** 本单已有流转记录 */
    private List<ApplyDetailVO.FlowRecordVO> records;

    /** 该对象历史出差与产出，用来判断这趟去得值不值 */
    private List<ApplyListItemVO> history;

    /**
     * 当前用户现在能不能对这张单下结论。
     *
     * <p>「看得了」和「批得了」是两回事：自己签过字的单会进「已办」，
     * 那时状态早已往前走了，回看当时的依据天经地义，但不能再改口。
     * false 时前端把「审批意见」那块换成只读提示。
     */
    private Boolean canApprove;

    /**
     * canApprove 为 false 时的一句话说明，前端直接摆在页面上。
     * 不写清楚的话，用户点进来看到没有按钮，只会以为是页面坏了。
     */
    private String readonlyReason;

    /**
     * 本次预估在参考区间中的位置，0~1。小于 0 说明低于下限，大于 1 说明高于上限。
     * 前端拿它画区间条并决定是否高亮。参考值不足时为 null。
     */
    private BigDecimal refPosition;

    /**
     * 同类花费参考。字段名与前端 mock/costRefData 对齐。
     */
    @Data
    public static class CostRefVO {

        private String toCity;

        private String tripType;

        /** 近 12 个月均值 */
        private BigDecimal avgAmount;

        /** 区间下限 */
        private BigDecimal minAmount;

        /** 区间上限 */
        private BigDecimal maxAmount;

        /** 样本量 */
        private Integer sampleCnt;
    }

    /**
     * 差标校验明细。逐段比对，超区间那一段由前端标黄或标红。
     */
    @Data
    public static class StandardHintVO {

        /** 城市等级 */
        private String cityLevel;

        /** 交通方式 */
        private String trafficType;

        /** 差标上限 */
        private BigDecimal maxAmount;

        /** 本段预估金额 */
        private BigDecimal estAmount;

        /** 该段结论：NORMAL / HIGH / EXCEED */
        private String result;
    }
}
