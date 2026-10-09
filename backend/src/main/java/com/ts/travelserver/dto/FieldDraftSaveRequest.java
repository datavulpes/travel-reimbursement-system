package com.ts.travelserver.dto;

import com.ts.travelserver.entity.FieldDraft;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存理由字段草稿。
 *
 * <p>content 允许为空串：那是「把框清空」的意思，后端按删除处理，
 * 不留一条空内容占着位——否则页面上会冒出「有上次保存的内容」却恢复出一片空白。
 */
@Data
public class FieldDraftSaveRequest {

    /** 业务场景，取值见 {@link FieldDraft} 的 BIZ_* 常量 */
    @NotBlank(message = "业务场景不能为空")
    private String bizType;

    /** 业务单号：申请号或报销单号 */
    @NotBlank(message = "业务单号不能为空")
    @Size(max = 32, message = "业务单号不合法")
    private String bizNo;

    /** 字段名，取值见 {@link FieldDraft} 的 FIELD_* 常量 */
    @NotBlank(message = "字段名不能为空")
    @Size(max = 32, message = "字段名不合法")
    private String fieldName;

    /** 草稿正文，留空表示清空 */
    @Size(max = FieldDraft.MAX_LEN, message = "内容不超过 " + FieldDraft.MAX_LEN + " 字")
    private String content;
}
