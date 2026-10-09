package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 问数助手的入参（就一句问话）。
 *
 * <p>与制度问答（PolicyQaRequest）同构：只带一句话，不带任何上下文参数。
 * 月份 / 部门 / 人名由后端从这句话里解析出来——问数助手要的是「用大白话问」，
 * 不该让用户先选好维度再问。解析不出来的维度按「当前月 / 全公司 / 不指定人」兜底。
 */
@Data
public class DataQaRequest {

    @NotBlank(message = "请写下想问的问题")
    @Size(max = 200, message = "问题不超过 200 字")
    private String question;
}
