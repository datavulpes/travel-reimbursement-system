package com.ts.travelserver.common;

import com.ts.travelserver.entity.SysUser;

/**
 * 当前登录用户的线程上下文。
 *
 * <p>设计报告 6.8：不引 Spring Security，用拦截器做角色校验（2.3 三个主动简化之一）。
 * 拦截器解析 token 后把用户塞进 ThreadLocal，Service 层直接取，不用每个方法都传 userId。
 *
 * <p>请求结束必须 {@link #clear()}，否则线程池复用会串号——这事放在拦截器的 afterCompletion 里做。
 */
public final class UserContext {

    private static final ThreadLocal<SysUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(SysUser user) {
        HOLDER.set(user);
    }

    public static SysUser get() {
        return HOLDER.get();
    }

    /**
     * 当前用户 id，取不到直接抛未登录。Controller / Service 里要用到人时统一走这里。
     */
    public static Long currentId() {
        SysUser user = HOLDER.get();
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_LOGIN);
        }
        return user.getId();
    }

    /**
     * 当前用户角色。
     */
    public static String currentRole() {
        SysUser user = HOLDER.get();
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_LOGIN);
        }
        return user.getRole();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
