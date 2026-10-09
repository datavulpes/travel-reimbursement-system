package com.ts.travelserver.dto;

import lombok.Data;

/**
 * 读取 / 删除理由字段草稿的定位条件。
 *
 * <p>三件套就是唯一键的前三段，第四段 user_id 由后端从登录态取——
 * 前端永远传不了别人的 userId，也就不可能读到别人的草稿。
 */
@Data
public class FieldDraftQuery {

    /** 业务场景，取值见 {@link com.ts.travelserver.entity.FieldDraft} 的 BIZ_* 常量 */
    private String bizType;

    /** 业务单号 */
    private String bizNo;

    /** 字段名 */
    private String fieldName;
}
