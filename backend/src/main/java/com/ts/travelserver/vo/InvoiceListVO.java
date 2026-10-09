package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 本单票据清单 + 缺票推算（C-03）。
 *
 * <p>缺票推算是按行程段推的（设计报告 6.4.4）：跨天行程应有住宿票、
 * 高铁/飞机应有对应交通票。只提示不阻拦，员工可以带说明强制提交（BR-05）。
 */
@Data
public class InvoiceListVO {

    private String applyNo;

    /** 已挂接的票据 */
    private List<InvoiceVO> invoices;

    /** 票据金额合计 */
    private BigDecimal totalAmount;

    /** 缺票推算结果 */
    private List<MissingTicketVO> missing;

    /** 有没有缺票，前端决定要不要显示「强制提交说明」输入框 */
    private Boolean hasMissing;

    /** 行程段，前端挂票时要选段 */
    private List<ApplyDetailVO.ItineraryVO> itineraries;
}
