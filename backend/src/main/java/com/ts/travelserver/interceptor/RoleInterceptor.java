package com.ts.travelserver.interceptor;

import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.ResultCode;
import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.mapper.SysUserMapper;
import com.ts.travelserver.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 登录态与角色鉴权拦截器。
 *
 * <p>设计报告 6.8：不引 Spring Security，账号密码登录成功后签发 token，
 * 之后每个请求从 {@code Authorization} 头取 token 验签，再按接口声明的角色比对。
 * 校验通过把用户塞进 {@link UserContext}，Service 层直接取，方法签名里不用层层传 userId。
 *
 * <p>三条判断顺序：CORS 预检放行 → 接口有没有声明角色 → token 有没有效 → 角色对不对。
 * 失败分别抛 4001（未登录 / token 失效）与 3001（角色不匹配），由全局异常处理器转成 R。
 *
 * <p>TODO-部署前确认：exclude-paths 目前只放行 /api/system/login 与 /api/error，
 * 如果后面加了健康检查、静态资源之类的免登录接口，要同步加到 application.yml 里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleInterceptor implements HandlerInterceptor {

    private final TokenService tokenService;
    private final SysUserMapper sysUserMapper;

    /** 请求头名，与前端 request.js 里的 key 一致 */
    @Value("${travel.security.token-header:Authorization}")
    private String tokenHeader;

    /** 免登录路径，来自 application.yml 的 travel.security.exclude-paths */
    @Value("${travel.security.exclude-paths:}")
    private List<String> excludePaths;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 静态资源、404 之类的请求不走鉴权
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        // 跨域预检不带自定义头，必须放行，否则前端第一次请求就会被拦
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }

        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }

        String uri = request.getRequestURI();
        if (requireRole == null || excluded(uri)) {
            // 没声明角色或命中白名单：带了 token 就顺手填上下文（/system/me 要用），没带也放行
            fillContextIfPresent(request);
            return true;
        }

        String token = request.getHeader(tokenHeader);
        Long userId = tokenService.parseUserId(token);
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_LOGIN);
        }

        String[] roles = requireRole.value();
        if (roles.length > 0 && !matches(roles, user.getRole())) {
            throw new BusinessException(ResultCode.NO_PERMISSION,
                    "该接口需要 " + String.join(" / ", roles) + " 角色，当前为 " + user.getRole());
        }

        UserContext.set(user);
        return true;
    }

    /**
     * 请求结束必须清 ThreadLocal，否则线程池复用会把上一个用户的信息带到下一个请求。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    /**
     * 有 token 就填上下文，解析失败静默放过——真正的拦截由声明了角色的接口触发。
     */
    private void fillContextIfPresent(HttpServletRequest request) {
        String token = request.getHeader(tokenHeader);
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            SysUser user = sysUserMapper.selectById(tokenService.parseUserId(token));
            if (user != null) {
                UserContext.set(user);
            }
        } catch (BusinessException e) {
            log.debug("请求带了无效 token，按未登录处理：{}", e.getMessage());
        }
    }

    private boolean excluded(String uri) {
        if (excludePaths == null || uri == null) {
            return false;
        }
        for (String path : excludePaths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            if (uri.equals(path) || uri.startsWith(path + "/")) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(String[] allowed, String role) {
        if (role == null) {
            return false;
        }
        for (String item : allowed) {
            if (item != null && item.equals(role)) {
                return true;
            }
        }
        return false;
    }
}
