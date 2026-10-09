package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 票据（C-03 本单票据清单）。
 *
 * <p>字段与前端 mock/invoiceListData.invoices 对应。
 * dupOf 非空说明这张票命中了查重，前端要标红提示（FR-0203）。
 */
@Data
public class InvoiceVO {

    private Long id;

    /** 发票号 */
    private String invoiceNo;

    /** 挂接申请号 */
    private String applyNo;

    /** 挂接行程段 */
    private Long itineraryId;

    /** 金额 */
    private BigDecimal amount;

    /** 开票日期，yyyy-MM-dd */
    private String invoiceDate;

    /** 销售方 */
    private String seller;

    /** 影像相对路径，如 2026/09/invoice_12345678.jpg */
    private String imgPath;

    /**
     * 影像访问地址（带签名、有过期时间）。
     * 前端拿这个去展示图片，没有权限的人拿不到链接（NFR-04）。
     */
    private String imgUrl;

    /**
     * 缩略图地址，给列表和票据墙用。
     *
     * <p>OSS 模式下走图片处理参数压到 240 宽，列表页不必要拉原图；
     * 本地模式没有图片处理，与 imgUrl 同一条，前端按 CSS 限尺寸。
     */
    private String thumbUrl;

    /** 是否 OCR 代填：0 手工、1 OCR */
    private Integer ocrFlag;

    /** 命中查重时的原申请号；正常为 null */
    private String dupOf;

    /**
     * 申请人姓名。只有票据查询（I-05）会填——
     * 财务在票据墙里看到一张票，得知道是谁的票才联系得上人。
     */
    private String applicantName;

    /**
     * 命中查重没有：给前端判断要不要标红。
     */
    public boolean duplicated() {
        return dupOf != null && !dupOf.isBlank();
    }
}
