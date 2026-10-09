package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 同类花费参考（cost_ref）：同目的地、同出差类型近 12 个月的花费均值与区间。
 *
 * <p>数据字典 8.7。（toCity, tripType）联合唯一。
 *
 * <p>每晚定时任务按（目的地，出差类型）分组统计近 12 个月已通过申请单重算；
 * 样本量不足 5 条时该参考不展示，避免误导（设计报告 6.3.4）。
 */
@Data
@TableName("cost_ref")
public class CostRef {

    /** 低于这个样本量就不展示参考值 */
    public static final int MIN_SAMPLE_CNT = 5;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 目的地 */
    private String toCity;

    /** 出差类型 */
    private String tripType;

    /** 近 12 个月均值 */
    private BigDecimal avgAmount;

    /** 区间下限（P25） */
    private BigDecimal minAmount;

    /** 区间上限（P75） */
    private BigDecimal maxAmount;

    /** 样本量 */
    private Integer sampleCnt;

    /**
     * 样本量够不够展示。样本太少时前端不显示参考卡。
     */
    public boolean available() {
        return sampleCnt != null && sampleCnt >= MIN_SAMPLE_CNT;
    }
}
