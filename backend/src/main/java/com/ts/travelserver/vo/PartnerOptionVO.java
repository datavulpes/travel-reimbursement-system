package com.ts.travelserver.vo;

import lombok.Data;

/**
 * 往来对象下拉项。
 *
 * <p>只带填单时真正要用到的三样：id、名称、类型。刻意不带 total_invest / total_output——
 * 那两列是 {@code AnalyticsService} 直接读的冗余统计，员工选对象时用不上，
 * 暴露出来反而让人以为页面上这个数字是他此刻该关心的。
 *
 * <p>与 {@link PartnerVO} 分开是故意的：后者是系统管理员的档案对象，带投入产出与可否删除，
 * 两个用途混用一张 VO，改一处就会连累另一处页面。
 */
@Data
public class PartnerOptionVO {

    private Long id;

    private String partnerName;

    /** CUSTOMER 客户 / SUPPLIER 供应商 */
    private String partnerType;
}
