package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 修改部门档案（dept 的名称与负责人）。
 *
 * <p>只改这两项。月度预算与差标等级不在这里——它们参与运行时计算
 * （BR-04 的预借额度取 min(预估合计, 部门月度预算)；差标等级决定按哪一档城市标准校验，
 * 直接决定是否判超标），属于财权，仍然由管理层在 H-03 里调。
 * 系统管理员拿的是档案维护权，不是业务规则决定权。
 */
@Data
public class DeptInfoUpdateRequest {

    /** 部门名称，库里唯一 */
    @NotBlank(message = "请填写部门名称")
    private String deptName;

    /** 部门初审人，指向 sys_user.id */
    @NotNull(message = "请选择部门负责人")
    private Long leaderId;

    /** 调整说明 */
    private String reason;
}
