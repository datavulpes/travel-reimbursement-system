package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交报销单（D-02）。
 *
 * <p>后端按 applyNo 归集票据生成报销单：
 * totalAmount = 票据金额之和，offsetAmount = min(预借余额, totalAmount)，
 * netAmount = totalAmount − offsetAmount（设计报告 6.4.5）。
 *
 * <p>预检结论为 FORCE 时 forceReason 必填，否则返回 2004（BR-05）。
 */
@Data
public class ExpenseSubmitRequest {

    /** 申请号，一张申请一张报销单 */
    @NotBlank(message = "申请号不能为空")
    private String applyNo;

    /** 强制提交说明：预检有缺票或提醒项时必填，提交后留痕 */
    @Size(max = 200, message = "强制说明不超过 200 字")
    private String forceReason;

    /**
     * 员工备注，选填。
     */
    @Size(max = 200, message = "备注不超过 200 字")
    private String remark;
}
