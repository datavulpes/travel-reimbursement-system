package com.ts.travelserver.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 同类花费参考的查询条件（A-06 实时聚合）。
 *
 * <p>除了事由之外，员工在表单上填的每一项都可以作为比对维度：目的地、出差类型、
 * 城市等级、交通方式。四个都允许为空——填一项就实时重算一次，不需要填齐。
 *
 * <p>本次预估（estTraffic / estHotel）与本次天数只用于回显和算偏差，
 * 不参与筛选：它们是「被比较的对象」，不是维度。
 */
@Data
public class CostRefQuery {

    private String toCity;

    private String tripType;

    /** 首段行程的城市等级 */
    private String cityLevel;

    /** 首段行程的交通方式 */
    private String trafficType;

    private BigDecimal estTraffic;

    private BigDecimal estHotel;

    /** 本次整趟天数，由前端按行程段算出 */
    private Integer estDays;

    /** 一个维度都没填——前端这时连请求都不该发，直接显示空表格 */
    public boolean empty() {
        return blank(toCity) && blank(tripType) && blank(cityLevel) && blank(trafficType);
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
