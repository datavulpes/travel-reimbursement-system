package com.ts.travelserver.service.support;

/**
 * 票据影像存储。
 *
 * <p>只有两种实现：本地磁盘（{@code travel.storage.type=local}，默认）与阿里云 OSS
 * （{@code travel.storage.type=oss}）。两套实现对外行为必须一致，换着配不出错。
 *
 * <h3>key 的口径（别改）</h3>
 * <p>key 就是 {@code invoice.img_path} 里存的那一串相对路径，形如
 * {@code 2026/09/invoice_ab12cd34.jpg}。OSS 模式下它等于 objectKey 去掉目录前缀的那一截，
 * 目录前缀（{@code travel.storage.oss.dir-prefix}）由 OSS 实现自己拼，
 * 这样切回本地时路径与老数据完全一致 —— 库里一行都不用改。
 *
 * <h3>为什么两个实现都返回「带过期的」地址</h3>
 * <p>票据影像不是公开资源。本地模式走后端签名链接（I-04），OSS 模式走私有桶预签名 URL，
 * 两种都在 {@code travel.file.url-expire-minutes} 分钟后失效，前端过期了重新取即可。
 */
public interface FileStorage {

    /**
     * 写入影像。key 相同则覆盖。
     *
     * @param key         相对路径，如 2026/09/invoice_ab12.jpg
     * @param bytes       文件内容
     * @param contentType 上传时的 MIME，OSS 存对象时要带；为空时按扩展名推断
     */
    void save(String key, byte[] bytes, String contentType);

    /**
     * 读回影像字节。不存在返回 null，不抛异常 —— 调用方按 null 处理成「影像缺失」。
     */
    byte[] load(String key);

    /**
     * 删除影像。不存在也不报错（票据删除是主流程，不能让清理动作把它打断）。
     */
    void delete(String key);

    boolean exists(String key);

    /**
     * 原图访问地址，带过期时间。key 为空返回 null。
     */
    String signedUrl(String key);

    /**
     * 缩略图访问地址，给列表页用。
     * 本地模式没有图片处理，直接返回原图地址，前端按 CSS 限尺寸即可。
     */
    String thumbUrl(String key);

    /**
     * 按扩展名推断 MIME。OSS 存对象与后端代理输出都要用，两边口径必须一致。
     */
    String contentType(String key);

    /**
     * 当前实现名，给启动日志与排查用：local / oss。
     */
    String name();
}
