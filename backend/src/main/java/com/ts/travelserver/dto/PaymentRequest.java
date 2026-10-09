package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 付款并冲抵预借（E-04）。
 *
 * <p>一个事务里做三件事：写 payment_record、报销单置为已付款、
 * 预借款累加已冲抵金额并按余额置为已结清。一笔预借对一笔付款（FR-0305）。
 *
 * <p>实付金额默认等于报销单净额，财务可在界面上改（比如尾款抹零），
 * 所以这里由前端传 payAmount，后端只校验不小于 0。
 */
@Data
public class PaymentRequest {

    /** 实付净额 */
    @NotNull(message = "请填写实付金额")
    private BigDecimal payAmount;

    /** 对冲的预借款 id */
    @NotNull(message = "请选择要冲抵的预借款")
    private Long loanId;

    /** 付款备注，如「差旅报销尾款」 */
    @Size(max = 200, message = "付款备注不超过 200 字")
    private String payRemark;
}
