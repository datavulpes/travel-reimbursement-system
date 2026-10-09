package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 确认票面字段并挂接行程段（C-02）。
 *
 * <p>OCR 回显后员工核对（或直接手工录入）点确认，才真正写 invoice 表。
 * 写库前按 invoiceNo 查重：命中就返回 2001 并把原申请号给前端标红（FR-0203）。
 *
 * <p>itineraryId 必填，票据必须落到具体行程段（BR-07）。
 */
@Data
public class InvoiceMatchRequest {

    /** 挂接申请号 */
    @NotBlank(message = "申请号不能为空")
    private String applyNo;

    /** 发票号，全局唯一，查重依据 */
    @NotBlank(message = "发票号不能为空")
    @Size(max = 50, message = "发票号不超过 50 位")
    private String invoiceNo;

    /** 金额，大于 0 */
    @NotNull(message = "请填写票据金额")
    private BigDecimal amount;

    /** 开票日期，yyyy-MM-dd */
    @NotBlank(message = "请填写开票日期")
    private String invoiceDate;

    /** 销售方 */
    @NotBlank(message = "请填写销售方")
    @Size(max = 100, message = "销售方不超过 100 字")
    private String seller;

    /**
     * 影像相对路径，上传接口返回的那个值。
     * 为空表示员工还没传图片，Service 层要按「缺影像」拦一道。
     */
    private String imgPath;

    /** 挂接的行程段 id，必填 */
    @NotNull(message = "请选择要挂接的行程段")
    private Long itineraryId;

    /** 是否 OCR 代填的结果，手工录入传 0 */
    private Integer ocrFlag = 0;

    /**
     * OCR 当初识别出来的四项原文，只有 ocrFlag = 1 时才需要带。
     *
     * <p>带它是为了记人工修正：员工核对时把票号或金额改了，
     * 事后要能知道「系统当时给的是什么、人改成了什么」，
     * 这是判断 OCR 与提取规则好不好用的唯一依据。
     *
     * <p>为什么不在库里存一份：识别值本来就是中间态，
     * 落进 invoice 表等于给每行加四个几乎用不到的列，而它只在「被改了」的时候才有意义。
     */
    private OcrValues ocrValues;

    /** OCR 识别出的四项，全是字符串——它只是原文快照，不参与计算 */
    @Data
    public static class OcrValues {

        private String invoiceNo;

        private String amount;

        /** yyyy-MM-dd */
        private String invoiceDate;

        private String seller;
    }
}
