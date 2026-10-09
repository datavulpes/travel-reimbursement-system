package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 提交申请的结果（A-01 存草稿 / A-02 正式提交 / A-05 重新提交共用）。
 *
 * <p>设计报告 7.4：提交瞬间后端就把差标结论与事前评价一起返回，
 * 员工不用再点一次详情才知道自己超没超标。
 *
 * <p>草稿态不做差标校验也不算评价，这两个字段为 null，前端据此跳过提示区。
 */
@Data
public class ApplySubmitResultVO {

    /** 申请号。A-05 重新提交会生成新申请号（设计报告 6.3.1） */
    private String applyNo;

    /** 状态：DRAFT / DEPT_REVIEW ... */
    private String status;

    /** 服务端重算的预估合计，以它为准，不认前端传的值 */
    private BigDecimal estTotal;

    /** 差标结论：NORMAL / HIGH / EXCEED */
    private String standardResult;

    /** 逐段差标明细，超在哪一段一眼看到 */
    private List<ApprovalViewVO.StandardHintVO> standardHints;

    /** 事前合理性评价，草稿态为 null */
    private EvalResultVO evalPreview;

    /** 超标说明，原样回显 */
    private String exceedReason;
}
