package com.ts.travelserver.annotation;

import com.ts.travelserver.entity.SysUser;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口角色声明。
 *
 * <p>打在 Controller 方法上，拦截器 {@code RoleInterceptor} 读它做鉴权：
 * 先要求已登录（token 有效），再看当前用户角色在不在 value 里，不在就返回 3001。
 *
 * <p>不写 value 表示「只要登录就能调」，用于 G-01、A-04 这类四个角色都能看的接口。
 *
 * <p>取值用 {@link SysUser} 的 ROLE_* 常量，别手写字符串。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /**
     * 允许访问的角色列表。留空表示任意已登录角色。
     */
    String[] value() default {};
}
