package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 票据（invoice）。
 *
 * <p>数据字典 8.10。invoiceNo 全局唯一，是查重拦截的根基（FR-0203）：
 * 入库前先按票号查，命中就拦下并把原申请号写进 dupOf 供界面标红。
 *
 * <p>影像不入库，只存相对路径 imgPath，读的时候走带签名的临时链接（设计报告 5.7）。
 */
@Data
@TableName("invoice")
public class Invoice {

    /** 手工录入 */
    public static final int OCR_FLAG_MANUAL = 0;
    /** OCR 识别代填 */
    public static final int OCR_FLAG_OCR = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发票号，全局唯一 */
    private String invoiceNo;

    /** 挂接申请号 */
    private String applyNo;

    /** 挂接行程段，必须落到具体段（BR-07） */
    private Long itineraryId;

    /** 金额，大于 0 */
    private BigDecimal amount;

    /** 开票日期 */
    private LocalDate invoiceDate;

    /** 销售方 */
    private String seller;

    /** 影像相对路径，如 2026/09/invoice_12345678.jpg */
    private String imgPath;

    /** 是否 OCR 代填，取值见本类 OCR_FLAG_* 常量 */
    private Integer ocrFlag = OCR_FLAG_MANUAL;

    /** 命中查重时指向的原申请号；没命中为 null */
    private String dupOf;
}
