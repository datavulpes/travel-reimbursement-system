package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 付款结果（E-04）。
 *
 * <p>字段与 Apifox 文档里的 PaymentVO 一致：
 * id / reportNo / payAmount / loanId / payAt / operator。
 *
 * <p>为什么要返回对象而不是 R&lt;Void&gt;：付完款总得给个凭证，
 * 前端 ReviewDetail.vue 也会读 id 提示「付款完成，流水号 xx」。
 * 之前返回 null，前端读 res.id 直接抛 TypeError。
 */
@Data
public class PaymentVO {

    /** 付款流水号，即 payment_record 主键 */
    private Long id;

    /** 报销单号 */
    private String reportNo;

    /** 实付金额 */
    private BigDecimal payAmount;

    /** 本次冲抵的预借款 id */
    private Long loanId;

    /** 付款时间，yyyy-MM-dd HH:mm:ss */
    private String payAt;

    /** 操作人姓名 */
    private String operator;
}
