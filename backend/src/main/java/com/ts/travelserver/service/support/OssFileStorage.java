package com.ts.travelserver.service.support;

import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.ResultCode;
import com.ts.travelserver.config.FileStorageConfig;
import com.ts.travelserver.config.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * 阿里云 OSS 存储（{@code travel.storage.type=oss}）。
 *
 * <h3>为什么不引官方 SDK</h3>
 * <p>本项目是 Spring Boot 4 + Jackson 3（{@code tools.jackson}）环境，官方 SDK 会拖进来
 * httpclient / jaxb / jackson-databind 2.x 一串传递依赖，跟现有环境打架的概率远高于收益。
 * 而 OSS 真正用到的只有四个动作：PUT 上传、GET 读取、DELETE 删除、GET 预签名，
 * 全是标准 HTTP + HMAC-SHA1 签名，跟 OcrService 里手写百度接口是同一套写法，
 * 也就两百行，可控还不用管依赖。
 *
 * <h3>签名口径（V1，别改成 V4）</h3>
 * <pre>
 * StringToSign = METHOD \n Content-MD5 \n Content-Type \n Date \n CanonicalizedResource
 * Authorization: OSS {akId}:{base64(HmacSHA1(akSecret, StringToSign))}
 *
 * 预签名（GET）：把 Date 换成过期时间戳，签名放 query 的 Signature
 * </pre>
 * <p>没有用到任何 x-oss-* 头，CanonicalizedOSSHeaders 恒为空串，因此不用拼那一段。
 * 子资源只有图片处理参数 x-oss-process 一个，它必须参与签名，否则缩略图 403。
 *
 * <h3>桶是私有的</h3>
 * <p>票据影像不公开。对外只发带 Expires 的预签名 URL，过期时间与本地模式一致
 * （{@code travel.file.url-expire-minutes}），过期后前端重新取链即可。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "travel.storage", name = "type", havingValue = "oss")
public class OssFileStorage implements FileStorage, InitializingBean {

    private static final DateTimeFormatter GMT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    private final StorageProperties storageProperties;
    private final FileStorageConfig fileStorageConfig;

    private HttpClient client;

    public OssFileStorage(StorageProperties storageProperties, FileStorageConfig fileStorageConfig) {
        this.storageProperties = storageProperties;
        this.fileStorageConfig = fileStorageConfig;
    }

