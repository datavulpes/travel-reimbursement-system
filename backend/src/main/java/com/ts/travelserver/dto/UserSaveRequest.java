package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 新增或修改用户账号（sys_user）。
 *
 * <p>新增与修改合一：以 id 是否为 null 区分。password 在新增时必填，
 * 修改时留空表示「不重置密码」——管理员改个人姓名、部门时不该被迫重设密码。
 *
 * <p>为什么新增时要管理员自己设密码而不是统一发 123456：
 * 演示账号确实是统一初始密码，但正式口径下账号就是账号管理员的活，
 * 这里只规定最小长度，不强插一个谁都知道的默认串。
 */
@Data
public class UserSaveRequest {

    /** 主键，有值为修改，空为新增 */
    private Long id;

    /** 登录名，库里唯一 */
    @NotBlank(message = "请填写登录名")
    private String username;

    /** 姓名 */
    @NotBlank(message = "请填写姓名")
    private String realName;

    /** 角色，取值见 SysUser 的 ROLE_* 常量 */
    @NotBlank(message = "请选择角色")
    private String role;

    /** 所属部门 */
    @NotNull(message = "请选择部门")
    private Long deptId;

    /** 银行账号，仅财务可见且脱敏，留空表示不设置 */
    private String bankAccount;

    /** 新密码，修改时留空表示不改密码 */
    private String password;

    /** 调整说明 */
    private String reason;
}
