package com.ts.travelserver.vo;

import lombok.Data;

/**
 * 当前登录用户信息（I-03）。
 *
 * <p>页面刷新后前端不知道自己是谁，靠这个接口把角色与部门取回来重新渲染菜单。
 * 数据从拦截器写好的上下文里取，不查库。
 */
@Data
public class UserInfoVO {

    private Long userId;

    private String username;

    private String realName;

    /** 角色：EMPLOYEE / APPROVER / FINANCE / MANAGER */
    private String role;

    private Long deptId;

    private String deptName;

    /** 本部门初审人 id，前端判断「我能不能审这一单」时要用 */
    private Long leaderId;

    /** 银行账号，仅财务返回且脱敏（前 3 后 4），其余角色为 null */
    private String bankAccountMasked;
}
