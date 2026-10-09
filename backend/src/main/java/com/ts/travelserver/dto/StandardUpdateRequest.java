package com.ts.travelserver.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 调整差标（H-02）。
 *
 * <p>不 UPDATE 旧行，而是插一条新的生效日期行，
 * 这样任何时点的标准都能追溯（BR-09，设计报告 5.9）。
 *
 * <p>生效日期不传默认今天，前端也可以填将来的日期做「提前发布」。
 */
@Data
public class StandardUpdateRequest {

    /** 城市等级：一级、二级、三级、四级、五级 */
    @NotBlank(message = "请选择城市等级")
    private String cityLevel;

    /**
     * 交通方式。与行程段共用同一张取值表（前端 utils/dict.js 的 TRAFFIC_TYPES）：
     * 高铁 / 飞机 / 自驾 / 长途汽车 / 轮船。
     *
     * <p>原先这里多写了一个「住宿」，可差标校验是逐段比对交通方式上限的，
     * 住宿费单独占 est_hotel 一列，从来不走这条路，库里也没有这一档标准——
     * 行程段选了它反而查不到上限、被静默跳过校验。值域要跟着实际能匹配上的那些走。
     */
    @NotBlank(message = "请选择交通方式")
    private String trafficType;

    /** 新的上限金额，必须大于 0 */
    @NotNull(message = "请填写上限金额")
    @DecimalMin(value = "0.01", message = "上限金额必须大于 0")
    private BigDecimal maxAmount;

    /** 生效日期，yyyy-MM-dd，不传为今天 */
    private LocalDate effectiveAt;

    /**
     * 调整说明，写进留痕。字段名用 reason 对齐 Apifox 文档与前端 StandardConfig.vue，
     * 后端原先叫 remark，前端传的值接不住，留痕里原因永远是空。
     */
    private String reason;
}
