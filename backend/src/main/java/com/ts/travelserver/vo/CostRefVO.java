package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 同类花费参考（A-06）。
 *
 * <p>同目的地、同出差类型近 12 个月的花费均值与区间，给员工填预估金额时一个参照。
 * 样本量不足 5 条时 lowSample 为 true（设计报告 6.3.4）。
 *
 * <h3>为什么交通和住宿分开给</h3>
 * 只给一个「同类均值 ¥1,798」时，员工分不清是自己交通填高了还是酒店订贵了，
 * 也看不出这个数到底是「一趟」还是「一天」。所以三项各自独立统计，
 * 并给出平均天数——少了天数，跨天数的金额比较没有意义。
 *
 * <h3>合计区间不是两项区间相加</h3>
 * P25 / P75 是分位数，分位数不可加：交通 P25 加住宿 P25 不等于合计 P25。
 * 合计区间是拿整单金额单独排出来的。页面上三个区间并排放着，
 * 员工会下意识拿两个分项去加总对账，对不上就以为算错了——前端必须写明这一点。
 *
 * <h3>住宿只统计住过的单</h3>
 * 当天往返的单据住宿费是 0。把它们算进均值会拉出「住宿参考 ¥0」这种结论，
 * 员工读到的是「这趟不用住」，而他的行程可能真要过夜。所以住宿均值只统计
 * est_hotel 大于 0 的单据，当天往返单数单独放在 sameDayCnt。
 *
 * <h3>appliedScope 与实际口径</h3>
 * 员工填的条件可能一个样本都凑不出（比如他选了飞机，而库里同类单全都是高铁）。
 * 这时后端按优先级丢维度重查，并把真正生效的条件写在 toCity / tripType /
 * cityLevel / trafficType 上，relaxed 为 true 表示放宽过。
 * 前端必须把它显示出来，否则员工以为看的是自己填的那组条件，实际是另一批单据。
 */
@Data
public class CostRefVO {

    /** 实际生效的目的地；放宽到没有目的地这一维时为 null */
    private String toCity;

    /** 实际生效的出差类型 */
    private String tripType;

    /** 实际生效的城市等级 */
    private String cityLevel;

    /** 实际生效的交通方式 */
    private String trafficType;

    /** 交通均值 */
    private BigDecimal avgTraffic;

    /** 交通区间下限（P25）；样本不足 2 条时为 null——一个点谈不上区间 */
    private BigDecimal minTraffic;

    /** 交通区间上限（P75） */
    private BigDecimal maxTraffic;

    /** 住宿均值，只统计住过的单；全都当天往返时为 null */
    private BigDecimal avgHotel;

    private BigDecimal minHotel;

    private BigDecimal maxHotel;

    /** 合计均值 */
    private BigDecimal avgAmount;

    /** 合计区间下限（P25） */
    private BigDecimal minAmount;

    /** 合计区间上限（P75） */
    private BigDecimal maxAmount;

    /** 平均整趟天数，一位小数 */
    private BigDecimal avgDays;

    private BigDecimal minDays;

    private BigDecimal maxDays;

    private Integer sampleCnt;

    /** 样本里当天往返（没住）的单数 */
    private Integer sameDayCnt;

    /** 样本量不足 5 条 */
    private Boolean lowSample;

    /** 填的条件没凑出样本，退到了更宽的一档 */
    private Boolean relaxed;

    /** 本次预估在区间中的位置，0~1；超出区间会大于 1 */
    private BigDecimal posInRange;

    /** 本次预估合计，原样回显 */
    private BigDecimal estAmount;

    /** 本次预估交通费，原样回显 */
    private BigDecimal estTraffic;

    /** 本次预估住宿费，原样回显 */
    private BigDecimal estHotel;

    /** 本次整趟天数，原样回显 */
    private Integer estDays;
}
