package com.ts.travelserver.vo;

import lombok.Data;

/**
 * 用户账号，系统管理员的「基础数据 / 用户账号」页用。
 *
 * <p>与 UserInfoVO 是两回事：UserInfoVO 是登录后给自己看的那一份（含 token 用户本人的银行账号脱敏），
 * 这份是管理员看全员用的，多了 department 名称、引用计数，密码一律不给。
 *
 * <p>password 字段从不出现在任何返回体里；bankAccount 给的是脱敏串，
 * 跟财务侧口径一致（前 3 后 4）。管理员要改就重新录入，不必先读出来。
 */
@Data
public class AdminUserVO {

    private Long id;

    /** 登录名 */
    private String username;

    /** 姓名 */
    private String realName;

    /** 角色，取值见 SysUser 的 ROLE_* 常量 */
    private String role;

    private Long deptId;

    /** 部门名称，列表要显示这个，免得前端拿 id 再查一轮 */
    private String deptName;

    /** 银行账号脱敏串，没设置时为 null */
    private String bankAccountMasked;

    /**
     * 被多少张单据引用（申请单、预借单、审批留痕），大于 0 不允许删除。
     * 不能删但改姓名、改部门、改角色都可以——那些是档案信息。
     */
    private Long refCount;

    /** 是否为本人登录的账号，页面不给删、不给禁用自己 */
    private Boolean self;

    private String createdAt;
}
