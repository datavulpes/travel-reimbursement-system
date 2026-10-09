package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 部门（dept）。
 *
 * <p>数据字典 8.2。部门经 standardLevel 引用一档城市差标，
 * leaderId 指定本部门初审人（设计报告 3.3 联系「隶属 / 适用」）。
 */
@Data
@TableName("dept")
public class Dept {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 部门名称，唯一 */
    private String deptName;

    /** 月度差旅预算，预借额度取「预估合计与部门预算的较小值」时要用 */
    private BigDecimal monthlyBudget;

    /** 差标等级，决定这个部门按哪一档城市标准校验 */
    private String standardLevel;

    /** 部门审批人（初审），指向 sys_user.id */
    private Long leaderId;
}
