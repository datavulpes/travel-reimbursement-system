package com.ts.travelserver.config;

import com.ts.travelserver.interceptor.RoleInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 注册鉴权拦截器。
 *
 * <p>拦截器对所有请求生效，白名单来自 application.yml 的 travel.security.exclude-paths。
 * 登录接口本身当然不能要求登录，CORS 预检（OPTIONS）在拦截器里单独放行。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private static final List<String> DEFAULT_EXCLUDES = List.of("/api/system/login", "/api/error");

    private final RoleInterceptor roleInterceptor;

    @Value("${travel.security.exclude-paths:}")
    private List<String> excludePaths;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        List<String> paths = excludePaths == null
                ? List.of()
                : excludePaths.stream().filter(p -> p != null && !p.isBlank()).toList();
        registry.addInterceptor(roleInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(paths.isEmpty() ? DEFAULT_EXCLUDES : paths);
    }
}
