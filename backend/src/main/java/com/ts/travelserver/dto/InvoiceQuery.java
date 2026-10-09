package com.ts.travelserver.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 票据影像查询条件（I-05）。
 *
 * <p>「查票据图片」这件事之前全系统没有入口：员工只在自己的上传页见过图，
 * 财务复审页只有个「已上传」标签，审批端连接口都调不通。这个查询把影像按条件捞出来，
 * 列表带缩略图、点开看原图，审票的人不用再靠想象。
 *
 * <p>四个角色里只有员工、审批人、财务能用，管理层不参与——
 * 管理层看的是投入产出，不看票面。
 */
@Data
public class InvoiceQuery {

    /** 页码，从 1 开始 */
    private Long pageNum = 1L;

    /** 每页条数，上限 100 */
    private Long pageSize = 10L;

    /** 按申请号精确查；传了别的条件就都在这个单里筛 */
    private String applyNo;

    /** 关键字：票号或销售方模糊匹配 */
    private String keyword;

    /** 开票日期起，yyyy-MM-dd */
    private String dateFrom;

    /** 开票日期止，yyyy-MM-dd */
    private String dateTo;

    private BigDecimal minAmount;

    private BigDecimal maxAmount;

    /**
     * 只看有影像的票。查「哪些票还没传图」时传 false，
     * 它是缺票核对最常用的一条。
     */
    private Boolean hasImage;

    public long safePageNum() {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    public long safePageSize() {
        if (pageSize == null || pageSize < 1) {
            return 10L;
        }
        return Math.min(pageSize, 100L);
    }
}
