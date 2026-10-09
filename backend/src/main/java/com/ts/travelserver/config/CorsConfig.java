package com.ts.travelserver.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置。
 *
 * <p>开发期前端跑在 5173、后端 8080，不开 CORS 联调不通。
 * 上线后前端与后端同源（nginx 反代 /api），这段配置留着也不影响。
 *
 * <p>TODO-部署前确认：allowedOriginPatterns 现在放开到本机 5173 与 80，
 * 正式环境请改成实际域名；带凭证的跨域不允许通配 "*"，别图省事改成 "*"。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(
                        "http://localhost:5173",
                        "http://127.0.0.1:5173",
                        "http://localhost:80",
                        "http://127.0.0.1:80")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
