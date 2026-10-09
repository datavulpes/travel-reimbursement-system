package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新增或修改往来对象（partner）。
 *
 * <p>这里只收名称与类型两个字段，刻意不收 totalInvest / totalOutput：
 * 那两列是付款与业务结果回填时增量维护的冗余汇总值（AnalyticsService 的对象页直接读它，
 * 不再全表汇总）。放进来等于给了一个「后台改报表数字」的口子，而且改完不会有任何报错。
 *
 * <p>reason 写进 config_log。不强制必填，但空着留痕里就没有调这一笔的原因。
 */
@Data
public class PartnerSaveRequest {

    /** 对象名称，库里唯一 */
    @NotBlank(message = "请填写对象名称")
    private String partnerName;

    /**
     * 类型，取值见 Partner.TYPE_CUSTOMER / TYPE_SUPPLIER。
     * 不在这里校验枚举：写死两个值，将来加类型要连前端一起改，
     * 落库前在 AdminService 里判一次就够了。
     */
    @NotBlank(message = "请选择对象类型")
    private String partnerType;

    /** 调整说明 */
    private String reason;
}
