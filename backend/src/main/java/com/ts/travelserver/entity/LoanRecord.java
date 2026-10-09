package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预借款（loan_record）。
 *
 * <p>数据字典 8.9。（applyNo, userId）联合唯一，一张申请一笔预借（BR-04）。
 * 复审通过的同事务内按「预估合计与部门预算取小者」生成。
 * 报销付款时先冲抵预借、只付差额，一笔预借对一笔付款，杜绝交叉冲抵（FR-0305）。
 */
@Data
@TableName("loan_record")
public class LoanRecord {

    /** 有效，还没冲抵 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 部分冲抵 */
    public static final String STATUS_OFFSET = "OFFSET";
    /** 已结清 */
    public static final String STATUS_SETTLED = "SETTLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请号 */
    private String applyNo;

    /** 预借人 */
    private Long userId;

    /** 额度，大于 0 */
    private BigDecimal amount;

    /** 状态，取值见本类 STATUS_* 常量 */
    private String status;

    /** 已冲抵金额 */
    private BigDecimal offsetAmount = BigDecimal.ZERO;

    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 剩余可冲抵额度。报销单算 offset_amount 时拿它卡上限，不能超过预借余额。
     */
    public BigDecimal remainAmount() {
        BigDecimal total = amount == null ? BigDecimal.ZERO : amount;
        BigDecimal offset = offsetAmount == null ? BigDecimal.ZERO : offsetAmount;
        BigDecimal remain = total.subtract(offset);
        return remain.compareTo(BigDecimal.ZERO) > 0 ? remain : BigDecimal.ZERO;
    }
}
