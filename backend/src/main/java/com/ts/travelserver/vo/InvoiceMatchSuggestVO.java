package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 挂段建议（C-05 单独查询时的返回体）。
 *
 * <p>C-01 上传时那三个字段是直接塞在 {@link InvoiceUploadVO} 里的，
 * 这里单独抽一个 VO 是为了给「没重新传文件、只是改了开票日期」的场景用：
 * OCR 出来的日期不准，人在表单里随手改一下就要重算建议，
 * 不可能为了问一句「挂哪段」把图片再传一遍。
 */
@Data
public class InvoiceMatchSuggestVO {

    /** 建议挂接的行程段 id；行程段为空时为 null */
    private Long itineraryId;

    /** 置信度 0~1。低于 0.7 时前端只提示不自动选中 */
    private BigDecimal confidence;

    /** 说给人听的理由，可以直接展示 */
    private String reason;

    public InvoiceMatchSuggestVO() {
    }

    public InvoiceMatchSuggestVO(Long itineraryId, BigDecimal confidence, String reason) {
        this.itineraryId = itineraryId;
        this.confidence = confidence;
        this.reason = reason;
    }
}
