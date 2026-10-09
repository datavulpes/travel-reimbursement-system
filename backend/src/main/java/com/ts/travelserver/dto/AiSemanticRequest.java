package com.ts.travelserver.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * G-03 语义评价的入参。
 *
 * <p>为什么不直接复用 {@link ApplySubmitRequest}：那是 A-02 提交的入参，
 * 上面挂满了 {@code @NotBlank / @NotNull / @NotEmpty}。而这个接口是<b>填表过程中</b>被调用的——
 * 事由刚敲两个字、对象还没选的时刻恰恰是它最有用的时候。
 * 实测 Spring 7 会对 {@code @RequestBody} 上带约束的类型自动做校验（即便没写 {@code @Valid}），
 * 复用那个 DTO 会让这类请求一律 400，页面上的 AI 区块就永远空白。
 *
 * <p>所以这里单独收一遍真正需要的五个字段，一个约束都不挂。
 * 缺什么由 {@code EvalService} 容错：日期解析不出来记 null、
 * partnerId 为空就不带对象信息，Δ 照算，只是依据少一条。
 *
 * <p>字段形状刻意与 ApplySubmitRequest.ItineraryItem 保持一致，
 * 前端同一份 form.itineraries 可以原样塞进来。
 */
@Data
public class AiSemanticRequest {

    /** 出差事由 */
    private String reason;

    /** 出差类型：验货、商务拜访、项目交付、供应商审核、展会、其他 */
    private String tripType;

    /** 关联往来对象，为空时不带对象信息 */
    private Long partnerId;

    /** 预估交通费，可选 */
    private BigDecimal estTraffic;

    /** 预估住宿费，可选 */
    private BigDecimal estHotel;

    /** 行程段；没有就传空数组，模型按「没有行程」处理 */
    private List<ItineraryItem> itineraries;

    @Data
    public static class ItineraryItem {

        private Integer seq;

        /** 开始日期，yyyy-MM-dd */
        private String startDate;

        /** 结束日期，yyyy-MM-dd */
        private String endDate;

        private String fromCity;

        private String toCity;

        private String trafficType;

        private String cityLevel;
    }
}
