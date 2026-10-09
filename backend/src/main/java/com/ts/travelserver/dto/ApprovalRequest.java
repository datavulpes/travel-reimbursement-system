package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 审批结论（B-03）。
 *
 * <p>result 为 REJECTED 时 opinion 必填（BR-03）。
 * 提交瞬间后端把页面上展示的参考值、差标结论、评价结论序列化写进
 * {@link com.ts.travelserver.entity.ApprovalRecord#getSnapshot()}，事后可回溯（FR-0107）。
 */
@Data
public class ApprovalRequest {

    /**
     * 结论：APPROVED 通过、REJECTED 驳回。
     * 取值见 {@link com.ts.travelserver.entity.ApprovalRecord} 的 RESULT_* 常量。
     */
    @NotBlank(message = "审批结论不能为空")
    private String result;

    /** 审批意见，驳回时必填，不超过 200 字 */
    @Size(max = 200, message = "审批意见不超过 200 字")
    private String opinion;

    /**
     * 结果回填内容。审批人可以在审批时顺手把这趟的结果填了（FR-0108）。
     * 为空时不回填。
     */
    private String resultType;

    private String resultDesc;

    /** 折算产出金额 */
    private java.math.BigDecimal outputAmount;

    public boolean rejected() {
        return "REJECTED".equals(result);
    }
}
