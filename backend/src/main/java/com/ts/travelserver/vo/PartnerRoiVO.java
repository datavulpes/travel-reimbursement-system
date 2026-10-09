package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 往来对象投入产出对照（F-03）。
 *
 * <p>累计投入来自差旅付款，累计产出来自业务结果折算。
 * 管理层看这张表判断哪些客户值得继续跑、哪些该降频。
 *
 * <p>totalInvest / totalOutput 是 partner 表上的冗余汇总字段，
 * 付款与回填时增量更新，月结校对（设计报告 5.6）。
 */
@Data
public class PartnerRoiVO {

    /** 统计月份，yyyy-MM；查累计值时不传月份 */
    private String month;

    /** 按对象汇总的行 */
    private List<PartnerRowVO> rows;

    /** 全部对象的投入合计 */
    private BigDecimal totalInvest;

    /** 全部对象的产出合计 */
    private BigDecimal totalOutput;

    /** 整体费效比 = 投入 ÷ 产出，越小越划算；产出为 0 时记 0 */
    private BigDecimal ratio;

    /**
     * 一行一个对象。
     */
    @Data
    public static class PartnerRowVO {

        private Long partnerId;

        private String partnerName;

        /** 对象类型：CUSTOMER / SUPPLIER */
        private String partnerType;

        /** 累计投入（差旅合计） */
        private BigDecimal totalInvest;

        /** 累计产出（签单验收折算） */
        private BigDecimal totalOutput;

        /** 费效比 = 投入 ÷ 产出，越小越划算；产出为 0 时记 0 */
        private BigDecimal ratio;

        /** 出差次数 */
        private Integer tripCnt;
    }
}
