package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 行程段（trip_itinerary）：一趟出差切成多段。
 *
 * <p>数据字典 8.5。（applyNo, seq）联合唯一。
 * 票据必须落到具体行程段（BR-07），缺票推算也是按段推的（设计报告 6.4.4）。
 */
@Data
@TableName("trip_itinerary")
public class TripItinerary {

    // ---------- 交通方式（数据字典 8.5） ----------
    public static final String TRAFFIC_HIGH_SPEED = "高铁";
    public static final String TRAFFIC_FLIGHT = "飞机";
    public static final String TRAFFIC_SELF_DRIVE = "自驾";
    public static final String TRAFFIC_COACH = "长途汽车";
    public static final String TRAFFIC_SHIP = "轮船";
    /*
     * 原先这里还有一个 TRAFFIC_HOTEL = "住宿"，注释写着「差标表里住宿单独占一行」。
     * 实际上差标校验是逐段比对 (city_level, traffic_type) 的上限，
     * 住宿费单独落在 trip_apply.est_hotel，从来不走这条路，库里也没有这一档标准。
     * 这个常量于是成了「下拉里能选、校验时查不到」的取值——选了它那段会被静默跳过。
     * 常量删掉，免得以后有人照着它把这个取值加回前端下拉。
     */

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属申请号 */
    private String applyNo;

    /** 段序号，从 1 开始 */
    private Integer seq;

    /** 开始日期 */
    private LocalDate startDate;

    /** 结束日期，不早于开始日期；跨天视为含住宿 */
    private LocalDate endDate;

    /** 出发地 */
    private String fromCity;

    /** 目的地，查同类花费参考与城市差标都按这个字段 */
    private String toCity;

    /** 交通方式，取值见本类 TRAFFIC_* 常量 */
    private String trafficType;

    /**
     * 目的地城市等级。冗余字段：差标按段取档，
     * 冗余下来就不用每次关联差标配置表（设计报告 5.6）。
     */
    private String cityLevel;
}
