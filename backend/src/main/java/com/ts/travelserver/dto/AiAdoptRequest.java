package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 填写助手的建议采纳回执。
 *
 * <p>员工提交申请后，前端把「每一条建议的最终取值」原样发回来，
 * 后端据此判断这条建议到底有没有被用上——
 * 出建议那一刻人是还没做决定的，采纳与否只能事后补。
 *
 * <p>为什么不让后端自己比：后端不知道员工在页面上勾了哪几条、改成了什么，
 * 而把勾选状态也塞进申请单主流程，等于让业务表替 AI 记一笔账，不划算。
 */
@Data
public class AiAdoptRequest {

    /**
     * 关联单号。新建单还没有申请号时传 {@code NEW + 用户 id}，
     * 与出建议时用的 refNo 保持一致，否则对不上那一条留痕。
     */
    @NotBlank
    private String bizNo;

    /** 逐字段回执 */
    private List<Item> items;

    @Data
    public static class Item {

        /** 字段名，与建议里的 field 同名 */
        private String field;

        /** 建议值；HINT 这类只给方向的建议为空 */
        private String suggested;

        /** 员工最终提交的值 */
        private String finalValue;
    }
}
