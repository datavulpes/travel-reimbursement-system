package com.ts.travelserver.vo;

import lombok.Data;

/**
 * AI 助手面板状态出参。
 *
 * <p>content 为 null 表示这一页没存过，前端不做恢复。
 */
@Data
public class AiPanelStateVO {

    private String pageKey;

    private String content;

    /** yyyy-MM-dd HH:mm:ss；没存过时为 null */
    private String updatedAt;
}
