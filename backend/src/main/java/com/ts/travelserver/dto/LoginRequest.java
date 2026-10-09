package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录入参（I-01）。
 *
 * <p>只有账号密码两项，登录失败不区分「账号不存在」和「密码错」，
 * 统一提示「用户名或密码错误」，避免暴露哪些账号是存在的。
 */
@Data
public class LoginRequest {

    @NotBlank(message = "请输入用户名")
    private String username;

    @NotBlank(message = "请输入密码")
    private String password;
}
