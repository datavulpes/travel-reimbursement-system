package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 往来对象（partner）：客户或供应商。
 *
 * <p>数据字典 8.3。每趟出差关联一个对象，对象页看投入产出对照。
 * totalInvest / totalOutput 是刻意保留的冗余汇总字段：
 * 管理层对象页每次全表汇总太慢，付款与业务结果回填时增量更新，月结校对（设计报告 5.6）。
 */
@Data
@TableName("partner")
public class Partner {

    /** 类型：客户 */
    public static final String TYPE_CUSTOMER = "CUSTOMER";
    /** 类型：供应商 */
    public static final String TYPE_SUPPLIER = "SUPPLIER";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对象名称，唯一 */
    private String partnerName;

    /** 类型，取值见本类 TYPE_* 常量 */
    private String partnerType;

    /** 累计投入（差旅合计） */
    private BigDecimal totalInvest = BigDecimal.ZERO;

    /** 累计产出（签单验收折算） */
    private BigDecimal totalOutput = BigDecimal.ZERO;
}
