package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 部门档案 + 预算（给管理层的「部门预算」页用）。
 *
 * <p>接口设计上补了这一份 VO 是因为后端以前没有部门列表接口，前端只能拿
 * {@code config/depts.js} 里写死的四个部门——库里加了新部门，页面看不见。
 *
 * <p>usedBudget（已使用）不在 VO 里：dept 表没有这个字段，
 * 真实花费来自 F-01 月报，前端已经会按 deptId 把当月实际付款额合进来。
 */
@Data
public class DeptVO {

    /** 部门 id，与 dept.id 一致，前端传参时用 deptId 这个名（H-03 也是这个名字） */
    private Long deptId;

    /** 部门名称 */
    private String deptName;

    /** 月度差旅预算 */
    private BigDecimal monthlyBudget;

    /** 差标等级，决定该部门按哪一档城市标准校验 */
    private String standardLevel;

    /** 部门审批人 id */
    private Long leaderId;

    /** 部门审批人姓名，页面上直接显示，避免前端再查一次用户表 */
    private String approverName;

    /** 部门在职员工数，删除部门前用它判断能不能删 */
    private Long memberCount;

    /** 该部门名下申请单数量，同样用于删除校验 */
    private Long applyCount;
}
