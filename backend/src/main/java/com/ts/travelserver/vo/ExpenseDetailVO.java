package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 报销单详情（D-04）。
 *
 * <p>报销单三个金额刻意全部返回：总额、冲抵、净额必须同时可见可审（BR-10）。
 * precheckIssues 是提交时预检跑出来的逐条结论，财务一眼能看出当时为什么给这个结论。
 *
 * <p>precheckIssues 原来是 List&lt;String&gt; checks，对齐 Apifox 文档改成对象数组，
 * 每一项带 type / desc / level，前端能按严重度分色展示。
 */
@Data
public class ExpenseDetailVO {

    private String reportNo;

    private String applyNo;

    /** 出差事由 */
    private String reason;

    private String applicant;

    private String deptName;

    /** 票据合计 */
    private BigDecimal totalAmount;

    /** 冲抵预借 */
    private BigDecimal offsetAmount;

    /** 净额付款 */
    private BigDecimal netAmount;

    /** 预检结论：PASS / WARN / FORCE */
    private String precheckResult;

    /** 状态 */
    private String status;

    /** 强制提交说明，员工提交时写的，预检为 FORCE 时必有值 */
    private String forceReason;

    /** 财务复核说明，带异常通过或退回时财务写的那句话；未复核或通过时为 null */
    private String reviewReason;

    /** 预检逐条结论 */
    private List<PrecheckIssueVO> precheckIssues;

    /** 本单票据 */
    private List<InvoiceVO> invoices;

    /** 预借情况，没有预借为 null */
    private LoanVO loan;

    /** 提交时间，yyyy-MM-dd HH:mm:ss */
    private String createdAt;
}