    /**
     * 配置缺项要当场炸，不能等上传时才报 —— 静默退回本地会让人以为传上了、其实没传。
     */
    @Override
    public void afterPropertiesSet() {
        StorageProperties.Oss oss = storageProperties.getOss();
        if (oss.getBucket().isBlank() || oss.getAccessKeyId().isBlank()
                || oss.getAccessKeySecret().isBlank() || oss.getEndpoint().isBlank()) {
            throw new IllegalStateException(
                    "travel.storage.type=oss，但 travel.storage.oss 的 endpoint / bucket / access-key-id / access-key-secret 没有填齐");
        }
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(oss.getConnectTimeoutMs()))
                .build();
        log.info("票据影像存储：阿里云 OSS（bucket={} endpoint={} 目录前缀={}）",
                oss.getBucket(), oss.getEndpoint(), blankToEmpty(oss.getDirPrefix()));
    }

    @Override
    public void save(String key, byte[] bytes, String contentType) {
        String objectKey = objectKey(key);
        String type = contentType == null || contentType.isBlank() ? contentType(key) : contentType;
        String date = gmtNow();
        String sign = sign("PUT", "/" + bucket() + "/" + objectKey, type, "", date);
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(urlOf(objectKey)))
                            .timeout(Duration.ofMillis(timeoutRead()))
                            .header("Date", date)
                            .header("Content-Type", type)
                            .header("Authorization", "OSS " + akId() + ":" + sign)
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.error("OSS 上传失败 HTTP {}：{}", response.statusCode(), response.body());
                throw new BusinessException(ResultCode.SYSTEM_ERROR, "影像上传失败，请重试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("OSS 上传异常：{}", objectKey, e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "影像服务不可用，请稍后重试");
        }
    }

    @Override
    public byte[] load(String key) {
        String objectKey = objectKey(key);
        String date = gmtNow();
        String sign = sign("GET", "/" + bucket() + "/" + objectKey, "", "", date);
        try {
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(urlOf(objectKey)))
                            .timeout(Duration.ofMillis(timeoutRead()))
                            .header("Date", date)
                            .header("Authorization", "OSS " + akId() + ":" + sign)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() / 100 != 2) {
                log.error("OSS 读取失败 HTTP {}：{}", response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.error("OSS 读取异常：{}", objectKey, e);
            return null;
        }
    }

    @Override
    public void delete(String key) {
        String objectKey = objectKey(key);
        String date = gmtNow();
        String sign = sign("DELETE", "/" + bucket() + "/" + objectKey, "", "", date);
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(urlOf(objectKey)))
                            .timeout(Duration.ofMillis(timeoutRead()))
                            .header("Date", date)
                            .header("Authorization", "OSS " + akId() + ":" + sign)
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2 && response.statusCode() != 404) {
                log.warn("OSS 删除失败 HTTP {}：{}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("OSS 删除异常：{}，{}", objectKey, e.getMessage());
        }
    }

    @Override
    public boolean exists(String key) {
        String objectKey = objectKey(key);
        String date = gmtNow();
        String sign = sign("HEAD", "/" + bucket() + "/" + objectKey, "", "", date);
        try {
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(urlOf(objectKey)))
                            .timeout(Duration.ofMillis(timeoutRead()))
                            .header("Date", date)
                            .header("Authorization", "OSS " + akId() + ":" + sign)
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.warn("OSS 存在性检查异常：{}，{}", objectKey, e.getMessage());
            return false;
        }
    }

    @Override
    public String signedUrl(String key) {
        return presigned(key, null);
    }

    @Override
    public String thumbUrl(String key) {
        String process = storageProperties.getOss().getThumbnailProcess();
        if (process == null || process.isBlank()) {
            return signedUrl(key);
        }
        return presigned(key, "x-oss-process=" + process.trim());
    }

    @Override
    public String contentType(String key) {
        String lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".heic")) {
            return "image/heic";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        return "image/jpeg";
    }

    @Override
    public String name() {
        return "oss";
    }

    // ---------- 签名 ----------

    /**
     * 预签名 URL。subResource 为 null 表示不带子资源（原图），
     * 缩略图走 {@code x-oss-process=...}，它必须参与签名，否则 403。
     */
    private String presigned(String key, String subResource) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String objectKey = objectKey(key);
        long expires = (System.currentTimeMillis()
                + fileStorageConfig.getUrlExpireMinutes() * 60 * 1000L) / 1000L;
        String canonical = "/" + bucket() + "/" + objectKey
                + (subResource == null ? "" : "?" + subResource);
        String signature = sign("GET", canonical, "", "", String.valueOf(expires));
        StringBuilder url = new StringBuilder(urlOf(objectKey))
                .append("?OSSAccessKeyId=").append(akId())
                .append("&Expires=").append(expires)
                .append("&Signature=").append(URLEncoder.encode(signature, StandardCharsets.UTF_8));
        if (subResource != null) {
            url.append('&').append(subResource);
        }
        return url.toString();
    }

    /**
     * HMAC-SHA1 签名。dateOrExpires 在 header 签名里传 GMT 时间，在预签名里传过期秒数。
     */
    private String sign(String method, String canonicalResource, String contentType,
                        String contentMd5, String dateOrExpires) {
        String toSign = method + "\n"
                + nullToEmpty(contentMd5) + "\n"
                + nullToEmpty(contentType) + "\n"
                + nullToEmpty(dateOrExpires) + "\n"
                + canonicalResource;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(akSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("OSS 签名失败（HmacSHA1 不可用）", e);
        }
    }

    // ---------- 路径与连接 ----------

    /** 目录前缀只加在 OSS 上，库里的 img_path 保持不带前缀，切回本地才对得上 */
    private String objectKey(String key) {
        String prefix = blankToEmpty(storageProperties.getOss().getDirPrefix());
        if (prefix.isEmpty()) {
            return key;
        }
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return key.startsWith(prefix) ? key : prefix + key;
    }

    /**
     * 对象访问地址。key 里的斜杠要保留，其余字符按段编码（中文文件名会用到）。
     */
    private String urlOf(String objectKey) {
        StringBuilder sb = new StringBuilder(baseUrl());
        for (String seg : objectKey.split("/")) {
            sb.append('/').append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    /** 桶访问根地址：填了自定义域名就用它，否则 https://{bucket}.{endpoint} */
    private String baseUrl() {
        StorageProperties.Oss oss = storageProperties.getOss();
        String domain = blankToEmpty(oss.getPublicDomain());
        if (!domain.isEmpty()) {
            return domain.startsWith("http") ? domain : "https://" + domain;
        }
        String endpoint = oss.getEndpoint().trim();
        endpoint = endpoint.replace("https://", "").replace("http://", "");
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        return "https://" + oss.getBucket().trim() + "." + endpoint;
    }

    private String bucket() {
        return storageProperties.getOss().getBucket().trim();
    }

    private String akId() {
        return storageProperties.getOss().getAccessKeyId().trim();
    }

    private String akSecret() {
        return storageProperties.getOss().getAccessKeySecret().trim();
    }

    private int timeoutRead() {
        return storageProperties.getOss().getReadTimeoutMs();
    }

    private static String gmtNow() {
        return GMT.format(ZonedDateTime.now(ZoneOffset.UTC));
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
