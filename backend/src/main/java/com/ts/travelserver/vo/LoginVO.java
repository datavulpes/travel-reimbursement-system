package com.ts.travelserver.vo;

import lombok.Data;

/**
 * 登录结果（I-01）。
 *
 * <p>前端拿到 token 后存 localStorage，之后每个请求的 Authorization 头带上它；
 * role 决定前端渲染哪套菜单（设计报告 6.8 权限矩阵）。
 */
@Data
public class LoginVO {

    /** 登录凭证，后续请求放 Authorization 头 */
    private String token;

    private Long userId;

    private String username;

    private String realName;

    /** 角色：EMPLOYEE / APPROVER / FINANCE / MANAGER */
    private String role;

    private Long deptId;

    private String deptName;

    /** 过期时间，yyyy-MM-dd HH:mm:ss */
    private String expiresAt;
}
