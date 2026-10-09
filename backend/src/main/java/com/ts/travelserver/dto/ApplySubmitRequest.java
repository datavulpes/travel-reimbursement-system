package com.ts.travelserver.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 提交出差申请（A-02 / A-05）。
 *
 * <p>设计报告 7.4：提交瞬间后端完成差标校验与事前合理性提示，
 * 返回 { applyNo, standardResult, standardHints, evalPreview }。
 *
 * <p>差标结论为 EXCEED 时 exceedReason 必填，否则返回 2003（BR-01）。
 * 这个条件必填没法用注解表达，放在 Service 里判。
 */
@Data
public class ApplySubmitRequest {

    /**
     * 要续写的草稿申请号。带上它就留在同一张单上，不带则新建。
     *
     * <p>员工常有一次填不完的情况：先存草稿走人，回来接着填。
     * 没有这个字段时每次存草稿都nextApplyNo 一次，同一次出差在库里躺好几张半成品，
     * 员工自己也分不清哪个是最新的。带上之后续写、提交都落在同一个申请号上。
     *
     * <p>只允许落到「本人 + DRAFT」的单上，其余状态一律拒绝，
     * 具体判定见 {@link com.ts.travelserver.service.ApplyService}。
     */
    private String applyNo;

    /** 出差事由，不超过 200 字，写得越具体 F4 事由完整度得分越高 */
    @NotBlank(message = "出差事由不能为空")
    @Size(max = 200, message = "出差事由不超过 200 字")
    private String reason;

    /** 关联往来对象 */
    @NotNull(message = "请选择关联对象")
    private Long partnerId;

    /** 出差类型：验货、商务拜访、项目交付、供应商审核、展会、其他 */
    private String tripType;

    /**
     * 出差类型选「其他」时的补充说明。非必填，前端只在选了「其他」时把这个框放出来。
     *
     * <p>后端不做「选了其他就必填」的联动校验：填不填由员工自己判断，
     * 强制必填只会换来一句「其他事项」——那和不填没有区别，还多一次提交失败。
     */
    @Size(max = 200, message = "类型补充说明不超过 200 字")
    private String tripTypeNote;

    /** 预估交通费 */
    @NotNull(message = "请填写预估交通费")
    private BigDecimal estTraffic;

    /** 预估住宿费 */
    @NotNull(message = "请填写预估住宿费")
    private BigDecimal estHotel;

    /**
     * 预估合计。前端会一起传过来，但后端以「交通 + 住宿」重算为准，
     * 不信任客户端算的值（设计报告 5.6：est_total 由后端写入）。
     */
    private BigDecimal estTotal;

    /** 超标说明，差标结论为 EXCEED 时必填 */
    @Size(max = 200, message = "超标说明不超过 200 字")
    private String exceedReason;

    /** 行程段，至少一段 */
    @NotEmpty(message = "至少填写一段行程")
    @Valid
    private List<ItineraryItem> itineraries;

    /**
     * 预估合计，以交通 + 住宿重算，不取前端传来的 estTotal。
     */
    public BigDecimal calcEstTotal() {
        BigDecimal traffic = estTraffic == null ? BigDecimal.ZERO : estTraffic;
        BigDecimal hotel = estHotel == null ? BigDecimal.ZERO : estHotel;
        return traffic.add(hotel);
    }

    /**
     * 行程段入参。日期用 yyyy-MM-dd 字符串，前端 el-date-picker 的 value-format 就是这个。
     */
    @Data
    public static class ItineraryItem {

        /** 段序号，从 1 开始；不传则由后端按数组顺序补 */
        private Integer seq;

        /** 开始日期，yyyy-MM-dd */
        @NotBlank(message = "请选择出发日期")
        private String startDate;

        /** 结束日期，yyyy-MM-dd，不早于开始日期 */
        @NotBlank(message = "请选择返程日期")
        private String endDate;

        /** 出发地 */
        @NotBlank(message = "请填写出发地")
        private String fromCity;

        /** 目的地 */
        @NotBlank(message = "请填写目的地")
        private String toCity;

        /** 交通方式：高铁、飞机、自驾、长途汽车、轮船 */
        @NotBlank(message = "请选择交通方式")
        private String trafficType;

        /** 目的地城市等级，不传则由后端按目的地查配置补 */
        private String cityLevel;
    }
}
