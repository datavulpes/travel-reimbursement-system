package com.ts.travelserver.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 调整部门月度预算（H-03）。
 *
 * <p>dept 表只有 monthly_budget 一个字段，改完前值就没了，所以「前后值 + 原因 + 操作人」
 * 由 StandardService 落到 config_log 表，H-04 的留痕页能翻出来。
 */
@Data
public class BudgetUpdateRequest {

    /** 部门 id */
    @NotNull(message = "请选择部门")
    private Long deptId;

    /** 新的月度预算 */
    @NotNull(message = "请填写月度预算")
    @DecimalMin(value = "0.01", message = "月度预算必须大于 0")
    private BigDecimal monthlyBudget;

    /**
     * 调整说明。字段名用 reason 对齐 Apifox 文档与前端 StandardConfig.vue，
     * 后端原先叫 remark，前端传的值接不住，留痕里原因永远是空。
     */
    private String reason;
}
