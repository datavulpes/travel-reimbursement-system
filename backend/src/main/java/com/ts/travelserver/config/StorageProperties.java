package com.ts.travelserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 影像存储方式配置，对应 application.yml 的 travel.storage.*。
 *
 * <p>默认 {@code local}，与改造前的行为完全一致：没网、没 OSS 账号也能照常演示。
 * 换成 {@code oss} 需要先在 application.yml 里把 travel.storage.oss.* 填齐。
 *
 * <p>TODO-按需填写：bucket / access-key-id / access-key-secret / endpoint 四项，
 * 缺任一项时 OSS 实现启动即失败，不会静默退回本地（静默退回会让演示时以为传上了、其实没传）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.storage")
public class StorageProperties {

    /** local（默认）或 oss */
    private String type = "local";

    /** 删除票据时是否一并删掉影像。关掉会留下孤儿文件，仅排查问题时临时关 */
    private boolean deleteWithInvoice = true;

    private final Oss oss = new Oss();

    @Data
    public static class Oss {

        /** 地域节点，如 oss-cn-hangzhou.aliyuncs.com；带 https:// 前缀也认 */
        private String endpoint = "oss-cn-hangzhou.aliyuncs.com";

        /** 桶名 */
        private String bucket = "";

        private String accessKeyId = "";

        private String accessKeySecret = "";

        /**
         * 对象目录前缀，如 invoice/。只影响 OSS 上的路径，库里的 img_path 不含它，
         * 这样切回本地存储时路径与老数据一致。
         */
        private String dirPrefix = "invoice/";

        /**
         * 绑定的自定义域名（CDN / 自有域名）。留空就用桶默认域名 https://{bucket}.{endpoint}。
         * 注意：自定义域名必须已绑定到该桶，否则签名算得再对也是 403。
         */
        private String publicDomain = "";

        /** 列表页缩略图的图片处理参数；留空表示不处理，列表直接用原图 */
        private String thumbnailProcess = "image/resize,w_240";

        private int connectTimeoutMs = 3000;

        /** 上传一张票几百 KB，给 15 秒；读取给同样时长 */
        private int readTimeoutMs = 15000;
    }
}
