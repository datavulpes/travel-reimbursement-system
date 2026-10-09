package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * OCR 识别结果（C-01 返回，C-02 用它来回填表单）。
 *
 * <p>设计报告 7.6：识别票号、金额、日期、销售方四项回填表单，员工核对后确认。
 * 识别失败时 ocrSuccess 为 false，前端直接转手工录入，不阻塞主流程。
 *
 * <p>TODO-外部依赖：travel.ocr.enabled=false 时这里恒为失败，
 * 需要演示自动识别效果就去百度智能云申请「增值税发票识别」免费额度，
 * 把密钥三件套填进 application.yml 的 travel.ocr.*，OcrService 里换成真实调用。
 */
@Data
public class OcrResultVO {

    /** 发票号 */
    private String invoiceNo;

    /** 金额 */
    private BigDecimal amount;

    /** 开票日期，yyyy-MM-dd */
    private String invoiceDate;

    /** 销售方 */
    private String seller;

    /** 是否识别成功 */
    private Boolean success;

    /** 失败原因，成功时为 null */
    private String message;
}
