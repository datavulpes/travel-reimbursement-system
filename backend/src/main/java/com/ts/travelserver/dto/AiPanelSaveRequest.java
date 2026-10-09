package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 助手面板保存入参。
 *
 * <p>content 是前端序列化好的一整块（最近一次问答 + 依据），后端不解析、不校验结构，
 * 只做长度与空白判断——结构变了不该让后端跟着改。
 */
@Data
public class AiPanelSaveRequest {

    /** 页面键，取值见 {@code AiPanelState.PAGE_*} */
    @NotBlank(message = "缺少页面标识")
    @Size(max = 40, message = "页面标识过长")
    private String pageKey;

    /** 面板内容；留空按删除处理 */
    @Size(max = 8000, message = "面板内容过长")
    private String content;
}
