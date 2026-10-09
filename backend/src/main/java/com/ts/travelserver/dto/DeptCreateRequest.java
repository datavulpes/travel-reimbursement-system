package com.ts.travelserver.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 新增部门（含初始月度预算）。
 *
 * <p>页面上「新增部门预算」走的其实是这条：部门本来就要先存在，预算才挂得上去，
 * 所以一次表单把部门名称、差标等级、审批人、初始预算一起收集了。
 */
@Data
public class DeptCreateRequest {

    /** 部门名称，库里唯一 */
    @NotBlank(message = "请填写部门名称")
    private String deptName;

    /** 初始月度预算 */
    @NotNull(message = "请填写月度预算")
    @DecimalMin(value = "0.01", message = "月度预算必须大于 0")
    private BigDecimal monthlyBudget;

    /** 差标等级：一级、二级、三级、四级、五级 */
    @NotBlank(message = "请选择差标等级")
    private String standardLevel;

    /**
     * 部门审批人（初审），指向 sys_user.id。
     * 不传的话 StandardService 会去找一个 APPROVER 顶上，避免新建的部门没人批导致单据卡死。
     */
    private Long leaderId;

    /** 新增说明，写进留痕 */
    private String reason;
}
