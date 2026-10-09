package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 归因钻取（F-02）：部门 → 单据 → 票据 / 审批依据 / 业务结果。
 *
 * <p>管理层在月报上点某个部门的柱子，往下钻到具体单据，再往下看到票据与审批依据，
 * 一路能追到原始凭证（FR-0402）。一次返回整条链，不让前端点一次请求一次。
 */
@Data
public class DrillVO {

    private Long deptId;

    private String deptName;

    /** 统计月份，yyyy-MM */
    private String month;

    /** 该部门该月的单据，点开某一行后看的明细（票据、审批依据、业务结果） */
    private List<ApplyRowVO> applies;

    /**
     * 钻取链的扁平视图：申请单与报销单混排，管理层用它看"哪笔花得值"。
     *
     * <p>与 {@link #applies} 是同一批数据的两种形态：
     * rows 是表格直接渲染的六列，applies 是点开某一行后要看的明细（票据、审批依据、业务结果）。
     * 字段名对齐 Apifox 文档与前端 Drilldown.vue（level / bizNo / name / payAmount / outputAmount / ratio）。
     */
    private List<DrillRowVO> rows;

    /**
     * 钻取链上的一张申请单。
     */
    @Data
    public static class ApplyRowVO {

        private String applyNo;

        private String reason;

        private String applicant;

        private BigDecimal estTotal;

        /** 实付金额，未付款为 0 */
        private BigDecimal payAmount;

        /** 折算产出合计 */
        private BigDecimal outputAmount;

        /** 评价档位 */
        private String evalLevel;

        private String status;

        /** 该单的票据 */
        private List<InvoiceVO> invoices;

        /** 该单的审批流转记录，含依据快照 */
        private List<ApplyDetailVO.FlowRecordVO> records;

        /** 该单回填的业务结果 */
        private List<BizResultRowVO> results;
    }

    /**
     * 钻取表格的一行。level 区分是报销单还是申请单，两张单据共用一组列。
     *
     * <p>ratio = payAmount ÷ outputAmount，产出为 0 时记 0（前端会把 0 显示为红色"无产出"）。
     */
    @Data
    public static class DrillRowVO {

        /** REPORT 报销单 / APPLY 申请单 */
        private String level;

        /** 业务单号：ER... 或 TA... */
        private String bizNo;

        /** 展示名：报销单带申请人，申请单只写事由 */
        private String name;

        /** 投入：报销单取单据总额，申请单取实付金额 */
        private BigDecimal payAmount;

        /** 产出：该申请单回填的业务结果折算金额 */
        private BigDecimal outputAmount;

        /** 费效比 = 投入 ÷ 产出，保留 4 位；产出为 0 时记 0（前端标红「无产出」） */
        private BigDecimal ratio;
    }

    /**
     * 业务结果回填的一行。
     */
    @Data
    public static class BizResultRowVO {

        private String resultType;

        private String resultDesc;

        private BigDecimal outputAmount;

        /** 回填时间，yyyy-MM-dd HH:mm:ss */
        private String filledAt;
    }
}
