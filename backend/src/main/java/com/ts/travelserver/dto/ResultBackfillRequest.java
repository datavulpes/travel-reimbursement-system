package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 业务结果回填（FR-0108）。
 *
 * <p>出差回来把「这趟到底办成什么」填回申请号，写 biz_result 表。
 * 它是费效比月报里「产出」那一半的数据来源，也是 F1 历史产出率因子的依据。
 *
 * <p>resultType 为「无进展」时，下一次同对象出差的事前提示会命中
 * 「上一趟无进展且间隔小于 15 天」（设计报告 6.3.5）。
 */
@Data
public class ResultBackfillRequest {

    /**
     * 申请号由路径参数带过来，Controller 里 setApplyNo 回填，请求体不用传。
     *
     * <p>这里不能加 @NotBlank：@Valid 在 Controller 方法体之前执行，
     * 加了的话还没来得及回填就先报 1001，接口永远走不通。
     */
    private String applyNo;

    /** 往来对象。不传则取申请单上的 partnerId */
    private Long partnerId;

    /** 结果类型：签单、验收、交付、无进展 */
    @NotBlank(message = "请选择结果类型")
    private String resultType;

    /** 结果说明 */
    @Size(max = 200, message = "结果说明不超过 200 字")
    private String resultDesc;

    /** 折算产出金额，签单类必填，用于算费效比 */
    @NotNull(message = "请填写折算产出金额")
    private BigDecimal outputAmount;
}
