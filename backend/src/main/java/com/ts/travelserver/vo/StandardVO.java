package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 差标（H-01、H-02）。
 *
 * <p>（城市等级，交通方式）一组只返回当前生效的那一档。
 * 历史版本不删，H-04 的留痕查询能把它翻出来（BR-09）。
 */
@Data
public class StandardVO {

    private Long id;

    /** 城市等级：一级、二级、三级、四级、五级 */
    private String cityLevel;

    /** 交通方式：高铁、飞机、自驾、长途汽车、轮船、住宿 */
    private String trafficType;

    /** 上限金额 */
    private BigDecimal maxAmount;

    /** 生效日期 */
    private LocalDate effectiveAt;

    /** 最近调整人姓名 */
    private String updatedBy;

    /** 上一版上限金额，调整留痕用；没有历史版本为 null */
    private BigDecimal prevMaxAmount;
}
