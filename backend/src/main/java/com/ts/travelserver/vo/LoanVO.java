package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预借款（D-01）。
 *
 * <p>复审通过的同事务内按「预估合计与部门月度预算取小者」生成（BR-04）。
 * 报销付款时先冲抵预借、只付差额，一笔预借对一笔付款（FR-0305）。
 */
@Data
public class LoanVO {

    private Long id;

    private String applyNo;

    private Long userId;

    /** 预借额度 */
    private BigDecimal amount;

    /** 已冲抵金额 */
    private BigDecimal offsetAmount;

    /** 剩余可冲抵额度，报销单算冲抵时以它为上限 */
    private BigDecimal remainAmount;

    /** 状态：ACTIVE / OFFSET / SETTLED */
    private String status;

    private LocalDateTime createdAt;
}
