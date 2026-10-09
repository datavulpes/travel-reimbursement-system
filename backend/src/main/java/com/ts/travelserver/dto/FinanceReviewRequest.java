package com.ts.travelserver.dto;

import com.ts.travelserver.entity.ExpenseReport;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 复核结论（E-03）。
 *
 * <p>三种结论：通过（APPROVED）、带异常通过（PASS_WITH_ISSUE）、退回（RETURNED）。
 * 后两种 reason 必填——带异常通过要说清什么异常，退回要说清为什么退（BR-06）。
 *
 * <p>字段名用 reason 而不是 remark：Apifox 接口文档与前端 ReviewDetail.vue 的表单
 * 都叫 reason，后端原先叫 remark，前端传过来的值接不住，
 * 带异常通过 / 退回会永远提示"说明不能为空"。三方统一为 reason。
 */
@Data
public class FinanceReviewRequest {

    /**
     * 结论：APPROVED / PASS_WITH_ISSUE / RETURNED，
     * 取值见 {@link ExpenseReport} 的 STATUS_* 常量。
     */
    @NotBlank(message = "请选择复核结论")
    private String result;

    /** 说明：带异常通过或退回时必填 */
    @Size(max = 200, message = "说明不超过 200 字")
    private String reason;

    public boolean needRemark() {
        return ExpenseReport.STATUS_PASS_WITH_ISSUE.equals(result)
                || ExpenseReport.STATUS_RETURNED.equals(result);
    }
}
