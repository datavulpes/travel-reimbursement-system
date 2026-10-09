package com.ts.travelserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 票据影像存储配置，对应 application.yml 的 travel.file.*。
 *
 * <p>设计报告 5.7：影像不入库，落本地磁盘 {root}/yyyy/MM/{uuid}.{ext}，
 * 库里只存相对路径，读取走签名临时链接（带过期时间）。
 *
 * <p>TODO-按需修改：root 默认 ${UPLOAD_DIR}，换机器或换盘要改 application.yml 的 travel.file.root。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.file")
public class FileStorageConfig {

    /** 影像存放根目录 */
    private String root = "${UPLOAD_DIR}";

    /** 允许的扩展名，逗号分隔 */
    private String allowedExtensions = "jpg,jpeg,png,webp,heic,pdf";

    /** 临时链接有效期（分钟） */
    private int urlExpireMinutes = 30;

    /** 签名密钥，与 application.yml 的 travel.file.sign-secret 一致 */
    private String signSecret = "change-me";

    /**
     * 扩展名白名单，统一转小写后返回。
     */
    public List<String> allowedExtensionList() {
        List<String> list = new java.util.ArrayList<>();
        for (String ext : allowedExtensions.split(",")) {
            String trim = ext.trim().toLowerCase();
            if (!trim.isEmpty()) {
                list.add(trim);
            }
        }
        return list;
    }
}
