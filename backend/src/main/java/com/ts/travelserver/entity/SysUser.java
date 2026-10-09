package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户（sys_user）。
 *
 * <p>数据字典 8.1。角色用 role 字段区分，不建角色表，
 * 权限矩阵见设计报告 6.8：员工、审批人、财务、管理层、系统管理员。
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 角色：普通员工，填申请、传票据、报销 */
    public static final String ROLE_EMPLOYEE = "EMPLOYEE";
    /** 角色：审批人，部门初审与管理层复审 */
    public static final String ROLE_APPROVER = "APPROVER";
    /** 角色：财务核算岗，复核、付款、查看银行账号 */
    public static final String ROLE_FINANCE = "FINANCE";
    /** 角色：经营管理层，看月报、调差标与预算 */
    public static final String ROLE_MANAGER = "MANAGER";
    /**
     * 角色：系统管理员，管往来对象、用户账号与部门档案，以及全表只读浏览。
     *
     * <p>为什么差标与部门预算不归他：那两项参与运行时计算——<code>dept.monthlyBudget</code>
     * 直接决定 BR-04 的预借额度上限，差标决定是否判超标、要不要强制写超标说明。
     * 这属于财权，交给管理层，费效分析的「看完能调」这个闭环才不断。
     *
     * <p>也不叫「数据库管理员」：他改的是业务档案，不负责备份、性能、账号权限这类 DBA 活。
     */
    public static final String ROLE_SYS_ADMIN = "SYS_ADMIN";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名，唯一 */
    private String username;

    /** BCrypt 密文，不存明文（设计报告 5.8） */
    private String password;

    /** 姓名 */
    private String realName;

    /** 角色，取值见本类 ROLE_* 常量 */
    private String role;

    /** 所属部门 */
    private Long deptId;

    /** 银行账号，仅财务可见且界面脱敏（前 3 后 4） */
    private String bankAccount;

    /** 逻辑删除：0 未删，1 已删 */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt = LocalDateTime.now();
}
