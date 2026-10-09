package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 业务结果（biz_result）：出差回来回填「这趟到底办成什么」。
 *
 * <p>数据字典 8.13。回填挂回申请号，是费效比月报里「产出」那一半的数据来源，
 * 也是 F1 历史产出率因子的依据。一张申请可以有多条结果（1:n）。
 */
@Data
@TableName("biz_result")
public class BizResult {

    /** 签单 */
    public static final String TYPE_SIGNED = "签单";
    /** 验收 */
    public static final String TYPE_ACCEPTED = "验收";
    /** 交付 */
    public static final String TYPE_DELIVERED = "交付";
    /** 无进展。上一趟是这个结果且间隔小于 15 天，事前提示会命中「到场没到效」 */
    public static final String TYPE_NO_PROGRESS = "无进展";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请号 */
    private String applyNo;

    /** 往来对象 */
    private Long partnerId;

    /** 结果类型，取值见本类 TYPE_* 常量 */
    private String resultType;

    /** 结果说明 */
    private String resultDesc;

    /** 折算产出金额，算费效比时用 */
    private BigDecimal outputAmount = BigDecimal.ZERO;

    /** 回填人 */
    private Long filledBy;

    private LocalDateTime filledAt = LocalDateTime.now();

    /**
     * 算不算有效产出。F1 历史产出率按「有效产出次数 ÷ 历史出差次数」算。
     */
    public boolean effective() {
        return !TYPE_NO_PROGRESS.equals(resultType);
    }
}
