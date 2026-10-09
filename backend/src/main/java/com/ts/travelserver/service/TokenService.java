package com.ts.travelserver.service;

import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.ResultCode;
import com.ts.travelserver.entity.SysUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;

/**
 * 登录凭证（token）的签发与校验。
 *
 * <p>设计报告 6.8：账号密码校验通过后签发 token，有效期 12 小时，拦截器按角色控制接口访问。
 * 这里不引 Spring Security，也不引 JWT 依赖——自己拼一串「载荷 Base64 + HMAC 签名」就够了，
 * 属于「三个主动简化」里不引安全框架那一档。
 *
 * <p>token 结构：{@code base64url(userId.expireAtMillis)} + "." + HMAC-SHA256 十六进制签名。
 * 密钥来自 application.yml 的 travel.security.token-secret。
 */
@Slf4j
@Service
public class TokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Value("${travel.security.token-secret}")
    private String tokenSecret;

    @Value("${travel.security.token-expire-hours:12}")
    private long expireHours;

    /**
     * 签发 token。
     */
    public String create(SysUser user) {
        long expireAt = System.currentTimeMillis() + expireHours * 3600 * 1000L;
        String payload = user.getId() + "." + expireAt;
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + sign(body);
    }

    /**
     * 校验 token 并解析出用户 id。串被改过、过期、密钥不对都会抛未登录。
     */
    public Long parseUserId(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ResultCode.NOT_LOGIN);
        }
        // 兼容 RFC 6750 的 "Bearer <token>" 写法：前端 request.js 与 Apifox 的 Auth 面板都按这个格式发，
        // 不剥前缀的话 split 后签名必然对不上，全部请求都会 4001
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw new BusinessException(ResultCode.NOT_LOGIN);
        }
        if (!sign(parts[0]).equals(parts[1])) {
            throw new BusinessException(ResultCode.NOT_LOGIN, "登录已失效，请重新登录");
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.NOT_LOGIN);
        }
        String[] kv = payload.split("\\.");
        long expireAt;
        try {
            expireAt = Long.parseLong(kv[1]);
        } catch (RuntimeException e) {
            throw new BusinessException(ResultCode.NOT_LOGIN);
        }
        if (System.currentTimeMillis() > expireAt) {
            throw new BusinessException(ResultCode.NOT_LOGIN, "登录已过期，请重新登录");
        }
        return Long.parseLong(kv[0]);
    }

    /**
     * 过期时间点，返回给前端展示用。
     */
    public LocalDateTime expireAt() {
        return LocalDateTime.now(ZoneId.systemDefault()).plusHours(expireHours);
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] bytes = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("token 签名失败", e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR);
        }
    }

    /**
     * 影像临时链接签名，用的是另一把密钥（travel.file.sign-secret），
     * 与登录 token 分开，避免影像链接泄漏牵连登录态。
     */
    public String signPath(String path, long expireAtMillis, String secret) {
        String body = path + "@" + expireAtMillis;
        String saved = this.tokenSecret;
        try {
            this.tokenSecret = secret;
            return sign(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(body.getBytes(StandardCharsets.UTF_8)));
        } finally {
            this.tokenSecret = saved;
        }
    }
}
