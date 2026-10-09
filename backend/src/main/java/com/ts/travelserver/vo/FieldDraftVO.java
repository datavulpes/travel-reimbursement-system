package com.ts.travelserver.vo;

import lombok.Data;

/**
 * 理由字段草稿（读回给前端）。
 *
 * <p>content 为 null 表示「没有存过」；前端据此决定显示「有上次保存的内容 · 恢复」
 * 还是什么都不显示。空串不会出现——保存时空内容按删除处理。
 */
@Data
public class FieldDraftVO {

    private String bizType;

    private String bizNo;

    private String fieldName;

    /** 草稿正文，没有存过为 null */
    private String content;

    /** 保存时间，yyyy-MM-dd HH:mm:ss；没有存过为 null */
    private String updatedAt;
}
