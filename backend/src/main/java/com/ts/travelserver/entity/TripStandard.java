package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 差标（trip_standard）：城市等级 + 交通方式的上限金额。
 *
 * <p>数据字典 8.6。（cityLevel, trafficType, effectiveAt）联合唯一。
 *
 * <p>调整差标不 UPDATE 旧行，而是插一条新的生效日期行，
 * 这样任何时点的标准都能追溯（BR-09，设计报告 5.9）。
 */
@Data
@TableName("trip_standard")
public class TripStandard {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 城市等级：一线、新一线、二级、三级、其他 */
    private String cityLevel;

    /** 交通方式，取值见 {@link TripItinerary} 的 TRAFFIC_* 常量；住宿单独一行 */
    private String trafficType;

    /** 上限金额，大于 0 */
    private BigDecimal maxAmount;

    /** 生效日期 */
    private LocalDate effectiveAt;

    /** 最近调整人，指向 sys_user.id */
    private Long updatedBy;
}
