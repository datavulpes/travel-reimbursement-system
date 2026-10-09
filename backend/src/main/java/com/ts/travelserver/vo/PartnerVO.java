package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 往来对象（partner）的列表项，系统管理员的「基础数据 / 往来对象」页用。
 *
 * <p>applyCount 是本对象被出差申请单引用的次数，唯一用途是判断能不能删：
 * 接待用过就不能删除，不然那些申请单会挂着一个不存在的对象，月报 join 过来直接丢行。
 *
 * <p>totalInvest / totalOutput 只读展示，页面不给任何编辑入口：
 * 这两个值只能由付款和结果回填去改，手一改就等于改报表，而且没人会发现。
 */
@Data
public class PartnerVO {

    private Long id;

    /** 对象名称 */
    private String partnerName;

    /** 类型，取值 CUSTOMER / SUPPLIER */
    private String partnerType;

    /** 累计投入（差旅合计） */
    private BigDecimal totalInvest;

    /** 累计产出（签单验收折算） */
    private BigDecimal totalOutput;

    /** 关联了多少张出差申请单，大于 0 不允许删除 */
    private Long applyCount;
}
