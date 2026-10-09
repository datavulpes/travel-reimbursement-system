package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 报销列表行（D-03 我的报销 / E-01 待复核共用）。
 *
 * <p>issueCount 只在财务侧（E-01）有值，员工侧不展示——
 * 异常结论由财务下，不该提前把清单甩给员工（BR-06）。
 */
@Data
public class ExpenseListItemVO {

    private String reportNo;

    private String applyNo;

    /** 出差事由 */
    private String reason;

    /** 报销人姓名 */
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

    /** 状态：PENDING / APPROVED / PASS_WITH_ISSUE / RETURNED / PAID */
    private String status;

    /** 提交时间，yyyy-MM-dd HH:mm:ss */
    private String createdAt;

    /** 四维比对命中的异常数，员工侧为 null */
    private Integer issueCount;

    /** 票据张数 */
    private Integer invoiceCnt;
}
