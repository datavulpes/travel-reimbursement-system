package com.ts.travelserver.service.support;

import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.ResultCode;
import com.ts.travelserver.config.FileStorageConfig;
import com.ts.travelserver.service.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * 本地磁盘存储（默认实现，{@code travel.storage.type=local}）。
 *
 * <p>逻辑是从 InvoiceService 原样搬过来的：落 {root}/yyyy/MM/{uuid}.{ext}，
 * 读取走后端签名链接 /api/invoice/file，路径里的 ../ 必须挡住 ——
 * 不挡的话顺着参数能读到磁盘上任意文件。
 *
 * <p>抽出来不是为了改行为，是为了让 OSS 实现有个一模一样的对照物：
 * 换 type 时只有「文件放哪」变了，签名、过期、取图方式对外都不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "travel.storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

    private final FileStorageConfig fileStorageConfig;
    private final TokenService tokenService;

    @Override
    public void save(String key, byte[] bytes, String contentType) {
        try {
            File target = resolve(key);
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), bytes);
        } catch (Exception e) {
            log.error("票据影像落盘失败：{}", key, e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "影像保存失败，请重试");
        }
    }

    @Override
    public byte[] load(String key) {
        try {
            File file = resolve(key);
            if (!file.exists()) {
                return null;
            }
            return Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            log.error("读取票据影像失败：{}", key, e);
            return null;
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key).toPath());
        } catch (Exception e) {
            // 清理失败不能让删票据这个主动作失败，记日志就够
            log.warn("删除票据影像失败：{}，{}", key, e.getMessage());
        }
    }

    @Override
    public boolean exists(String key) {
        return resolve(key).exists();
    }

    @Override
    public String signedUrl(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        long expireAt = System.currentTimeMillis() + fileStorageConfig.getUrlExpireMinutes() * 60 * 1000L;
        String sign = tokenService.signPath(key, expireAt, fileStorageConfig.getSignSecret());
        return "/api/invoice/file?path=" + key + "&exp=" + expireAt + "&sign=" + sign;
    }

    @Override
    public String thumbUrl(String key) {
        // 本地没有图片处理，列表页直接用原图，前端按 CSS 限尺寸
        return signedUrl(key);
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
        // jpg / jpeg 以及认不出来的都按 jpeg 处理，浏览器能兜住
        return "image/jpeg";
    }

    @Override
    public String name() {
        return "local";
    }

    /**
     * key → 磁盘文件。挡住 ../ 之后再拼，别拿参数直接拼路径。
     */
    private File resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "影像路径为空");
        }
        String normalized = Paths.get(key).normalize().toString().replace("\\", "/");
        if (normalized.startsWith("..") || normalized.contains("../")) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "影像路径不合法");
        }
        Path root = Paths.get(fileStorageConfig.getRoot());
        return root.resolve(normalized).toFile();
    }
}
