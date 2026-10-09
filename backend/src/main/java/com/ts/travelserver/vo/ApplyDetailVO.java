package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 申请详情（A-04，也是审批详情页里 apply 那一段）。
 *
 * <p>一次取齐：单据信息 + 行程段 + 票据 + 流转记录。
 * 顶部固定显示申请号与状态，任何单据页都能一眼确认在处理哪一趟差（需求报告 6.x 界面约定）。
 */
@Data
public class ApplyDetailVO {

    /** 申请号 */
    private String applyNo;

    /** 申请人姓名 */
    private String applicant;

    /** 部门名称 */
    private String deptName;

    /** 往来对象 id。草稿回填表单时要把下拉框选回原值，光有名称选不中 */
    private Long partnerId;

    /** 往来对象名称 */
    private String partnerName;

    /** 出差事由 */
    private String reason;

    /** 出差类型 */
    private String tripType;

    /** 出差类型为「其他」时的补充说明，其余类型为空 */
    private String tripTypeNote;

    /** 预估交通费 */
    private BigDecimal estTraffic;

    /** 预估住宿费 */
    private BigDecimal estHotel;

    /** 预估合计 */
    private BigDecimal estTotal;

    /** 差标结论：NORMAL / HIGH / EXCEED */
    private String standardResult;

    /** 超标说明 */
    private String exceedReason;

    /** 状态 */
    private String status;

    /** 提交时间，yyyy-MM-dd HH:mm:ss */
    private String createdAt;

    /** 行程段 */
    private List<ItineraryVO> itineraries;

    /** 本单已挂接的票据 */
    private List<InvoiceVO> invoices;

    /** 流转记录：提交、初审、复审 */
    private List<FlowRecordVO> records;

    /**
     * 已回填的业务结果（A-07）。出差回来填的「这趟办成什么」，一单可以有多条。
     *
     * <p>带在详情里是为了让员工回填完当场就能看到自己填了什么，
     * 否则接口返回 200 但页面上什么都没变，跟没填一样。
     */
    private List<BizResultVO> results;

    /**
     * 行程段。invoiceCnt 让前端直接在行程表上显示每段挂了几张票，
     * 缺票推算的结果在 MissingTicketVO 里单独返回。
     */
    @Data
    public static class ItineraryVO {

        private Long id;

        /** 段序号 */
        private Integer seq;

        /** 开始日期，yyyy-MM-dd */
        private String startDate;

        /** 结束日期，yyyy-MM-dd */
        private String endDate;

        /** 出发地 */
        private String fromCity;

        /** 目的地 */
        private String toCity;

        /** 交通方式 */
        private String trafficType;

        /** 目的地城市等级 */
        private String cityLevel;

        /** 该段已挂接的票据数 */
        private Integer invoiceCnt;
    }

    /**
     * 流转记录。submit 那一步 result 也是 APPROVED，只是 step 为 SUBMIT。
     */
    @Data
    public static class FlowRecordVO {

        private Long id;

        private String applyNo;

        /** 操作人姓名 */
        private String approver;

        /** 环节：SUBMIT / DEPT_REVIEW / FINAL_REVIEW */
        private String step;

        /** 结论：APPROVED / REJECTED */
        private String result;

        /** 意见 */
        private String opinion;

        /**
         * 依据快照（JSON 文本），前端展示时当字符串原样渲染即可。
         * 存它是为了「批有依据、拒有理由、事后可溯」。
         */
        private String snapshot;

        /** 审批时间，yyyy-MM-dd HH:mm:ss */
        private String approvedAt;
    }

    /**
     * 业务结果回填记录。resultType 取值见 BizResult 的 TYPE_* 常量。
     */
    @Data
    public static class BizResultVO {

        private Long id;

        /** 结果类型：签单 / 验收 / 交付 / 无进展 */
        private String resultType;

        /** 结果说明 */
        private String resultDesc;

        /** 折算产出金额 */
        private BigDecimal outputAmount;

        /** 回填人姓名 */
        private String filledBy;

        /** 回填时间，yyyy-MM-dd HH:mm:ss */
        private String filledAt;
    }
}
