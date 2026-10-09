package com.ts.travelserver.vo;

import lombok.Data;

/**
 * 缺票推算结果（C-03）。
 *
 * <p>按行程段推算还缺哪几张票（设计报告 6.4.4）：
 * 行程跨天提示应有住宿票；高铁/飞机提示应有对应交通票；已挂接的票据满足后提示补齐。
 *
 * <p>只提示不阻拦，员工可以带说明强制提交（BR-05）。
 */
@Data
public class MissingTicketVO {

    /** 缺票的行程段 id */
    private Long itineraryId;

    /** 段序号 */
    private Integer seq;

    /** 行程描述，如「惠州 → 深圳」 */
    private String route;

    /** 缺的票据类型：住宿发票、交通票据 */
    private String ticketType;

    /** 提示语，直接展示给员工 */
    private String hint;
}
