package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 上传票据的返回（C-01）。
 *
 * <p>文件先落盘拿到 tempPath，OCR 结果只是「建议值」，
 * 员工在 C-02 确认时还能改，改完才真正写 invoice 表。
 */
@Data
public class InvoiceUploadVO {

    /** 影像相对路径，如 2026/09/invoice_1a2b3c4d.jpg，C-02 确认时回传 */
    private String tempPath;

    /**
     * 刚上传这张图的带签名临时链接，前端直接拿它做预览。
     *
     * <p>上传完立刻回一条，省得前端等 C-03 列表返回才知道图长什么样——
     * 联调 OCR 时最关键的一件事就是确认「识别的是不是我刚传的那张」。
     * 链接有效期同 travel.file.url-expire-minutes。
     */
    private String previewUrl;

    /** 缩略图地址，列表里展示用；本地模式下与 previewUrl 相同 */
    private String thumbUrl;

    /** 原始文件名，回显用 */
    private String fileName;

    /** 文件大小（字节） */
    private Long fileSize;

    /** OCR 识别结果 */
    private OcrResultVO ocr;

    /** 是否识别成功；false 时前端转手工录入 */
    private Boolean ocrSuccess;

    // ---------- 挂段建议 ----------
    // 上传时就顺手算出来，省得前端为了拿一条建议再发一次请求。
    // 这是唯一一处 AI 直接替人做选择的地方，所以置信度比建议本身更重要：
    // 高置信度前端自动选中，低置信度只提示、不改员工的选择。

    /** 建议挂接的行程段 id；行程段为空时为 null */
    private Long suggestItineraryId;

    /** 置信度 0~1，见 InvoiceMatchSuggester 的三档 */
    private BigDecimal suggestConfidence;

    /** 为什么推荐这一段，原样展示给员工 */
    private String suggestReason;
}
