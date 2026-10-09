package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配置调整留痕（config_log）。
 *
 * <p>数据字典 8.15。差标的每一次新增 / 调整 / 删除、部门预算的每一次调整，都在这张表落一条，
 * 系统管理员改往来对象、用户账号、部门档案也一样。应用层只 INSERT 不 UPDATE、不 DELETE，
 * 页面上的「调整留痕」是只读的。
 *
 * <p>beforeValue / afterValue 设计为字符串，因为要装的不是只有金额：
 * 部门预算记数字，往来对象记名称，用户账号记「角色从 EMPLOYEE 改成 MANAGER」这类变化。
 *
 * <p>为什么必须要这张表：dept 表只有一个 monthly_budget 字段，改完前值就没了；
 * trip_standard 虽然按版本存，但没地方记「谁改的、为什么改」，
 * 以前 H-04 的 reason 只能拼一句「生效日期 xxx」，管理层填的原因白填了。
 */
@Data
@TableName("config_log")
public class ConfigLog {

    /** 变更类型：差标 */
    public static final String TYPE_STANDARD = "STANDARD";
    /** 变更类型：部门预算 */
    public static final String TYPE_BUDGET = "BUDGET";
    /** 变更类型：部门本身（新增 / 删除部门） */
    public static final String TYPE_DEPT = "DEPT";
    /** 变更类型：往来对象，系统管理员维护 */
    public static final String TYPE_PARTNER = "PARTNER";
    /** 变更类型：用户账号，系统管理员维护 */
    public static final String TYPE_USER = "USER";
    /** 变更类型：部门档案（名称与负责人），与部门预算 TYPE_BUDGET 分开记，改的是两回事 */
    public static final String TYPE_DEPT_INFO = "DEPT_INFO";

    /** 操作类型：新增 */
    public static final String ACTION_INSERT = "INSERT";
    /** 操作类型：修改 */
    public static final String ACTION_UPDATE = "UPDATE";
    /** 操作类型：删除 */
    public static final String ACTION_DELETE = "DELETE";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 变更类型，取值见本类 TYPE_* 常量 */
    private String itemType;

    /** 变更项标识，如「二级-高铁」「dept:3」 */
    private String itemKey;

    /** 操作类型，取值见本类 ACTION_* 常量 */
    private String action;

    /** 调整前的值，新增时为 null */
    private String beforeValue;

    /** 调整后的值，删除时为 null */
    private String afterValue;

    /** 操作人，指向 sys_user.id */
    private Long operatorId;

    /** 操作时间 */
    private LocalDateTime operateAt;

    /** 调整原因，管理层填写后原样存下来 */
    private String reason;
}
