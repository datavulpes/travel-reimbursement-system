package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 付款记录（payment_record）。
 *
 * <p>数据字典 8.12。一笔预借对一笔付款（1:1），杜绝交叉冲抵。
 * 付款在同一事务里做三件事：写这条记录、报销单置为已付款、预借款累加已冲抵金额（FR-0305）。
 */
@Data
@TableName("payment_record")
public class PaymentRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 报销单号 */
    private String reportNo;

    /** 实付净额 */
    private BigDecimal payAmount;

    /** 对冲的预借款 id */
    private Long loanId;

    /** 付款时间 */
    private LocalDateTime payAt = LocalDateTime.now();

    /** 经办财务 */
    private Long operatorId;

    /** 付款备注，如「差旅报销尾款」 */
    private String payRemark;
}
